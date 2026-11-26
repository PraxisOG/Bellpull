# Bellpull plugin 0.9.25 experimental

The full stock Hermes 0.23.4 gateway now supplies opted-in phone context to new personal Android chats. In 0.9.23 and 0.9.24, syncing worked, but the worker that executed an admitted run could miss the snapshot.

An existing chat keeps its initial snapshot when the phone syncs newer records. A new chat gets the newer snapshot. Turning off sharing clears those excerpts for subsequent turns. The gateway applies the snapshot to the model’s system prompt during the turn and preserves the original submission and transcript.

This release includes signed Android 0.20.26 experimental, build 76.
