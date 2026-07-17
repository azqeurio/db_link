#!/usr/bin/env python3
"""Apply the explicit RawForge mobile tensor boundary to an onnx2tf SavedModel."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import tensorflow as tf


class MobileContract(tf.Module):
    def __init__(self, signature):
        super().__init__()
        self.signature = signature

    @tf.function(input_signature=[
        tf.TensorSpec([1, 256, 256, 3], tf.float32, name="input"),
        tf.TensorSpec([1, 1], tf.float32, name="cond"),
    ])
    def serve(self, image, condition):
        result = self.signature(input=image, cond=condition)
        value = next(iter(result.values()))
        return {"output": tf.transpose(value, [0, 3, 1, 2], name="output_nchw")}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--saved-model", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--stem", required=True)
    args = parser.parse_args()
    source = tf.saved_model.load(str(args.saved_model))
    wrapper = MobileContract(source.signatures["serving_default"])
    concrete = wrapper.serve.get_concrete_function()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    wrapped = args.output_dir / "saved_model"
    tf.saved_model.save(wrapper, str(wrapped), signatures={"serving_default": concrete})

    converter = tf.lite.TFLiteConverter.from_saved_model(str(wrapped))
    (args.output_dir / f"{args.stem}_fp32.tflite").write_bytes(converter.convert())
    converter = tf.lite.TFLiteConverter.from_saved_model(str(wrapped))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    (args.output_dir / f"{args.stem}_fp16_weights.tflite").write_bytes(converter.convert())
    converter = tf.lite.TFLiteConverter.from_saved_model(str(wrapped))
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    converter.inference_input_type = tf.float16
    converter.inference_output_type = tf.float16
    try:
        true_fp16 = converter.convert()
    except ValueError as failure:
        (args.output_dir / f"{args.stem}_true_fp16_io_status.json").write_text(
            json.dumps({"status": "FAIL", "reason": str(failure)}, indent=2),
            encoding="utf-8", newline="\n",
        )
    else:
        (args.output_dir / f"{args.stem}_true_fp16_io_experimental.tflite").write_bytes(true_fp16)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
