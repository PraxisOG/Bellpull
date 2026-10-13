# Phase 6 preview: monitor OpenCode through Hermes

Independent branch `feature/phase6-opencode-activity`, based on corrected 0.5.5.
This is a local preview, not a published release or a personal-server deployment.
The personal app remains corrected Phase 5. The user returned ADB for isolated
Phase 6 acceptance, followed by planning acceptance only after Phase 6 passes.

## Implemented slice

Chat → Activity observes existing Hermes-owned OpenCode background processes. It
never starts another runner or inference. The plugin binds an exact terminal tool
receipt to the active Hermes run/turn, session, owning task and process incarnation.
Simple JSON launches show typed completed text/reasoning/tool parts. Simple PTY/TUI
and ordinary run launches have an explicitly labeled inert text fallback. Shell
pipelines, administrative/attach commands and foreground-only work are not attached.
No OpenCode-server adapter, live token promise or OpenCode approval/question control
is implied. Existing Hermes approvals, Stop and chat follow-up remain available.

The plugin journals bounded activity in profile-local SQLite and exposes authenticated
read-only APIs documented in [the contract](hermes-api.md). Native profile prefixes
and legitimate compression lineage are preserved; ordinary branches remain separate.
Reconnect uses durable cursors and snapshots. Tail loss, retention and restart are
visible. Process exit is distinct from parent completion and step completion. A
missing process becomes lost, never successful. Running records are not evicted to
make room for newer work; capacity exhaustion is an explicit incomplete view.

Workspace changes list bounded Git changes and read-only text diffs using opaque
file IDs. Symlinks, traversal, binary/large data, external diff/textconv/hooks and
parent-repository expansion are refused. Changes are not attributed exclusively
to OpenCode. Phone state is an encrypted, connection-scoped cache; drafts and run
ownership continue through the existing chat runtime.

## Local verification — October 12

- **Android:** 532 discovered JVM tests, 531 passed and the opt-in live-provider
  smoke test skipped; 164 screenshot baselines verified; debug APK and lint passed.
  New coverage exercises cursor merging, profile-prefixed GETs, retry/no-admission,
  shutdown, cache isolation, output expansion and both display widths.
- **Python:** all 18 focused coding tests passed against disposable unmodified
  Hermes commit `d4d2ab7b5d104166e7e22a02cafd12f1fe456a31`. This includes one actual
  native adapter/plugin-hook/ProcessRegistry integration with exactly one admitted
  run and a deterministic local executable named `opencode`. It is not a real
  OpenCode/provider run. Core file hashes stayed unchanged; wrong bearer auth failed.
- The complete plugin suite was also attempted on that newer stock checkout:
  **97 tests, 20 failures and 4 errors**. The legacy source-pinned branch/pagination
  routes are deliberately disabled on this unreviewed core revision, and the optional
  extended run module is absent. These are not passing checks. The pinned Docker
  CI host is unavailable locally; no fresh CI result or provider/device result is claimed.
- A second **local compatibility fixture** combines the same newer tree/plugin loader
  with archived hash-verified legacy adapter/approval modules, the optional host v1
  overlay and a synthetic 0.21.1 install stamp. It ran **116 tests: 113 passed, three
  legacy approval failures**. The same three failures reproduce with unchanged 0.5.5
  plugin `b25d09c` (10 approval tests, three failures). This narrows the limitation to
  the mixed fixture's upstream approval compatibility; it is not evidence that the
  exact pinned Docker CI host passed. Logs: `plugin-legacy-fixture-final.log` and
  `legacy-approval-baseline.log`. No deployment uses these mixed modules.
- Logs: `build/phase6/android-final.log`, `python-final.log`,
  `plugin-full-stock.log`. No personal server, phone, push, merge or release publication.

## Isolated phone acceptance — October 12

On Pixel 10 Pro Fold / GrapheneOS API 37, two instrumentation tests passed at
both the physical unfolded viewport and a simulated narrow 1080×2424 viewport.
The latter is a display override, not a physical fold/hinge transition test.
`CodingActivityDeviceTest` exercises the actual MainActivity/runtime, encrypted
cache, HTTP client and plugin against unmodified disposable stock Hermes. One
native run starts two deterministic executables named `opencode`, covering JSON
and ordinary terminal output. Reasoning expansion, workspace file selection/diff,
background catch-up, injected 503/disconnection, Activity recreation, retry,
chat/draft isolation, independent child completion, final history reconciliation,
cache reopening, compression and ordinary branch isolation passed. Exactly **one
admission per run of the test**; two injected failures recovered. The carried
startup/missing-model regression also passed. All 18 focused Python coding tests
were rerun and passed.

The initial UI harness could touch the clipped part of a button. It now checks
its clickable ancestor against the scroll viewport, scrolls fully into view and
injects a proper touchscreen gesture after window animations settle. No product
change was necessary. The fixture uses no real inference/OpenCode binary/provider,
and does not prove personal-host Stop or deployment compatibility. Existing
full-suite/legacy CI limitations above still apply. Personal app/accounts/server
were not used; no push or publication.

