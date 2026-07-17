#!/usr/bin/env python3
"""Benchmark the two Phase 5 TFLite models and compare optional device outputs."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import statistics
import time
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def difference(reference: np.ndarray, actual: np.ndarray) -> dict[str, float]:
    delta = actual.astype(np.float64) - reference.astype(np.float64)
    return {
        "max_abs": float(np.max(np.abs(delta))),
        "mean_abs": float(np.mean(np.abs(delta))),
        "rmse": float(np.sqrt(np.mean(delta * delta))),
        "correlation": float(np.corrcoef(reference.ravel(), actual.ravel())[0, 1]),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--device-results", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    rows, parity, pairwise = [], [], []
    per_sample: dict[str, dict[str, np.ndarray]] = {}
    for key in ("superlight", "standard"):
        info = manifest["model_files"][key]
        model = args.assets / info["path"]
        if sha256(model) != info["sha256"]:
            raise ValueError(f"Model hash mismatch: {model}")
        interpreter = Interpreter(model_path=str(model), num_threads=4)
        interpreter.allocate_tensors()
        input_details = {item["name"]: item for item in interpreter.get_input_details()}
        output_detail = interpreter.get_output_details()[0]
        for source_manifest in sorted(args.inputs.glob("*.manifest.json")):
            source = json.loads(source_manifest.read_text(encoding="utf-8"))
            record = next(item for item in source["tensors"] if item["stage"] == "rawforge_malvar_linrec2020")
            sample = source_manifest.name.removesuffix(".manifest.json")
            image = np.fromfile(args.inputs / record["file"], dtype="<f4").reshape(1, 256, 256, 3)
            iso = float(source["source"]["iso"])
            condition = np.array([[min(iso, 65535.0) / 6400.0]], dtype=np.float32)
            interpreter.set_tensor(input_details["input"]["index"], image)
            interpreter.set_tensor(input_details["cond"]["index"], condition)
            interpreter.invoke()
            timings = []
            for _ in range(args.repeats):
                started = time.perf_counter(); interpreter.invoke()
                timings.append((time.perf_counter() - started) * 1000)
            output = interpreter.get_tensor(output_detail["index"])
            if not np.isfinite(output).all():
                raise ValueError(f"Non-finite output: {key}/{sample}")
            per_sample.setdefault(sample, {})[key] = output
            output_file = args.output / f"{sample}.desktop_{key}_fp32.nchw.f32le.bin"
            output.astype("<f4").tofile(output_file)
            rows.append({
                "model": key, "sample": sample, "iso": iso,
                "median_ms": statistics.median(timings), "mean_ms": statistics.mean(timings),
                "minimum_ms": min(timings), "maximum_ms": max(timings),
                "output_sha256": sha256(output_file),
            })
            if args.device_results:
                mobile_file = args.device_results / f"{sample}.mobile_{key}_fp32.nchw.f32le.bin"
                mobile = np.fromfile(mobile_file, dtype="<f4").reshape(1, 3, 256, 256)
                parity.append({"model": key, "sample": sample, "iso": iso, **difference(output, mobile)})
    for sample, outputs in per_sample.items():
        if outputs.keys() == {"superlight", "standard"}:
            pairwise.append({"sample": sample, **difference(outputs["superlight"], outputs["standard"])})
    with (args.output / "desktop_timing.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
    (args.output / "desktop_vs_device.json").write_text(json.dumps(parity, indent=2), encoding="utf-8")
    (args.output / "standard_vs_superlight.json").write_text(json.dumps(pairwise, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
