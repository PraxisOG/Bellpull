# Bellpull 0.20.13 experimental

Android build 63 and plugin 0.9.8-mobile-preview recover missed run notifications on Hermes hosts with the native run-status hook. Open **Today → Inbox**, choose a run, then select **Read saved item** to see when it occurred, when the relay accepted it and when the device saved its status. **Open conversation** returns to the original chat.

The plugin persists each received run transition before attempting ntfy delivery. A gateway restart resumes pending deliveries. Bellpull reads authenticated receipt pages after reconnect and saves each page and its cursor together. Transport updates preserve a locally opened or dismissed item. A device with Android notifications turned off can still read its Inbox.

## Verification

The released build 62 recovered an ordinary chat reply after a test outage but had no corresponding run receipt. The new client archived the missed run on a disposable Android emulator with notification permission denied. When ntfy recovered, the existing receipt showed transport acceptance. The same run remained readable after a cold offline restart. Two source runs executed once each.

The reference Hermes host and ntfy 2.28.0 ran locally. Stock hosts without the native status hook retain their existing recovery behavior. Production relay deployment, an independent source for gateway-down alerts and physical-device delivery remain open under [issue #34](https://github.com/PraxisOG/Bellpull/issues/34).
