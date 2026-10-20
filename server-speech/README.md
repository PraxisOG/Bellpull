# Bellpull CPU speech helper

Provides authenticated batch and WebSocket STT using Moonshine on CPU. The default
is a pinned **FP32 medium export**, loaded by the native incremental runtime.
The user selected FP32 after it outperformed INT8 on PowerMac. The model stays
warm. Two utterances may be active;
each has independent recognition state. Native operations run on one worker so
chunks interleave without freeing a stream during inference. Overload returns
429. This helper does not provide TTS; keep using the selected phone TTS engine
or an independently configured speech service that implements the existing TTS
contract.

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
cp server-speech/server.py server-speech/export_fp32.py ~/.local/share/bellpull-speech/
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
