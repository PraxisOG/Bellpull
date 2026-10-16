"""Install the reviewed generic hooks into an OFFLINE pinned Hermes source tree.

No fuzzy patching, dependency on git/patch, or production restart. A thin image
build invokes --apply; ordinary invocation only verifies compatibility.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def patch_file(before, hunks):
    source = before.splitlines(keepends=True)
    result, cursor, index = [], 0, 0
    while index < len(hunks):
        header = re.fullmatch(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*\n?", hunks[index])
        if not header:
            raise ValueError("Invalid reviewed patch hunk")
        start = int(header[1]) - 1
        result.extend(source[cursor:start])
        cursor = start
        index += 1
        while index < len(hunks) and not hunks[index].startswith("@@"):
            line = hunks[index]
            if line[:1] in (" ", "-"):
                if cursor >= len(source) or source[cursor] != line[1:]:
                    raise ValueError("Patch context differs from pinned source")
                cursor += 1
            if line[:1] in (" ", "+"):
                result.append(line[1:])
            index += 1
    result.extend(source[cursor:])
    return "".join(result).encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("/opt/hermes"))
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    bundle = Path(__file__).parent
    metadata = json.loads((bundle / "source-sha256.json").read_text())
    state = {path: digest((args.source / path).read_bytes()) for path in metadata}
    if all(state[path] == checks["after"] for path, checks in metadata.items()):
        print("Native API hooks are already installed; verifying contract module.")
        target = args.source / "gateway/platforms/api_server_extensions.py"
        if not target.exists() or target.read_bytes() != (bundle / target.name).read_bytes():
            raise SystemExit("Host contract module is absent or differs; rebuild the pinned image")
        return
    if not all(state[path] == checks["before"] for path, checks in metadata.items()):
        raise SystemExit("Unsupported/mixed Hermes source. Refusing to overlay an unknown version.")
    print("Verified unmodified Hermes v2026.9.7 / 0.21.1 source.")
    if not args.apply:
        return
    sections = {}
    path = None
    for line in (bundle / "native-api-hooks.patch").read_text().splitlines(keepends=True):
        if line.startswith("--- a/"):
            path = line[6:].strip()
            sections[path] = []
        elif line.startswith("+++ b/"):
            continue
        elif path is not None:
            sections[path].append(line)
    staged = {}
    for path, checks in metadata.items():
        data = patch_file((args.source / path).read_text(), sections[path])
        if digest(data) != checks["after"]:
            raise SystemExit("Reviewed patch result checksum mismatch")
        staged[args.source / path] = data
    staged[args.source / "gateway/platforms/api_server_extensions.py"] = (bundle / "api_server_extensions.py").read_bytes()
    for path, data in staged.items():
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as file:
            temporary = Path(file.name)
            file.write(data)
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    print("Installed API extension host v1; no Bellpull application logic in core.")


if __name__ == "__main__":
    main()
