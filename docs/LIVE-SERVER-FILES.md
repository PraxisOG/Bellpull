# Live server files

The Files tab replaces Tools. Its left pane lists pinned and recently referenced
or opened documents. Selecting one reads its original server path into the native
Markdown/plain-text viewer. Attachments and uploads retain their existing browser.
Settings begins with up to three recent documents/attachments, **See all files**,
then the compact expandable GPU cards and the existing settings sections.

## Agent registration and permissions

The plugin registers `bellpull_file` and the agent skill `bellpull:live-files`.
The skill ships inside the plugin and is available through Hermes `skills_list`
and `skill_view`; no copy into a user skill directory is needed.

Only explicit absolute paths are accepted. A first reference is read-only;
`access: checklist` grants checkbox/add-item editing when requested by the user.
Omitting access preserves an existing grant; `access: read` revokes it. Optional
`pinned` follows the same preserve-when-omitted rule. HTTP clients cannot register
arbitrary paths or list server directories. The active session identity comes
from native tool dispatch and must exist in this Hermes data home.

References belong to the authenticated server profile, across its conversations.
The registry is an allowlist of individual paths. Reading a document never creates
an artifact copy. Files must be regular UTF-8 text of at most 2 MiB, without hard
links or symlink traversal. Editing also requires server ownership and a writable
parent directory. Register paths as seen by the server process/container.

## Refresh and editing

Opening a document, resuming Files/Settings, manual Refresh, completed
`bellpull_file` calls and reply completion refresh the view. Conditional GET uses
an ETag covering content, modification time and access, so a grant change also
invalidates the view. There is no continuous document polling.

Checkboxes change only the Markdown task marker. Add item inserts a new `- [ ]`
entry after the last actionable item, or at the end of a document without tasks.
Input is plain text and Markdown metacharacters are escaped. Existing line endings
and file permissions are preserved. Top-level task lists are interactive; fenced
and indented code examples and deeper nested lists stay ordinary Markdown.

Every edit includes the viewed ETag and a stable operation ID. Writes are staged,
fsynced and atomically replaced after a fresh source comparison. Concurrent
Bellpull edits serialize. External editors are checked optimistically; they do
not participate in the registry lock. A changed version returns 409 for review.
The phone journals pending edits in its profile's encrypted preferences before
sending. A lost reply can retry the same operation without duplicating an add,
including after app restart. Review current file abandons the pending retry;
it explicitly does not undo an edit already saved on the server.

## API

All routes use the plugin's existing bearer authentication and native profile scope.
Capability: `bellpull_live_files`.

| Route | Result |
| --- | --- |
| `GET /api/bellpull/files` | Up to 2,000 references, pinned first then most recently accessed/referenced |
| `GET /api/bellpull/files/{id}` | Current text, task line indices, access and ETag; supports `If-None-Match`/304 |
| `POST /api/bellpull/files/{id}/pin` | `{ "pinned": true/false }` |
| `POST /api/bellpull/files/{id}/edit` | `operation_id`, `base_etag`, and `action: check` with `line`/`checked`, or `action: add` with `text` and optional `after_line` |

Request bodies are limited to 16 KiB; new item text to 1,000 characters. Edit
receipts are retained to prevent old retries from replaying, capped at 100,000
per profile. No delete, arbitrary text replacement or directory-browser API is
introduced.

## Open a shared path from the phone

The server owner can create `bellpull/file-roots.json` in the active Hermes data
home. Each name maps to an absolute directory on that server. For example,
`{"notes":"/srv/shared-notes"}` makes `notes/weekly/plan.md` available by path.
In **Files**, type that path into **Find a file** and tap **Open by path**. The
viewer supports Markdown, plain text, text sizing, Save, Share and pull-to-refresh.
Returning to the app checks the current document again. Opening a path grants
read access for that request and creates no registered file or checklist grant.
Removing an alias revokes subsequent requests, including conditional refreshes.

The authenticated file list exposes configured aliases under `path_roots`. The
`bellpull_file_paths` capability indicates whether this profile has configured
roots. An absent or invalid configuration disables direct reads. The existing
per-file registry continues serving its own explicitly referenced documents.
`GET /api/bellpull/files?path=notes%2Fweekly%2Fplan.md` returns UTF-8 text, a
strong ETag, source path, byte size and modification time in nanoseconds. Optional
integer `offset` and `limit` parameters page the 2 MiB maximum document without
splitting UTF-8 characters. Use `next_offset` for the next page. The minimum page
limit is four bytes. A matching `If-None-Match` returns HTTP 304. The service
rejects traversal, symlinks, hard links, special files and binary content.

The API 36 emulator opened `docs/Weekly notes.txt` through the Files search field
against a disposable stock Hermes server. The document had never been registered
by the agent. The same run verified the read-only controls, saved a checklist
change to a separately registered document and reopened both views. Server and
client tests cover conditional reads, permission revocation, non-ASCII page
boundaries and path containment.
