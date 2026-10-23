# Bellpull 0.14.8 experimental

Open a conversation’s **⋮ → Thinking level**, then choose a level. The submenu
shows only controls supported by the current model and marks the active choice.
Selecting closes both menu levels and saves the conversation’s reasoning setting.
For Moe’s Qwen model: Default, Off, On, Low, Medium and XHigh. Default restores
server reasoning defaults. A back arrow returns to the main menu without changes.

- **Android:** 0.14.8-experimental / build40; signed in-place update.
- **Plugin:** 0.6.1-integration-preview, unchanged; no server update needed.
- Model/provider selection is preserved. Failed saves retain the previous choice
  and display an error in the chat. Missing catalog/selection data offers Retry.
- This shortcut applies to supported server conversations. Offline Gemma retains
  its existing settings controls.

Validation: 737 declared JVM checks, 736 passing and one existing opt-in skip;
191 screenshot baselines verified, including four new folded/unfolded menu views;
lint zero errors/79 existing warnings; signed ARM64/x86_64 release assembly.
New UI checks use the actual workspace/client with a synthetic HTTP fixture to
verify supported options, saving, dismissal, reopening, default restoration,
submenu back navigation and rejected-save handling. Initial fixture waits missed
Compose effects; explicit UI synchronization fixed the harness before final checks.
Transient touch ripples are disabled only in these screenshot fixtures to keep
menu captures deterministic.
The plugin and provider mapping are unchanged from the tested 0.14.7 release.

New views (the previous 187 baselines are unchanged):
[phone overflow](../android/app/src/test/snapshots/thinking-cover-overflow.png),
[phone thinking submenu](../android/app/src/test/snapshots/thinking-cover-levels.png),
[unfolded overflow](../android/app/src/test/snapshots/thinking-unfolded-dark-overflow.png),
[unfolded thinking submenu](../android/app/src/test/snapshots/thinking-unfolded-dark-levels.png).

[Download experimental](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.14.8-experimental.1).

APK SHA-256: `0a84fa712cdc7bdd93b6c3bb4014c5eeab53a0949c4880062f729a5cb4004227`.
