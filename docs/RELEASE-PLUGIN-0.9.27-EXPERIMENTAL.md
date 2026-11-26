# Bellpull plugin 0.9.27 experimental

Full stock Hermes 0.23.4 gateways now save run notification receipts. A completed reply, failure, cancellation or pending approval appears in **Today → Inbox** for the profile that owns the run. The phone can distinguish a saved receipt awaiting the relay from a wake hint accepted by the broker.

The gateway retains its notification observations across restarts. An interrupted connection or duplicate submission can recover the same receipt. If Hermes loses a running worker, Bellpull waits for a verified outcome. The gateway reads the existing admission throughout recovery.

This package includes the signed Android 0.20.28 experimental build 78. The notification change is in the server plugin. Remote relay deployment and an independent alert path during gateway outages remain open in [issue #34](https://github.com/PraxisOG/Bellpull/issues/34).
