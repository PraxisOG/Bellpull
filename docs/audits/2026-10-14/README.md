# Audit evidence — 2026-10-14

Companion to the [project audit](../../AUDIT-2026-10-14.md), covering source revision
`4fc426f22379babed067120b54705bcb23fb6d47` on `repair/experimental-open-issues`.
Run commands from the repository root with the build prerequisites in
[BUILDING.md](../../BUILDING.md).

The probes originally asserted the defects observed at this revision. F01, F04 and
F05 now assert the repaired behavior; the remaining probes still assert the original
defects. They are opt-in evidence, with permanent workspace regressions in
`plugins/bellpull/tests/test_coding.py` and planning regressions in
`plugins/bellpull/tests/test_planning.py`.
All fixture values are synthetic. No phone, personal server, API credential,
provider account or model inference is required.

## Server reproductions

Build the existing pinned legacy fixture, then run the probe with no network and
an ephemeral Hermes data directory:

```bash
docker build -f server-host/Dockerfile -t bellpull:audit-20261014 .
docker run --rm -i --network none \
  -e HERMES_HOME=/tmp/bellpull-audit-home -e BELLPULL_NTFY_URL= \
  -w /opt/hermes --entrypoint python bellpull:audit-20261014 - \
  < docs/audits/2026-10-14/reproduce_server.py
```

[`reproduce_server.py`](reproduce_server.py) creates disposable SQLite stores and
Git repositories. Its only filter command creates a harmless marker within its
own temporary repository. It confirms:

- F01: workspace listing does not execute a configured Git clean filter.
- F02: a fresh upload arriving after revoke restores the server calendar cache.
- F04: after 100 rejected proposals leave zero pending, proposal 101 is accepted.
  Pending admission is capped at 100; the newest 1,000 terminal proposals by creation
  order are retained, with replay receipts for the newest 5,000 evictions.
- F05: a hard-linked external fixture is rejected without appearing in the diff.
  All regular files with more than one link are excluded from previews, including
  intentional links entirely within the workspace, because containment cannot be
  proven. The listing note and preview error explain this compatibility restriction.

The original audit run output is [server-results.json](server-results.json); it
predates these repairs and is not validation of the updated probes.

## Android reproductions

The opt-in init script adds the audit probe directory to this invocation's unit
test sources without modifying the normal Android project configuration:

```bash
./android/gradlew -p android --no-configuration-cache \
  -I "$PWD/docs/audits/2026-10-14/probes.init.gradle" \
  :app:testDebugUnitTest \
  --tests org.praxisog.hermes.AuditPlanningProbeTest
```

[`AuditPlanningProbeTest.kt`](android-probes/AuditPlanningProbeTest.kt) uses the
existing Robolectric calendar fixtures and an OkHttp interceptor. It confirms:

- F02: the real workspace issues a revoke that completes before an older upload,
  while its saved policy is off and its pending-revoke flag is clear. The separate
  server probe establishes the persistence consequence of that ordering.
- F03: the actual transcription validator accepts a public-shaped HTTP hostname
  with numeric Tailscale-looking labels, while rejecting an ordinary public name.
- F06: a title containing surrounding whitespace is committed in normalized form
  but the actual calendar adapter throws afterward.

The three final probes passed. Their normalized result and stdout are in
[validation-results.json](validation-results.json). The call ordering is controlled
by latches; the probe does not rely on a real network race. As with ordinary Gradle
test runs, this invocation replaces the app's generated test reports with the
selected tests. Full-suite counts in the audit refer to the earlier complete run.

## Regression checks

The fresh Android commands were:

```bash
./android/gradlew -p android test verifyRoborazziDebug assembleDebug lintDebug --rerun-tasks
./android/gradlew -p android :app:assembleRelease :app:lintRelease
```

The full plugin command used the image built above:

```bash
docker run --rm --network none \
  -e HERMES_HOME=/tmp/bellpull-tests -e BELLPULL_NTFY_URL= \
  --entrypoint python bellpull:audit-20261014 \
  -m unittest discover -s /opt/hermes/plugins/bellpull/tests -v
```

Stock portable/projects/planning/coding commands use the exact image digests and
test patterns in [checks.yml](../../../.github/workflows/checks.yml). The audit ran
them independently, with the current plugin mounted read-only and disposable data.
Desktop and thermal checks used `unittest discover` with their respective
`test_launcher.py` and `test_speech_thermal_monitor.py` patterns.

## Dependency evidence

| File | Contents |
|---|---|
| [dependencies.json](dependencies.json) | 141 Maven coordinates resolved from Android `releaseRuntimeClasspath` |
| [host-python-dependencies.json](host-python-dependencies.json) | Installed distributions enumerated using Python `importlib.metadata` inside each isolated image |
| [dependency-findings.json](dependency-findings.json) | OSV match summary, deduplicated GHSA records, aliases, fix-version metadata and primary-source links |
| [source-inventory.json](source-inventory.json) | Tracked-file counts and limited current-tree secret-pattern scan outcome |

The `legacy` inventory is from the disposable image built with
`server-host/Dockerfile`, based on:

```text
nousresearch/hermes-agent:v2026.9.7@sha256:63bfb6d732f49a55d453e801057273785cc61e0f6ee43db3fa2f2a79846301b7
```

The `modern` inventory is from the unmodified stock image:

```text
nousresearch/hermes-agent:v2026.9.24@sha256:2fd023efbb8d3d2b0ce1a73d028b07370cff34f567cfe0e999553e8c327ea283
```

For each distinct package/version pair the audit submitted an OSV batch query with
`package.ecosystem` set to `Maven` or `PyPI` and its resolved version. Three native
queries used these Git commits:

```text
Qwen TTS    51512f129a7419567f4b8abfb06801451789b8f1
GGML        2eddaf94a8cdbbf1c30017d28313cd661a9da543
Sherpa ONNX 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf
```

Advisory matches were recorded on 2026-10-14 and can change as upstream metadata
changes. No match is an inventory observation, not a guarantee of safety; a match
does not by itself prove an exploitable path in Bellpull. Fixed-version metadata
does not establish compatibility with either supported Hermes image.

The secret scan tested private-key headers and common GitHub, AWS access-ID and
provider key patterns in tracked text files under 8 MB. It excluded binary files,
untracked files, Git history and submodule contents. It found zero candidates for
those patterns. Credential entropy analysis and a full-history scan were not run.