Reproduce with a fresh `tools/coding-device-fixture.py --hermes-source /PATH/TO/STOCK`,
reverse TCP 18648, then build with `-PbellpullDeviceFixture=true` and install only
`org.praxisog.hermes.fixture` plus its test APK. Run only `CodingActivityDeviceTest`
and `VoiceStartupDeviceTest`; restart the host before repeating (one admission
allowed). Do not run unrelated destructive instrumentation on personal app data.
Ignored evidence: `build/phase6/device-{tests,narrow-tests,python,build,server}.log`
and `build/phase6/device-{wide,narrow}/files/*.png`.

## Compatibility and installation when ready

Use this branch's single Bellpull plugin; its preview is `0.4.0-phase6` and discovery
must advertise `bellpull_coding_activity`. Existing chat/voice/files remain capability
gated and work with the older plugin. New observation requires modern identity-bearing
post-tool hooks and process ownership fields. Unsupported hosts show an update message.
Do not install the separate planning branch's plugin expecting coding features.
Back up the profile home; plugin storage is additive under `bellpull-coding/`. Restore
the previous plugin and restart the gateway to roll back; do not replace core modules.

## Manual acceptance on return

1. Install the preview APK/plugin manually on a disposable or backed-up stock host.
   Refresh discovery; check root and a served named profile without root fallback.
2. Ask Hermes for one normal OpenCode task. Confirm Chat/Activity shows the same run
   and child, with no second admission. Check JSON and ordinary PTY/text invocations.
3. Expand output, preserve a draft, switch chats/projects, background and reconnect.
   Confirm no duplicates; gaps and stale cached child states must remain visible.
4. Stop through the existing Hermes control. Verify the actual child on the host;
   older/remote backends may not reap it. No fake OpenCode approval buttons should appear.
5. Inspect changed/untracked/binary/large/symlink files read-only. Confirm other actors'
   edits are labeled Workspace changes. Test a compressed continuation and an ordinary
   branch/profile; only legitimate ownership shares activity.
6. Restart the gateway with a running child. Lost identity/output must not become success.
   Reopen and follow up exactly once through normal chat.

## Source findings

| Evidence | What it establishes | What it does not establish |
| --- | --- | --- |
| Existing [Bellpull contract](hermes-api.md), StreamEvent/RunStatus/Message/ToolResult | Hermes run lifecycle, replay/reconciliation, genuine approvals where supported, tool status and persisted tool results | OpenCode session binding, full live subprocess output, workspace/file-diff authority |
| [Hermes OpenCode skill](https://github.com/NousResearch/hermes-agent/blob/d4d2ab7b5d104166e7e22a02cafd12f1fe456a31/skills/autonomous-ai-agents/opencode/SKILL.md) | Hermes can invoke the CLI through terminal/process tools, including background PTY and structured JSON output | Which invocation/version/configuration the personal server uses |
| [Hermes process registry](https://github.com/NousResearch/hermes-agent/blob/d4d2ab7b5d104166e7e22a02cafd12f1fe456a31/tools/process_registry.py) | Process ownership/task/session context, cwd, rolling output and exit observations exist internally | A supported authenticated mobile API or a durable lossless replay feed |
| [OpenCode 1.19.0 CLI](https://github.com/anomalyco/opencode/blob/733b2c627e5aec39cb33cc22373bdce33053ef2c/packages/opencode/src/cli/cmd/run.ts) | JSON lines include timestamp/sessionID and selected completed parts | Live token/tool-start deltas, resumable cursors, interactive questions or final-run evidence in that output |
| [OpenCode server docs](https://opencode.ai/docs/server/) | A separate OpenCode server can expose session messages, events and diffs | That Hermes launched one, that a separately started server owns the existing work, or that the phone should connect directly |

The stock Hermes disposable source checkout is pinned to
`d4d2ab7b5d104166e7e22a02cafd12f1fe456a31`; tracked files are unmodified.
OpenCode source was checked at released **v1.19.0**, commit
`733b2c627e5aec39cb33cc22373bdce33053ef2c`. Current main was also located but is
already reorganizing package paths; do not design a versionless adapter around it.
Nothing here establishes the user's installed versions.

Important source details:

- Hermes process IDs (`proc_…`), Hermes conversation/run IDs and OpenCode session
  IDs (`ses_…`) are separate. An OpenCode provider/relay header is also unrelated
  to the coding CLI session. Never match by title, cwd alone, PID alone or by
  finding the word OpenCode in a terminal command.
- CLI JSON mode emits tool parts after completion/error and text after its part
  finishes. A step finishing is not the whole task finishing. EOF, lost connection,
  empty output or a Hermes coordinator completion do not prove its child stopped.
- In the checked non-interactive CLI, permission requests are rejected unless an
  explicit automatic mode changes that behavior. Permission warning text may
  appear beside JSON lines. Do not render it as an actionable pending approval or
  enable automatic permissions to make monitoring convenient.
- The process registry has a single `on_output` sink. Replacing it would steal
  another consumer's events. Its rolling tail can lose earlier data. A future
  observer needs compatible composition, retention and lifecycle cleanup; it
  cannot advertise lossless replay merely by polling the tail.
- Newer stock source includes bounded tool-completion previews. That is source
  evidence of upstream drift, not a verified change to Bellpull's documented
  installed-server contract or a substitute for complete command output.
- Run Stop in the inspected Hermes source tracks and reaps owned processes using
  ownership/epoch evidence. Older builds/remote backends may differ; current
  Bellpull Stop remains the only enabled control. Do not advertise that it kills
  every OpenCode child on the personal host before verification.
