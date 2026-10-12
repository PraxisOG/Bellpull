# Phase 5 voice — development preview

The current `feature/local-voice-models` branch builds 0.5.5/code 17 with Moonshine
on-device CPU recognition. See [local setup, status and benchmarks](LOCAL-SPEECH-LAB.md).
All three local models completed native phone benchmarks; earlier device
results below describe the 0.5.0 private-service foundation.

Authorized 2026-10-11 on `feature/phase5-voice`, from the validated Phase 4 branch.
Android version 0.5.0, build 12. This does not publish a release. The updated October
11 roadmap governs scope: conversational voice in small slices, independent of
Quick Chat, on-device inference, assistant replacement and Android Auto.

## Setup on your phone

1. Keep your existing Hermes connection and Bellpull plugin. This client-only change
   does not require a new plugin; projects still require plugin 0.3.0 or newer.
2. Open Settings → Voice input and select **On device**. Choose Moonshine Tiny,
   Small or Medium Streaming, download it, then load it or start voice to load on
   the first captured utterance. Models and their tuning are device-wide. No URL
   or speech-service key is needed. Voice input → Experimental provides direct-WAV
   benchmarks; Display is its own settings category.
3. Alternatively, explicitly select **Private server** and supply the full
   transcription URL, speech-service key if required, and model ID. Existing
   0.5.0 private configurations retain this choice until changed. These settings
   remain isolated to the selected Bellpull server connection and stored encrypted.
   The private Whisper-compatible service must accept multipart POST fields
   `file` (16 kHz, mono, PCM16 WAV), `model`, and `response_format=json`, returning
   `{"text":"recognized words"}`. `/v1/audio/transcriptions` is an example path,
   not an assumed Hermes endpoint. Bellpull does not install a speech model/server.
4. For private-server mode use HTTPS, or HTTP over Tailscale/loopback. Embedded credentials, query strings,
   fragments and insecure ordinary LAN URLs are rejected. Redirects are refused.
   The speech request never borrows your Hermes bearer key.
5. **Tap the chat microphone** to start conversation mode; tap again to end voice.
   The button pulses while voice is on (steady when Reduce motion is enabled).
   Wait for the brief soft chime before speaking. Recognized turns send after the
   configured pause. **Hold the microphone** to dictate, then release to insert
   words at the message draft's cursor/selection; review and send normally. This
   gesture never auto-submits an inference. If a permission dialog interrupts the
   hold, release, grant permission and hold again. Releasing/canceling during startup
   cannot open the microphone later.
6. Settings → Voice input → **Using voice** documents these controls. Enable **Debugging
   voice interface** to restore the detailed panel on tap, including review,
   hands-free and its older push-to-talk/send-on-release mode. The debug toggle is
   off by default and persists on this device. Readiness uses a 90 ms native chime
   at 12% of voice-call stream volume; perceived loudness depends on route/volume.

On the test Fold, GrapheneOS provides an installed offline English TTS voice;
Android’s platform SpeechRecognizer is unavailable; 0.5.1 supplies its own
Moonshine runtime. No cloud fallback is selected. Other phones need an installed TTS voice that reports offline
support in their current language. Existing message read-aloud remains separate.

## Implemented behavior

- One service owns a user-started voice session, audio focus, communication route,
  microphone and media-session controls. A persistent notification offers Pause
  microphone and End voice. Killing the process never restarts the microphone.
- PCM capture uses Android's communication source, available acoustic echo
  cancellation and noise suppression. Energy-based turn detection keeps a short
  lead-in, tolerates a configurable 1–3.5 second pause, rejects silence/brief clicks,
  and caps recordings at approximately 55 seconds. Hold my turn defers silence
  completion; Finish speaking ends capture. The cap still applies while holding.
- Review, push-to-talk and continuous hands-free modes share one conversation, including its
  project, history, tools and existing run permissions. Typed drafts and model
  preferences remain independent. A visible voice-message preset asks for concise,
  friendly answers of at most one paragraph/180 words, minimal reasoning and a brief
  explanation before each tool/search. **Provider thinking controls remain unchanged**;
  this is a visible prompt instruction. Spoken answer text has a separate 180-word
  cap even if the provider ignores the prompt. Activity announcements are outside
  that cap; full written output remains available if a provider ignores the limit.
- An actual **“Working”** TTS preamble completes **before** the phone submits a run. Coherent
  answer sentences begin playback while generation continues; incomplete words,
  thinking and raw tool payloads are excluded. Code/link formatting is converted
  for speech. Status output can drop earlier tool commentary without corrupting
  the spoken final answer. Genuine live thinking/tool/search events generate short,
  deduplicated announcements, prioritized ahead of queued answer sentences. Thinking
  announces only its status, never its content; raw tool arguments/previews are never
  spoken. The preceding public assistant commentary supplies the stated purpose;
  otherwise a factual tool-role description says when no specific purpose was given.
