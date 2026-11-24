# Bellpull 0.20.9 experimental

Android build **59** keeps Bot Chat delivery receipts under **Today → Inbox**. If a routine finishes and its delivery later becomes ambiguous, you can inspect each outcome independently. Open **Read saved item** on a delivery to find its native receipt ID, the status source and the time the phone saved it. **View saved results** opens the routine’s retained outputs when the server supports them. The matching Bellpull plugin is **0.9.4-mobile-preview**. [Android change](https://github.com/PraxisOG/Bellpull/pull/77)

The phone keeps one item per observed delivery ID in its profile’s existing 200-item Inbox. Updates preserve the item’s opened and dismissed state. A subsequent routine run can replace the server’s current receipt reference while the earlier item stays readable offline. On upgrade, Bellpull imports receipts from the last planning snapshot already on the phone.

## Verification

The regression reproduced the missing receipt on build 58. Tests exercise an ambiguous owner result followed by a stale queued hint, a second execution with a different receipt, reopening offline, importing an older cache, and Inbox eviction. They also cover a deferred delivery transferring to its owner and settling. The receipt history covers observations available to this phone. An authenticated history for missed events and notification retry by original event ID remain infrastructure work. [Delivery issue](https://github.com/PraxisOG/Bellpull/issues/41)
