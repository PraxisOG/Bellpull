# Hermes Android handoff — 2026-09-16

- Repo instructions: read `CLAUDE.md`, `ROADMAP.md`, and `docs/hermes-api.md`. Only Tier 1 is in scope. Do not print or commit the Hermes API key.
- Gate 0 API verification is done. Sanitized live fixtures are in `mock-hermes/fixtures/` on commit `dd1ff33`; Hermes state was recovered and the dev server works.
- Minimal Kotlin/Compose app is committed on `slice1-minimal-app` as `c1a0153`. It stores the key in EncryptedSharedPreferences and checks `GET /v1/capabilities`. Folded and unfolded Roborazzi screenshots pass; `:app:assembleDebug` passes.
- The app is installed on the Pixel 10 Pro Fold (Android 17/API 37). Wireless ADB is paired. `adb reverse tcp:8642 tcp:8642` exposes the host's loopback Hermes listener to the phone temporarily. The phone reported **“Authenticated. Session streaming is available.”**
- Hermes currently listens only on host `127.0.0.1:8642`. Tailscale is not installed on the host yet; the phone has not been tested over a direct tailnet path. Do not mark the Android 17 `ACCESS_LOCAL_NETWORK` decision complete from the ADB tunnel test.
- Next: install/connect Tailscale on host and phone, expose only this loopback Hermes service privately with Tailscale Serve, remove the ADB reverse tunnel, and verify the app against the tailnet URL. Then record the network permission result in `CLAUDE.md`.
- The effective dev key is in the local private file `~/.config/hermes-android/dev-api-key` (mode 600). The user enters it on the phone; never paste it into chat or logs.
