#!/usr/bin/env python3
"""Prepare private signing material or build a locally signed APK. Never publish.

Passwords and keys live outside the repository. The lineage retains updates from
the published 0.3.3 development certificate on every supported Android version.
"""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
PREVIEW_CERT = "b8ff7d02769b42f0a41ab922334875bfc2766086c78b2caa581ba5f5b6da53cf"


def run(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def sdk_tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Android/Sdk")
    tool = sdk / "build-tools/37.0.0" / name
    if not tool.is_file():
        raise SystemExit(f"Install Android SDK build-tools 37.0.0: missing {tool}")
    return tool


def initialize(folder):
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(folder, 0o700)
    password, key = folder / "release-password", folder / "release.p12"
    legacy, lineage = folder / "preview.keystore", folder / "preview-to-release.lineage"
    if not legacy.exists():
        source = Path.home() / ".android/debug.keystore"
        if not source.is_file():
            raise SystemExit("The published preview key is required for an in-place upgrade lineage")
        shutil.copyfile(source, legacy)
        os.chmod(legacy, 0o600)
    # Verify the actual public certificate, never assume any machine's debug key matches.
    certificate = run("keytool", "-exportcert", "-alias", "androiddebugkey", "-keystore", legacy,
        "-storepass", "android", capture_output=True).stdout
    if hashlib.sha256(certificate).hexdigest() != PREVIEW_CERT:
        raise SystemExit("Preview certificate differs from the published APK; refusing an unrelated lineage")
    if not password.exists():
        with password.open("x") as file:
            os.chmod(password, 0o600)
            file.write(secrets.token_urlsafe(48) + "\n")
    if not key.exists():
        environment = dict(os.environ, BELLPULL_RELEASE_STORE_PASS=password.read_text().strip())
        run("keytool", "-genkeypair", "-noprompt", "-alias", "bellpull", "-keyalg", "RSA", "-keysize", "4096",
            "-validity", "36500", "-dname", "CN=Bellpull, O=PraxisOG", "-storetype", "PKCS12", "-keystore", key,
            "-storepass:env", "BELLPULL_RELEASE_STORE_PASS", "-keypass:env", "BELLPULL_RELEASE_STORE_PASS", env=environment)
        os.chmod(key, 0o600)
    if not lineage.exists():
        run(sdk_tool("apksigner"), "rotate", "--out", lineage, "--old-signer", "--ks", legacy,
            "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
            "--new-signer", "--ks", key, "--ks-key-alias", "bellpull", "--ks-pass", f"file:{password}")
    print(f"Private signing material ready in {folder}. Back up this entire directory securely before publishing.")


def build(folder):
    for name in ("release-password", "release.p12", "preview.keystore", "preview-to-release.lineage"):
        if not (folder / name).is_file():
            raise SystemExit("Signing material is incomplete; run init first")
    run(ROOT / "android/gradlew", "-p", ROOT / "android", ":app:assembleRelease")
    output = ROOT / "android/app/build/outputs/apk/release"
    source = output / "app-release-unsigned.apk"
    if not source.exists():
        source = output / "app-release.apk"
    aligned, signed = output / "Bellpull-aligned.apk", output / "Bellpull.apk"
    run(sdk_tool("zipalign"), "-f", "-p", "4", source, aligned)
    password = folder / "release-password"
    run(sdk_tool("apksigner"), "sign", "--ks", folder / "preview.keystore", "--ks-key-alias", "androiddebugkey",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--next-signer", "--ks", folder / "release.p12",
        "--ks-key-alias", "bellpull", "--ks-pass", f"file:{password}",
        "--lineage", folder / "preview-to-release.lineage", "--rotation-min-sdk-version", "28", "--out", signed, aligned)
    run(sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", signed)
    digest = hashlib.sha256(signed.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(f"{digest}  Bellpull.apk\n")
    print(f"Signed APK: {signed}\nSHA-256: {digest}\nLocal build only; device upgrade verification remains required.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("init", "build"))
    parser.add_argument("--signing-dir", type=Path, default=Path.home() / ".config/bellpull/signing")
    args = parser.parse_args()
    os.umask(0o077)
    if args.signing_dir.resolve().is_relative_to(ROOT):
        raise SystemExit("Keep signing material outside the repository")
    (initialize if args.action == "init" else build)(args.signing_dir)
