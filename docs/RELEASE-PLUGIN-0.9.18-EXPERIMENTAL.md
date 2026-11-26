# Bellpull plugin 0.9.18 experimental

Today can show historical routine deliveries on stock Hermes 0.23.4. Bellpull looks up the original delivery ID for each scheduled execution and reads the owner receipt or the retained deferred record. Older timeout notices keep their separate IDs. Reading the history leaves queued work in place.

On stock Hermes 0.21.5, a reviewed retry for an owner-pinned delivery now checks that the original conversation still holds the Bot Chat title. If you restore that chat, you can retry the saved result once. The original receipt and the scheduled execution stay intact. On 0.23.4, history is read-only. Its gateway can redirect a pinned delivery to a replacement chat.

The Android download is the same signed 0.20.22 build 72. [Routine delivery issue](https://github.com/PraxisOG/Bellpull/issues/41) · [Previous plugin release](https://github.com/PraxisOG/Bellpull/releases/tag/plugin-v0.9.17-experimental.1)
