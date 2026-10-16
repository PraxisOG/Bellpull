# B2 — Manual scrolling to the bottom does not resume streaming follow

Type: user-reported bug. Reported October 15, 2026.

## Observed behavior and reproduction

1. Open a chat with a streaming answer.
2. Press Jump to bottom: the chat follows newly arriving content correctly.
3. Scroll away, then manually scroll back to the bottom.
4. Automatic following does not resume. The owner must keep scrolling manually
   or press Jump to bottom again.

## Expected behavior

Reaching the bottom manually should enable the same streaming-follow behavior as
Jump to bottom. Deliberately scrolling upward should pause following so earlier
messages remain readable.

## Proposed acceptance

- Both ways of reaching the bottom resume following while new content arrives.
- A deliberate upward scroll pauses following without snapping back.
- Reaching the bottom again resumes it, including when stream updates and user
  scrolling overlap.
- Define a small, consistent bottom-position tolerance to avoid rounding gaps;
  do not force following while the reader is visibly above the bottom.
- Verify behavior over a growing response with an interaction test or device
  check; a static screenshot alone does not establish this behavior.

## October 15 audit repair

Reproduced with a Compose touch-gesture regression. At the bottom an extra upward
finger movement is unconsumed; pre-scroll disabled following but post-scroll only
restored it when pixels were consumed. The repair recognizes that overscroll and
uses a 16 dp bottom tolerance. `StreamScrollTest` verifies returning manually,
dragging past the bottom and receiving a growing answer; its existing scroll-away
test still prevents snapping the reader back. Physical car use is unrelated to
this source-confirmed UI defect.
