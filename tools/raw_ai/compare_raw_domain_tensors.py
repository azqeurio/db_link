#!/usr/bin/env python3
"""Strict HWC float32 RAW-domain comparison, alignment search, and bounded attribution."""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
from pathlib import Path

import numpy as np


THRESHOLDS = (1e-6, 1e-5, 1e-4, 1e-3, 1e-2)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path: Path, width: int, height: int) -> np.ndarray:
    expected = width * height * 3
    values = np.fromfile(path, dtype="<f4")
    if values.size != expected:
        raise ValueError(f"Element mismatch for {path}: expected={expected} actual={values.size}")
    return values.reshape(height, width, 3)


def image_stats(tensor: np.ndarray) -> dict:
    flat = tensor.astype(np.float64).reshape(-1)
    finite = np.isfinite(flat)
    return {
        "elements": int(flat.size),
        "finite": int(finite.sum()),
        "nan": int(np.isnan(flat).sum()),
        "positive_infinity": int(np.isposinf(flat).sum()),
        "negative_infinity": int(np.isneginf(flat).sum()),
        "minimum": float(np.min(flat)),
        "maximum": float(np.max(flat)),
        "mean": float(np.mean(flat)),
        "standard_deviation": float(np.std(flat)),
        "percentiles": {str(p): float(np.percentile(flat, p)) for p in (0, 1, 5, 50, 95, 99, 100)},
        "per_channel": [
            {
                "minimum": float(tensor[:, :, c].min()),
                "maximum": float(tensor[:, :, c].max()),
                "mean": float(tensor[:, :, c].mean()),
                "standard_deviation": float(tensor[:, :, c].std()),
            }
            for c in range(3)
        ],
    }


def difference(reference: np.ndarray, actual: np.ndarray) -> dict:
    if reference.shape != actual.shape:
        raise ValueError(f"Shape mismatch: {reference.shape} vs {actual.shape}")
    ref = reference.astype(np.float64)
    value = actual.astype(np.float64)
    error = np.abs(ref - value)
    flat_error = error.reshape(-1)
    largest = int(np.argmax(flat_error))
    y, x, channel = np.unravel_index(largest, reference.shape)
    correlation = float(np.corrcoef(ref.reshape(-1), value.reshape(-1))[0, 1])
    return {
        "maximum_absolute_error": float(flat_error[largest]),
        "mean_absolute_error": float(np.mean(flat_error)),
        "rmse": float(np.sqrt(np.mean(np.square(flat_error)))),
        "mean_relative_error_epsilon_1e-6": float(np.mean(flat_error / np.maximum(np.abs(ref.reshape(-1)), 1e-6))),
        "correlation": correlation,
        "largest_error": {
            "x": int(x),
            "y": int(y),
            "channel": "RGB"[channel],
            "flat_index": largest,
            "reference": float(reference[y, x, channel]),
            "actual": float(actual[y, x, channel]),
        },
        "percent_above_threshold": {str(t): float(np.mean(flat_error > t) * 100.0) for t in THRESHOLDS},
        "per_channel": [
            {
                "maximum_absolute_error": float(error[:, :, c].max()),
                "mean_absolute_error": float(error[:, :, c].mean()),
                "rmse": float(np.sqrt(np.mean(np.square(error[:, :, c])))),
                "correlation": float(np.corrcoef(ref[:, :, c].reshape(-1), value[:, :, c].reshape(-1))[0, 1]),
            }
            for c in range(3)
        ],
    }


def transforms(tensor: np.ndarray) -> dict[str, np.ndarray]:
    return {
        "identity": tensor,
        "rotate90": np.rot90(tensor, 1),
        "rotate180": np.rot90(tensor, 2),
        "rotate270": np.rot90(tensor, 3),
        "horizontal_flip": np.flip(tensor, 1),
        "vertical_flip": np.flip(tensor, 0),
        "transpose": np.transpose(tensor, (1, 0, 2)),
        "transverse": np.flip(np.transpose(tensor, (1, 0, 2)), (0, 1)),
    }


def offset_search(reference: np.ndarray, actual: np.ndarray, radius: int) -> list[dict]:
    height, width, _ = reference.shape
    results = []
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            ref = reference[max(0, -dy) : min(height, height - dy), max(0, -dx) : min(width, width - dx)]
            value = actual[max(0, dy) : min(height, height + dy), max(0, dx) : min(width, width + dx)]
            metrics = difference(ref, value)
            results.append({"dx": dx, "dy": dy, "rmse": metrics["rmse"], "correlation": metrics["correlation"]})
    return sorted(results, key=lambda item: item["rmse"])


