# Bellpull plugin 0.9.17 experimental

On stock Hermes 0.23.4, Android build 72 can open Today, read or share a phone calendar and inspect Routines. Planning previously depended on a Projects session API that Hermes had removed. The plugin now checks the APIs that planning uses.

When the scheduler last saw a queued Bot Chat delivery, Routines reads the owner’s current receipt. A completed delivery now appears as **Bot Chat · completed**. On Hermes 0.23.4 the full historical receipt list and reviewed retry remain unavailable. The scheduler’s saved hint is still available when the host has no matching owner receipt.

Verified with stock Hermes 0.21.5 and 0.23.4. On 0.23.4 a real gateway admission with controlled execution produced one settled receipt. The signed Android build displayed it in Today → Routines. The APK is the same signed build 72 published with plugin 0.9.16. [Previous Android release](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.20.22-experimental.1) · [Routine delivery](https://github.com/PraxisOG/Bellpull/issues/41) · [Calendar reliability](https://github.com/PraxisOG/Bellpull/issues/28)
