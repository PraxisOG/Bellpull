# Optional legacy Hermes API extension host v1

Stock file sharing uses `plugins/bellpull` 0.2.0 directly. Do not apply this core
patch or build this host image to enable artifacts on a stock install. This
directory remains for regression checks and existing model/input/approval
compatibility. Portable installation instructions are in
[`plugins/bellpull/README.md`](../plugins/bellpull/README.md).

This is a narrow local compatibility patch for Hermes Agent `v2026.9.7`
(`2237be355906fbe6065ce1815711eee52b2d646e`, internal `0.21.1`). It is not
advertised as upstream support. `apply.py` rejects unsupported/mixed source
checksums; the Dockerfile builds directly from the pinned upstream digest.

`api_server_extensions.py` defines typed `PreparedRun`/`Hooks` contracts. Plugins
register during normal handler-factory wiring. Preparers return execution input,
plain persisted text, ephemeral instructions, validated model options and start/
finish callbacks. Preparation follows authenticated replay lookup and precedes
atomic reservation; it must be read-only/idempotent. Only the admission winner
runs start/finish effects. The native fingerprint still covers the original body.
Replays bypass fresh catalog/file validation and concurrency checks.

The host exposes narrow scoped status observations (including a stale owner's
interrupted status), bounded per-route body limits, and provider transport options.
It owns all authentication, native admission, run status, task cleanup, cancellation,
and approval policy. Explicit Boolean `interactive_approvals` applies only inside
that worker's context and is reset in `finally`; default API calls stay unattended.
Persisted model locks also govern native durable runs. No Bellpull imports or
business rules are added to Hermes core.

`native-api-hooks.patch` is the complete reviewable change. `source-sha256.json`
records all before/after hashes; `LICENSE.hermes-agent` preserves the copied code's
MIT notice. Reapplying verifies an exact contract-module match instead of guessing.
Use `python server-host/apply.py --source PATH` to check an offline source tree;
only `--apply` changes that tree. No live container/configuration is touched.

Before supporting another Hermes release, regenerate the minimal diff against
that exact source and run the entire plugin/native contract suite, including
legacy default approvals. Newer upstream approval-presence logic must be tested
against the explicit opt-in contract before removing this compatibility code.
Do not carry this patch forward with fuzzy matching or adapter monkey-patching.

Installation, data migration and rollback are in
[the plugin guide](../plugins/bellpull/README.md).
