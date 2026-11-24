# Bellpull 0.20.12 experimental

Android build 62 and plugin 0.9.7-mobile-preview add Codex and Claude Code to the chat’s **Coding activity** menu. Open a process to inspect its Hermes run, executor session, progress and exit. Codex commands can report a numeric exit code. Claude Bash results show the corresponding command, outcome and redacted output.

**Workspace changes** compares the live worktree with the commit present when Bellpull first observed the process. The comparison includes subsequent commits. The dialog identifies the base revision. Other edits in the same worktree also appear.

An acknowledged Stop now survives a concurrent history write and app recovery.

## Verification

On stock Hermes 0.21.5, deterministic Codex and Claude fixtures each ran a failing unit test, fixed it in a detached worktree, reran the test successfully and committed the change. A second client reconnected while each process was running. Both clients saw one run and one child. The committed diff remained visible and the original checkout retained its unrelated edit.

Real Codex CLI 0.171.0 completed a text turn against a local test provider. In a separate command run, the test container denied creation of the CLI sandbox. Bellpull showed the individual command failures even though the CLI finished its turn. Real Claude Code 2.1.274 completed a read-only Git command with a local test provider. It reported permission requests for an edit and test command in its default headless configuration. The native Hermes terminal’s signed-out Claude run showed an authentication failure in its originating chat.

Authenticated Claude execution through Hermes, desktop controls, genuine approval routing and cancellation still need end-to-end verification. [Issue #43](https://github.com/PraxisOG/Bellpull/issues/43)
