#!/usr/bin/env python3
"""Fetch pinned Apache-2.0 MiniLM assets; verify every build, download only if absent."""
import hashlib
import os
from pathlib import Path
import sys
import urllib.request

REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
FILES = {
    "vocab.txt": "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
    "onnx/model_qint8_arm64.onnx": "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474",
}
root = Path(sys.argv[1]) / "search"
root.mkdir(parents=True, exist_ok=True)
for remote, digest in FILES.items():
    destination = root / Path(remote).name
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == digest:
        continue
    cache = Path.home() / ".cache/bellpull-search" / destination.name
    part = destination.with_suffix(".part")
    try:
        if cache.exists() and hashlib.sha256(cache.read_bytes()).hexdigest() == digest:
            part.write_bytes(cache.read_bytes())
        else:
            urllib.request.urlretrieve(f"https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/{REVISION}/{remote}", part)
        if hashlib.sha256(part.read_bytes()).hexdigest() != digest:
            raise RuntimeError("Embedding asset checksum mismatch: " + remote)
        os.replace(part, destination)
    finally:
        part.unlink(missing_ok=True)
