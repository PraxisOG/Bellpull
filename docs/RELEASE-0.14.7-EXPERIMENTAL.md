# Bellpull 0.14.7 experimental

Choose Qwen thinking effort in Bellpull and have the plugin pass it through stock
Hermes to vLLM. The configured `qwen38-flash-next` model supports Off, On, Low,
Medium and XHigh. Default keeps the server's reasoning choice. Levels guide
reasoning depth; they do not impose a fixed thinking-token limit.

- **Android:** 0.14.7-experimental, build39.
- **Plugin:** 0.6.1-integration-preview; install the complete matching plugin and
  restart Hermes when idle. No core patch required on the tested stock 0.21.5.
- **Use:** open **Model · change**, select the configured Qwen model, choose its
  thinking level and Apply. Settings also offers a default model for new chats.
- **Recovery:** choices persist per conversation. Retrying an admitted request
  preserves its original model and effort without another inference.

Validation: 732 passing JVM checks plus one existing skip; lint with no errors;
signed ARM64/x86_64 assembly; 170 passing plugin checks plus one existing skip;
six targeted stock-host checks; actual Moe inference through plugin → Hermes →
vLLM for Off/Low/Medium/XHigh, including persistence and replay/conflict checks.
The signed app was installed in place and the live PowerMac plugin/catalog checked.
No physical voice or reasoning-quality benchmark was repeated for this increment.
See [evidence and compatibility limits](evidence/vllm-reasoning-20261022/README.md).

Update in place to preserve profiles, chats, settings and downloaded models.
[Download the experimental release](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.14.7-experimental.1).

APK SHA-256: `29454b33c35eff86c2cbb2876f09048a523902ea8a1bef799da4d210ad952be0`.
