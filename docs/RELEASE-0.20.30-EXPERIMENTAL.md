# Bellpull 0.20.30 experimental · Remote reminder alerts

With reply notifications enabled, Bellpull checks for reminders while another app is open. The previous build could skip a healthy one-minute poll when the preceding sync finished just after the timer fired. Failed requests still back off, and reconnecting or tapping Refresh requests an immediate check.

When a second reminder comes due, the existing Android notification now includes both reminders. Finishing or postponing one updates the group. Clearing a notification keeps those reminders in the Inbox without adding them to the next alert.

This release includes signed Android build 80 and server plugin 0.9.27-mobile-preview. [Notification tracker](https://github.com/PraxisOG/Bellpull/issues/34)
