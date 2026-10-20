#!/usr/bin/env python3
"""CPU-only, public-fixture comparison. Runtime+quantization comparison, not a
controlled measurement of quantization alone. No microphone or personal audio.
"""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import re
import resource
import statistics
import time

os.environ["CUDA_VISIBLE_DEVICES"] = ""


def words(text):
    return re.findall(r"[a-z0-9]+(?:'[a-z]+)?", text.lower())


def errors(reference, text):
    ref, hyp = words(reference), words(text)
    previous = list(range(len(hyp) + 1))
    for i, a in enumerate(ref, 1):
        row = [i]
        for j, b in enumerate(hyp, 1):
            row.append(min(row[-1] + 1, previous[j] + 1, previous[j - 1] + (a != b)))
        previous = row
    return {"word_errors": previous[-1], "reference_words": len(ref),
            "wer": previous[-1] / max(1, len(ref))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine", choices=["native-medium", "native-small", "fp32-medium", "native-fp32-medium"], required=True)
    parser.add_argument("--model-dir", type=Path, help="Required for an explicitly exported native FP32 model")
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    import soundfile as sf

    manifest = json.loads((args.fixtures / "manifest.json").read_text())
    start = time.perf_counter()
    if args.engine.startswith("native"):
        from moonshine_voice import Transcriber
        from moonshine_voice.download import get_model_for_language
        from moonshine_voice.moonshine_api import ModelArch
        arch = ModelArch.MEDIUM_STREAMING if args.engine.endswith("medium") else ModelArch.SMALL_STREAMING
        if args.engine == "native-fp32-medium":
            if args.model_dir is None:
                parser.error("--model-dir is required for native-fp32-medium")
            path = str(args.model_dir)
        else:
            path, arch = get_model_for_language("en", arch, on_progress=lambda *_: None)
        model = Transcriber(path, arch, options={"ort_providers": "CPU"})
        files = {}
        for p in Path(path).glob("*"):
            if p.is_file() and p.suffix in (".ort", ".json", ".bin"):
                with p.open("rb") as file:
                    files[p.name] = hashlib.file_digest(file, "sha256").hexdigest()
        metadata = {"runtime": "moonshine-voice", "version": importlib.metadata.version("moonshine-voice"),
                    "model_directory": Path(path).name, "weights_sha256": files,
                    "precision": "float32 export" if args.engine == "native-fp32-medium" else
                        "upstream 8-bit quantized; floating-point frontend", "threads": "runtime default"}

        def transcribe(audio):
            result = model.transcribe_without_streaming(audio.tolist(), 16000)
            return " ".join(line.text for line in result.lines).strip()
    else:
        import torch
        from transformers import AutoProcessor, MoonshineStreamingForConditionalGeneration
        from huggingface_hub import try_to_load_from_cache
        torch.set_num_threads(6)
        torch.set_num_interop_threads(1)
        name = "moonshine-ai/moonshine-streaming-medium"
        model = MoonshineStreamingForConditionalGeneration.from_pretrained(name, dtype=torch.float32).to("cpu").eval()
        processor = AutoProcessor.from_pretrained(name)
        metadata = {"runtime": "transformers", "version": importlib.metadata.version("transformers"),
                    "torch": torch.__version__, "model": name,
                    "revision": Path(try_to_load_from_cache(name, "config.json")).parent.name,
                    "precision": "float32", "threads": 6, "device": str(next(model.parameters()).device)}

        def transcribe(audio):
            inputs = processor(audio, sampling_rate=16000, return_tensors="pt")
            with torch.inference_mode():
                ids = model.generate(**inputs, max_new_tokens=max(32, int(len(audio) / 16000 * 6.5)), do_sample=False)
            return processor.decode(ids[0], skip_special_tokens=True).strip()

    load_ms = (time.perf_counter() - start) * 1000
    warm, sr = sf.read(args.fixtures / manifest["fixtures"][0]["file"], dtype="float32")
    assert sr == 16000
    transcribe(warm)
    rows = []
    for case in manifest["fixtures"]:
        path = args.fixtures / case["file"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == case["sha256"]
        audio, sr = sf.read(path, dtype="float32")
        assert sr == 16000 and audio.ndim == 1
        times, texts = [], []
        for _ in range(args.repeats):
            start = time.perf_counter()
            text = transcribe(audio)
            times.append((time.perf_counter() - start) * 1000)
            texts.append(text)
        row = {"fixture": case["id"], "sha256": case["sha256"], "audio_seconds": len(audio) / sr,
               "milliseconds": times, "median_ms": statistics.median(times),
               "rtf": statistics.median(times) / 1000 / (len(audio) / sr),
               "text": texts[0], "repeat_stable": len(set(texts)) == 1, **errors(case["reference"], texts[0])}
        rows.append(row)
        print(json.dumps(row), flush=True)
    result = {"engine": args.engine, "cpu_only": True, "cpu": platform.processor(),
              "platform": platform.platform(), "load_ms_including_download": load_ms,
              "peak_rss_mib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024,
              "metadata": metadata, "fixtures": rows,
              "limitations": "Four short public clips, including one traffic mix. Not a car microphone or broad accuracy benchmark."}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    if hasattr(model, "close"):
        model.close()


if __name__ == "__main__":
    main()
