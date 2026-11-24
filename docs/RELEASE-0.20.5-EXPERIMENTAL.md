# Bellpull 0.20.5 experimental

Android build **55** pairs with plugin **0.9.4-mobile-preview**. Install the signed APK over 0.20.4. The matching plugin exposes retained routine results and explains model-provider configuration failures.

## Changes

- After a spoken interruption, Android waits for the confirmed Stop and sends the replacement once. Pending words survive switching chats or restarting the app. Speech received while approval is pending stays queued. [Voice handoff](https://github.com/PraxisOG/Bellpull/pull/73)
- Android tracks each child of a grouped background delegation and recognizes repeated completion receipts. [Grouped replies](https://github.com/PraxisOG/Bellpull/pull/70)
- Open retained routine output through Routines or Inbox after reconnecting. Execution status and Bot Chat delivery receipts have separate identities. [Routine results](https://github.com/PraxisOG/Bellpull/pull/71)
- **Settings → Servers → Integration status** reports gateway, plugin, model catalog and native readiness independently for the selected profile. Each check shows its last success. [Integration status](https://github.com/PraxisOG/Bellpull/pull/72)

## Verification

On an API 36 emulator, two synthetic spoken questions went through Android microphone capture and local Moonshine Tiny. A stock Hermes gateway running a deterministic worker accepted the first question, stopped it without saving a reply, and received the second question in the same chat. The server transcript and the reopened Android journal contained both requests. The native gateway recorded exactly two admissions and one Stop.

Separate emulator tests opened a saved routine result after a delivery failure and reconnect, and exercised plugin outage and recovery, missing model configuration, and rejected credentials. Reading the routine result started zero model runs.

A live model finishing a delegation after its parent, notification retry by original event ID, and physical car audio remain open acceptance items.

[Download the signed APK, matching plugin and complete source](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.20.5-experimental.1).
