#!/usr/bin/env python3
"""Export RawForge Standard and add the explicit onnx2tf mobile-boundary adapter."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import torch
import onnx


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--torchscript", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    base = args.output_dir / "rawforge_standard_fp32_static.onnx"
    fp16_base = args.output_dir / "rawforge_standard_true_fp16_static.onnx"

    model = torch.jit.load(str(args.torchscript), map_location="cpu").eval().float()
    image = torch.zeros((1, 3, 256, 256), dtype=torch.float32)
    condition = torch.zeros((1, 1), dtype=torch.float32)
    with torch.inference_mode():
        torch.onnx.export(
            model, (image, condition), str(base), input_names=["input", "cond"],
            output_names=["output"], opset_version=18, do_constant_folding=True,
            dynamic_axes=None, dynamo=False,
        )
    fp16_model = torch.jit.load(str(args.torchscript), map_location="cpu").eval().half()
    with torch.inference_mode():
        torch.onnx.export(
            fp16_model, (image.half(), condition.half()), str(fp16_base),
            input_names=["input", "cond"], output_names=["output"], opset_version=18,
            do_constant_folding=True, dynamic_axes=None, dynamo=False,
        )

    onnx.checker.check_model(onnx.load(base))
    onnx.checker.check_model(onnx.load(fp16_base))
    report = {
        "torchscript": str(args.torchscript.resolve()),
        "torchscript_sha256": digest(args.torchscript),
        "base_onnx": base.name,
        "base_onnx_sha256": digest(base),
        "true_fp16_experimental_onnx": fp16_base.name,
        "true_fp16_experimental_onnx_sha256": digest(fp16_base),
        "mobile_adapter": "Apply wrap_onnx2tf_saved_model.py after onnx2tf; then finalize tensor labels",
        "torch": torch.__version__,
        "onnx": onnx.__version__,
    }
    (args.output_dir / "standard_export_manifest.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8", newline="\n",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
