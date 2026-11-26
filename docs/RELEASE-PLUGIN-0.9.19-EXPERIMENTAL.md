# Bellpull plugin 0.9.19 experimental

In Coding activity, a Claude Code turn that finishes after a denied write now shows **Write · permission not granted** alongside the turn outcome. Bellpull uses the final result from the matching Claude session and keeps the tool arguments out of the permission row.

Bellpull also recognizes Codex JSON runs with approval, configuration, model or profile options before the `exec` subcommand. Opening the activity panel follows the existing Hermes run and process.

This release includes the same signed Android 0.20.22 build 72. Answering a live executor approval requires a host that connects the executor’s interactive protocol to the originating Hermes run. [Coding activity issue](https://github.com/PraxisOG/Bellpull/issues/43) · [Previous release](https://github.com/PraxisOG/Bellpull/releases/tag/plugin-v0.9.18-experimental.1)
