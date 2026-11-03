# Prioritized mobile bug repairs

Worktree: `Bellpull-mobile-bugs`, branch `fix/mobile-bugs-20261102`, based on
`6efc813` (published Android 0.19.0/build48 and plugin 0.8.3 source).
The development server is the owner's local Docker Hermes, with isolated test
homes and synthetic inference. The personal Moe server is outside this work.

## Priority and disposition

| Order | Issue | Reason | Disposition |
| --- | --- | --- | --- |
| 1 | #47 Chat attachments | Blocks a basic chat workflow | Implemented stock-Hermes attachment handoff after native replay lookup and before admission. |
| 2 | #27 Thinking settings | Invalid choices can prevent further messages | Existing 0.6.2 repair verified; exact named-route options and old-lock recovery tests pass. |
| 3 | #37 Approvals interrupt voice | Breaks an active conversation | Separate approval waiting from history-review failures; keep voice/playback alive, queue new speech, use silent notifications. |
| 4 | #16 Read aloud routes to phone | Makes car playback unusable | Existing media-focus/output repair verified in tests. Physical car-route acceptance remains open. |
| 5 | #24 Noise tolerance | Background noise can create unwanted dictation | Exposed the per-model threshold in regular voice settings, using existing persistence and next-load behavior. |
| 6 | #48 SVG upload/preview | Blocks generated diagrams | Added MIME support, native previews and thumbnails using user-approved AndroidSVG 1.4. |

## Implementation

Native attachment requests keep their original bodies and idempotency keys.
The native journal remains the only admission authority. The plugin adds model
and file validation at the native lookup boundary, then supplies files to that
run's agent and associates uploaded/generated artifacts with saved transcript
rows. It does not patch Hermes core or alter model defaults. Files remain scoped
to their conversation and active profile. The live desktop Bot Chat mailbox is
text-only; attachment turns targeting its active lease fail explicitly.

Approvals stay pending in the existing chat and notification inbox. New speech
waits for that run rather than implicitly approving, denying or cancelling it.
Connection loss and history-review failures still pause voice. Quiet notification
channels avoid an audible approval notification taking audio focus during voice.

Noise tolerance ranges from 0.1 to 0.9, defaults to 0.5, and belongs to the
selected on-device Moonshine model. Saving preserves its other tuning values.
It changes native VAD on the next model load; this is not a new microphone energy
gate or a measured acoustic improvement.

SVG originals remain intact. A bounded XML sanitizer excludes active/external
content, rejects DTDs and cyclic/expansive references, and feeds a native renderer
with no external resolver. SVG source is a document for model input; it is not
silently sent to a different vision provider. Unsupported rendering features can
be omitted; original Save/Share remain available. AndroidSVG is Apache-2.0 and
its license ships in the app's assets.

## Validation

- 32 focused container checks pass on the current stock image, covering native
  attachment admission/replay/conflicts, images/documents/SVG, concurrent chats,
  live-owner rejection, artifact persistence/lineage, reasoning, plugin rediscovery
  and profile isolation.
- 13 attachment/reload checks pass on the earlier supported stock image, with one
  expected skip because that host predates live desktop Bot Chat ownership.
- Focused Android voice, notification, settings persistence and SVG checks pass.
  SVG tests include native raster output, external-content exclusion, DTD/entity
  rejection, reference cycles and dimension/source limits.
- New SVG/noise screenshots at cover and unfolded widths were visually inspected.
  The two existing local-settings baselines were intentionally updated for the
  now-visible noise control.
- Full Android checks pass: 729 app tests, 48 core Hermes tests and 37 core model
  tests, with one opt-in live-server test skipped. `verifyRoborazziDebug` and
  `lintDebug` pass. The isolated fixture app and instrumentation APK both build.
- The wireless Pixel 10 Pro Fold passed the isolated share/upload/run/history/SVG
  preview test against the native adapter inside the local Hermes container.
  See [device evidence and reproduction](evidence/mobile-bugs-20261102/README.md).

## Remaining acceptance

Physical car media routing and navigation/call interruptions need the actual car
and chosen output engine. Noise rejection needs listening in the owner's actual
environment. These are not established by JVM tests or synthetic inference.

No GitHub issue has been closed and no release has been published by this work.
At the initial repair handoff, the normal phone app and main local gateway plugin
retained their previous installed versions. Fixture acceptance used a separate
package, loopback port and disposable server home.

## Signed phone update follow-up

The user subsequently requested installation. The candidate is Android
**0.19.1-experimental / build 49**, paired with plugin
**0.8.4-mobile-fixes-preview**. The signed APK uses the existing release certificate
and preview-to-release lineage; the prior installed APK is retained locally.
Installation verification is recorded below after the in-place update completes.

The plugin is required for stock-Hermes chat attachment handoff and SVG MIME
support. Android-only voice approval/noise controls work without the new plugin;
SVG previews also need the new app. Install the whole plugin bundle, restart the
gateway when idle, and reconnect the app to refresh capabilities. Moe remains a
manual user deployment. No server deployment or GitHub publication is part of
this phone-install follow-up. The latest published experimental remains 0.19.0.

