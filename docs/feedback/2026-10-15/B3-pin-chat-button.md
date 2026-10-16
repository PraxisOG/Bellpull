# B3 — Pin Chat button gives no visible response

Type: user-reported bug. Reported October 15, 2026.
Related: [existing pin feature #13](https://github.com/PraxisOG/Bellpull/issues/13)
and [prior repair record](../../EXPERIMENTAL-ISSUE-REPAIRS.md).

## Observed behavior

Pressing Pin Chat appears to do nothing. The owner sees no pressed-state color
change around the control and cannot use the button to test pinning.

The report establishes an apparently unresponsive control. It does **not**
establish whether a request reaches the server, whether server pin state changes,
whether an overlay intercepts input, or whether the control is disabled.
The owner could not determine whether pinning succeeds despite missing feedback.

## Reported reproduction

Open the chat controls and press Pin Chat. Observe the missing visual response.
Exact control location/layout, project versus Unfiled state, server capabilities
and installed plugin version were not confirmed during note-taking.

## Expected behavior and proposed acceptance

- The visible control receives a tap across its intended touch target and gives
  appropriate feedback.
- Eligible taps trigger one pin/unpin action, show the resulting state and persist
  it across refresh/restart.
- Unsupported or unavailable pinning presents an explicit explanation instead of
  a control that appears broken.
- Inspect hit testing, enabled state, semantics, callback/action dispatch and
  capability gating before assigning a cause.
- The prior repair record says global Unfiled pins require plugin 0.4.1 and the
  `bellpull_conversation_pins` capability. Check this possibility without treating
  it as the diagnosis or deploying a personal-server update without authorization.
- Verify with real touch interaction; a programmatically invoked action alone
  cannot prove that the on-screen button receives taps.
