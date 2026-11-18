# Voice interruption repair — 0.20.3 experimental

Android **0.20.3-experimental / build 53** fixes
[#57](https://github.com/PraxisOG/Bellpull/issues/57), where speaking over a running
turn stopped the old run but left the replacement stuck on “Sending…”.

This release starts from published **0.20.2/build 52** and preserves its server
voice, live-file, workspace and mobile reliability features. The matching plugin
remains **0.9.2-voice-files-preview**, byte-for-byte unchanged. This repair needs
only an app update when upgrading from 0.20.2; no server deployment is required.

## Changes

- Hermes can report a stopped run as `completed` with partial output or no saved
  assistant reply. A confirmed stop with terminal status now releases the send
  gate immediately, without waiting for a final reply match.
- A replacement cancels the previous tracking job before starting its own. A
  delayed history fetch cannot leave the new turn without a submission tracker.
- Terminal runs that exhaust history retries allow new messages while retaining
  their review and Check status controls. Ambiguous delivery and active runs
  remain protected against duplicate submission.
- Late stop acknowledgments preserve terminal labels and cannot change a newer
  run. If an interruption cannot settle within five seconds, the recognized words
  return to review and cannot be submitted later without user action.

## Validation and installation

`bash tools/build-local-lab.sh test verifyRoborazziDebug lintDebug --max-workers=4`
passed: **911 tests passed**, one opt-in live-server test skipped, and all **221
screenshot baselines** verified. Lint completed successfully. The regression
fixtures cover a completed stop with no saved reply, delayed history, immediate
tracker handoff, late stop acknowledgment, partial-output recovery and timeout
review without late submission. [Test results](evidence/voice-barge-in-20261117/validation.json).

The release APK uses the existing signing certificate and update lineage.
Signature, version metadata and 16 KiB ZIP alignment are checked before an
in-place `pm install -r` upgrade. The prior installed APK is retained locally for
recovery; the install preserves the package UID, data-directory identities and
original installation time. The installed APK checksum is compared with the
release artifact. [Phone upgrade verification](evidence/voice-barge-in-20261117/phone-upgrade.json).

The personal app is left stopped for the owner to open. No personal server is
accessed, and real microphone/provider barge-in acceptance remains a user check.
The automated tests exercise the real store/client with synthetic HTTP and audio
ports; they do not establish acoustic echo behavior, car audio or cellular latency.
Earlier experimental limitations remain documented in the
[0.20.2 release record](SERVER-VOICE-FILES-2026-11-15.md).

## Downloads and source

The [experimental release](https://github.com/PraxisOG/Bellpull/releases/tag/android-v0.20.3-experimental.1)
contains the signed `Bellpull.apk`, unchanged complete plugin/speech-helper bundle,
`Bellpull-source-0.20.3.tar.gz`, phase roadmap, `SOURCE.json` and `SHA256SUMS`.
The source archive includes all pinned recursive submodules and
`SOURCE-REVISIONS.json`; GitHub's automatic source archives omit submodule contents.
Signing material, local configuration, model downloads and build caches are excluded.
