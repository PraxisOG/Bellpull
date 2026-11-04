# Conversation pull-to-refresh

Implementation: `2dcd85f`, based on the completed board chat shortcut at `de02c78`.

Pull down at the top of Conversations to reload the existing server list. A small
progress indicator appears over the rows while the request runs. The rows remain
tappable, source/project filters stay selected, and ordinary scrolling through
older conversations does not refresh until the top is reached. Empty and failed
lists also accept the gesture. An accessibility action provides Refresh conversations.

The existing fetch/cache path supplies all pages and keeps its offline/error
reporting. User pulls are coalesced while a load is active; only the current
request may finish the progress indicator or apply cached failure results.
Successful recovery clears the offline notice. This adds no plugin/API dependency.
The UI uses the existing Material 3 [pull-to-refresh component](https://developer.android.com/develop/ui/compose/components/pull-to-refresh).

Validation: 786 app tests, 42 model tests and 48 client tests passed, with one
optional live-server test skipped. All 218 screenshot comparisons and lint passed.
Focused checks cover real Compose gestures, empty/error/loading states, duplicate
pulls, source-filter routing, cached failure/recovery and overlapping source loads.
Existing list-order, navigation, offline-editing and screenshot checks remain green.

The isolated Pixel 10 Pro Fold test passed at both unfolded and narrow widths,
using the real app/store with synthetic held HTTP responses: swipe, retain the
saved row while refreshing, reject another pull, then show the updated row and
stop the indicator. The temporary width override and fixture packages were removed;
the original display size and density were retained. This fixture validation was
followed by the separately authorized signed release installation.

Evidence: [checks](evidence/conversations-refresh-20261103/checks.log),
[cover test](evidence/conversations-refresh-20261103/phone-cover.log),
[unfolded test](evidence/conversations-refresh-20261103/phone-unfolded.log),
[before](evidence/conversations-refresh-20261103/cover/before.png),
[refreshing](evidence/conversations-refresh-20261103/cover/refreshing.png),
[after](evidence/conversations-refresh-20261103/cover/after.png),
and [unfolded result](evidence/conversations-refresh-20261103/unfolded/after.png).
