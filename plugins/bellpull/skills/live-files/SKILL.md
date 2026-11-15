---
name: live-files
description: Show server documents and editable lists in Bellpull.
version: 0.1.0
author: praxisog, Hermes Agent
license: MIT
platforms: [linux]
---

# Live files in Bellpull

Use this skill when the user wants to view a server document on their phone,
keep a shopping list handy, pin a document, or check off and add list items.
The Bellpull plugin must provide the `bellpull_file` tool.

## Procedure

1. Use the exact file path supplied by the user or already established in the
   requested work. Resolve a relative path only against a known working directory.
   If its base is unclear, ask for that base; do not search the filesystem for a match.
2. Call `bellpull_file` with the absolute `path` and optionally a short `name`.
   The tool registers the original file. Do not copy it into an outbox or upload it.
   New references default to read-only. Omit `access` on routine refresh calls so
   an existing permission is preserved.
3. When the user explicitly asks to check/uncheck or add items from the phone,
   include `access: "checklist"`. This grants only those list operations, not a
   raw text editor. Use `access: "read"` when the user wants to revoke editing.
   Include `pinned: true` only when requested; omit it to preserve the user's pin.
4. Check that the result reports `status: "referenced"` and a file ID. Only then
   say it is available in **Files**. If the tool reports an error, explain that
   error rather than claiming the phone received the file.
5. After modifying a referenced file as part of authorized work, call
   `bellpull_file` again with the same path. Its completion refreshes Bellpull's
   file list and open document. Opening/resuming and manual Refresh also read
   the current source file.

## List format

Use ordinary Markdown task lists: `- [ ] Milk` and `- [x] Bread`. The phone writes
this format when users add items or toggle checkboxes. Native editing covers
top-level task lists; code examples and deeply indented lists remain read-only
Markdown. Keep unrelated headings and prose intact.

For example, after the user has requested an editable grocery list at an already
known absolute path, call `bellpull_file` with that path, `name: "Groceries"`, and
`access: "checklist"`. Creating a missing file is a separate action and requires
the user's request to create it; registration never creates source files.

## Limits and recovery

Files must be UTF-8 text, at most 2 MiB, and accessible inside this server's own
filesystem. Symlinks, hard links and special files are rejected. Editing also
requires server ownership and permission to replace the file in its directory.
The reference is visible to this authenticated server profile across chats.

If a file changes during an edit, Bellpull asks the user to refresh and review.
Do not work around a conflict by blindly rewriting the source. A retry retains
the same operation ID so adding an item cannot duplicate it after a lost response.
