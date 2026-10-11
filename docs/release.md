# Building and upgrading Bellpull

The public download remains 0.3.3 preview. This repair branch builds **0.3.4,
version code 7**. It is not published. Do not reuse a version code after release.

The current manual test delivery pairs this APK with **bellpull plugin 0.2.0**.
Use the stock installation instructions in `plugins/bellpull/README.md`; Docker
and `server-host/` are not required for persistent generated-file sharing. The
user manages the personal server and has explicitly prohibited agent access.
Phone checks use `-PbellpullDeviceFixture=true`, which produces the separate
`org.praxisog.hermes.fixture` package and cannot load personal app profiles.
The preview-to-release signing transition was verified on the Fold before the
manual-install instruction; the final repaired APK awaits the user's install.

## Checks before a release

```bash
./android/gradlew -p android test verifyRoborazziDebug assembleDebug lintDebug
docker build -f server-host/Dockerfile -t bellpull:checks .
docker run --rm --network none -e HERMES_HOME=/tmp/bellpull-tests \
  -e BELLPULL_NTFY_URL= --entrypoint python bellpull:checks \
  -m unittest discover -s /opt/hermes/plugins/bellpull/tests -v
```

CI runs these checks without signing secrets, production mounts or inference.
Inspect intentional screenshot differences before updating baselines. Check cover,
unfolded, dark/light and large-text layouts. Test the signed APK on the Fold;
rendered screenshots cannot establish actual upgrade, fold or network behavior.

## Stable signing

The published preview certificate SHA-256 is
`b8ff7d02769b42f0a41ab922334875bfc2766086c78b2caa581ba5f5b6da53cf`.
Do not uninstall the app to get around a certificate mismatch: that loses private
connections, drafts, read state and recovery records.

```bash
python3 tools/release.py init
python3 tools/release.py build
```

`init` is idempotent. It verifies the original preview certificate, snapshots its
key, creates a dedicated RSA-4096 release key, and constructs an Android signing
lineage. Secrets live in `~/.config/bellpull/signing/` (directory 0700, private
files 0600), outside Git. The password is randomly generated and is not printed.
Keep this whole directory, including the lineage and preview key, in a secure
offline backup before publishing. No backup to a second device has been made by
this repository's build scripts. Losing the release key prevents future updates.

The script zipaligns and signs `android/app/build/outputs/apk/release/Bellpull.apk`,
then verifies the signature and writes `SHA256SUMS`. Rotation targets API 28+, so
the lineage applies to all supported devices (Bellpull's minimum is API 29).
The old key also supplies compatibility signatures. See the official
[apksigner rotation contract](https://developer.android.com/tools/apksigner).
Future release builds must use this script and the same signing directory;
Gradle's optional `keystore.properties` signing alone does not attach the lineage.

Before publishing, install over the existing preview with `adb -s SERIAL install -r
Bellpull.apk`. Verify the same profile, drafts, encrypted settings and chat cache
remain accessible; restart the app and check run recovery. A debug APK cannot be
assumed to update a release-signed installation. Use the stable signing path for
subsequent upgrades. Keep a private data backup before a signing transition.

## Publication and rollback

After device acceptance and maintainer approval, tag the reviewed commit and
upload the signed APK with its SHA-256 and release notes. Never publish the
keystore, password, server configuration, bearer key, or private screenshots.
Publishing is a separate action; neither CI nor the release script performs it.

Keep the preceding APK and server image. Android normally rejects lower version
codes; prefer a fix with a higher code signed through the same lineage. Server
rollback is described in [the plugin migration guide](../plugins/bellpull/README.md).
