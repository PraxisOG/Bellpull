# Bellpull speech helper

Supports the existing ROCm Moonshine/Kokoro service and CPU recognition. Kokoro
read-aloud now streams mono 24 kHz signed little-endian PCM in bounded sentence
batches. `POST /tts` with `stream: true` starts returning audio after the first
batch; the non-streaming request still returns a 24 kHz WAV. Audio format headers
survive the Bellpull proxy. Android explicitly converts 24 kHz to its existing
48 kHz server-speech player, preserving pitch and duration. Legacy MOSS's implicit
48 kHz octet-stream response remains supported.

Each request owns one synthesis worker. Disconnect/cancel stops later batches;
in-flight native computation finishes before another request can own the model.
Overlapping requests receive 429. Sentence batches are at most 220 characters,
requests at most 4,000 characters, and generated audio at most 32 MiB. Android
buffers at most two bounded sentence queues, prefetching the next sentence while
the current one plays. This is sentence streaming, not token-level generation.

## Existing ROCm installation

`rocm_models.py`, `run_rocm.py` and `kokoro-models.json` preserve the installed
GPU service's model selection and pinned checkpoints. The runner expects the
previously provisioned `~/.local/share/bellpull-speech-rocm` environment, including
`device.json`, its Python 3.12 venv, GPU device groups and cached Hugging Face
models. It does not download weights or silently fall back to CPU. The measured
environment uses torch 2.6.0+rocm6.2.4 and the dependencies listed in
`requirements-rocm.txt`. That file is a record of the tested Python dependencies,
not a new GPU/device installer.

When updating that existing installation, deploy `server.py`, `tts_stream.py`,
`rocm_models.py`, `run_rocm.py` and `kokoro-models.json` together into the service's
source directory, then restart when idle. The service continues to run
`run_rocm.py`; keep its existing credentials, model cache and GPU configuration.
ROCm Moonshine currently advertises `stt_streaming: false` and uses batch `/stt`.
CPU Moonshine retains incremental WebSocket recognition.

## CPU recognition

Provides authenticated batch and WebSocket STT using Moonshine on CPU. The default
is a pinned **FP32 medium export**, loaded by the native incremental runtime.
The user selected FP32 after it outperformed INT8 on PowerMac. The model stays
warm. Two utterances may be active;
each has independent recognition state. Native operations run on one worker so
chunks interleave without freeing a stream during inference. Overload returns
429. `--stt-only` preserves the recognition-only deployment. Optional CPU Kokoro
uses the files and hashes in `kokoro-models.json`; missing TTS weights leave
recognition available and are reported in health instead of triggering downloads.

## Install

Use Python 3.12 and the CPU-only PyTorch wheel for the one-time export:

```bash
python3 -m venv ~/.local/share/bellpull-speech-venv
speech_python=~/.local/share/bellpull-speech-venv/bin/python
"$speech_python" -m pip install -r server-speech/requirements.txt
"$speech_python" -m pip install torch==2.14.1+cpu --index-url https://download.pytorch.org/whl/cpu
"$speech_python" -m pip install -r server-speech/requirements-export.txt
"$speech_python" server-speech/export_fp32.py
mkdir -p ~/.local/share/bellpull-speech
cp server-speech/server.py server-speech/tts_stream.py server-speech/export_fp32.py server-speech/kokoro-models.json ~/.local/share/bellpull-speech/
```

This exports original FP32 weights using Moonshine's public exporter, then converts
the graphs to ORT **without quantization**. Export checks reject quantized tensors
and operations; a manifest pins the checkpoint, tools and file hashes. Startup
verifies those hashes. It does not inflate an INT8 checkpoint back to floating
point. PyTorch/Transformers/ONNX export packages are not imported while serving.

`--precision int8` explicitly selects the upstream quantized runtime; `--model small`
is available with that choice. It is retained for comparisons, not selected by the
installed service. There is no automatic precision fallback.

Store a random `BELLPULL_SPEECH_KEY` in `~/.config/bellpull/speech.env` (mode 0600).
Configure the same key in the Hermes gateway environment, alongside
`BELLPULL_SPEECH_URL=http://127.0.0.1:8645`. The helper binds only to loopback.
For a containerized gateway, loopback must reach the helper (for example, host
networking); do not expose the helper publicly.

Install `bellpull-speech.service` into `~/.config/systemd/user/`, then run
`systemctl --user daemon-reload` and
`systemctl --user enable --now bellpull-speech.service`. Install Bellpull plugin
0.6.0 or later and restart the gateway when idle. The phone uses its existing
Hermes profile credential; the helper key stays on the server.

The HTTP and WebSocket protocol, limits, cancellation and compatibility fallback
are specified in [hermes-api.md](../docs/hermes-api.md).

## Verification

Run `python -m unittest discover -s server-speech -v` from the repository root.
The tests use fake recognition to exercise auth, isolation, malformed input,
overload and cancellation. They do not claim model accuracy.

`tools/benchmark-server-stt.py` measures completed clips with official quantized
medium/small and a Transformers FP32 CPU baseline. The latter needs CPU PyTorch,
Transformers and soundfile in the benchmark environment. This compares whole
runtimes as well as quantization. `tools/benchmark-streaming-stt.py` replays the
pinned public fixtures at microphone speed, recording first partial and final
latency. Set `BELLPULL_BENCH_KEY` without putting it into logs.

An isolated phone test uses `tools/streaming-device-fixture.py` (loopback 18648),
this helper on loopback 18645 with the synthetic key named in the fixture,
ADB reverse ports, and `StreamingSpeechDeviceTest`. Only public audio is used.
Never run the device suite against the personal application package.

See [measured results and limits](../docs/evidence/car-streaming-20261020/README.md).
