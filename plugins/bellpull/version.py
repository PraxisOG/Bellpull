"""Public compatibility metadata shared by the running plugin and its packager."""
import hashlib
import json
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent
PROTOCOL = 1
TESTED_HERMES = ("0.21.1", "0.21.5")
VERSION = yaml.safe_load((ROOT / "plugin.yaml").read_text())["version"]


def source_digest(root):
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if not path.is_file() or set(relative.parts) & {"__pycache__", "tests"}:
            continue
        if relative.as_posix() == "build.json" or path.suffix == ".pyc":
            continue
        digest.update(relative.as_posix().encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def compatibility():
    sha = source_digest(ROOT)
    result = {"plugin_version": VERSION, "protocol_version": PROTOCOL,
              "source_sha256": sha, "tested_hermes_versions": list(TESTED_HERMES)}
    build = ROOT / "build.json"
    if build.is_file():
        stamp = json.loads(build.read_text())
        if stamp.get("source_sha256") == sha:
            result["source_commit"] = stamp["source_commit"]
    return result
