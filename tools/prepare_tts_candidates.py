#!/usr/bin/env python3
"""Prepare pinned public models and official-frontend tokens for isolated CPU benchmarks.

Run in a disposable venv with Paradee's official package and soundfile installed.
This stages no personal settings and installs no production Android voice engine.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import urllib.request

MODELS = {
    "paradee": ("sahilmahendrakar/Paradee-8M-v1.0", "c7f83f86c04e84d0ae94cbe1aad7618e0544f80a",
                ["onnx/paradee_int8.onnx", "config.json", "LICENSE"]),
    "inflect": ("owensong/Inflect-Micro-v2-ONNX", "91b1ab6432323064ec0e8e9704d92fcecd24855f",
                ["onnx/duration.onnx", "onnx/decode.onnx", "onnx/checksums.sha256", "onnx/inference_onnx.py",
                 "inflect_vits_frontend.py", "inflect_nano_v2_frontend.py", "runtime/text/__init__.py",
                 "runtime/text/symbols.py", "runtime/text/cleaners.py", "config.json", "LICENSE", "THIRD_PARTY_NOTICES.md"]),
}
TEXTS = ["Working.", "Your answer is ready.",
         "The board measures twenty four inches by twelve inches.",
         "Doctor Chen checked SQLite over USB-C at 3:30 p.m. and counted 42 records.",
         "A long research answer can continue across many sentences while the next phrase is prepared. "
         "Each completed phrase should be heard once, and an interrupted phrase can restart without asking the server for another answer."]


def prepare(root):
    from paradee import Paradee
    manifest = {}
    for key, (repo, revision, files) in MODELS.items():
        directory = root / key
        checksums = {}
        for name in files:
            target = directory / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                urllib.request.urlretrieve(f"https://huggingface.co/{repo}/resolve/{revision}/{name}", target)
            checksums[name] = hashlib.sha256(target.read_bytes()).hexdigest()
        manifest[key] = {"repo": repo, "revision": revision, "sha256": checksums}
    model = Paradee(model_path=root / "paradee/onnx/paradee_int8.onnx", config_path=root / "paradee/config.json", threads=1)
    # The official Inflect inference module configures its eSpeak-ng frontend.
    sys.path.insert(0, str(root / "inflect"))
    spec = importlib.util.spec_from_file_location("inflect_official", root / "inflect/onnx/inference_onnx.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    rows = []
    for index, text in enumerate(TEXTS):
        phones = model.phonemize(text)
        ids = [0] + [model.vocab[c] for c in phones if c in model.vocab] + [0]
        if len(ids) > 512:
            raise ValueError("Fixture exceeds Paradee graph capacity; split it without clipping.")
        inflect_phones = module.run_vits_frontend(text).phoneme_text
        inflect_ids = module.phonemes_to_tokens(inflect_phones)[0].tolist()
        rows.append({"id": index, "text": text, "paradee": ids, "inflect": inflect_ids})
    (root / "fixtures.json").write_text(json.dumps({"models": manifest, "fixtures": rows}, indent=2))
    print(root / "fixtures.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    prepare(args.directory.resolve())
