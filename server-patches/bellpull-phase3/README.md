# Bellpull Phase 3 gateway extension

Adds configured model/reasoning controls, persistent authenticated attachments and
generated-file discovery to the tested Phase 2 image. This snapshot targets Hermes
`v2026.9.7` plus the preceding Bellpull patches, not arbitrary upstream versions.
`phase3.patch` and `source-sha256.json` make the exact changes reviewable.

From the repository root, after building the preceding images:

```sh
docker build -t hermes-agent:bellpull-phase3-20261010 server-patches/bellpull-phase3
docker run --rm --network none -e HERMES_HOME=/tmp/bellpull-phase3-tests \
  -v "$PWD/server-patches:/tmp/patches:ro" \
  --entrypoint /bin/bash hermes-agent:bellpull-phase3-20261010 \
  -c 'cd /opt/hermes && PYTHONPATH=/opt/hermes python /tmp/patches/bellpull-phase3/test_artifacts.py && PYTHONPATH=/opt/hermes python /tmp/patches/bellpull-phase3/test_http.py && PYTHONPATH=/opt/hermes python /tmp/patches/bellpull-phase2/test_phase2.py && PYTHONPATH=/opt/hermes python /tmp/patches/bellpull-edit-fork/test_selected_fork.py'
```

The tests use disposable SQLite stores and fake inference. The HTTP suite exercises
the real adapter and Hermes request builder, including image bytes, upload limits,
repeatable protected download, idempotent admission, model scope and reasoning fields.
`emulator_fixture.py` additionally serves a disposable Android acceptance environment
with a deliberately failed first upload and generated PDF/text fixtures. It cannot
run with a production HERMES_HOME and contains no upstream credentials.

For the original development host only, `python3 activate.py check|apply|rollback`
recognizes exact installed hashes, refuses to interrupt active agent/background work,
and copies/restarts the gateway. Review its container name, URL and credential-file
path before adapting it elsewhere. A source copy survives restart but not container
recreation: configure your deployment to use the derived image. The helper does not
change model routes, credentials, ports or global inference defaults.

Artifacts live under `$HERMES_HOME/bellpull-artifacts`, separated by authenticated
principal/profile scope, with a 512 MiB quota per scope. Preserve that volume during
deployment. Files are capped at 25 MiB each and per turn, eight attachments per turn.
There is no automatic retention deletion in this preview. Removing an Android profile
removes local copies only. Rollback removes the new API modules but retains server files.
Close/settle active runs before rollback; clients connected to an older server disable
these features on capability refresh.

The [GPU plugin](../../plugins/bellpull_gpu/README.md) is installed and enabled
separately on the Hermes host. It reads local CLI telemetry, not an inference endpoint
or an SSH target. Missing hardware/tools are reported as unavailable.

Model controls use configured Hermes routes and provider-declared capabilities.
Fireworks DeepSeek V4/V4.1 maps explicit low/high/max/Off to `reasoning_effort`.
The verified custom `qwen38-flash-next` route maps On/Off to
`chat_template_kwargs.enable_thinking`; no graded local effort was verified.
Installing this patch does not configure a custom route or change other chats' defaults.

See [the API contract](../../docs/hermes-api.md) and [handoff](../../docs/HANDOFF.md)
for exact wire formats, deployment evidence and hardware-testing limits.