def attribution(reference: np.ndarray, actual: np.ndarray) -> list[dict]:
    before = difference(reference, actual)["rmse"]
    candidates: list[tuple[str, np.ndarray, str]] = []
    denominator = float(np.sum(actual.astype(np.float64) ** 2))
    scale = float(np.sum(reference.astype(np.float64) * actual) / denominator) if denominator else 1.0
    candidates.append(("global_scale", actual * scale, f"scale={scale}"))
    channel_scale = []
    scaled = actual.astype(np.float64).copy()
    for channel in range(3):
        d = float(np.sum(actual[:, :, channel].astype(np.float64) ** 2))
        s = float(np.sum(reference[:, :, channel].astype(np.float64) * actual[:, :, channel]) / d) if d else 1.0
        channel_scale.append(s)
        scaled[:, :, channel] *= s
    candidates.append(("per_channel_scale", scaled, f"scale={channel_scale}"))
    affine = actual.astype(np.float64).copy()
    coefficients = []
    for channel in range(3):
        x = actual[:, :, channel].astype(np.float64).reshape(-1)
        y = reference[:, :, channel].astype(np.float64).reshape(-1)
        matrix = np.stack([x, np.ones_like(x)], axis=1)
        a, b = np.linalg.lstsq(matrix, y, rcond=None)[0]
        affine[:, :, channel] = a * actual[:, :, channel] + b
        coefficients.append([float(a), float(b)])
    candidates.append(("per_channel_affine", affine, f"coefficients={coefficients}"))
    candidates.append(("clip_actual_0_1", np.clip(actual, 0, 1), "diagnostic only"))
    for permutation in itertools.permutations(range(3)):
        if permutation != (0, 1, 2):
            candidates.append((f"channel_permutation_{permutation}", actual[:, :, permutation], "diagnostic only"))
    results = []
    for name, candidate, parameters in candidates:
        after = difference(reference, candidate)["rmse"]
        results.append(
            {
                "candidate": name,
                "parameters": parameters,
                "rmse_before": before,
                "rmse_after": after,
                "explained_fraction": 0.0 if before == 0 else 1.0 - after / before,
            }
        )
    return sorted(results, key=lambda item: item["rmse_after"])


def ppm(path: Path, tensor: np.ndarray, scale: float = 1.0) -> None:
    display = np.clip(tensor * scale, 0, 1)
    rgb = np.rint(display * 255.0).astype(np.uint8)
    with path.open("wb") as stream:
        stream.write(f"P6\n{rgb.shape[1]} {rgb.shape[0]}\n255\n".encode("ascii"))
        stream.write(rgb.tobytes())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    parser.add_argument("actual", type=Path)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--debug-dir", type=Path)
    parser.add_argument("--offset-radius", type=int, default=4)
    parser.add_argument("--diff-display-scale", type=float, default=10.0)
    args = parser.parse_args()
    reference = read(args.reference, args.width, args.height)
    actual = read(args.actual, args.width, args.height)
    orientation = []
    for name, candidate in transforms(actual).items():
        if candidate.shape == reference.shape:
            metrics = difference(reference, candidate)
            orientation.append({"transform": name, "rmse": metrics["rmse"], "correlation": metrics["correlation"]})
    report = {
        "schema_version": 1,
        "reference": {"path_name": args.reference.name, "sha256": sha256(args.reference), "statistics": image_stats(reference)},
        "actual": {"path_name": args.actual.name, "sha256": sha256(args.actual), "statistics": image_stats(actual)},
        "difference": difference(reference, actual),
        "orientation_candidates": sorted(orientation, key=lambda item: item["rmse"]),
        "offset_candidates_best_first": offset_search(reference, actual, args.offset_radius),
        "attribution_candidates_best_first": attribution(reference, actual),
        "relative_error_policy": "abs(reference-actual)/max(abs(reference),1e-6)",
        "visualization": "PPM clips to [0,1]; difference PPM uses fixed recorded multiplier",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8", newline="\n")
    if args.debug_dir:
        args.debug_dir.mkdir(parents=True, exist_ok=True)
        ppm(args.debug_dir / "reference.ppm", reference)
        ppm(args.debug_dir / "actual.ppm", actual)
        error = np.abs(reference.astype(np.float64) - actual.astype(np.float64))
        ppm(args.debug_dir / "absolute-difference.ppm", error, args.diff_display_scale)
        for channel in range(3):
            view = np.zeros_like(error)
            view[:, :, channel] = error[:, :, channel]
            ppm(args.debug_dir / f"absolute-difference-{'rgb'[channel]}.ppm", view, args.diff_display_scale)
        high = np.repeat((np.max(error, axis=2, keepdims=True) > 1e-2).astype(np.float64), 3, axis=2)
        ppm(args.debug_dir / "high-error-mask-1e-2.ppm", high)
        clipped = np.stack([np.any(actual <= 0, axis=2), np.any(actual >= 1, axis=2), np.zeros(actual.shape[:2], bool)], axis=2)
        ppm(args.debug_dir / "clipping-mask.ppm", clipped.astype(np.float64))
    print(json.dumps({"output": str(args.output), "difference": report["difference"], "best_offset": report["offset_candidates_best_first"][0]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
