# Moe named-route reasoning — plugin 0.6.2

Fixes the source defect reported in [#27](https://github.com/PraxisOG/Bellpull/issues/27).
Android 0.14.9/build41 is unchanged. The user will install this plugin on Moe.

## Cause and scope

0.6.1 recognized `custom/qwen38-flash-next` and official Qwen model IDs. Moe's
configured route is `moe/moe-local`, which fell through to CustomProfile's broad
reasoning vocabulary. Minimal/High/Max could therefore be persisted despite being
rejected by the backend. Native Hermes can subsequently normalize a named custom
provider to `custom`, losing the identity needed for template overrides too.

0.6.2 recognizes the exact verified `moe/moe-local` pair. The catalog advertises
Low, Medium, XHigh, Off and On; the app supplies Default. A named request keeps its
verified identity for the translation only when the resolved model still matches
and the resolved provider is that provider or `custom`. Other resolved models do
not inherit Qwen controls. Unknown aliases retain their existing contracts; no
endpoint fingerprint or inference-time capability guessing was added.

Unsupported saves return 409 without overwriting the previous valid selection.
An invalid confirmed lock saved by an older plugin is rejected before agent
construction with instructions to open Thinking level and select Default or a
supported choice. Choosing Default repairs the same conversation and preserves
its provider/model/history. The plugin does not silently choose a different
reasoning level or retry inference with different options.

## Validation

- Full legacy supported-host plugin suite: **176 tests, 175 passed, one existing skip**.
- Stock Hermes 0.21.5: **eight targeted reasoning/catalog tests passed**. These cover
  exact aliases, request validation, native named-provider normalization, actual
  OpenAI SDK JSON, unrelated defaults, other resolved models and plugin unload.
- Twelve native API/catalog tests passed on the legacy supported-host fixture,
  including rejected saves leaving the valid lock intact and resetting an old
  invalid SQLite lock through the existing endpoint.
- Android: **four actual workspace/thinking-menu UI tests passed** for save,
  dismissal, reopening, Default, back navigation and rejected-save handling.
  No app code/layout changes, new APK build or new screenshot baselines.
- Live probe: disposable stock Hermes 0.21.5, this plugin, a named `providers.moe`
  route and a temporary model-alias relay to Moe's actual vLLM API. The relay only
  rewrites `moe-local` to the served `qwen38-flash-next` ID. It leaves thinking
  fields intact and never changes the actual backend/container configuration.
- Minimal/High/Max saves were rejected without inference or changing the valid
  choice. A seeded old High lock failed through the actual native session chat
  stream with the reset instruction and zero inference. Default then recovered
  that same stream and returned 391 for the public arithmetic prompt.
- Default, Off, On, Low, Medium and XHigh each completed through the actual Hermes
  transport and vLLM, returned 391, persisted their choice, replayed a durable run
  without another inference and rejected a changed choice using the same key.
  **Seven successful live inferences total** (recovery plus six choices).
  [`named-route-probe.json`](named-route-probe.json) records the six mode results;
  [`recovery-checks.json`](recovery-checks.json) records the rejection/recovery checks.

Default inherited Medium in this isolated Hermes configuration. Default means
Hermes defaults, not necessarily the model template's own XHigh default. Off sent
`reasoning_effort=none` and `enable_thinking=false`; graded choices sent matching
standard/template efforts. These are control-path checks, not reasoning quality
or speed benchmarks.

The initial extended probe incorrectly tried to exercise a persisted session lock
through stock 0.21.5's bare durable-run route, which does not load that lock.
The corrected probe uses the native session chat stream for persisted-lock
recovery and explicit native selection fields for durable runs. A dedicated
relay thread also avoids blocking synchronous model-metadata lookup during
agent construction. No failed product check was waived.

## Reproduce

Run from this checkout with Docker. All Hermes state is disposable; only a fixed
public arithmetic prompt is sent and tools/memory are disabled. This sends seven
successful requests to the explicitly selected backend.

```bash
docker run --rm --network host \
  -v "$PWD/plugins/bellpull:/opt/hermes/plugins/bellpull:ro" \
  -v "$PWD/tools/probe-vllm-reasoning.py:/tmp/probe.py:ro" \
  --entrypoint python \
  nousresearch/hermes-agent@sha256:2fd023efbb8d3d2b0ce1a73d028b07370cff34f567cfe0e999553e8c327ea283 \
  /tmp/probe.py --base-url http://YOUR-VLLM-HOST:8080/v1 \
  --provider moe --model moe-local --backend-model qwen38-flash-next
```

The probe expects the conventional nonsecret `EMPTY` backend key. It does not
read personal credentials. The exact Moe relay/gateway installation (reported
Hermes 0.21.6) was not updated or tested in place: this computer's SSH key was
rejected and the user elected to install the published plugin themselves.
