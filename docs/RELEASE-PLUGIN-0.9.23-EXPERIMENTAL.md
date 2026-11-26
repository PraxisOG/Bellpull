# Bellpull plugin 0.9.23 experimental

Phones can sync opted-in notification and calendar context to stock Hermes 0.23.4. Previously the gateway returned HTTP 404 and the app could not enable new-chat sync.

New chats use the selected profile’s snapshot. A chat keeps its initial snapshot across retries, follow-ups and compression. The phone-context tool reads the latest synced records, including the removal time for a notification the phone has dismissed. Turning off sharing removes the cached excerpts from saved snapshots.

The release includes signed Android 0.20.26 experimental, build 76.
