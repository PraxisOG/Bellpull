# Mobile UI repairs — November 9, 2026

Historical November 9 record, based on published 0.20.0 source at `9c8f3a2`.
These changes are now integrated into the [November 15 reliability candidate](PRIORITY-BUG-REPAIRS-2026-11-15.md),
which supersedes the local/unpublished status and next-investigation notes below.

## Reply links (#50)

Bare URLs failed a real Compose touch test on the original source. Markdown
links already opened correctly in both saved replies and dense streaming replies;
the issue's older bubble-click-handler hypothesis does not apply to this release.

The renderer now annotates bare HTTP(S) and `www.` URLs using the existing
AndroidX detector. Sentence punctuation stays outside the link, and balanced path
parentheses are retained. Explicit Markdown destinations keep precedence. Code,
image descriptions, HTML and blocked schemes remain inert. No dependency was added.
Touch tests verify opening through `UriHandler`, plus long-press actions and the
real text-selection dialog.

## Dialog keyboard handling (#52)

Custom dialogs now share `BellpullDialog`: an explicitly edge-to-edge window,
matching normal/immersive chrome, and one owner for keyboard, system-bar and
cutout insets. It also hosts profile-renaming and quote-selection alerts. Removed
the redundant form-level keyboard padding. The window policy follows Android's
[DialogProperties contract](https://developer.android.com/reference/kotlin/androidx/compose/ui/window/DialogProperties).

A tall keyboard at the simulated landscape size leaves only a very small usable
area. The initial wrapper candidate exposed a second layout problem there: forms
measured their footer to zero. Below 320 dp of usable height, the final wrapper
allows the whole form to scroll so Save remains reachable. Dismissing the keyboard
resets that temporary scroll and restores the original dialog position. Outside
taps still dismiss; taps within form content do not fall through.

The exact reported stranded-dialog sequence was **not reproduced in the project
editor on the original release**. Its existing test already calls `ProjectEditor`,
which creates a real dialog in this source revision. The expanded device regression
tests that real editor in both normal and immersive mode, shows the keyboard,
reaches Save, dismisses with Back, compares the title/button positions within 4 dp,
reopens the keyboard, and saves using actual touch injection. The short landscape
case additionally exercises whole-dialog scrolling. It does not use a semantic
click to save or issue another scroll request after the target is positioned.

## Validation

- **887 JVM tests passed**, one opt-in live-server smoke test skipped:
  app 797, core-model 42, core-hermes 48 passed / one skipped.
- Screenshot verification passed; the tree has **221 baselines**, including new
  narrow/wide reply links and the tiny-height scroll regression. Nineteen existing
  dialog images were updated for the reviewed positioning change.
- Lint: zero errors; 93 warnings. Fixture APK and instrumentation APK build passed.
- **Six physical-phone test executions passed**: normal and immersive at
  2076×2152, simulated narrow 1080×2152, and simulated landscape 2152×1080,
  retaining density 440. The size override was reset afterward.
- Only the disposable `org.praxisog.hermes.fixture` package was installed.
  The normal app was not upgraded. No server deployment or GitHub publication.

The size simulations verify landscape/narrow layouts; they are not a physical
rotation or hinge-transition test while a dialog is open. The original reporter's
specific dialog still needs identification before claiming full closure of #52.

Evidence: [validation record](evidence/mobile-ui-20261109/validation.json),
[JVM/screenshot/lint log](evidence/mobile-ui-20261109/validation.log),
[project dialog comparison](evidence/mobile-ui-20261109/project-dialog-compare.png),
[voice dialog comparison](evidence/mobile-ui-20261109/voice-dialog-compare.png).

## Next outstanding investigation (at the November 9 checkpoint)

[#51: missing delegated results](https://github.com/PraxisOG/Bellpull/issues/51)
remains open. There are no `async_delegation_complete` consumers in the checked
Android/plugin source. Durable delivery, session ownership, deduplication and
approval-gate behavior need a separate app/server change; this UI repair does not
address that delivery contract.
