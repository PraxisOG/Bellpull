# Phase 14: offline conversations and reconnection

Android **0.14.3-experimental / build 35**, Bellpull plugin **0.5.2-integration-preview**.
Gemma 4 E2B Q4_0 runs on CPU. Thinking defaults off and is saved per local chat.

## Use

Settings → **Local fallback** configures the installed Gemma E2B CPU model,
automatic loading, default thinking and 2K/4K/8K contexts (2K remains the default).
The editable **Local system prompt** is encrypted on this device and applies to
subsequent replies in all local chats after **Save prompt**. **Restore default**
loads the default into the editor; save to apply it. The app appends instructions
for the actually available local tools. Compaction retains its dedicated prompt.
A prompt that cannot fit alongside the question and answer allowance is rejected
before consuming the draft or compacting history. Increase context or shorten the
prompt; prompt text is never silently truncated.

**Ask Hermes for a short prompt** creates a separate chat on the selected online
server and sends a request for a 150–250-token personal profile using Hermes's
saved knowledge. The result is a normal copyable reply. Review it, copy it into
the settings field and save manually. Nothing automatically replaces the local
prompt. The request uses existing chat APIs. Plugin 0.5.2 additionally repairs automatic voice chat titles. If creation
or history loading fails, the ordinary error/draft flow remains available; an
edited draft or navigation away cancels automatic sending.

When the server becomes unreachable while Bellpull is visible, automatic loading starts on
the native worker after 10 seconds of continuous disconnection. Explicit Load is immediate.
A connection change does not cancel an already-started load; backgrounding still does. The red unavailable/loading banner becomes yellow with
**Local model ready · limited functionality** only after successful loading.
The authenticated foreground probe runs every 12 seconds with a 5-second deadline.
Backgrounding the app cancels local work and unloads the model; navigation within
the app and Activity recreation retain the conversation.

Offline **+** creates a local chat. Online, long-press **+ → New local chat**.
Local and server chats share the normal conversation list and adaptive shell.
An existing chat's toolbar or row menu offers **Create local fork**. Online it
fetches complete history; offline it uses the saved transcript. Gemma compacts
conversation data in chunks, with thinking disabled during compaction. Sources
longer than the selected context display **This will take a hot minute** before
inference begins. Stop preserves completed chunks; **Resume compaction** continues.
The original transcript remains encrypted and searchable through semantic/keyword
retrieval and bounded read-only tools. No bulk chat summarization is scheduled.

Before a local reply would require trimming older turns, Gemma compacts those
turns into a rolling continuation note. Original local messages remain visible and
searchable. Exact template token counts control chunk and reply budgets. Summaries
are lossy; retrieval can recover omitted details. Settings can increase the context
at a CPU prefill/RAM cost. Large single messages may still need shortening.

Details includes the generated note, optional user brief, memory refresh and
**Continue in Hermes**. Review before resync. The plugin imports complete local
messages and the note into Hermes's conversation database, where normal session
search can find them. It does not rewrite permanent MEMORY/USER notes or execute
an agent. An imported local copy is read-only; use **Open Hermes conversation**
to continue. The list hides its local row once the matching server row arrives,
retaining the local copy as a fallback until then. Lost responses retry the same
persistent operation; a second handoff of a synced fork returns its existing ID.
The original server chat remains a distinct, unchanged source.

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
- Native context is 2,048, 4,096 or 8,192 tokens with exact template accounting.
  Rolling compaction retains older local context; memory/excerpts are reduced to fit. Answers are capped
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

## 0.14.3 acceptance

699 JVM tests completed without failures (one opt-in live test skipped), all 187
existing screenshot baselines passed, and lint/release assembly passed for ARM64
and x86_64. Four distinct phone tests covered prompt editing/persistence, real E2B
inference at 4K, draft/history preservation on oversized prompts, the Settings
button's live Hermes round trip, first-send loading, and local retrieval.

The live test submitted one request, received a normal assistant reply, and
verified that local settings stayed unchanged. Its temporary chat was removed;
the generated personal profile is not included in the evidence. Encrypted storage
was checked using a fictional name. The unchanged 0.5.1 plugin was exercised by
this real-Hermes test; its full suite results remain those recorded below.
Reports: `docs/evidence/phase14-prompts`.

## 0.14.1 acceptance

The expanded synthetic summary test used 3,148 source tokens with a 2K context.
The generated note retained the corrected $720 budget and 11:15 train, November 12
travel date, wheelchair/step-free constraint, final lunch decision and Lee's
unfinished taxi confirmation. Rejected options were discarded. This is one
controlled example, not general summary-quality proof. The repeated “Cobalt
Orchard” venue in the structural test is deliberately seeded synthetic data;
no personal conversation is used in these fixtures.

Device coverage includes foreground automatic loading, offline + into the normal
chat shell, first-message readiness during model loading, Activity recreation,
background unload, native loading/replies at all
three context sizes, long-source warning, chunk checkpoint/resume and automatic
local-history compaction. Larger context tests establish allocation and short
inference, not full-window latency or sustained battery behavior. The 2K default
is retained. See the machine-readable reports in `docs/evidence/phase14-fallback`.

All ten distinct physical-device checks passed. JVM verification ran 694 tests
with no failures (one opt-in live test skipped), and all 187 existing screenshot
baselines passed. Android lint and release assembly passed for arm64-v8a and
x86_64. The signed build upgrades the existing installation without clearing data.

The plugin's 161-test suite passed (one existing skip) on the pinned host. Eight
offline/profile tests passed on local Hermes 0.21.5, including FTS search of the
imported conversation and exactly-once retry. The test gateway is updated to
0.5.1. The 0.5 protocol remains compatible with 0.5.0 servers.

## Original 0.14.0 acceptance evidence

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

Preparation in 0.14.1 uses local Gemma compaction plus indexing. It does not request
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
