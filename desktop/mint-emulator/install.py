#!/usr/bin/env python3
"""Install this portable preview for the current user, retaining all virtual-device data."""
import os
from pathlib import Path
import shutil
import sys


def desktop_quote(value: str) -> str:
    # Desktop Entry Exec uses its own quoting rules, not shell single quotes.
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"').replace("`", "\\`").replace("$", "\\$").replace("%", "%%") + '"'


def main():
    if os.geteuid() == 0:
        sys.exit("Run this installer as your normal desktop user, not with sudo.")
    source = Path(__file__).resolve().parent
    filenames = ("bellpull-desktop.py", "install.py", "bellpull.apk", "bellpull.png", "build-info.json", "README.txt")
    if not all((source / name).is_file() for name in filenames):
        sys.exit("Run install.py from the extracted release archive, not the source checkout.")
    data = Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local/share")))
    destination = data / "bellpull-desktop/app"
    launcher = Path.home() / ".local/bin/bellpull-desktop"
    if launcher.exists() or launcher.is_symlink():
        if not launcher.is_symlink() or launcher.resolve() != (destination / "bellpull-desktop.py").resolve():
            sys.exit(f"An unrelated launcher already exists at {launcher}; it was left untouched.")
    if source != destination.resolve():
        destination.mkdir(parents=True, exist_ok=True, mode=0o700)
        for filename in filenames:
            shutil.copy2(source / filename, destination / filename)
    (destination / "bellpull-desktop.py").chmod(0o755)
    launcher.parent.mkdir(parents=True, exist_ok=True)
    if not launcher.is_symlink():
        launcher.symlink_to(destination / "bellpull-desktop.py")
    entries = data / "applications"
    entries.mkdir(parents=True, exist_ok=True)
    (entries / "bellpull-desktop.desktop").write_text(
        "[Desktop Entry]\nType=Application\nName=Bellpull (Android preview)\n"
        "Comment=Bellpull in a dedicated Android virtual device\n"
        f"Exec={desktop_quote(str(destination / 'bellpull-desktop.py'))}\n"
        f"Icon={destination / 'bellpull.png'}\nTerminal=false\nCategories=Network;Chat;\nKeywords=Hermes;AI;Chat;\n")
    if shutil.which("update-desktop-database"):
        import subprocess
        subprocess.run(["update-desktop-database", str(entries)], check=False)
    print(f"Installed: {launcher}\nOpen Bellpull (Android preview) from the Mint menu, or run:\n{launcher}")


if __name__ == "__main__":
    main()
