# Compact Tasks and nested Tree

Base: `7de6b73` on `fix/mobile-bugs-20261102`, descendant of experimental 0.19.0.
Work branch: `feature/kanban-tree-20261103`.

Plan: first extend the plugin contract and durable models, then implement toolbar
routing and the three views, then verify persistence/recovery, hierarchy safety,
and phone/unfolded rendering. No new library is required.

Native inspection: Hermes 0.21.5's `hermes_cli/kanban_db.py` has dependency
`task_links`; `creator_task_id` is creation provenance used for session/subscription
inheritance. Neither is editable containment. Bellpull therefore owns a separate
`bellpull_kanban_containment(child_id PRIMARY KEY, parent_id)` table in each native
board database. Tasks have at most one container; dependencies remain independent.
Containment writes and receipts share the native write transaction, check both
task existence and ancestry, and never schedule work or update task status.

Progress counts all unique descendants, excluding the branch itself. Only status
`done` counts as completed; archived tasks remain in the denominator. Partial
trees show progress as incomplete. Search shows matches plus loaded ancestors.
Indentation is capped on narrow screens; explicit depth remains visible.

Hierarchy pages use stable task-ID ordering and a content revision. Every page is
checked against that revision so concurrent changes cannot manufacture a complete
tree. The newest-500 snapshot stays backward compatible. Complete hierarchies are
cached separately, and failed refreshes retain the previous cache with a notice.

The Tasks toolbar now uses the selected board name, opens a board-scoped new-task
form, and offers search, refresh, creation, board switching and board information.
Owner/time are in board information. Search can be hidden while keeping its filter
visible, or cleared. The three views share one complete hierarchy cache. Per-board
expansion survives detail navigation and restart. The phone's existing dependency
controls and chat toolbar keep their original behavior.

The follow-up request adds **Open chat** directly to cards in List, Board and
Tree when a task has a nonblank native `session_id`. The action uses the existing
conversation navigation callback independently of the task-details click. No
conversation is fabricated for tasks without a link.

Operational limits:

- Containment requires this updated plugin. Older plugins still support the
  existing list/board and edits; Tree explicitly reports missing hierarchy support.
  Native Hermes's own UI/CLI does not yet display Bellpull's containment table.
- Up to three complete board hierarchies are cached, with a 100,000-task safety
  bound per board. Exceeding it reports an incomplete/unavailable hierarchy and
  retains the previous cache. The API itself pages all tasks without a newest-500
  cutoff. Interrupted initial loads retain the existing newest-500 snapshot;
  refresh starts paging again. A board changing between pages requires retry.
- Search covers cached titles, IDs, assignees and body excerpts (up to 1,000
  characters per task), and includes loaded ancestors. It is not full-text search
  across all comments or execution history.
- Only confirmed native tasks can contain new subtasks; an offline new parent
  must sync before another draft can be created inside it. Pending reparenting
  remains visibly pending until confirmed, rather than moving the cached branch.

No dependency was added. No release was published and no Moe deployment was made.

Validation completed: 830 Android unit/UI checks passed (one opt-in live-server
check skipped), 207 screenshot results verified, lint and fixture APK builds
passed. The native suite passed 17 tests on each of two Hermes images. Three
physical-phone fixture tests passed, covering main navigation/board switching,
offline creation and receipt recovery, and recursive Tree/subtask/reparent flows.
See [verification evidence](evidence/kanban-tree-20261103/README.md) for logs,
before/after images, device screenshots and the exact commands. The main phone app
remains 0.19.1-experimental/build49; the disposable fixture was removed afterward.
