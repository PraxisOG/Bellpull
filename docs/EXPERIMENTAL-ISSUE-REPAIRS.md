# Experimental issue repairs — 2026-10-13

## October 19 experimental follow-up

App 0.14.3 / build 35 and plugin 0.5.2 address #14 and #20–23 on the current
experimental line. The new title endpoint uses a compare-and-swap transaction
against Hermes title provenance; it never treats an unknown legacy title as
permission to rename. Transcript reconciliation and gesture/runtime regressions
are covered in JVM tests and physical fixture-phone tests. #13's Pin/Unpin control
passed real touch, mutation and recreation checks on the Pixel; the historical
personal-server failure was not reproduced. #1/#3/#7/#16 still require physical
car/acoustic/thermal acceptance; #18/#19 concern a separate speech server whose
implementation is not in this repository. Details and test counts are in the
[current release record](release.md) and
[machine-readable evidence](evidence/issues-20261019/validation.json).

## October 15 re-audit

Audited all 15 open GitHub issues against `695f399`, their acceptance criteria,
current source, local regression suites and the separately dated device evidence
below. The latest commit itself records requests; it does not implement continuous
call-style voice or server speech. The preceding code adds useful title recovery,
calendar recovery and message statistics, but recovery boundaries still contain
confirmed defects. Commit authorship does not establish which model wrote a change.

| Issue | Disposition | Evidence and remaining scope |
|---|---|---|
| #1 | Open | Parent still needs sustained physical car acceptance; new heat/recovery report B1 remains unexplained. |
| #2 | Close | `VoiceService` additions only diagnose inventory; selected-device removal still pauses. Prior isolated built-in-route startup stayed LISTENING; current voice readiness/callback tests pass. Car behavior stays under #1/#3. |
| #3 | Open | Queue preservation/focus recovery tests pass, but wired Android Auto, navigation, real calls and route removal have not established that the reported cutoff is resolved. |
| #4 | Close | `VoiceSessionTest` covers rejected noise, pending echo recognition plus backend completion, confirmed interruption and stale callbacks. The partially played phrase restarts; completed phrases stay consumed. |
| #5 | Close source defect | `VoiceSignalTest.shortEchoRequiresRecentWholePlaybackAndPreservesUserCommands` covers one/two-word audible phrases and genuine commands. Session tests cover rejected echo without another Stop/send. This is exact normalized matching, not acoustic speaker identification; physical headset/car acoustic acceptance stays under #1. |
| #6 | Close original defect | `MainActivity.onReadMessage` no longer pauses voice. Its factory consistently rejects active/paused sessions with an End voice notice; no microphone/server action occurs. The newly reported car routing bug is separate, #16. |
| #7 | Open | Native measurements and bounded synthesis-ahead exist; Pocket's paired gap improved from 1,478.5 ms to 38.8 ms. Sustained thermal tests, subjective listening acceptance and production candidate frontend integration remain incomplete. Owner reiterates that a good on-device voice is a priority. |
| #8 | Close | `SettingsOrganizationTest` opens About directly; cover/unfolded snapshots and prior physical taps verify the layout. `AboutSettingsTest` covers app/plugin information. |
| #9 | Close source defect | `toolBatchesAndReplayedSnapshotsNeverRepeatPublicProse` verifies tools/replays/new commentary without suppressing legitimate repetitions. Activity labels and answer prose have separate provenance. No new physical multi-tool listening run is claimed. |
| #10 | Close | All four combinations of tool/search flags and all-off admission are tested. Settings interaction and encrypted per-profile persistence were also device-tested October 13. |
| #11 | Close | Full final-word playback beyond 180 words, unrestricted detail preset, bounded prefetch and cancellation tests pass. Provider limits and thermal failures remain separate concerns. |
| #12 | Close | `Composer` hides IME/clears focus only after accepted send; Ctrl+Enter shares that path. Prior physical IME test preserved multiline text and deliberately reopened the keyboard. |
| #13 | Open | Global pin API/persistence and capability tests pass, but B3 reports the actual control is unresponsive. Inspect the exact touch target and server capability before closing. A missing plugin capability is a possibility, not a diagnosis. |
| #14 | Open, defect reproduced | `recoverSessionTitles` drops a pending repair on any nonblank server title, including an automatically derived voice-wrapper title. Synthetic real-store probe loses the desired title. Recovery must distinguish automatic titling from a user rename atomically; blindly overwriting nonblank titles is unsafe. |
| #15 | Close | `MessageInfoTest` verifies duration/token attribution to a unique final reply, ambiguity rejection and explicitly estimated fallback. `DenseChatScreenshotTest` long-presses the actual row and opens Message info. Unavailable server usage remains unknown. |
| #16 | Open, new routing report | Read aloud played on the phone instead of car speakers. Source classification/focus repair is below; actual car output must still be verified. |

