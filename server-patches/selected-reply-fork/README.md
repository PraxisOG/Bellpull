# Selected-reply branching for Hermes Android

Prepared and activated October 8, 2026, with the user's explicit authorization to apply
the server patch and control the Hermes host. The phone now exposes selected-reply branching.

The Android response toolbar requires branching through one saved assistant message while
leaving the original conversation usable. The installed API's existing fork operation
copies all messages and ends the original. This patch adds an explicit capability and a
separate authenticated endpoint. The existing fork endpoint remains unchanged. A separate
route makes older servers reject a request even if the app's cached capability is stale.

## Contract

`GET /v1/capabilities` advertises `features.session_fork_at_message: true`.
The Android client must require that flag before sending:

```http
POST /api/sessions/{source_id}/fork-at-message
Content-Type: application/json

{"through_message_id": 123}
```

The cutoff is an inclusive, positive integer identifying an active saved assistant message
with text in the transcript returned by the source's history endpoint. Optional `id`
(`session_id` alias) and `title` retain their existing meanings. Success is HTTP 201 with
`{"object":"hermes.session","session":{...}}`.

The new child preserves the source model, model configuration, and deduplicated system
prompt. It copies the saved transcript prefix in a single database transaction. It uses
Hermes' existing `_branched_from` marker, so normal session lists show the branch and
resuming the source does not redirect into it. `_branched_at_message_id` records the cutoff.
Later messages and the original's metadata are untouched. The branch starts fresh usage
accounting, with accurate copied message/tool-call counts. Copied rows receive the branch's
creation timestamp, so a fresh branch appears at the top of the recent-conversation list;
the original timestamps remain untouched. No inference or tools run on
branch creation. If the selected assistant message requested tools, its still-pending calls
are omitted because their results fall outside the selected prefix.

Invalid/foreign/non-assistant cutoffs fail with HTTP 400 `invalid_branch_message`; missing
sources return 404; duplicate destination IDs return 409; conflicting titles return 400.
Failed copies roll back both the new session and its messages. The client keeps the
original open on failure and displays the server error. The Android patch is in
`/home/praxisog/Hermes-App-Project`.

## Files and validation

- `selected-reply-fork.patch`: reviewable diff of `gateway/platforms/api_server.py`.
- `original/`: code copied read-only from the running container for reference/rollback.
- `patched/gateway/platforms/api_server.py`: replacement API module.
- `source-sha256.txt`: hashes used to avoid overwriting an unrecognized server version.
- `test_selected_fork.py`: disposable-database HTTP tests, including exact cutoff,
  original/model preservation, visible branches, invalid/foreign IDs, authentication,
  compressed-session resolution, failure rollback, duplicate-request races, tool prefixes,
  and compatibility with the existing full-conversation fork.
- `Dockerfile`: built locally as `hermes-agent:selected-reply-fork-20261008`.

Tests run inside the built image with network access disabled and a temporary Hermes home.
No live data/configuration or credentials are mounted:

```bash
docker run --rm --network none --entrypoint python \
  -e HERMES_HOME=/tmp/hermes-test-home -e PYTHONPATH=/opt/hermes \
  -v /home/praxisog/hermes-agent/patches/selected-reply-fork/test_selected_fork.py:/tmp/test_selected_fork.py:ro \
  hermes-agent:selected-reply-fork-20261008 /tmp/test_selected_fork.py
```

## Activation and rollback

Activation restarts the shared `hermes-gateway` container. The user explicitly authorized
this on October 8, and the patch has been installed and activated.
`activate.sh check` verifies the live source/image without changing either. After approval,
`activate.sh apply` installs only the API module and restarts that container. The paired
`activate.sh rollback` restores the original module and restarts it again.

This direct installation persists across container restarts, but **container recreation
reverts to its configured image**. The separately built image is the durable deployment
artifact. The container labels name `/home/praxisog/hermes-agent/infra/docker-compose.yml`,
but that file is absent, so no deployment definition was rewritten or reconstructed from
its secret-bearing environment. Set the gateway service's image to
`hermes-agent:selected-reply-fork-20261008` in the maintained deployment definition before
its next recreation. Keep the other service settings unchanged.

After future activation, reopen the Android app so it fetches the new capability. Test a branch
from an earlier assistant reply in a disposable conversation, confirm later messages are
excluded, then reopen the original to confirm it remains unchanged. The app automatically
disables this action again if a future gateway reports no selected-reply capability.

The deployed handler was exercised from the real phone. Branching at the first of two
assistant replies created a two-message chat; the four-message original's history and
metadata were unchanged. Twelve isolated server tests pass, including fresh-branch sorting.
Live test artifacts are under the Android project's
`android/app/build/device-tests/2026-10-08-wave3/`.
