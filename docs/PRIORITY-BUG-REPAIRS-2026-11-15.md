# Experimental priority bug repairs — November 15, 2026

Candidate: Android **0.20.1-experimental / build 51**, paired with plugin
**0.9.1-reliability-preview**. Branch `fix/experimental-priority-bugs-20261115`,
based on published 0.20.0 source `9c8f3a2`. This is a local candidate, not a
published release or a live server deployment.

## Changes

- **#51 — Background results:** track dispatched delegation identities beyond the
  parent run, persist pending/delivered receipts per server profile, recover after
  process death, and surface canonical results as separate Background result
  bubbles. Pending work stays visible in the chat/list. Result delivery uses GET
  requests only. It cannot launch a model turn or cross an approval gate.
- **#56 — Voice coherence:** tool/search/working events no longer flush unfinished
  prose. Ready answer sentences precede waiting cues; only one unplayed activity
  cue is retained. Sentence detection preserves trailing whitespace through Canvas
  filtering, so a complete sentence does not need another word to become audible.
  The four announcement flags, final-tail flush, echo rejection, focus recovery,
  cancellation and synthesis-ahead queue remain covered by regression tests.
- **#50 — Reply links:** incorporates the previously unpublished November 9 repair.
  Bare HTTP(S)/www links use AndroidX detection and the existing scheme policy;
  explicit Markdown links, long-press actions and text selection retain their
  behavior. Both saved and streaming replies were checked by real phone touches.
- **#52 — Dialog keyboard behavior:** incorporates the shared edge-to-edge dialog
  wrapper, one inset owner and scrolling when a keyboard leaves very little
  height. Normal and immersive project editors return to their pre-keyboard bounds
  after Back and remain touchable when the keyboard reopens.

Full background recovery needs the matching plugin. Its read-only snapshot path
now works on the tested stock Hermes host as well as the legacy compatibility
fixture and exposes only the delegation identity fields needed by the client.
See [the protocol](hermes-api.md#background-delegation-delivery--november-15-2026).

## Validation

- **895 Android JVM tests passed; one opt-in live-server check skipped.**
  App: 805; core-model: 42; core-hermes: 48 passed / one skipped.
- **221 screenshot baselines verified.** The earlier UI repair contributes 19
  reviewed dialog baseline updates and three new baselines; this integration does
  not regenerate them. Lint has zero errors (95 warnings, six hints).
- Targeted voice tests cover tool boundaries with all announcements disabled,
  source order, final unterminated tails, cue coalescing and interrupted playback.
- Background tests cover process restoration, duplicate delivery IDs, independent
  sessions/profiles, two outstanding units, diagnostic notices, storage/network
  failures, hidden rows, and approval gating. All consumer requests are asserted
  to be transcript GETs.
- **Eight physical Pixel test executions passed:** three link/dialog checks at
  unfolded 2076×2152 and simulated narrow 1080×2152; plus two separate process
  executions for delegation recovery. The fixture app was force-stopped between
  dispatch and recovery; a real stock HTTP/SQLite server persisted and deduplicated
  the synthetic result. It appeared once after restart, without another prompt.
- The normal app remains installed at build 50. Only the disposable fixture APKs
  were installed. The size override was reset; the original density override 440
  was retained. Personal server profiles and live gateway configuration were not
  used for these tests.

The full plugin and signed build outcomes are recorded in
[evidence](evidence/priority-bugs-20261115/validation.json).

During validation, running JVM notification-capture tests with the disposable
fixture application ID correctly triggered that package's capture restrictions;
those tests were rerun successfully with the production application ID. The
stock-host snapshot tests initially exposed the old source-hash gate; the new
read-only gate and its authentication tests resolve that failure. Two legacy
installation expectations were updated to assert the newly supported read path.
The first phone-link harness tapped the message ancestor instead of the text;
the corrected harness injects touch at the link text's own bounds.

## Remaining acceptance boundaries

The original #52 report did not identify a particular dialog, and its exact
stranded motion was not reproduced in the project editor. Shared handling and
project-editor regressions are verified; physical rotation/hinge transitions
and the reporter's exact dialog remain separate acceptance. The phone delegation
fixture uses a synthetic worker and actual host persistence/HTTP, without model
inference or personal data. Voice queue behavior is deterministic test coverage,
not a new wired-car/audio-route acceptance claim. Notification delivery cannot
run while Android has force-stopped the application; saved results recover on
relaunch. None of these changes closes the other outstanding integration issues.
