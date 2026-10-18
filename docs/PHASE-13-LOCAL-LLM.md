# Phase 13: local LLM experiment

The user authorized a working experimental implementation after publishing 0.12.0,
and asked to use `/home/praxisog/Imagination-gpu-experiment` as a reference.
This branch builds **0.13.0-experimental / build 31**. Plugin runtime remains
0.4.3-integration-preview; there is no new server contract or deployment.

## Scope

Settings → Local chat lab opens an explicit local-only text workspace. Import a
GGUF instruction model through the document picker, then load CPU. Import copies
and hashes the model in private app storage (up to 10 GiB, with 512 MiB free-space
headroom). The model name and size are visible, and private copies can be deleted.
Models must provide a chat template; multimodal projector files are rejected.

Streaming chat uses the selected model with greedy sampling, four threads, a
2,048-token context and at most 256 response tokens. Context overflow is a visible
error, never silent truncation. New chat clears the conversation. Stop reaches the
native loading/prefill/decoding cancellation callbacks. No automatic cloud/model
fallback, tools, phone actions, notification retrieval, voice or Hermes sync is
part of this lab. Phase 14 remains separate.

The lab owns its engine independently of the Hermes runtime. Chat/drafts survive
Activity recreation within the app process but are not saved across process exit.
Backgrounding stops work and unloads weights; reloading is explicit. Benchmarks
contain only fixed synthetic prompts and can be exported as JSON. Model weights
and benchmark reports are private files, not encrypted; chat is held in memory.

## Native implementation

Pinned llama.cpp: `a97cce86a8addeb9f40cba7a261c94b1f0c576cb`.
Pinned Vulkan-Headers: `3c65a01745e4a1134d32b9c2c456472212dba16d`.
Both are unmodified submodules borrowed from the supplied experiment. llama.cpp
fetches KleidiAI v1.24.0 through its pinned build configuration. Android NDK r29
and CMake 3.31.6 match the existing voice build.

The `local-llm` module is independent of the existing Qwen GGML build. Its static
dependencies are hidden behind JNI exports to prevent symbol interposition.
ARM64 includes CPU/Vulkan; x86_64 provides CPU. No Google service or accelerator
SDK is added. CPU selection explicitly passes an empty accelerator list, since
the supplied experiment showed that zero offloaded layers alone is insufficient.

GPU uses the experiment's four disable switches (coopmat, coopmat2, coopmat2
decode-vector, integer dot product), plus disabled F16, fusion and asynchronous
execution. Flash attention is disabled. These are hypotheses to test, not claims
of GPU correctness. Layer offload and backend details come from the runtime.

The comparison repeats two fixed prompts twice on each backend and requires
nonempty token streams, within-backend repeatability and exact CPU/GPU equality.
Failure unloads the engine, disables GPU chat and offers explicit CPU loading.
A passing result only authorizes this model in this process/configuration and
does not establish correctness on arbitrary prompts. There is no silent fallback.

## Build and validation

Initialize submodules recursively, then use the SDK's Ninja for shader generation:

```bash
bash tools/build-local-lab.sh test verifyRoborazziDebug lintDebug assembleRelease --max-workers=4
bash tools/build-local-lab.sh :app:assembleDebug :app:assembleDebugAndroidTest \
  -PbellpullDeviceFixture=true -Pandroid.injected.build.abi=arm64-v8a --max-workers=4
```

Device tests use only `org.praxisog.hermes.fixture`, synthetic prompts and staged
GGUF files. No personal Hermes credentials or chats are read. Measurements include
model hash, load time, prompt/generation speed, wall time, PSS, thermal status,
battery temperature/charge counter and whether external power is present. A short
USB-powered run cannot establish battery life or sustained thermal behavior.

### Measured acceptance (October 17)

683 Android tests passed, one opt-in live smoke skipped, 187 screenshot baselines,
ARM64/x86_64 release assembly and lint passed. A pre-existing voice test raced send
admission against capture restart; its wait now checks the completed restart.
The unchanged plugin passed 153 tests with one skip on the isolated compatibility
host. No live plugin/configuration deployment occurred.

Three physical-device tests passed: E2B chat/benchmark/cancellation/lifecycle,
E2B CPU/Vulkan comparison with blocked GPU/recovery, and E4B offline chat/benchmark
at 1080×2400 (393 dp) width. Activity recreation retains the model; backgrounding
unloads it. Stop during E2B prefill settled in 559 ms. Tests cover multi-turn recall,
emoji/Japanese UTF-8, context overflow/recovery and the actual Send button.
Network/airplane/display settings were restored. No model or cloud was substituted.

| Measurement | E2B Q4_0 CPU | E4B Q4_0 CPU |
| --- | --- | --- |
| Longer-response generation | 11.2–11.8 tokens/s | 6.8–6.9 tokens/s |
| Six-prompt processing range | 37.4–76.4 tokens/s | 21.3–43.2 tokens/s |
| Observed PSS during benchmark | 4,565–4,622 MiB | 7,425–7,433 MiB |
| Model load in benchmark | 1.56 s (reload) | 6.45 s |
| Battery temperature start → end | 30.9 → 31.5 °C | 32.8 → 34.3 °C |
| Benchmark wall duration | 30.1 s | 50.4 s |

Both models correctly answered fixed repetition, arithmetic and passage lookup.
They wrapped requested JSON in Markdown fences, so strict JSON compliance failed.
The longer explanatory prompt hit the benchmark's 128-token cap (the UI uses 256).
These results support short local text assistance, not autonomous tool use or a
12B recommendation. Phone was USB-powered; no battery-life claim follows. Android
reported thermal status 0 during these short runs, not a sustained thermal pass.

E2B Vulkan offloaded 36/36 layers but failed both exact comparison and repeatability.
It answered `2 + 3` with `8` once and an empty answer on repeat. Generation on the
nonempty runs was about 1.5 tokens/s. GPU chat was blocked and an explicit CPU reload
returned the correct answer. The extra conservative switches did not fix this
Gemma/PowerVR path. An additional standalone CLI probe at the same native revision with only one
GPU layer also failed: repetition returned no text, and arithmetic returned
`getRepository`. Its reported speed was about 0.4 tokens/s; this is an exploratory
CLI result, separate from the paired in-app comparison.

E4B Vulkan was not repeated in this app after E2B failed; the
supplied independent project's E4B Vulkan result also failed.

Raw synthetic measurements: [benchmark directory](../benchmarks/local-llm/pixel-10-pro-fold-2026-10-17/).

The signed 0.13.0/build31 APK is `Bellpull-0.13.0-experimental-build31.apk`,
180,907,432 bytes, SHA-256
`30a7cd200709074e96a28a53fe3b75bd4a75306023956eecc20cf0c624d527ed`.
Signature/lineage and 16-KiB ZIP alignment passed. Installed over build30 in place,
retaining UID10308 and app data; the pulled installed APK matches this hash.
Fixture packages/test weights and the standalone probe were removed. Personal app
is stopped for the user to open; its models, voice choice and server profiles were
not changed. Weights are not bundled; import the desired local GGUF in the lab.


## References

- Supplied GPU experiment's README, RESULTS and independent engine source.
- [llama.cpp Android build documentation](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md).
- [Reported Tensor G5 Vulkan corruption and four-switch workaround](https://github.com/ggml-org/llama.cpp/issues/28502).
  The report is a lead; the supplied Gemma tests did not pass with those switches.