Closing a source defect here does not certify every physical route. Unverified
car/headset acceptance is explicitly retained under the parent and route tickets.
The October 13 device evidence is historical; it was not rerun during this audit.

### Additional confirmed repairs

Work is isolated on `repair/reliability-followups-20261015`, with separate commits:

- `c3ca0c5`: manual return to the bottom resumes streaming follow even when the
  final drag is unconsumed overscroll (B2); a real Compose gesture reproduces the
  old failure and verifies the repair.
- `547e540`: a rejected Stop remains visible across successful status polls until
  retry/terminal completion. The deterministic regression failed on the old code;
  this explains a plausible cause of the existing intermittent CI failure rather
  than treating it as harmless test noise.
- `a70f3e0`: Retry no longer admits concurrent recognition jobs, and unavailable
  audio gives a useful notice while retaining the same recording. B4's original
  long-dictation cutoff remains unresolved.
- `f4156f9`: calendar revoke removes personal fields from pruned proposal receipts,
  retains bounded identity-only tombstones against replay, and repairs currently
  revoked policies at startup. Admission rechecks its calendar snapshot in the
  write transaction so a concurrent revoke cannot recreate private proposal data.
  Four regression cases cover retained rows, upgrade, pending replay and the race.
  This is application-record cleanup, not erasure of backups/storage remnants.
- `7515d02`: standalone Read aloud uses media speech and matching audio focus for
  Android/Pocket/Qwen, without changing communication-mode conversation routing.
  Five reader tests verify usage, completion/error cleanup, denied focus, stale
  callbacks and explicit restart after interruption. Physical car success remains
  an acceptance item in #16.

Final Android validation: **598 passed, one live-server skip**, **172 unchanged
screenshot baselines**, release build and lint passed. Plugin: 148 passed and one compatibility skip in the full
legacy-image suite; 66 passed and one compatibility skip in the stock 0.21.5 subset
(coding, planning, projects, portability). No real inference was submitted.

The ten closures and six remaining open issues (including new #16) were verified
through GitHub after per-ticket audit comments were posted. Repairs are published
as [draft PR #17](https://github.com/PraxisOG/Bellpull/pull/17), targeting the existing
experimental branch.

### October 15 testing installation

Installed source `16ece99` as **0.8.3-test.16ece99/build 24**, using an external
Gradle version override. SHA-256:
`334dcd3e1497ff3588b89f589779c07afbf108667e71e60ba3f06022e5abbce9`.
Signing lineage, installed APK hash and 16 KiB alignment passed. UID 10260 and
CE/DE identities 117766/116002 stayed unchanged; build 23's APK is backed up.
The personal app was left stopped; no voice choice or saved profile was changed.

The **local Docker test server** now runs plugin **0.4.2-integration-preview**.
Both writers were stopped for a full data backup. Health/auth/capabilities and
planning/projects reads passed; all 58 sessions and 562 messages remain, and
configuration/environment bytes are unchanged. The personal Hermes server was
not accessed. Evidence, test XML, issue audit results, signed APK and install
records are under `/home/praxisog/Bellpull-builds/2026-10-15-reliability-audit/`.

### On-device voice recommendation

Pocket is the first practical option to audition in the current app: Settings →
Voice output → Download Pocket → select Pocket TTS → Save, for the current server
profile. Its shipped voice is Loona; defaults are 5 steps, temperature 0.7 and
15-frame chunks. This is an explicit local selection, with no remote fallback.

The recorded Pixel test synthesized Pocket at median RTF 0.528 (about 1.9× playback
speed), with peak process PSS 674 MiB. Current Qwen defaults measured RTF 6.862 and
2,688 MiB on three matching short texts, making that implementation unsuitable for
responsive conversation on this phone. These are performance measurements, not a
listening-quality ranking. Pocket still needs long car-session thermal acceptance.
Paradee/Inflect remain benchmark candidates, not selectable production voices.
See the [measured report](../benchmarks/voice-output/pixel-10-pro-fold-2026-10-13/README.md)
and [Pocket's official samples](https://kyutai-labs.github.io/pocket-tts/).

## Original October 13 repair record

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
