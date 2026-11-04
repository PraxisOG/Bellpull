# Tools cleanup

Branch: `feature/tools-cleanup-20261103`, based on `9a918df` (including the current
experimental mobile fixes, Kanban Tree and chat display repairs).
Implementation commits: `8968b75` (direct uploads) and `4f963f4` (Tools layout).

Tools now starts with Upload files on the left and Search on the right. A bounded
gallery takes about half the available workspace height (220–480 dp) and contains
the eight newest distinct, nondeleted files. Narrow displays scroll within that
gallery; unfolded displays fit more columns. Tapping a tile opens the existing
preview/download/share controls. The full attachment list is no longer always
present on Tools.

Search opens a dismissible library with filename/type search, a clear action,
conversation filtering, date/name/type/size sorting and a lazy file list. Storage,
retention warnings, source-chat links and confirmed deletion remain available
there. Compact load/error notices remain outside the dialog; loading, failed and
empty galleries have distinct messages.

Recent images and SVGs reuse the profile-scoped thumbnail cache. The gallery also
renders the first PDF page natively. Automatic previews remain bounded to files
of at most 8 MiB, with the existing stricter SVG limit, 768-pixel decoded bounds,
12 MiB bitmap cache and 64 MiB disk cache. Other formats, oversized files and
disabled image previews use labeled file tiles. Chat's existing image-preview
setting and PDF behavior are preserved.

Each GPU shows one read-only temperature bar and an expand/collapse control.
The summary prefers a valid edge/core/GPU reading, then the hottest available
sensor; it always names the sensor actually shown. Expanding reveals the existing
sensor bars, ranges, slowdown threshold, device identifier, sample time and scale
note. Stale/unavailable readings remain apparent without expanding. Telemetry
polling and its foreground-only behavior are unchanged.

Removed from Tools: Session info, Search apps & chats, their introductory text and
the successful file-sharing notice. Their normal conversation/navigation features
remain available elsewhere. Unsupported server file sharing still has a compact
retry notice.

Direct uploads use the existing authenticated artifact PUT API. The user chooses
an existing server conversation as the storage destination because that is
required by the server's file contract. Selecting files starts the transfer;
it does not send a message, choose a model, require vision support or start a run.
Chat's attachment plus remains available and keeps its normal draft/send behavior.

The direct-upload queue is stored in the existing encrypted profile metadata and
uses private file copies. A defaulted `uploadOnly` flag distinguishes it from chat
attachments; old metadata continues to mean a chat draft. Each queued file captures
its destination and retains its identity across failures/cancellation/retry.
Confirmed uploads are removed from the local queue and appear in the library.
Failed or interrupted uploads remain available for manual retry, including after
restart. The upload panel provides progress, cancel, retry and remove controls.
Each file uses the existing supported types and 25 MiB limit; the pending queue
holds up to eight files. Server storage quotas still apply.
Cancellation also prevents an already-running import from automatically restarting
transfers when that import finishes.

No dependency, plugin change or server deployment is required. This remains local
development source; the user's regular app and Moe have not been updated.

Validation records and screenshots are in
[evidence/tools-cleanup-20261103](evidence/tools-cleanup-20261103/).

Automated validation passed: 768 app tests, 42 model tests and 48 client tests;
one opt-in live-server test skipped. All 214 screenshot baselines verified and
lint passed. Focused cases cover recent-eight selection and deduplication,
older-file search/clear/dismiss, gallery loading/failure/empty states, GPU details
and fallback readings, direct-upload isolation from chat/model execution, retained
identity after failure, cancellation/retry, cancellation during import, saved
queues and legacy draft metadata.

Cover (393×850 dp) and unfolded (840×900 dp) layouts were visually inspected.
Compare [GPU before](evidence/tools-cleanup-20261103/gpu-before.png) with
[GPU after](evidence/tools-cleanup-20261103/gpu-after.png), and review
[Tools on the cover display](evidence/tools-cleanup-20261103/tools-cover.png),
[unfolded Tools](evidence/tools-cleanup-20261103/tools-unfolded.png) and
[file search](evidence/tools-cleanup-20261103/file-search.png).

The final physical-phone acceptance test passed on the unfolded Pixel 10 Pro Fold
(Android 17, existing 2076×2152 px / density-440 configuration). It used the separate
fixture app and native Hermes image `2fd023efbb8d`, a disposable home, this plugin
mounted read-only, and ADB reverse on loopback port 18662. It rendered real PNG/PDF
thumbnails, opened a PDF, exercised the upload picker callback, uploaded through
the native API, searched the resulting file, expanded GPU details and used the
chat attachment plus. It verified downloaded bytes, an empty conversation history
and a separate unsent chat attachment. The container recorded zero agent executions.
The test substitutes a synthetic document-picker result; it does not browse the
user's files. The final run took 18 seconds.

See the [phone test log](evidence/tools-cleanup-20261103/phone-tests.log),
[native fixture log](evidence/tools-cleanup-20261103/native-fixture.log),
[real thumbnails](evidence/tools-cleanup-20261103/device/tools-recent-gallery.png),
[completed upload](evidence/tools-cleanup-20261103/device/tools-upload-complete.png)
and [expanded GPU](evidence/tools-cleanup-20261103/device/tools-gpu-details.png).
Both fixture APKs, their temporary install file, the port reverse and the disposable
container were removed afterward. The personal app remains
0.19.1-experimental/build49; no branch or release has been pushed.
