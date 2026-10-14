# Compact Bellpull voice prompt — October 14, 2026

Voice user messages now start with a compact **Bellpull voice enabled ▾** row.
Tap it to show the original voice instructions; tap again to hide them. The
user's spoken words remain visible below it. Saved and pending messages use the
same renderer, with separate expansion state per message that survives UI
recreation.

This is a display change. Submitted/stored prompts and copy, selection, quote
and edit actions continue to use the complete original message. Recognition
accepts only the exact current leading preset or the shipped historical preset
from integration `4fa43d1`. A recognized interrupted-playback note stays inside
the disclosure with its preset. Ordinary typed mentions, unknown/malformed
prefixes and assistant messages remain fully visible. No TTS model, inference
setting, server contract or plugin change is involved.

## Verification

- App JVM/UI suite: 511 tests passed, including five parsing tests and seven
  disclosure tests. Copy/edit content, independent state restoration, pending
  messages and ordinary/assistant text are covered.
- Screenshot verification: all 172 baselines passed, including new collapsed
  cover and expanded unfolded captures. Lint, release build, APK signature and
  16-KiB ZIP alignment passed.
- Pixel 10 Pro Fold, GrapheneOS/API37: two fixture tests passed at each of the
  unfolded physical viewport (2076×2152) and simulated cover viewport
  (1080×2424). Real touches expand/collapse both saved and pending messages;
  long press/edit retains the original preset and words.
- The initial fixture host drew under the status bar, intercepting the header
  tap. System-bar padding corrected the test host; the product needed no inset
  change. An unnecessary test API annotation was removed for lint.

The temporary size override and fixture packages were removed. The existing
density override of 440 was retained. This UI fixture uses no account,
microphone, inference or personal server.

## Installed update

Signed `0.8.3-integration-preview`/build22 was installed over build21 via ADB.
The installed APK matches the signed artifact byte for byte. Signing certificate
SHA-256 remains
`c1211fd4dbb2b4c0710fe35858bbc4d19eb2d2d27abb18eb5ad51e8192db55bf`.
UID 10260 and CE/DE data-directory identities 117766/116002 were preserved. The
personal app was left stopped for the user to open.

Local artifact directory:
`/home/praxisog/Bellpull-builds/2026-10-14-voice-disclosure/`.
It contains the prior installed APK, the signed/installed build22 APKs, package
identity evidence, test/build logs and four phone screenshots. Build22 SHA-256:
`6e34b41c29926b4aae5db08857d5c5941617deaed94e52472e04e43f38832257`.

Source stays on `repair/experimental-open-issues`; no push or publication.
