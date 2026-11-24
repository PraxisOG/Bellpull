#!/usr/bin/env python3
"""Package a committed Bellpull plugin and its helper with matching public build metadata."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import runpy
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def package(destination, revision):
    paths = ("LICENSE", "tools/install-plugin.py", "plugins/bellpull", "server-speech", "docs")
    commit = subprocess.check_output(["git", "rev-parse", "--verify", "--end-of-options", revision + "^{commit}"],
                                     cwd=ROOT, text=True).strip()
    timestamp = int(subprocess.check_output(["git", "show", "-s", "--format=%ct", commit], cwd=ROOT))
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination) as tmp:
        stage = Path(tmp) / "bundle"
        source = Path(tmp) / "committed.tar"
        subprocess.run(["git", "archive", "-o", str(source), commit, "--", *paths], cwd=ROOT, check=True)
        with tarfile.open(source) as archive:
            archive.extractall(stage, filter="data")
        plugin = stage / "plugins/bellpull"
        metadata = runpy.run_path(str(plugin / "version.py"))
        version = metadata["VERSION"]
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9.-]+)?", version):
            raise ValueError("Invalid plugin manifest version")
        if version not in (plugin / "README.md").read_text().splitlines()[0]:
            raise ValueError("Plugin release note and manifest version differ")
        build = dict(plugin_version=version, source_commit=commit, source_sha256=metadata["source_digest"](plugin),
                     protocol_version=metadata["PROTOCOL"], tested_hermes_versions=metadata["TESTED_HERMES"])
        (plugin / "build.json").write_text(json.dumps(build, indent=2) + "\n")
        prefix = "bellpull-plugin-" + version
        output = destination / (prefix + ".tar.gz")
        def canonical(member):
            member.mtime = timestamp
            member.uid = member.gid = 0
            member.uname = member.gname = ""
            return member
        candidate = Path(tmp) / output.name
        with candidate.open("wb") as raw:
            with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=timestamp) as compressed:
                with tarfile.open(fileobj=compressed, mode="w|") as archive:
                    archive.add(stage, arcname=prefix, filter=canonical)
        with candidate.open("rb") as source_file:
            digest = hashlib.file_digest(source_file, "sha256").hexdigest()
        checksum = candidate.with_suffix(candidate.suffix + ".sha256")
        checksum.write_text(f"{digest}  {output.name}\n")
        candidate.replace(output)
        checksum.replace(destination / checksum.name)
        print(output)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--revision", default="HEAD")
    args = parser.parse_args()
    package(args.destination, args.revision)