- Barge-in stops playback and clears stale queued speech. A contiguous-text echo
  guard prevents recognized playback phrases becoming another inference. A new
  utterance now requests the existing **chat Stop** after nonempty, non-echo recognition.
  The new utterance is saved as one replacement turn; it is not appended to the
  original request. It waits for Stop and authoritative history to settle before one
  fresh admission. Pre-admission interruption waits for the same run to obtain its
  stoppable identity. Stop errors or 30 seconds without settlement pause voice and
  retain new words for review. Playback stops on speech onset; remote Stop waits for
  recognition, which includes the VAD pause/model latency. The debug **Stop speaking**
  still stops only playback. No stop request can undo tools already executed.
- The latest unsent transcription and completed-chunk playback progress are encrypted and
  scoped to profile/chat. Restored words require review. Admission acknowledgment,
  rather than merely tapping Send, clears the saved voice draft. Interrupted
  playback adds a bounded, quoted historical note to the next voice message;
  playback completion is not a claim the user heard it. Raw microphone recordings
  remain bounded in memory; failed transcription can be retried during the session.
- Switching conversations/profiles, approval/review requests, connection loss,
  audio-focus loss, headset disconnection and route changes pause voice. Runs keep
  their existing durable identity/recovery. Resume explicitly reacquires focus and
  selects the current communication route. UI dismissal and app backgrounding do
  not end the service. The notification returns to the bound conversation.
- The panel reports capture-to-transcription, first text and first speech-start
  timings. These include network and preamble delays but start **after** the VAD
  pause, not at the physical end of speech. Speech start is the engine callback,
  not a measurement of acoustic output. Full written answers remain in Hermes.

## Honest limits before calling Phase 5 complete

In private-server mode a private STT service must be configured by the user. The wire fixture validates
requests; it is deliberately **not a speech recognizer** and is never selected by
production defaults. Real provider recognition, speaker echo/barge-in quality,
false-turn rejection in ambient noise and perceived latency need listening tests.
Energy-based VAD and platform AEC are a first implementation, not a proven neural
turn detector or acoustic guarantee. Hold/review controls remain available.

The current Hermes stream reports tool activity but has no verified playback-ACK
contract that lets the phone gate each individual tool execution. Native plugin
`pre_tool_call` is a bounded policy hook, not a verified speech-delivery handshake.
This build guarantees “Working” before submission, requests brief specific purposes
through the visible preset, and announces observed live activity with public commentary.
It **does not guarantee** that specific commentary finishes before every
tool starts. Completing that requirement needs a separately tested host contract;
no core patch or fake approval was introduced.

Physical screen lock, Bluetooth/car hardware, navigation/call interruptions, fold
movement while talking, real-network reconnection and long idle survival remain
manual acceptance items. Microphone service types, pause/resume and media controls
are implemented; source tracing alone does not prove those device scenarios.
Offline-only TTS eligibility follows the installed engine's voice metadata; an
independent network/engine audit has not been performed. This preview exposes one
installed offline voice and rate controls, not a full voice-selection catalog.

## Manual acceptance

- [ ] For local mode, download/load each Moonshine size and check offline recognition,
      switching, restart, unload/delete and visible failure when selected weights are missing.
- [ ] If using private mode, configure the STT URL/key/model for each desired server profile.
- [ ] With Debugging voice interface off, tap the microphone, wait for its chime,
      speak and hear a response. Confirm the active button pulses; tap again to end.
- [ ] Hold the microphone, dictate and release. Confirm words land at the draft's
      cursor/selection and no inference starts until you send. Cancel a hold and
      confirm no dictation is inserted. Check typed text around the cursor survives.
- [ ] Enable Debugging voice interface. Confirm the older detailed panel opens on
      tap and its review/push-to-talk controls still work. Turn it off for normal use.
- [ ] Continue hands-free for several turns. Verify the same chat/project is used.
- [ ] Request a long answer. Confirm speech begins before generation completes and
      does not read thinking/tool arguments. Review the timing values.
- [ ] Speak over an answer on the speaker, then on a headset. Check stale speech
      never resumes, echo does not submit a new turn, chat Stop settles and the
      follow-up submits once. Repeat while Hermes is still silently working.
- [ ] Ask for a web search and a file/tool operation. Hear Working, then the real
      activity and its stated purpose. Check thinking is announced without reading
      reasoning, answers stay brief and provider thinking settings are unchanged.
- [ ] Compare Stop speaking with Cancel agent work. Inspect the saved full answer.
- [ ] Make STT unavailable. Retry the recording; verify Hermes receives at most one
      admitted turn. Restart before sending a reviewed transcription and recover it.
- [ ] Switch projects without changing chats, then switch chats/server profiles.
      Confirm drafts/runs survive and voice cannot send to the new destination.
- [ ] Lock/unlock, switch apps, change Bluetooth routes and unplug headphones.
      Try navigation audio and a call; check focus loss pauses the mic and Resume
      uses the new route. End voice from its notification; verify the mic stops.
- [ ] Disconnect/reconnect during inference. Verify the existing run recovers and
      voice does not submit it again. Review genuine approvals in the chat UI.
- [ ] In the drawer, use the filter icon immediately left of Search. Select a
      project, API/CLI/TUI or All sources. Confirm two-line titles, right-edge chat
      actions, no source badges, and correct behavior on cover/unfolded screens.

## Verification of 0.5.3 voice controls

