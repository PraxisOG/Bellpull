# Bellpull plugin 0.9.22 experimental

Desktop 0.7.0 can send a message with an explicitly selected model on stock Hermes 0.23.4. Previously the plugin returned HTTP 500 before Hermes could start the run. The same fix lets file attachments and generated outputs link to messages on the current conversation store.

Hermes checks the requested model before admitting the run and owns its completion and replay. The plugin reads the selected profile’s transcript to match files to the original messages. A Bot Chat owned by the Hermes authority still needs a handoff that can carry the selected model and files.

The release includes signed Android 0.20.22, build 72.
