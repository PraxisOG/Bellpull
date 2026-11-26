# Bellpull 0.20.17 experimental · Notification dispatch status

Build 67 pairs with Bellpull plugin 0.9.12-mobile-preview. Open **Settings → Servers → Integration status → Check now** to check the gateway’s notification relay. **Run notification dispatch** shows whether the relay answered its health check and how many run receipts are awaiting broker acceptance for the selected profile’s credentials.

For a supported journal, the check shows when the oldest pending notification was stored and when the relay last accepted a notification. Hosts without the native receipt hook show an unsupported journal. An unconfigured or unreachable relay has a separate status. The check reads existing receipts and never sends a test alert. [Integration tracker](https://github.com/PraxisOG/Bellpull/issues/42)
