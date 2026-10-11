"""Fail closed on exact tested sources, including local vendor modifications."""
import hashlib
import json
from pathlib import Path


def source_mode():
    from gateway.platforms import api_server
    root = Path(api_server.__file__).resolve().parents[2]
    expected = json.loads(Path(__file__).with_name("compatibility.json").read_text())
    try:
        actual = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in expected}
    except OSError:
        return None
    for mode in ("before", "after"):
        if all(actual[name] == values[mode] for name, values in expected.items()):
            return mode
    return None
