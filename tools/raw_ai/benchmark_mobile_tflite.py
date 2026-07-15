#!/usr/bin/env python3
"""Benchmark the phone-portable TFLite model against upstream SuperLight tiles."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import statistics
import time
from pathlib import Path

import numpy as np
import tensorflow as tf

try:
    from PIL import Image
except ImportError:
    Image = None


TILE = 256


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def display_rgb(image: np.ndarray) -> np.ndarray:
    matrix = np.array([[1.660491, -0.587641, -0.072850],
                       [-0.124550, 1.132900, -0.008349],
                       [-0.018151, -0.100579, 1.118730]], dtype=np.float32)
    linear = np.clip((image @ matrix.T) * 2.0, 0.0, 1.0)
    encoded = np.where(linear <= 0.0031308, 12.92 * linear,
                       1.055 * np.power(linear, 1 / 2.4) - 0.055)
    return np.rint(np.clip(encoded, 0, 1) * 255).astype(np.uint8)


def save_png(path: Path, image: np.ndarray) -> None:
    if Image is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(display_rgb(image), "RGB").save(path, compress_level=9)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--reference-results", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=5)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    interpreter = tf.lite.Interpreter(model_path=str(args.model), num_threads=4)
    interpreter.allocate_tensors()
    inputs = {item["name"]: item for item in interpreter.get_input_details()}
    output_info = interpreter.get_output_details()[0]
    rows, differences, hashes = [], [], []
    for manifest in sorted(args.inputs.glob("_*.manifest.json")):
        metadata = json.loads(manifest.read_text(encoding="utf-8"))
        sample = manifest.name.removesuffix(".manifest.json")
        iso = float(metadata["source"]["iso"])
        record = next(item for item in metadata["tensors"]
                      if item["stage"] == "rawforge_malvar_linrec2020")
        input_path = manifest.parent / record["file"]
        image = np.fromfile(input_path, dtype="<f4").reshape(1, TILE, TILE, 3)
        condition = np.array([[min(iso, 65535.0) / 6400.0]], dtype=np.float32)

        interpreter.set_tensor(inputs["input"]["index"], image)
        interpreter.set_tensor(inputs["cond"]["index"], condition)
        for _ in range(3):
            interpreter.invoke()
        timings = []
        for _ in range(args.repeats):
            started = time.perf_counter()
            interpreter.invoke()
            timings.append((time.perf_counter() - started) * 1000)
        output = interpreter.get_tensor(output_info["index"])[0].transpose(1, 2, 0)
        output_path = args.output / "tensors" / sample / "mobile_tflite.cpu.fp32.f32le.bin"
        output_path.parent.mkdir(parents=True, exist_ok=True)
        np.ascontiguousarray(output.astype("<f4")).tofile(output_path)
        save_png(args.output / "technical" / sample / "mobile_tflite.png", output)

        reference_path = (args.reference_results / "tensors" / sample /
                          "superlight.cuda.fp32.f32le.bin")
        reference = np.fromfile(reference_path, dtype="<f4").reshape(TILE, TILE, 3)
        delta = output.astype(np.float64) - reference.astype(np.float64)
        differences.append({
            "sample": sample,
            "iso": iso,
            "reference": "upstream_superlight_pytorch_cuda_fp32",
            "actual": "mobile_tflite_cpu_fp32",
            "maximum_absolute_error": float(np.max(np.abs(delta))),
            "mean_absolute_error": float(np.mean(np.abs(delta))),
            "rmse": float(np.sqrt(np.mean(delta * delta))),
            "correlation": float(np.corrcoef(reference.ravel(), output.ravel())[0, 1]),
        })
        rows.append({
            "sample": sample, "iso": iso, "repeats": args.repeats,
            "warm_mean_ms": statistics.mean(timings),
            "warm_median_ms": statistics.median(timings),
            "warm_minimum_ms": min(timings), "warm_maximum_ms": max(timings),
        })
        hashes.append({"sample": sample, "input_sha256": sha256(input_path),
                       "output_sha256": sha256(output_path)})

    with (args.output / "mobile_desktop_timing.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
        writer.writeheader(); writer.writerows(rows)
    (args.output / "mobile_vs_superlight.json").write_text(
        json.dumps(differences, indent=2), encoding="utf-8")
    (args.output / "mobile_output_hashes.json").write_text(
        json.dumps(hashes, indent=2), encoding="utf-8")
    (args.output / "mobile_run_manifest.json").write_text(json.dumps({
        "model": str(args.model), "model_sha256": sha256(args.model),
        "tensorflow": tf.__version__, "runtime": "TensorFlow Lite CPU XNNPACK",
        "threads": 4, "output_layout": "HWC float32 little-endian",
    }, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
