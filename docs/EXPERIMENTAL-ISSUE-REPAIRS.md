# Experimental issue repairs — 2026-10-13

Work started from `integration/phase6-8-9` at `4fa43d1` and is isolated on
`repair/experimental-open-issues`. Android 0.8.2-integration-preview, build 21;
Bellpull plugin 0.4.1-integration-preview. The user requested all open issues,
then authorized ADB acceptance and installing the signed update on their phone.

| Issue | Implemented behavior |
|---|---|
| #1 | Parent reliability tracker; the focused repairs below share one recoverable playback queue. |
| #2 | Added-device callbacks establish/extend inventory without pausing. Only selected-route loss/removal, external mode changes and genuine focus loss pause voice. Initial callbacks cannot invalidate readiness. |
| #3 | Transient/duck focus loss suspends speech and closes capture; gain restores unfinished speech without another request. Permanent loss, calls, route loss and user pause require explicit Resume. Resume retains pending speech even if generation finished while paused. Audio acquisition cycles fence old focus callbacks. Content-free diagnostics include event, time, route IDs/types, mode, state and queue counts. |
| #4 | Acoustic onset tentatively suspends output. Blank/echo rejection restores it, including completion during recognition. Confirmed speech commits interruption through the existing Stop/replacement path. |
| #5 | A bounded recent audible phrase history protects short exact echoes and output tails; yes/no/stop/cancel/pause remain actionable. Short matches require whole-phrase playback evidence. This is a normalized exact-match guard, not perfect acoustic speaker identification. |
| #6 | Read aloud consistently requires ending conversational voice first, including paused voice. It explains the restriction without pausing/disrupting the existing reply. Separate message reading never starts inference or reopens the microphone. |
| #7 | Pocket/Qwen support one phrase of synthesis ahead, with 16 × 2,400-sample buffers per phrase (1.6 seconds, 153,600 bytes of float PCM). Stop cancels current and ahead producers. Native Pixel benchmarks compare the two requested candidates and existing engines; see the linked report. |
| #8 | Settings renders the existing About action directly; one tap opens its dialog. |
| #9 | Generated activity speech contains genuine status/tool labels only. Public prose has one answer lane, with a flush at tool boundaries. IDs deduplicate replayed/growing activity snapshots; legitimate repeated prose remains eligible. |
| #10 | Four profile-scoped independent announcement choices live under Settings → Voice announcements. Missing flags default on; changes apply next session. Working off admits through the same checks without waiting for an absent cue. Answers and visual indicators stay enabled. |
| #11 | Removed the cumulative speech budget. Brief responses is a style preference honoring explicit detail/research/long narration, with no replacement paragraph/response cap. PCM is progressively generated and bounded; written output is unchanged. |
| #12 | Accepted local submission clears composer focus and hides IME. Rejected/blank/blocked attempts preserve focus and drafts. Ctrl+Enter uses the same submission path; ordinary Enter remains multiline. Existing draft recovery is retained. |
| #13 | One server membership pin works for project and Unfiled chats, appears in All/applicable filters, and survives move/delete/restart. Existing project pins need no migration. Forks remain initially unpinned; aliases share one state. Capability `bellpull_conversation_pins` protects older servers. |

The global pin extension needs the supplied 0.4.1 server plugin. The app keeps
existing project pins on older servers and shows an update notice for Unfiled
pinning. No personal server is contacted or deployed to during this repair.

A partially played phrase restarts after recovery. Fully completed phrases are
retained as completed and are not replayed. Process-death recovery continues to
avoid resubmitting an admitted request; automatic microphone restart remains off.

## Verification

- Local Android suites, screenshot verification and lint passed: 499 app tests,
  43 core-hermes tests (one expected live-server skip), 21 core-model tests.
- Plugin suite: 127 tests passed with one expected skip. Updated projects also
  passed 14 tests on the unmodified stock Hermes image.
- Native candidate benchmark: 40 inference measurements passed on the Pixel 10
  Pro Fold; source revisions, staged tokens and runtime settings are recorded.
- Qwen native cold/warm, streaming cancellation, AudioTrack drain and Stop:
  two isolated phone tests passed.
- Unfolded fixture UI, encrypted choices, real offline Android playback/capture,
  foreground startup/background lifecycle and explicit pause/end: seven tests
  passed. The strengthened startup test stays LISTENING after backgrounding.
- Cover fixture acceptance: three UI/profile tests passed. Actual IME visibility
  confirms accepted sends close the keyboard and deliberate composer taps reopen it.
- Pocket: ten matching inference measurements and paired actual playback passed.
  The inter-phrase gap fell from 1,478.5 ms to 38.8 ms with synthesis ahead.
  All phone work uses `org.praxisog.hermes.fixture` and loopback mocks.

The initial API 37 UI run hit Espresso's removed InputManager hook. Tests now use
the repository's public UiAutomation touch/accessibility helper. This was a
harness failure; no product behavior was changed to accommodate it.

Physical wired Android Auto, real calls and actual headset removal still need
route-specific acceptance. This repair does not establish the origin of the
reported sound or prove which car callback caused the original cutoff.

See [native speech measurements](../benchmarks/voice-output/pixel-10-pro-fold-2026-10-13/README.md).

## Installed update

The signed 0.8.2-integration-preview/build 21 APK was installed in place over
0.8.1/build 20 using ADB. Certificate SHA-256 remains
`c1211fd4dbb2b4c0710fe35858bbc4d19eb2d2d27abb18eb5ad51e8192db55bf`;
UID remains 10260; CE/DE directory identities remain 117766/116002. Existing app
data was not cleared. The app was left stopped for the owner to open; no personal
server was contacted during acceptance or installation.

APK SHA-256:
`27366d361b9aa81a9905d38bc4db8fdedf176bf9e067931129f3236a94352384`.
Signature/lineage, package/version and 16 KiB ZIP alignment passed. Candidate JNI
exports are absent from the signed personal APK. Artifacts, prior APK backup,
certificate/package/UID evidence, candidate audio samples and the plugin archive
are in `/home/praxisog/Bellpull-builds/2026-10-13-issue-repairs/`.

Fixture packages/staged device weights and the two test reverse ports were removed.
Original physical size and the existing 440 density override were preserved.

The source remains on the local repair branch. GitHub issues remain open for
review/route-specific acceptance; this task did not publish a release or deploy
the personal server plugin. The supplied plugin archive implements global pins.
