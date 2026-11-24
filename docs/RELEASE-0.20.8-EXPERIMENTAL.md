# Bellpull 0.20.8 experimental

Android build **58** adds **Review approvals** to Today when two or more tracked chats have pending Hermes requests. Each row shows the chat and the operation. Select **Allow once** or **Deny** for each, or set the same choice across the list and adjust individual rows. **Send decisions** submits the selected rows and shows each result. Requests that you leave unselected stay pending. This release pairs with Bellpull plugin **0.9.4-mobile-preview**. [Android change](https://github.com/PraxisOG/Bellpull/pull/76)

Bellpull checks the original run, request ID, operation and available choices again before sending each answer. If a selected operation changes or Hermes cannot be reached for that check, the app skips it. If Hermes receives a decision but the connection drops before confirmation, the result screen directs you to check that chat.

## Verification

Three chats on an API 36 emulator requested a harmless color-label decision through the native approval mechanism on stock Hermes 0.21.5. The model transport was deterministic. After force-stopping and reopening Bellpull, the reviewer allowed one request, denied another and left the third unanswered in one submission. The first two native runs completed with the matching tool results and one original user message each. The third retained its original pending request ID. Android regression tests also cover replacement operations, arrivals during review, unavailable choices, a failed status check, and a lost decision acknowledgement. Sandbox-scoped grants remain open in the host policy request. [Approval issue](https://github.com/PraxisOG/Bellpull/issues/37)
