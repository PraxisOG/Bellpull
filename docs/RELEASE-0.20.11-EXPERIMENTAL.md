# Bellpull 0.20.11 experimental

Android build 61 and plugin 0.9.6-mobile-preview recover retained Bot Chat receipts for the active Hermes profile. The Inbox imports recent deliveries, and **Bot Chat delivery history** pages through earlier native executions. Open a receipt to see its execution and delivery IDs. A timeout notice tracks the short warning about the original delivery. The routine’s saved results contain any outputs Hermes still retains.

## Verification

On a disposable stock Hermes 0.21.5 gateway, one scheduled routine saved two outputs. Its first Bot Chat delivery timed out and left an ambiguous notice. The second had a settled owner receipt. The previous plugin returned only the second receipt. The new authenticated history returned both, with their original identities. Repeated reads preserved both statuses and the execution count. The legacy timeout was set to two seconds in this fixture.

Cross-profile historical lookup, recovery of a queued receipt after its original owner restarts, and retrying notifications by their original event ID remain separate acceptance work. [Change and tests](https://github.com/PraxisOG/Bellpull/pull/79) · [Delivery recovery](https://github.com/PraxisOG/Bellpull/issues/41)
