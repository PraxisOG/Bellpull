# vLLM reasoning controls — app 0.14.7 / plugin 0.6.1

Bellpull now passes a conversation's reasoning choice through stock Hermes to
the configured `qwen38-flash-next` vLLM service. Select the model under **Model ·
change**, then choose Default, Off, On, low, medium or xhigh and Apply. The default
for new chats can also be selected in Settings. Existing conversation choices and
the server-wide default are separate.

## Backend findings

Moe's old hostname address was stale. A reachable LAN address matched all three
previously trusted SSH host keys. SSH authentication was unavailable on this
computer, so the container launch arguments and exact vLLM version remain
unverified. The actual OpenAI-compatible API on port 8080 was reachable and
reported `qwen38-flash-next`, owned by `vllm`, with a 262,144-token maximum context.
No Moe container or inference-service configuration was changed.

The [official model template](https://huggingface.co/Qwen/Qwen3.8-Flash-Next/blob/main/chat_template.jinja)
accepts low, medium and xhigh; xhigh is its default. Low adds a concise-reasoning
instruction and xhigh a careful-reasoning instruction. Off suppresses thinking.
These are soft instructions, not fixed token budgets. The separately documented
[vLLM thinking token budget](https://docs.vllm.ai/en/latest/features/reasoning_outputs/)
is outside this increment and was not verified on Moe's installed version.

Direct probes used the same public arithmetic prompt, temperature 0, seed 7 and
512 maximum output tokens. All answers were 391. With the plugin's standard plus
template fields, Off used zero reasoning tokens; Low/Medium/XHigh used 57/57/42.
Different template prompt counts were 27/55/25/67 tokens, respectively. This is
evidence that the choices reach the template, not a speed or quality ranking.
The standard top-level `reasoning_effort` field alone also worked for all three
efforts. [Direct results](direct-probe.json), [standard-field results](standard-field-probe.json).

## Implementation and compatibility

Modern stock Hermes already supports native per-run `model`, `provider`,
`model_options`, `require_model_lock`, persisted locks and `request_overrides`.
The earlier plugin gated model controls on an optional legacy core extension.
The new plugin feature-detects the stock surface and translates explicit options
through a small adapter-instance wrapper. Native admission, replay, routing,
fallback prevention and transport remain in Hermes. No Hermes core file changes.

The app sends the same selection in both native fields and the existing plugin
envelope. `bellpull_native_model_controls` enables the new client without falsely
advertising the legacy `bellpull_model_controls`/attachment surface to old clients.
Unsupported adapters remain disabled. Request extras merge without modifying
global provider defaults, and unload restores the original method.

Exact custom Qwen aliases receive `enable_thinking` and, for graded choices,
`reasoning_effort` both at the standard top level and inside `chat_template_kwargs`.
Matching fields prevent a configured template value from overriding the explicit
selection. The existing Fireworks translations are also usable on the native path.

## Validation

- Android: 733 declared JVM checks, **732 passed and one existing opt-in skip**;
  lint zero errors/79 existing warnings. Signed ARM64/x86_64 release assembly passed.
  No layout changes or screenshot baseline updates in this increment.
- Plugin: 171 checks against the legacy fixture host, **170 passed and one existing
  skip**. Targeted modern stock-host reasoning (3) and portable installation (3)
  checks passed. The reasoning tests serialize through the real Hermes transport
  and OpenAI SDK, verifying actual HTTP JSON, isolation, validation and unload.
- A disposable stock Hermes 0.21.5 instance with this plugin completed actual Moe
  inference for Off/Low/Medium/XHigh. Each choice persisted per chat; replay of the
  same request returned the original run without another inference; changed
  effort with the same key returned 409. Only public arithmetic text and no tools.
  [End-to-end results](hermes-probe.json).
- The first full plugin run began before the compatibility flag separation and
  failed one legacy-capability assertion. The corrected final tree passed the
  complete suite. No failed check is waived.

Reproduce the live check with an explicitly chosen backend; it performs four
inferences. `HERMES_TEST_IMAGE` is the tested stock image below. This command starts
an isolated home and never mounts the personal Hermes data directory:

```bash
docker run --rm --network host \
  -v "$PWD/plugins/bellpull:/opt/hermes/plugins/bellpull:ro" \
  -v "$PWD/tools/probe-vllm-reasoning.py:/tmp/probe.py:ro" \
  --entrypoint python "$HERMES_TEST_IMAGE" /tmp/probe.py \
  --base-url http://YOUR-VLLM-HOST:8080/v1 --model qwen38-flash-next
```

Tested stock image:
`nousresearch/hermes-agent@sha256:2fd023efbb8d3d2b0ce1a73d028b07370cff34f567cfe0e999553e8c327ea283`.
This probe expects an endpoint accepting the conventional nonsecret `EMPTY` key.
It is not a credential-discovery tool.

## Deployment and rollback

The signed **0.14.7-experimental / build 39** APK was installed in place on the
Pixel 10 Pro Fold. Installed SHA-256 matches the built artifact; UID10308 and the
existing signing lineage are preserved. Profiles, chats, voice selections and
downloaded models were not cleared. This increment did not rerun physical voice
tests or measure reasoning-quality/latency across a workload.

PowerMac's idle gateway was updated to plugin **0.6.1-integration-preview**, with
the old plugin backed up. An optional `providers.custom` route for Moe was added
after a private config backup. Existing config fields and the Fireworks default
were preserved. Authenticated live discovery, configured catalog, and a persisted
Low selection passed; the disposable configuration-check chat was deleted without
starting inference. The phone continues to connect to PowerMac.

For another install, configure the real endpoint in Hermes before selecting it:

```yaml
providers:
  custom:
    base_url: http://YOUR-VLLM-HOST:8080/v1
    api_key: EMPTY  # Use your real server credential if authentication is enabled.
    transport: chat_completions
    default_model: qwen38-flash-next
    models:
      qwen38-flash-next: {}
```

Merge this into existing configuration; do not overwrite another custom route.
The plugin installer does not make this provider configuration change. Roll back
by restoring the complete prior plugin and restarting the idle gateway; restore
the private config backup to remove the optional route. App data remains in place.

APK SHA-256: `29454b33c35eff86c2cbb2876f09048a523902ea8a1bef799da4d210ad952be0`.
