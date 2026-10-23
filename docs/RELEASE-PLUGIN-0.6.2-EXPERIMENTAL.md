# Bellpull plugin 0.6.2 experimental

Fixes [#27](https://github.com/PraxisOG/Bellpull/issues/27): Moe's named
`moe/moe-local` route advertised unsupported thinking levels, which could leave
subsequent messages failing with HTTP 400.

- The menu now offers **Default, Off, On, Low, Medium and XHigh** for this route.
- Minimal/High/Max are rejected before saving; the previous selection survives.
- Named-provider identity survives Hermes' normalization to `custom`, so explicit
  thinking choices reach the actual vLLM template.
- An older invalid saved choice gets a clear reset instruction. Select
  **⋮ → Thinking level → Default** in that conversation, then retry the message.
- **Existing Android 0.14.9/build41 works unchanged.** The included APK is the
  previously tested, signed 0.14.9 artifact; no phone reinstall is required.

## Install on the Hermes server

Download and unpack `bellpull-plugin-0.6.2-integration-preview.tar.gz`, then run
from its extracted directory with the actual data home used by your gateway:

```bash
python3 tools/install-plugin.py --home /YOUR/HERMES/DATA/HOME --apply
```

The installer backs up the previous plugin automatically. Restart the Hermes
gateway when idle using its existing service manager, then reopen Bellpull to
refresh its catalog. Existing enabled-plugin configuration can remain in place.
No vLLM restart, provider reconfiguration or chat deletion is required.
Roll back by restoring the backed-up complete plugin and restarting the gateway.

Validation: **175 plugin tests passed, one existing skip**; eight targeted stock
Hermes tests and four app thinking-menu UI tests passed. An isolated named-route
Hermes instance completed **seven real vLLM inferences**, verified every menu
choice, rejected unsupported saves, recovered an old invalid lock and checked
idempotent durable replay. The user's exact Moe gateway still requires installation;
the user is handling that step. Tested stock Hermes was 0.21.5; the reported Moe
installation is 0.21.6.

[Detailed evidence](https://github.com/PraxisOG/Bellpull/blob/fix/moe-reasoning-062/docs/evidence/moe-reasoning-20261022/README.md).

APK SHA-256 (unchanged): `3cc32e02547ee7111618f50db1318e6b456b7bfa8f13c8511adecc3e125f9f4e`.
