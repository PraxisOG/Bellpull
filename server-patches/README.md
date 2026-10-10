# Server extensions for Bellpull

These bundles were imported from the working host on 2026-10-09 so the Android repository can be handed to another server without losing its required API changes. They contain source, reviewable diffs, isolated tests and historical activation helpers. They do not contain live configuration, databases or API credentials.

Build the image chain in this order from the repository root:

```sh
docker build -t hermes-agent:selected-reply-fork-20261008 server-patches/selected-reply-fork
docker build -t hermes-agent:bellpull-edit-fork-20261009 server-patches/bellpull-edit-fork
docker build -t hermes-agent:bellpull-phase2-20261009 server-patches/bellpull-phase2
```

The first Dockerfile is based on `nousresearch/hermes-agent:v2026.9.7`; the later images extend it. These are snapshots against that tested server version, not automatic upgrades for arbitrary newer Hermes versions.

- [selected-reply-fork](selected-reply-fork/README.md) adds branching through a saved assistant reply while preserving the original chat.
- [bellpull-edit-fork](bellpull-edit-fork/README.md) adds branching immediately before a saved user message for prompt editing.
- [bellpull-phase2](bellpull-phase2/README.md) adds request-scoped interactive approvals and an authenticated ntfy event relay. It builds on the durable run API already present in the tested base.

Each bundle includes its test instructions. The latest validation passed eight Phase 2 tests and 16 branch/edit regressions in isolated containers with no live mounts or inference access. The Android contract and device results are in [the API documentation](../docs/hermes-api.md) and [handoff](../docs/HANDOFF.md).

The `activate.sh` and `activate.py` files describe the original host installation: container name `hermes-gateway`, loopback API port 8642, local credential-file paths and exact source/image hashes. **Review and adapt those helpers for a different host before using them.** Build alone does not deploy anything. Maintain the new host's own deployment configuration and use the final derived image for container recreation; copying modules into a running container does not update that configuration. The relay expects the existing ntfy service at loopback port 2586 in the gateway's network context.

`import-source-sha256.json` records the source hashes at import. Only two explanatory comments were added to the copied Phase 2 test to identify its dummy fixture credential for secret scanning. The original host bundles were not modified. The `original/`, `patched/` and `revisions/` files include upstream Hermes source; its [MIT license](LICENSE.hermes-agent) is preserved here.
