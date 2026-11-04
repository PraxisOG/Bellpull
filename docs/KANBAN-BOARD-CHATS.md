# Board chat shortcut

Branch: `feature/kanban-board-chats-20261103`, from `b837387` (current local
experimental development, including the Tools and chat display changes).
Implementation: `4c7b75a`.

The selected board's toolbar now has a visible Chat/Chats button beside New task.
One distinct linked chat opens directly when the saved board data is complete.
Several chats open a chooser showing available conversation titles, the number of
linked tasks and their titles. Missing conversation titles fall back to the
explicit session ID. Choosing a row follows the same Chats navigation callback as
the existing per-task Open chat controls; no new conversation is created.

Hermes stores the originating conversation in each task's `session_id`; its board
metadata has no separate conversation link. The button groups those explicit task
links by session ID, ignores blank links and counts each task once. It includes
nested, completed and archived tasks and does not depend on the task search filter
or List/Board/Tree mode. It does not infer links from titles, task dependencies or
external notification subscriptions.

The complete hierarchy cache supplies older tasks beyond the newest-500 snapshot.
If only a partial snapshot is available, the chooser marks the list as incomplete
and offers refresh when the connection supports it. Incomplete data never takes
the single-chat shortcut. Saved links remain usable offline, and unlinked boards
have an explicit empty state. A chat that is no longer accessible follows the
normal conversation-loading error path. Switching boards resets the chooser.

This adds no dependency, API change or stored relationship. Boards/tasks without
an originating session cannot acquire a link from this button. Task edits,
execution, status and tree expansion are untouched.

Validation: 776 app tests, 42 model tests and 48 client tests passed; one optional
live-server test skipped. All 216 screenshot baselines verified and lint passed.
New coverage checks distinct grouping, nested/completed origins, ignored filters,
archived/offline access, older tasks beyond the snapshot, missing chat titles,
partial/empty states and actual navigation through HermesApp without creating a
chat. The new chooser was inspected at cover and unfolded sizes.

The isolated Android fixture also passed a real touch test on the unfolded Pixel
10 Pro Fold: choose the second linked chat, verify its conversation content, return
to Tasks and open a single linked chat directly. No new conversation was created.
The fixture uses saved synthetic tasks and conversation content, so it needs no
server or inference. The fixture packages and temporary APK were removed afterward;
the personal app remains 0.19.1-experimental/build49 with its data preserved, and
the unrelated Canvas test package remains installed.

Review the [toolbar before](evidence/kanban-board-chats-20261103/toolbar-before.png),
[toolbar after](evidence/kanban-board-chats-20261103/toolbar-after.png),
[cover chooser](evidence/kanban-board-chats-20261103/picker-cover.png),
[unfolded chooser](evidence/kanban-board-chats-20261103/picker-unfolded.png)
and [check log](evidence/kanban-board-chats-20261103/checks.log).
Phone evidence: [toolbar](evidence/kanban-board-chats-20261103/device/board-chats-toolbar.png),
[chooser](evidence/kanban-board-chats-20261103/device/board-chats-picker.png),
[opened chat](evidence/kanban-board-chats-20261103/device/board-chat-opened.png)
and [passing instrumentation log](evidence/kanban-board-chats-20261103/phone-tests.log).

Source remains local. No release publication, personal-app replacement or Moe
deployment is part of this change.
