# Bellpull plugin 0.9.21 experimental

On stock Hermes 0.23.4, Android and Desktop can open, edit and follow Canvas in an existing conversation. The 0.9.20 plugin returned HTTP 404 for the Canvas panel, and its drawing tool could fail after a conversation was compacted.

The panel, live event stream and drawing tool now look up the selected profile’s conversation lineage. Existing drawings keep their document IDs and revisions across compression. Forks keep their own drawing state.

This release includes signed Android 0.20.22 build 72. [Shared Canvas](SHARED-CANVAS.md) · [Tracking issue](https://github.com/PraxisOG/Bellpull/issues/38) · [Previous release](https://github.com/PraxisOG/Bellpull/releases/tag/plugin-v0.9.20-experimental.1)
