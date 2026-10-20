"""Export pinned original FP32 weights for the native incremental CPU decoder.

No quantization or dequantization of an INT8 checkpoint. The public Moonshine
exporter splits the original HF weights into stateful graphs; ORT preserves FP32.
Export dependencies are separate from the lightweight serving environment.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import subprocess
import sys

MODEL = "moonshine-ai/moonshine-streaming-medium"
REVISION = "57b843633a8c183cadf6699ffa761377a933a866"
GRAPHS = ("frontend", "encoder", "adapter", "cross_kv", "decoder_kv")
FILES = tuple(name + ".ort" for name in GRAPHS) + ("streaming_config.json", "tokenizer.bin")


def digest(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest()


def verify_export(folder):
    manifest = json.loads((folder / "model-manifest.json").read_text())
    if (manifest.get("precision"), manifest.get("model"), manifest.get("revision")) != ("fp32", MODEL, REVISION):
        raise ValueError("Expected the pinned FP32 medium export")
    for name in FILES:
        if digest(folder / name) != manifest["sha256"].get(name):
            raise ValueError("FP32 export checksum mismatch: " + name)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path,
                        default=Path.home() / ".local/share/bellpull-speech/fp32-medium")
    parser.add_argument("--reuse-onnx", action="store_true",
                        help="Finish an interrupted conversion of this pinned export")
    args = parser.parse_args()
    import onnx
    if not args.reuse_onnx:
        import torch
        from huggingface_hub import snapshot_download
        from moonshine_voice.download import get_model_for_language
        from moonshine_voice.moonshine_api import ModelArch
        from moonshine_voice.lora.export import export_checkpoint
        source = snapshot_download(MODEL, revision=REVISION)
        native, _ = get_model_for_language("en", ModelArch.MEDIUM_STREAMING, on_progress=lambda *_: None)
        torch.set_num_threads(6)
        export_checkpoint(source, args.output, tokenizer_bin=Path(native) / "tokenizer.bin")
    # Check the export before converting: no quantized weight tensors or operators.
    for name in GRAPHS:
        graph = onnx.load(args.output / (name + ".onnx")).graph
        if any(t.data_type in (onnx.TensorProto.INT8, onnx.TensorProto.UINT8) for t in graph.initializer):
            raise ValueError("Unexpected quantized weights")
        if any(n.op_type in ("MatMulInteger", "QLinearMatMul", "QuantizeLinear",
                            "DequantizeLinear", "DynamicQuantizeLinear") for n in graph.node):
            raise ValueError("Unexpected quantized compute")
    subprocess.run([sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort",
                    str(args.output), "--optimization_style", "Fixed"], check=True)
    manifest = {"model": MODEL, "revision": REVISION, "precision": "fp32", "device": "cpu",
                "packages": {n: importlib.metadata.version(n) for n in
                             ("moonshine-voice", "torch", "transformers", "onnx", "onnxscript", "onnxruntime")},
                "sha256": {name: digest(args.output / name) for name in FILES}}
    (args.output / "model-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    verify_export(args.output)
    print("Verified FP32 export:", args.output)


if __name__ == "__main__":
    main()
