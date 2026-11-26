# Bellpull 0.20.14 experimental

Android build 64 and plugin 0.9.9-mobile-preview show when a Bot Chat delivery is still claimed after its original owner exits. Open **Today → Inbox** or **Bot Chat delivery history** to inspect the receipt. On supported Hermes hosts it reports which owner held the conversation at the last check. Open the routine’s saved results to read its output.

One test on stock Hermes 0.21.5 ended an owner process after it claimed a delivery. A replacement took over the same chat with a different lease. The native receipt stayed claimed, the plugin reported that the owner changed, and the original saved output remained readable. The scheduler recorded one execution.

Operator resolution and a new processing attempt, infrastructure notification retry, and deployed provider checks remain open in [issue #41](https://github.com/PraxisOG/Bellpull/issues/41).
