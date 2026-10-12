# Synthetic OpenCode CLI fixture

Constructed from v1.19.0 `run.ts` and `packages/schema/src/v1/session.ts`.
No real agent, server conversation, process, account or command execution.
Session ID `ses_fixture_open_code`; all times/IDs/text are fictional.

Feed chunks into a fresh `OpenCodeCliTimeline` with an explicit binding for that
session. The last step finish must leave `authoritativeCompletion == false`.
Replaying the retained parts should replace their rows rather than duplicate them.
These are inputs for forthcoming regressions, **not tests already executed**.
The prototype is not used by the app or HTTP client.