The required Android local checks passed: **501 tests passed**, one opt-in
live-server test skipped, **158 screenshot baselines** verified, debug/release
builds and lint. New regressions cover recognized interruption, delayed admission,
completed runs awaiting history, failed/timed-out Stop, draft insertion, canceled
holds, release before audio readiness, announcement priority/replay suppression
and the spoken word limit. The actual store/durable journal test checks exactly
one Stop, settled history and one new admission without resubmitting the old run.
Speech during silent work also suppresses a late answer before ASR confirms Stop.

Five isolated checks passed on the unfolded Pixel 10 Pro Fold: native taps/holds/
release/cancel through button recomposition, missing-model Start protection, About,
native chime/offline TTS/microphone release and foreground-service background/
Pause/End behavior. The first gesture test exposed the standard long-press handler
reporting cancel as release; the corrected handler and both JVM/phone regressions
passed. The service test uses fake loopback inference/transcription; it does not
establish human recognition, echo quality or provider behavior. Existing 0.5.1
Moonshine benchmarks remain the recognition evidence; they were not rerun for this
controls-only change. Thermal service status was NONE before/after these brief
device checks. No display/brightness/fold overrides were applied.

Signed 0.5.3/code 15 was installed over 0.5.2 with the same signing lineage, UID and
data-directory inodes, without clearing data. Selected models/downloads were not
modified. The personal app stayed stopped; the personal Hermes server was not
accessed. Fixture apps, our reverse ports and fixture processes were removed.
Local evidence is under ignored `build/speech-lab/voice-controls-*` and
`build/speech-lab/personal-upgrade/0.5.3/`; signed APK/hash/rollback instructions are
in [release instructions](release.md). This is a branch delivery, not a release.

## Verification of the original 0.5.0 foundation

The required local command `./android/gradlew -p android test
verifyRoborazziDebug assembleDebug lintDebug` passed: 444 tests passed, one opt-in
live-provider test skipped; 150 screenshot baselines verified; lint has no errors
(50 warnings, 4 hints). The signed release build also passed. Tests include the
real store/durable journal with fake inference, PCM turn detection, sentence
playback, interruption, retry, restart/admission identity and drawer interactions.

Seven isolated tests passed on the unfolded Fold using a separate fixture app.
These cover native offline TTS callbacks and microphone restart/release, voice
foreground-service background survival and notification controls, encrypted
settings isolation, transcription HTTP/WAV fields, drawer touches, keyboard Save
and About. The cover-state attempt timed out behind keyguard in all three UI
tests; it is not a passing cover-device run. Both screenshot widths passed.
Actual microphone/TTS callbacks do not establish recognition or acoustic quality.
The fake STT fixture returns fixed text and never appears in production defaults.

Android 0.5.0/build 12 was installed in place with the existing signing lineage
and UID. Personal app data was not cleared, and the personal app stayed stopped.
The personal Hermes server was not accessed. Test applications, display overrides,
port forwarding and local fixture processes were removed. Evidence is stored
locally under ignored `build/releases/0.5.0/`; pushed-branch CI results are separate
evidence, not inferred from local success.

## Sources and implementation references

Reuses Bellpull's durable runs, profile isolation and native installed TTS. The
0.5.0 foundation added no dependencies; 0.5.1 adds the user-approved Moonshine CPU
SDK. Audio integration follows Android's
[communication capture source](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#VOICE_COMMUNICATION),
[acoustic echo cancellation](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler),
[audio focus](https://developer.android.com/media/optimize/audio-focus), and
[microphone service type](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone).
The separate transcription wire shape follows the
[compatible multipart transcription contract](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create).

## Local voice output (0.5.4)

Voice input and Voice output now have separate Settings categories. Output offers
installed Android voice or the approved, pinned Qwen CPU experiment. See
[setup, measured results and pending acceptance](VOICE-OUTPUT.md). The signed
0.5.4 APK was built/tested but **not installed** before ADB disconnected.
Phase 5 remains pending human/device acceptance, not declared fully verified.

## Offline implementation handoff: 0.5.5

Local output now includes Pocket TTS as an explicit CPU experiment alongside
Qwen and Android. Model downloads, safe switching, tuning and output recovery are
implemented; [VOICE-OUTPUT.md](VOICE-OUTPUT.md) records exact pins/licenses and
separates 0.5.4 measurements from untested 0.5.5. Service pause now releases audio
ownership and explicit Resume safely reacquires it; communication-route/mode
changes are observed in addition to device addition/removal/focus changes.

No further tests or phone access were used after the user's “finish Phase 5
minus tests”. The source compiles, but the new UI baselines, tests, Pocket
inference, Bluetooth/navigation/call and screen-lock checks are pending.

Phase 5 implementation is ready for those checks within the currently verified
Hermes contract. One roadmap requirement remains backend-dependent: **actual
announcement playback before each tool executes**. Only Working is gated before
admission; later tool events can arrive after execution starts. Prompt instructions
and observed tool status cannot establish a before-execution guarantee. No
fabricated approval or undocumented endpoint was introduced to pretend otherwise.
A future verified host pause/ack mechanism would be needed for that guarantee.
