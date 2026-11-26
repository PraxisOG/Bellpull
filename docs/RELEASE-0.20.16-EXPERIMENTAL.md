# Bellpull 0.20.16 experimental · Owner memory status

Build 66 pairs with Bellpull plugin 0.9.11-mobile-preview. In a conversation with a completed **Memory status** call, tap its row to see whether owner memory was available to that chat. The detail view shows the number of visible records, messages awaiting indexing and whether the background organizer was enabled, when the provider reports those values. A chat with zero visible records can still have owner access.

With the matching plugin and a supported memory-subsystem provider, the row also shows which provider handled the call, when it ran, and when the provider built the chat’s startup snapshot. Older results show the information they recorded. **Settings → Servers → Integration status** shows the last completed check for the open chat on the selected profile. Opening either view reads the existing transcript.

The separately installed memory provider and the gateway profile’s owner policy govern access. To verify a configured installation, use a synthetic record and check the actual memory directory, search, read and status tools in two authorized chats. A shared chat must keep its own access scope. See [the integration tracker](https://github.com/PraxisOG/Bellpull/issues/45).
