# Bellpull 0.14.9 experimental

Fixes startup freezes and memory exhaustion when restoring a long live reply.
Android recorded two ANRs in replay deduplication and a low-memory exit at about
8.9 GB RSS. The old scan retained a native regex matcher per word; the replacement
compares tokens with linear memory and preserves reconnect deduplication.

- **Android:** 0.14.9-experimental / build41; signed in-place update.
- **Plugin:** 0.6.1-integration-preview, unchanged; no server update needed.
- Saved chats, server profiles, settings and downloaded models are preserved.
- Thinking level controls remain available under the conversation’s **⋮** menu.

Validation: **740 declared JVM checks, 739 passing and one existing opt-in skip**;
lint zero errors/79 existing warnings; signed ARM64/x86_64 release assembly.
A physical Android regression test ran 20 projections with 120,000-word saved
text and reasoning: 721 ms median, 830 ms maximum, 70.9 KiB native heap growth.
The bounded legacy demonstration needed 7.09 MiB for just 512 words. Inputs differ
in size, so these figures are not a like-for-like speed comparison. The repaired
scan also uses linear Java character buffers. The one-second stress limit is not
a 60 fps rendering claim. See [sanitized failure and device evidence](evidence/startup-replay-20261022/README.md).

Installed in place on the owner’s phone; startup and UI responsiveness checked.
During a 45-second observation the same process stayed alive at 463–470 MiB RSS,
with no new crash/ANR exit. This is a short startup check.

No UI layout or plugin runtime changes. The preceding 0.14.8 release verified
191 screenshot baselines; those baselines were not regenerated for this repair.

[Download experimental](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.14.9-experimental.1).

APK SHA-256: `3cc32e02547ee7111618f50db1318e6b456b7bfa8f13c8511adecc3e125f9f4e`.
