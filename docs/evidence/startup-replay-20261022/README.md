# Startup replay memory repair — October 22, 2026

App 0.14.9-experimental/build41; plugin 0.6.1-integration-preview unchanged.

## Failure evidence

The owner reported crashes on opening Bellpull. The physical Pixel 10 Pro Fold
(GrapheneOS, Android 17) recorded two ANRs in 0.14.8 at 20:32:57 and 20:35:09,
and a low-memory exit at 20:34:11 with 8.9 GB RSS. Both ANR main-thread stacks
ended in the same replay deduplication path (sanitized excerpt):

```text
com.android.icu.util.regex.MatcherNative.setInputImpl
java.util.regex.Matcher.resetForInput / find
kotlin.text.MatcherMatchResult.next
kotlin.sequences.SequencesKt___SequencesKt.toList
org.praxisog.hermes.LiveHistoryProjectionKt.removeSavedPrefix
org.praxisog.hermes.LiveHistoryProjectionKt.projectLiveHistory
org.praxisog.hermes.SessionDetailScreenKt.SessionDetailScreen
```

`Regex("\\S+").findAll(text).toList()` retained one matcher per word. Android's
ICU matchers can copy the entire input into native memory, producing quadratic
work and memory as the saved/live reply grows. The scan ran during composition
when opening/reconnecting a conversation. This path was added in 0.14.5's
reconnect deduplication; the 0.14.8 Thinking level menu does not appear in either
failure stack. Historical voice-service exceptions predate those repairs.
Private transcripts, raw device dumps and credentials are not included here.

## Repair

Compare tokens using two character buffers and cursor indices. Memory and time
are linear in the input length per comparison; no matcher or substring is retained
per word. Final suffixes preserve Markdown. Prompt/run scoping, mismatch behavior,
reasoning/prose part handling and the original raw stream remain intact.
The projection also reuses matching aggregate/part comparisons and joined saved reasoning.

An initial constant-space String cursor implementation fixed native memory growth
but failed the physical-device latency gate: a 600,000-character saved reply plus
reasoning/parts took up to 4,065 ms. Android's interpreted String character access
was expensive. Character buffers and an ASCII whitespace fast path address that
cost while retaining Unicode whitespace handling. The latency limit was not raised.

## Reproduce the physical regression check

Use an isolated fixture package, never clear/uninstall the personal app:

```bash
bash tools/build-local-lab.sh :app:assembleDebug :app:assembleDebugAndroidTest \
  -PbellpullDeviceFixture=true --max-workers=4
adb install --no-incremental -r android/app/build/outputs/apk/debug/app-debug.apk
adb install --no-incremental -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class org.praxisog.hermes.LiveHistoryDeviceTest \
  org.praxisog.hermes.fixture.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as org.praxisog.hermes.fixture cat files/live-history-metrics.json
```

The legacy demonstration is deliberately capped at 512 words/2,560 characters;
**never scale it to the stress input**. The repaired projection runs on the actual
Android main thread 20 times with 120,000 words/600,000 characters each in saved
text and reasoning, plus corresponding live text/reasoning parts. Assertions verify
correct new suffixes, a separate main-loop dispatch between projections, native
heap growth below 64 MiB and every projection below one second. This is a severe
replay regression limit, not a 60 fps rendering claim or an inference benchmark.

## Results

Physical fixture instrumentation: **1 test passed**, 20 projections. See
[`live-history-metrics.json`](live-history-metrics.json).

| Measurement | Result |
| --- | ---: |
| Legacy sample size | 512 words / 2,560 characters |
| Legacy retained native heap growth | 7,429,888 bytes (7.09 MiB) |
| Repaired stress size per saved text/reasoning | 120,000 words / 600,000 characters |
| Repaired median projection | 721.0 ms |
| Repaired slowest projection | 830.0 ms |
| Repaired native heap growth over 20 projections | 72,560 bytes (70.9 KiB) |

The inputs deliberately differ in size; this is not a like-for-like speedup ratio.
The replacement also uses linear Java character buffers; the native-heap measurement
is not total app memory. Debug/interpreted device latency is reported as measured.

## App acceptance

The signed 0.14.9/build41 APK was installed over 0.14.8 in place with the same
release certificate and UID 10308. The installed APK hash matches the release
artifact. The fixture packages were removed after testing. No personal app data
was cleared and no inference request was submitted during startup checks.

The actual app opened successfully, responded to a UI hierarchy request and had
no crash/ANR dialog. The process remained alive through 45 seconds of observation
with RSS between 462.6 and 469.8 MiB (see [`startup-memory.json`](startup-memory.json));
no new process exit appeared during that interval. A subsequent explicit force-stop
and cold launch succeeded in 1.802 seconds; the new process also remained running.
The only added exit was that intentional force-stop. This is a short startup/reconnect
check, not a guarantee against every future crash or sustained-use regression.

Final host checks: 740 declared JVM tests, 739 passing and one existing opt-in
skip; lint zero errors, 79 existing warnings and five informational hints. The
release APK includes ARM64/x86_64 and verifies with the existing signing lineage.
There are no UI layout or plugin runtime changes; 191 screenshot baselines remain
those verified in 0.14.8. Plugin 0.6.1's previous integration evidence still applies.
