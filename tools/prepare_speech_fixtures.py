#!/usr/bin/env python3
"""Prepare direct-input speech fixtures from source-checked human transcripts.

Requires the system libsndfile (no Python packages). Downloads three short public
clips and one 7.4 MB compressed traffic channel. Writes WAVs plus provenance/hashes.
"""
import argparse
import array
import ctypes as c
from ctypes.util import find_library
import hashlib
import io
import json
import math
import struct
import sys
import urllib.parse
import urllib.request
import wave
import zlib
from pathlib import Path

CASES = [("clean", 0, "1089-134686-0006"), ("numbers-measurements", 2020, "6829-68769-0008"),
         ("natural-pauses", 35, "1188-133604-0012")]


def fetch(url, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=45) as response:
        return response.read()


def query(route, **parameters):
    return json.loads(fetch("https://datasets-server.huggingface.co/" + route + "?" + urllib.parse.urlencode(parameters)))


def flac_samples(path):
    class Info(c.Structure):
        _fields_ = [("frames", c.c_longlong), ("samplerate", c.c_int), ("channels", c.c_int),
                    ("format", c.c_int), ("sections", c.c_int), ("seekable", c.c_int)]
    library = c.CDLL(find_library("sndfile") or "libsndfile.so.1")
    library.sf_open.argtypes = [c.c_char_p, c.c_int, c.POINTER(Info)]
    library.sf_open.restype = c.c_void_p
    library.sf_readf_float.argtypes = [c.c_void_p, c.POINTER(c.c_float), c.c_longlong]
    library.sf_readf_float.restype = c.c_longlong
    library.sf_close.argtypes = [c.c_void_p]
    info = Info()
    handle = library.sf_open(str(path).encode(), 0x10, c.byref(info))
    if not handle:
        raise ValueError("libsndfile could not decode " + str(path))
    try:
        assert info.samplerate == 16000 and info.channels == 1 and 0 < info.frames < 960000
        output = (c.c_float * info.frames)()
        assert library.sf_readf_float(handle, output, info.frames) == info.frames
        return list(output)
    finally:
        library.sf_close(handle)


def wav_bytes(samples):
    pcm = array.array("h", (max(-32768, min(32767, round(v * 32768))) for v in samples))
    if sys.byteorder != "little":
        pcm.byteswap()
    output = io.BytesIO()
    with wave.open(output, "wb") as file:
        file.setnchannels(1); file.setsampwidth(2); file.setframerate(16000)
        file.writeframes(pcm.tobytes())
    return output.getvalue()


def traffic():
    url = "https://zenodo.org/records/1227121/files/STRAFFIC_16k.zip?download=1"

    def part(start, end):
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Range": f"bytes={start}-{end}"}), timeout=45) as response:
            assert response.status == 206 and response.headers["Content-Range"].startswith(f"bytes {start}-")
            return response.read()

    offset = 44694869
    header = part(offset, offset + 99)
    assert header[:4] == b"PK\x03\x04"
    name_size, extra_size = struct.unpack_from("<HH", header, 26)
    assert header[30:30 + name_size] == b"STRAFFIC/ch01.wav"
    start = offset + 30 + name_size + extra_size
    compressed = part(start, start + 7362827 - 1)
    raw = zlib.decompressobj(-15).decompress(compressed, 10_000_000)
    assert hashlib.sha256(raw).hexdigest() == "8fa49dcf3996af655a3ecef8d06f0d9ef217c35410e3adce3d461da797987dbb"
    with wave.open(io.BytesIO(raw)) as file:
        assert file.getnchannels() == 1 and file.getframerate() == 16000 and file.getsampwidth() == 2
        pcm = array.array("h", file.readframes(file.getnframes()))
        if sys.byteorder != "little":
            pcm.byteswap()
    return [v / 32768 for v in pcm], hashlib.sha256(raw).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/speech-lab/fixtures"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {"schema": 1, "sample_rate": 16000, "appended_silence_ms": 2000,
                "timing_annotation": "Dataset forced-alignment estimate; not manually verified word boundaries",
                "fixtures": []}

    def save(name, samples, metadata):
        data = wav_bytes(samples + [0.0] * 32000)
        (args.output / (name + ".wav")).write_bytes(data)
        manifest["fixtures"].append(dict(id=name, file=name + ".wav", sha256=hashlib.sha256(data).hexdigest(),
                                          audio_seconds=(len(samples) + 32000) / 16000, **metadata))

    clean = None
    clean_metadata = None
    for name, index, identity in CASES:
        aligned = query("rows", dataset="gilkeyio/librispeech-alignments", config="default", split="test_clean", offset=index, length=1)["rows"][0]["row"]
        assert aligned["id"] == identity
        primary = query("filter", dataset="openslr/librispeech_asr", config="clean", split="test",
                        where=f'"id"=\'{identity}\'', offset=0, length=1)["rows"][0]["row"]
        assert primary["id"] == identity and primary["text"].lower() == aligned["transcript"]
        raw = fetch(aligned["audio"][0]["src"])
        source = args.output / (identity + ".flac")
        source.write_bytes(raw)
        samples = flac_samples(source)
        source.unlink()
        words = aligned["words"]
        metadata = dict(reference=primary["text"], source_id=identity,
                        source_dataset="openslr/librispeech_asr:test-clean",
                        source_sha256=hashlib.sha256(raw).hexdigest(),
                        alignment_dataset="gilkeyio/librispeech-alignments:test_clean",
                        last_word_end_ms=words[-1]["end"] * 1000,
                        largest_internal_pause_ms=max(b["start"] - a["end"] for a, b in zip(words, words[1:])) * 1000,
                        license="CC-BY-4.0", modifications="Lossless PCM16 decode; append 2 seconds silence")
        assert metadata["last_word_end_ms"] <= len(samples) / 16
        save(name, samples, metadata)
        if name == "clean":
            clean, clean_metadata = samples, metadata
        print("Prepared", name, identity, flush=True)

    noise, noise_hash = traffic()
    noise = noise[30 * 16000:30 * 16000 + len(clean)]
    speech_rms = math.sqrt(sum(v * v for v in clean) / len(clean))
    noise_rms = math.sqrt(sum(v * v for v in noise) / len(noise))
    scale = speech_rms / (noise_rms * 10 ** (10 / 20))
    mixed = [a + b * scale for a, b in zip(clean, noise)]
    attenuation = min(1, .95 / max(abs(v) for v in mixed))
    save("road-noise", [v * attenuation for v in mixed], dict(clean_metadata,
         noise_source="DEMAND STRAFFIC/ch01.wav, busy traffic intersection, offset 30 seconds",
         noise_sha256=noise_hash, snr_db=10, snr_definition="Whole source clip RMS, including its existing pauses",
         license="Speech CC-BY-4.0; noise and mixed derivative CC-BY-SA-3.0",
         modifications="Recorded traffic mixed at 10 dB SNR, global attenuation to avoid clipping, append 2 seconds silence"))
    expected_path = Path(__file__).resolve().parent.parent / "benchmarks/speech/fixtures-manifest.json"
    if expected_path.exists():
        expected = {r["id"]: r for r in json.loads(expected_path.read_text())["fixtures"]}
        for row in manifest["fixtures"]:
            for key in ("sha256", "reference", "source_sha256", "last_word_end_ms"):
                assert row[key] == expected[row["id"]][key], f"Pinned fixture changed: {row['id']} {key}"
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Wrote", args.output / "manifest.json")


if __name__ == "__main__":
    main()
