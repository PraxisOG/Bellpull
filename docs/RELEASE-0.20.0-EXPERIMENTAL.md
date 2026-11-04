# Bellpull 0.20.0 experimental

Android **0.20.0-experimental / build 50**, paired with plugin
**0.9.0-kanban-tree-preview**. Source branch: `release/0.20.0-experimental`.
Release tag: `android-v0.20.0-experimental.1`.

This combines the completed local development since published 0.19.0:

- Compact Tasks toolbar with List, Board and recursively nested Tree views;
  explicit subtask containment, cycle prevention, complete hierarchy pagination,
  saved expansion and existing offline edit/conflict recovery.
- Board Chat/Chats shortcut that opens one linked conversation directly or shows
  distinct chats and related tasks. Existing task chat links remain available.
- Pull down on Conversations to refresh, including empty and failed lists, with
  progress, cached rows and filters preserved.
- Reliable Thinking taps in long replies; local switches for terminal/thinking
  display and thinking previews, plus live activity counts.
- Compact Tools: eight recent attachment previews, Search library, direct server
  uploads with durable recovery, and expandable GPU summaries. Chat attachments
  remain available through the plus control.
- The previously installed 0.19.1 mobile repairs: native chat attachment handoff,
  voice continuity during approvals, per-model noise tolerance and SVG previews.

Install the signed APK over the existing app to preserve data. Install the entire
matching plugin bundle and restart the gateway when idle for complete Tree
hierarchies/containment and attachment handoff. The refresh gesture, board chat
shortcut and display controls themselves need no new plugin endpoints. Moe is a
manual user update; this release does not deploy a live server.

Android validation: **876 tests passed**, one optional live-server test skipped;
**218 screenshot baselines verified**, lint passed. Signed assembly, matching
release certificate/signing lineage and 16 KiB APK alignment checks passed.
The isolated physical phone refresh test passed at narrow and unfolded widths.
Earlier slice evidence also covers native Tree edits/recovery, board chat navigation,
Thinking interaction, Tools uploads and file previews on the phone.

On stock Hermes 0.21.5, 31 native Kanban, rediscovery and attachment checks passed
using disposable local Docker homes. The complete plugin suite runs separately
against the freshly built documented legacy host fixture. Native attachment
capabilities are checked independently of private host-source compatibility;
the native-only lifecycle check belongs to the stock host suite.

The signed APK was installed over 0.19.1/build49 on the Pixel. Installed bytes
match the release SHA-256, and app ID, data directory and original installation
time are unchanged. The previous APK is retained locally for recovery. No app
data was cleared and no personal server was contacted for acceptance.
[APK verification](evidence/release-0.20.0/apk-verification.json) ·
[phone verification](evidence/release-0.20.0/phone-verification.json).

Known limits: Canvas still has the previously recorded unresolved full-device
main-conversation opening assertion; this release does not claim to resolve it.
Complete Tree data requires the updated plugin; partial/offline data remains
explicitly marked. Chat shortcuts use recorded task-origin links and cannot infer
missing links. Hiding tool/thinking blocks changes display only, not model output
or execution. Direct Tools uploads do not send a chat message or start a run.

Detailed behavior and evidence: [Kanban Tree](KANBAN-TREE.md),
[board chats](KANBAN-BOARD-CHATS.md), [refresh](CONVERSATION-REFRESH.md),
[chat display](CHAT-DISPLAY-REPAIRS.md), [Tools](TOOLS-CLEANUP.md),
and [mobile repairs](MOBILE-BUG-REPAIRS-2026-11-02.md).

Release assets include the signed APK, complete matching plugin, source archive
with pinned recursive submodules, the phase roadmap and SHA-256 checksums.
