#!/usr/bin/env python3
"""Install Bellpull's plugin files without modifying Hermes core or config."""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import shutil
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--home", type=Path, default=Path(os.environ.get("HERMES_HOME", "~/.hermes")).expanduser(),
                        help="Your actual Hermes data home, including for a named profile")
    parser.add_argument("--apply", action="store_true", help="Copy plugin files; default is a read-only preview")
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1] / "plugins" / "bellpull"
    target = args.home.expanduser().resolve() / "plugins" / "bellpull"
    if not (source / "plugin.yaml").is_file():
        parser.error("Run this installer from the unpacked Bellpull bundle or repository")
    if target.is_symlink() or target.parent.is_symlink():
        parser.error("Refusing a symlinked plugin destination")
    print(f"Bellpull plugin: {source} -> {target}")
    print("Hermes core files and config will not be edited.")
    if not args.apply:
        print("Preview only. Add --apply to install with an automatic backup.")
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    stage = target.parent.parent / (".bellpull-plugin-stage-" + uuid.uuid4().hex)
    backup = None
    try:
        shutil.copytree(source, stage, ignore=shutil.ignore_patterns("__pycache__", "tests", "*.pyc"))
        if target.exists():
            backup = target.parent.parent / "bellpull-plugin-backups" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
            backup.parent.mkdir(parents=True, exist_ok=True)
            target.rename(backup)
            print(f"Previous plugin retained at {backup}")
        stage.rename(target)
    except BaseException:
        if backup is not None and backup.exists() and not target.exists():
            backup.rename(target)
        raise
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    print("Enable bellpull using Hermes' plugin manager for this data home.")
    print("Disable the old bellpull_gpu plugin before restarting your existing gateway service.")
    print("No Docker, host URL, source checkout path or core patch is required for file sharing.")


if __name__ == "__main__":
    main()
