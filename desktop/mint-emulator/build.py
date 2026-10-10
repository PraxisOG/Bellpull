#!/usr/bin/env python3
"""Package the tested Android APK; never include a developer SDK or virtual device."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="0.1.0~preview20261009.1")
    parser.add_argument("--apk", type=Path, default=ROOT / "android/app/build/outputs/apk/debug/app-debug.apk")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9][A-Za-z0-9.+~]*", args.version):
        parser.error("Version must be a Debian-compatible version without separators or spaces.")
    if not args.apk.is_file():
        parser.error("APK not found. First run ./android/gradlew -p android :app:assembleDebug")
    if not shutil.which("dpkg-deb"):
        parser.error("dpkg-deb is required (included on Linux Mint).")
    output = HERE / "build"
    output.mkdir(exist_ok=True)
    stage = output / "stage"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir()
    name = f"bellpull-desktop-preview-{args.version}"
    portable = stage / name
    portable.mkdir()
    for filename in ("bellpull-desktop.py", "install.py", "README.txt"):
        shutil.copy2(HERE / filename, portable / filename)
    for filename in ("bellpull-desktop.py", "install.py"):
        (portable / filename).chmod(0o755)
    shutil.copy2(args.apk, portable / "bellpull.apk")
    shutil.copy2(ROOT / "android/app/src/main/res/drawable-nodpi/bellpull_icon.png", portable / "bellpull.png")
    metadata = {
        "name": "Bellpull desktop emulator preview", "version": args.version,
        "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "working_tree_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
        "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "apk_variant": "debug", "android_package": "org.praxisog.hermes",
        "android_image": "system-images;android-36;default;x86_64",
    }
    (portable / "build-info.json").write_text(json.dumps(metadata, indent=2) + "\n")
    archive = output / f"{name}-linux-x86_64.tar.gz"
    with tarfile.open(archive, "w:gz") as tar:
        tar.add(portable, arcname=name)
    debroot = stage / "deb"
    app = debroot / "usr/lib/bellpull-desktop"
    shutil.copytree(portable, app)
    bindir = debroot / "usr/bin"
    bindir.mkdir(parents=True)
    (bindir / "bellpull-desktop").symlink_to("../lib/bellpull-desktop/bellpull-desktop.py")
    icons = debroot / "usr/share/pixmaps"
    icons.mkdir(parents=True)
    shutil.copy2(portable / "bellpull.png", icons / "bellpull-desktop.png")
    entries = debroot / "usr/share/applications"
    entries.mkdir(parents=True)
    (entries / "bellpull-desktop.desktop").write_text(
        "[Desktop Entry]\nType=Application\nName=Bellpull (Android preview)\n"
        "Comment=Bellpull in a dedicated Android virtual device\n"
        "Exec=/usr/bin/bellpull-desktop\nIcon=bellpull-desktop\nTerminal=false\n"
        "Categories=Network;Chat;\nKeywords=Hermes;AI;Chat;\n")
    control = debroot / "DEBIAN"
    control.mkdir()
    (control / "control").write_text(
        f"Package: bellpull-desktop-preview\nVersion: {args.version}\nArchitecture: amd64\n"
        "Maintainer: Bellpull project <noreply@github.com>\nSection: net\nPriority: optional\n"
        "Depends: python3 (>= 3.10), openjdk-17-jre-headless, x-terminal-emulator, zenity, libgl1, libpulse0, libnss3, libx11-6, libxcb1, libxkbcommon-x11-0\n"
        "Homepage: https://github.com/PraxisOG/Bellpull\n"
        "Description: Bellpull Android emulator preview for Linux Mint\n"
        " Runs the Bellpull Android client in an isolated Android virtual device.\n"
        " Requires x86-64 Linux, KVM access and an Android SDK download at first setup.\n"
        " The Android runtime is downloaded from Google and is not bundled.\n")
    # Packages are readable after extraction, regardless of the build shell's umask.
    for path in debroot.rglob("*"):
        if not path.is_symlink():
            path.chmod(0o755 if path.is_dir() or path.name.endswith(".py") else 0o644)
    deb = output / f"bellpull-desktop-preview_{args.version}_amd64.deb"
    subprocess.run(["dpkg-deb", "--root-owner-group", "--build", str(debroot), str(deb)], check=True)
    (output / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in (archive, deb)))
    print(json.dumps({"portable": str(archive), "deb": str(deb), "metadata": metadata}, indent=2))


if __name__ == "__main__":
    main()
