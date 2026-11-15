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
