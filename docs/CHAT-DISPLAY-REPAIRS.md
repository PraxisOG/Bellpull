# Chat display repairs

Base: `09f585f`, including current experimental mobile repairs and Kanban Tree.
Branch: `fix/chat-display-20261103`.
Implementation: `fa82af5`.

Implemented and validated on 2026-11-03. No dependency or plugin/API change.

The regression test reproduced a missed Thinking tap below a 45,000-character
response. Clipping the entire response through a rounded graphics layer interfered
with hit testing far down the bubble. The response now clips drawing only, retaining
its border and rounded appearance. Transcript pointer-down and disclosure actions
also pause tail following before expanding content or incoming text can move the
control. Scrolling back to the end or using Jump to latest resumes following.
Touch observation does not consume child gestures or text selection.

Settings → Display adds four persistent, independent switches:

| Setting | Default | Behavior |
| --- | --- | --- |
| Hide terminal calls | Off | Hides routine `terminal` rows in history and live responses. |
| Hide thinking | Off | Hides the thinking disclosure and its body. |
| Thinking previews | Off | Adds a single-line excerpt beside collapsed Thinking labels. |
| Activity counts | On | Adds counts beneath the current response's status label. |

Terminal errors, nonzero exits, refusals, pending approvals and flagged results
remain visible. Groups containing retained terminal warnings initially expand.
The existing Collapse thinking by default choice and the preview choice remain
saved while Hide thinking temporarily disables their controls.

Previews show up to 140 Unicode code points from received thinking, normalize
whitespace and truncate to one visible line. The scan is bounded to 4,096 source
characters. Opening the disclosure still shows the full text. No model-generated
summary or prompt modification is involved.

Counts describe received thinking blocks and tool starts, including hidden and
running calls. For example: `2 thinking blocks · 2 terminal · 1 search`. They count
distinct part/row identities before history/live projection or display filtering;
stream replay sequence deduplication prevents counting retransmissions. `web_search`
counts as search, `terminal` as terminal, and other tools have a separate count.
Zero categories are omitted. Counts describe the current received run, not tokens,
hidden model iterations or a permanent history total.

Display changes preserve execution, model reasoning options, raw history and
speech. Canonical response blocks remain the source of copy/read/branch actions,
even when the last message in a response is hidden thinking. Raw history remains
the input for live/history overlap detection. This change makes the visible
transcript more compact; it does not shorten the model's answer text.

Validation:

- `:app:verifyRoborazziDebug :app:lintDebug :core-model:test :core-hermes:test`:
  754 app tests, 42 model tests and 48 client tests passed; one opt-in live-server
  test skipped. All 211 screenshot baselines verified; lint passed.
- Added focused tests for physical touch on a long response during streaming,
  replay/identity counting, bounded Unicode previews, persistent switches,
  history/live filtering, retained failures and grouped refusals, restored rows,
  canonical branch targets, and counting before history projection.
- Cover layout at 393×850 dp and unfolded layout at 840×900 dp inspected in
  screenshot tests. Four new baselines; five existing activity-footer baselines
  updated and their visual changes reviewed.
- Three Android instrumentation tests passed on the Pixel 10 Pro Fold, Android 17,
  unfolded at 2076×2152 px with its existing density override. Tests exercised
  the long response, continued streaming, hidden/restored activity and previews,
  disclosure body taps, native text selection/copy and message long-press actions.
  No account, network or inference was used in these isolated UI fixtures.
- The disposable `org.praxisog.hermes.fixture` app and its test package were
  removed afterward. The personal app remains 0.19.1-experimental/build49.
  No release, push, personal-app update or Moe deployment was performed.

Evidence: [check log](evidence/chat-display-20261103/checks.log),
[phone tests](evidence/chat-display-20261103/phone-tests.log),
[original tap failure](evidence/chat-display-20261103/thinking-before-fix.log),
[footer comparison](evidence/chat-display-20261103/activity-footer-diff.png),
[cover preview](evidence/chat-display-20261103/phone-preview.png),
[unfolded preview](evidence/chat-display-20261103/unfolded-preview.png),
[hidden activity](evidence/chat-display-20261103/phone-hidden.png),
[settings](evidence/chat-display-20261103/settings.png),
[physical-phone preview](evidence/chat-display-20261103/device/chat-thinking-previews.png),
[long response with Thinking open](evidence/chat-display-20261103/device/chat-long-response-thinking-open.png).
