# Bellpull 0.20.10 experimental

Android build **60** shows the model and provider behind a completed durable run. Long-press its reply and open **Message info**. If an older client saved the run ID, opening the sheet can recover the route from Hermes. **Settings → Servers → Integration status** shows the last observed completed run with model evidence for the active profile.

Plugin **0.9.5-mobile-preview** reports a confirmed provider switch during a durable run and retains it with the run status. The notice includes the original route, destination and advertised image capability when available. Stock 0.21.5 also needs the plugin to forward an explicit Bellpull model lock from durable admission to agent construction. Unpinned native runs use the configured fallback chain. An explicitly selected model keeps its lock during a provider outage.

## Verification

The signed build 59 omitted the serving model for a native reply that reported it in both the stream and the pollable status. On a disposable stock gateway the primary provider returned HTTP 503 and the fallback answered the unpinned request. The new event and saved status named the primary and fallback, including the destination’s text-only capability. A healthy primary answered a separate run directly. Both model endpoints in this test were local fixtures. Production credentials, provider outages, infrastructure health and maintenance remain tracked separately. [Change](https://github.com/PraxisOG/Bellpull/pull/78) · [Integration readiness](https://github.com/PraxisOG/Bellpull/issues/42)
