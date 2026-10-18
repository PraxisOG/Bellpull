# Phase 14: offline conversations and reconnection

Android **0.14.0-experimental / build 32**, Bellpull plugin **0.5.0-integration-preview**.
Gemma 4 E2B Q4_0 runs on CPU. Thinking defaults off and is saved per local chat.

## Use

Open a server chat's **More → Use on phone**. **Fork saved chat** uses its cached
transcript; **Prepare for offline** downloads the complete transcript and builds
semantic search. Settings' local chat section also opens the saved fork list or a
fresh local conversation. Import the existing Gemma E2B GGUF in Model setup if needed.

Local messages and drafts are separate from the source. Search, citation buttons,
and the model's `search_chat` / `read_chunk` tools access only that fork's immutable
source snapshot. Indexing can also run offline and resumes completed chunks after
interruption. Keyword search covers chunks whose embeddings are not yet available.

Details includes an optional user-written brief, memory refresh, and **Continue in
Hermes**. Review that handoff to create a new server conversation. A lost response
leaves a persistent pending operation: retry recovers the same conversation. Thinking,
incomplete replies and executable tool calls are excluded. Import does not run an
agent. The source stays unchanged; there is no automatic merging or tool replay.

## Implementation boundaries

- AES-GCM atomic records use profile/record authenticated data. Source snapshots
  are stored separately so streaming a reply does not rewrite a long old chat.
  Profile deletion removes the offline files along with the existing file cache.
- Complete viewed transcripts are cached and displayed before a network refresh.
  Compact Hermes memory snapshots sync on connection, session refresh and completed
  replies, throttled to once per minute. Existing forks retain a stable snapshot
  until explicitly refreshed. Deleted and disabled server memory entries disappear
  from the next snapshot. No bulk chat summarization runs in the background.
- MiniLM-L6-v2: pinned Apache-2.0 int8 ONNX weights, 23,026,053 bytes; 384-dimensional
  normalized mean-pooled embeddings and uncased WordPiece. The existing ONNX runtime
  is reused. `tools/prepare-search-model.py` fetches and verifies assets during build.
  See the packaged MiniLM notice for exact revision/checksums. Chunks have at most
  220 UTF-16 units, overlap, and stable message/offset citations.
- Native context remains 2,048 tokens with exact template token accounting. Recent
  local history, memory and retrieved excerpts are reduced to fit. Answers are capped
  at 384 generated tokens; thinking at 768, shared across all local tool rounds,
  with a 120-second deadline for each generation step.
  Prefill is separate from that deadline. Stop cancels prefill and generation.
- The single native owner is shared with the old lab. Verified E2B is selected and
  CPU is explicit. Matching prompt prefixes reuse KV state only within the same
  profile/fork, with fallback to full prefill when partial removal is unavailable.
  Metrics distinguish full prompt size, reused tokens and newly prefetched tokens.
- Thinking uses the template's `enable_thinking` / `<|think|>` setting and explicitly
  prefills Gemma's thought channel. The embedded template otherwise allowed greedy
  short-answer generation to skip reasoning. Parsing separates thought from final
  answer; neither thoughts nor incomplete answers enter subsequent local history.
  [Gemma's documented thinking format](https://ai.google.dev/gemma/docs/capabilities/thinking).
- Local tools are read-only, source-scoped, strictly parsed and limited to two calls
  per reply. Their adherence depends on the small model; manual source search remains
  available. There are no device-action, shell, network or memory-write tools.
- The plugin adds authenticated, profile-scoped `/api/bellpull/offline/memory` and
  `/api/bellpull/offline/handoff`. Handoff validates plain role/content pairs and
  imports the branch plus fingerprint in one SessionDB transaction. Its narrow
  contract is tested on Hermes 0.21.1 and 0.21.5 independently of optional run hooks.

## Acceptance evidence

The physical Pixel 10 Pro Fold fixture tested encryption/AAD profile isolation,
interrupted-state recovery, actual ONNX retrieval, E2B CPU replies, Activity recreation,
thinking, cache reuse and Stop. A separate real-Hermes device test prepared a source,
answered with its network client unavailable, deliberately discarded a successful
handoff response, retried via the review UI and verified one branch, unchanged source
and no tool calls. Synthetic server conversations were removed afterward.

Example short-run measurements, not sustained performance promises:

| Check | Result |
|---|---:|
| Index 41 short source messages | 1.63 seconds |
| First source answer, 229 prompt / 3 output tokens | 2.41 s prefill + 0.26 s generation |
| Follow-up, 253 prompt / 2 output tokens | 107 tokens reused; 1.67 s prefill + 0.18 s generation |
| Thinking time arithmetic | Correct 11:14; 340 generated tokens; 3.72 s prefill + 31.01 s generation |
| Stop during generation | 404 ms |

Machine-readable reports: [device](evidence/phase14/device.json),
[live handoff](evidence/phase14/live-handoff.json), and
[actual local tool execution](evidence/phase14/tools.json). The tool probe executed
two bounded source searches and returned the correct cited room using 42 total
generated tokens over three steps. These synthetic short prompts do
not establish quality over arbitrary long chats or prove that intermittent CPU stalls
are eliminated. Thinking can still exhaust its budget without a final answer.

Preparation currently caches/indexes and accepts a manual brief; it does not request
a server-generated summary. The bundled embedding model is strongest in English.
Images, attachments, voice routing and arbitrary Hermes tools are outside this local
text experiment. Moe requires the matching plugin for memory/handoff; local forks
can still use previously cached transcripts from older servers.
Unsupported servers are detected before a pending handoff is saved, so local chat
stays editable. Confirmed request rejection also unlocks the local branch; uncertain
transport failures retain the operation for safe retry.

Automated checks: 688 JVM tests (one opt-in live smoke skipped), 187 existing
screenshot baselines, lint, and ARM64/x86_64 release assembly. Plugin: 160 tests
(one skipped) on the pinned host; seven offline/profile integration tests also
passed on the local Hermes 0.21.5 image. Physical acceptance includes the storage,
CPU/thinking/cancellation, tool execution and real-Hermes handoff tests above.
