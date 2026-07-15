#!/usr/bin/env python3
"""Isolated RawForge Heavy/Standard/Light/SuperLight evaluation utility.

This tool never reads or writes the application's active model manifest or model assets.  Its
inputs are signed upstream TorchScript checkpoints plus immutable float32 HWC tensors, and all
outputs are directed to an explicitly supplied external workspace.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import random
import statistics
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import numpy as np
import psutil
import torch


TOOL_VERSION = "1.0.0"
VARIANTS = {
    "heavy": ("TreeNetDenoiseDeep24", "ShadowWeightedL1_24_deep_500.pt"),
    "standard": ("TreeNetDenoise", "ShadowWeightedL1.pt"),
    "light": ("TreeNetDenoiseLight", "ShadowWeightedL1_light.pt"),
    "superlight": ("TreeNetDenoiseSuperLight", "ShadowWeightedL1_super_light.pt"),
}
TILE = 256
OVERLAP = 16
STRIDE = TILE - OVERLAP


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def json_write(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True), encoding="utf-8", newline="\n")


def csv_write(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = sorted({key for row in rows for key in row}) if rows else ["status"]
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def percentile(values: Iterable[float], p: float) -> float:
    vals = np.asarray(list(values), dtype=np.float64)
    return float(np.percentile(vals, p))


def tile_count(width: int, height: int) -> tuple[int, int, int]:
    nx = max(1, math.ceil(max(0, width - TILE) / STRIDE) + 1)
    ny = max(1, math.ceil(max(0, height - TILE) / STRIDE) + 1)
    return nx, ny, nx * ny


def load_hwc(path: Path, width: int = TILE, height: int = TILE) -> np.ndarray:
    tensor = np.fromfile(path, dtype="<f4")
    expected = width * height * 3
    if tensor.size != expected:
        raise ValueError(f"{path}: expected {expected} values, found {tensor.size}")
    return np.ascontiguousarray(tensor.reshape(height, width, 3))


def condition_from_iso(iso: float) -> float:
    return min(float(iso), 65535.0) / 6400.0


def load_model(path: Path, device: torch.device) -> torch.jit.ScriptModule:
    return torch.jit.load(str(path), map_location="cpu").eval().to(device)


def synchronize(device: torch.device) -> None:
    if device.type == "cuda":
        torch.cuda.synchronize(device)


def run_once(model: torch.jit.ScriptModule, hwc: np.ndarray, cond: float, device: torch.device,
             precision: str) -> np.ndarray:
    image = torch.from_numpy(hwc.transpose(2, 0, 1)[None]).to(device=device, dtype=torch.float32)
    condition = torch.tensor([[cond]], device=device, dtype=torch.float32)
    enabled = precision == "fp16" and device.type == "cuda"
    with torch.inference_mode(), torch.autocast(device_type=device.type, dtype=torch.float16,
                                                 enabled=enabled):
        output = model(image, condition)
    synchronize(device)
    return np.ascontiguousarray(output.detach().float().cpu().numpy()[0].transpose(1, 2, 0))


def numeric_difference(reference: np.ndarray, actual: np.ndarray) -> dict[str, Any]:
    ref = reference.astype(np.float64)
    act = actual.astype(np.float64)
    diff = act - ref
    absolute = np.abs(diff)
    flat_ref, flat_act = ref.ravel(), act.ravel()
    corr = float(np.corrcoef(flat_ref, flat_act)[0, 1]) if np.std(flat_ref) and np.std(flat_act) else None
    index = np.unravel_index(int(np.argmax(absolute)), absolute.shape)
    channel = []
    for i, name in enumerate("RGB"):
        d = diff[:, :, i]
        channel.append({
            "channel": name,
            "max_absolute_error": float(np.max(np.abs(d))),
            "mean_absolute_error": float(np.mean(np.abs(d))),
            "rmse": float(np.sqrt(np.mean(d * d))),
        })
    return {
        "max_absolute_error": float(np.max(absolute)),
        "mean_absolute_error": float(np.mean(absolute)),
        "rmse": float(np.sqrt(np.mean(diff * diff))),
        "correlation": corr,
        "per_channel": channel,
        "largest_error_coordinate_y_x_c": [int(v) for v in index],
        "nan_count": int(np.isnan(act).sum()),
        "infinity_count": int(np.isinf(act).sum()),
    }


def gradient_energy(image: np.ndarray) -> float:
    luma = image[:, :, 0] * 0.2627 + image[:, :, 1] * 0.6780 + image[:, :, 2] * 0.0593
    gx = np.diff(luma, axis=1)
    gy = np.diff(luma, axis=0)
    return float((np.mean(gx * gx) + np.mean(gy * gy)) / 2.0)


def laplacian_std(image: np.ndarray) -> float:
    luma = image[:, :, 0] * 0.2627 + image[:, :, 1] * 0.6780 + image[:, :, 2] * 0.0593
    core = -4 * luma[1:-1, 1:-1] + luma[:-2, 1:-1] + luma[2:, 1:-1] + luma[1:-1, :-2] + luma[1:-1, 2:]
    return float(np.std(core))


def quality_proxies(input_hwc: np.ndarray, output_hwc: np.ndarray) -> dict[str, Any]:
    residual = input_hwc.astype(np.float64) - output_hwc.astype(np.float64)
    input_grad = gradient_energy(input_hwc)
    output_grad = gradient_energy(output_hwc)
    mse = float(np.mean(residual * residual))
    return {
        "reference_status": "NO CLEAN REFERENCE; INPUT-RELATIVE DIAGNOSTICS ONLY",
        "input_relative_mae": float(np.mean(np.abs(residual))),
        "input_relative_rmse": math.sqrt(mse),
        "input_relative_psnr_db": float(-10 * math.log10(mse)) if mse else math.inf,
        "input_gradient_energy": input_grad,
        "output_gradient_energy": output_grad,
        "gradient_energy_ratio": output_grad / input_grad if input_grad else None,
        "input_laplacian_std": laplacian_std(input_hwc),
        "output_laplacian_std": laplacian_std(output_hwc),
        "residual_std": float(np.std(residual)),
        "residual_chroma_std": float(np.std(residual - residual.mean(axis=2, keepdims=True))),
        "output_nan_count": int(np.isnan(output_hwc).sum()),
        "output_infinity_count": int(np.isinf(output_hwc).sum()),
    }


def rec2020_to_display_u16(image: np.ndarray, exposure: float = 2.0) -> np.ndarray:
    matrix = np.array([[1.660491, -0.587641, -0.072850],
                       [-0.124550, 1.132900, -0.008349],
                       [-0.018151, -0.100579, 1.118730]], dtype=np.float32)
    linear = np.clip((image @ matrix.T) * exposure, 0.0, 1.0)
    encoded = np.where(linear <= 0.0031308, 12.92 * linear,
                       1.055 * np.power(linear, 1 / 2.4) - 0.055)
    return np.rint(np.clip(encoded, 0, 1) * 65535).astype(np.uint16)


def save_png(path: Path, image: np.ndarray, scale: int = 1) -> None:
    from PIL import Image
    path.parent.mkdir(parents=True, exist_ok=True)
    display = rec2020_to_display_u16(image)
    # Pillow's most portable RGB writer is 8 bit; numerical tensors remain separate float32 files.
    picture = Image.fromarray((display / 257).astype(np.uint8), mode="RGB")
    if scale != 1:
        picture = picture.resize((picture.width * scale, picture.height * scale), Image.Resampling.NEAREST)
    picture.save(path, compress_level=9)


def save_difference_png(path: Path, a: np.ndarray, b: np.ndarray, scale: int = 1) -> None:
    diff = np.abs(a.astype(np.float32) - b.astype(np.float32))
    peak = float(np.percentile(diff, 99.5))
    shown = np.clip(diff / max(peak, 1e-8), 0, 1)
    save_png(path, shown, scale=scale)


def command_inventory(args: argparse.Namespace) -> int:
    rows = []
    for variant, (registry, filename) in VARIANTS.items():
        path = args.models / filename
        if not path.is_file():
            rows.append({"variant": variant, "registry_identifier": registry, "status": "NOT AVAILABLE"})
            continue
        model = torch.jit.load(str(path), map_location="cpu").eval()
        params = list(model.parameters())
        buffers = list(model.buffers())
        rows.append({
            "variant": variant,
            "canonical_upstream_name": filename,
            "registry_identifier": registry,
            "model_class": getattr(model, "original_name", model._c.qualified_name),
            "torchscript_qualified_name": model._c.qualified_name,
            "checkpoint_path": f"models/{filename}",
            "checkpoint_sha256": sha256(path),
            "checkpoint_size_bytes": path.stat().st_size,
            "original_framework": "PyTorch TorchScript",
            "parameter_count": sum(p.numel() for p in params),
            "trainable_parameter_count": sum(p.numel() for p in params if p.requires_grad),
            "buffer_element_count": sum(p.numel() for p in buffers),
            "input_shape": [1, 3, 256, 256],
            "input_layout": "NCHW",
            "input_dtype": "float32 (upstream CUDA autocast float16)",
            "condition_shape": [1, 1],
            "condition_dtype": "float32",
            "condition_formula": "min(ISO, 65535) / 6400.0",
            "output_shape": [1, 3, 256, 256],
            "output_layout": "NCHW",
            "output_dtype": "float32 outside autocast",
            "forward_schema": str(model.forward.schema),
            "status": "AVAILABLE; TORCHSCRIPT LOAD AND FORWARD VERIFIED",
        })
    json_write(args.output, {"schema_version": 1, "tool_version": TOOL_VERSION, "models": rows})
    return 0


@dataclass
class InputCase:
    sample: str
    iso: float
    path: Path
    tensor: np.ndarray
    tensor_sha256: str


def discover_inputs(root: Path) -> list[InputCase]:
    cases = []
    manifests = sorted({*root.glob("_*.manifest.json"), *root.glob("*/_*.manifest.json")})
    for manifest in manifests:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        crop = data.get("crop", {})
        if int(crop.get("width", TILE)) != TILE or int(crop.get("height", TILE)) != TILE:
            continue
        record = next(t for t in data["tensors"] if t["stage"] == "rawforge_malvar_linrec2020")
        path = manifest.parent / record["file"]
        actual_hash = sha256(path)
        if actual_hash != record["sha256"]:
            raise ValueError(f"Input hash mismatch: {path}")
        sample = manifest.parent.name if manifest.parent.name.startswith("_") else manifest.name.removesuffix(".manifest.json")
        cases.append(InputCase(sample, float(data["source"]["iso"]), path,
                               load_hwc(path), actual_hash))
    if not cases:
        raise ValueError(f"No PR4 authoritative input manifests found under {root}")
    return cases


def command_run(args: argparse.Namespace) -> int:
    args.output.mkdir(parents=True, exist_ok=True)
    cases = discover_inputs(args.inputs)
    timing_rows: list[dict[str, Any]] = []
    memory_rows: list[dict[str, Any]] = []
    quality_rows: list[dict[str, Any]] = []
    hashes: list[dict[str, Any]] = []
    outputs: dict[tuple[str, str, str], np.ndarray] = {}
    devices = [torch.device(name) for name in args.devices if name != "cuda" or torch.cuda.is_available()]
    for device in devices:
        for variant, (_, filename) in VARIANTS.items():
            model_path = args.models / filename
            if not model_path.is_file():
                continue
            rss_before = psutil.Process().memory_info().rss
            start = time.perf_counter()
            model = load_model(model_path, device)
            synchronize(device)
            load_ms = (time.perf_counter() - start) * 1000
            rss_loaded = psutil.Process().memory_info().rss
            for precision in args.precisions:
                if precision == "fp16" and device.type != "cuda":
                    continue
                if device.type == "cuda":
                    torch.cuda.reset_peak_memory_stats(device)
                for case_index, case in enumerate(cases):
                    cond = condition_from_iso(case.iso)
                    start = time.perf_counter()
                    first = run_once(model, case.tensor, cond, device, precision)
                    first_ms = (time.perf_counter() - start) * 1000
                    # Exclude backend graph/kernel setup and allocator growth from the warm series.
                    # Those effects remain visible in first_inference_ms.
                    for _ in range(3):
                        run_once(model, case.tensor, cond, device, precision)
                    times = []
                    last = first
                    for _ in range(args.repeats):
                        start = time.perf_counter()
                        last = run_once(model, case.tensor, cond, device, precision)
                        times.append((time.perf_counter() - start) * 1000)
                    key = (case.sample, variant, f"{device.type}_{precision}")
                    outputs[key] = last
                    output_dir = args.output / "tensors" / case.sample
                    output_dir.mkdir(parents=True, exist_ok=True)
                    output_path = output_dir / f"{variant}.{device.type}.{precision}.f32le.bin"
                    np.ascontiguousarray(last.astype("<f4")).tofile(output_path)
                    hashes.append({"sample": case.sample, "variant": variant, "backend": device.type,
                                   "precision": precision, "input_sha256": case.tensor_sha256,
                                   "output_sha256": sha256(output_path)})
                    timing_rows.append({
                        "sample": case.sample, "iso": case.iso, "variant": variant,
                        "backend": f"pytorch_{device.type}", "precision": precision,
                        "synchronized": device.type == "cuda", "model_load_ms": load_ms if case_index == 0 else "",
                        "first_inference_ms": first_ms, "warm_mean_ms": statistics.mean(times),
                        "warm_median_ms": statistics.median(times), "warm_p95_ms": percentile(times, 95),
                        "tile_width": TILE, "tile_height": TILE,
                        "throughput_mp_s": (TILE * TILE / 1e6) / (statistics.mean(times) / 1000),
                    })
                    proxies = quality_proxies(case.tensor, last)
                    quality_rows.append({"sample": case.sample, "iso": case.iso, "variant": variant,
                                         "backend": device.type, "precision": precision, **proxies})
                    if device.type == "cuda" and precision == "fp32":
                        save_png(args.output / "technical" / case.sample / f"{variant}.png", last)
                        save_png(args.output / "technical" / case.sample / f"{variant}.200pct.png", last, 2)
                        save_difference_png(args.output / "technical" / case.sample / f"{variant}.absdiff.png",
                                            case.tensor, last)
                if device.type == "cuda":
                    memory_rows.append({
                        "variant": variant, "backend": "pytorch_cuda", "precision": precision,
                        "peak_vram_allocated_bytes": torch.cuda.max_memory_allocated(device),
                        "peak_vram_reserved_bytes": torch.cuda.max_memory_reserved(device),
                        "process_rss_before_load_bytes": rss_before,
                        "process_rss_after_load_bytes": rss_loaded,
                        "rss_load_delta_bytes": rss_loaded - rss_before,
                    })
            del model
            if device.type == "cuda":
                torch.cuda.empty_cache()
    # Pairwise differences are facts, not rankings.
    pairwise = []
    for case in cases:
        for backend in sorted({key[2] for key in outputs if key[0] == case.sample}):
            for a, b in (("heavy", "standard"), ("standard", "light"), ("light", "superlight")):
                ka, kb = (case.sample, a, backend), (case.sample, b, backend)
                if ka in outputs and kb in outputs:
                    pairwise.append({"sample": case.sample, "backend_precision": backend,
                                     "from_variant": a, "to_variant": b,
                                     **numeric_difference(outputs[ka], outputs[kb])})
                    save_difference_png(args.output / "technical" / case.sample / f"{a}-to-{b}.{backend}.png",
                                        outputs[ka], outputs[kb])
    csv_write(args.output / "desktop_timing.csv", timing_rows)
    csv_write(args.output / "desktop_memory.csv", memory_rows)
    csv_write(args.output / "quality_metrics.csv", quality_rows)
    json_write(args.output / "pairwise_differences.json", pairwise)
    json_write(args.output / "output_hashes.json", hashes)
    json_write(args.output / "run_manifest.json", {
        "tool_version": TOOL_VERSION, "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "torch": torch.__version__, "python": sys.version, "platform": platform.platform(),
        "gpu_synchronization": True, "devices": [d.type for d in devices],
        "precisions": args.precisions, "repeats": args.repeats,
        "display_transform": "linear Rec.2020 to linear sRGB matrix; exposure x2; sRGB transfer; clip [0,1]",
        "numerical_outputs": "little-endian float32 HWC; display PNGs are not numerical references",
    })
    return 0


def command_projections(args: argparse.Namespace) -> int:
    rows = list(csv.DictReader(args.timing.open(encoding="utf-8")))
    # Use per-variant median across samples for each backend/precision.
    projections = []
    for variant in VARIANTS:
        matching = [float(r["warm_median_ms"]) for r in rows if r["variant"] == variant
                    and r["backend"] == args.backend and r["precision"] == args.precision]
        if not matching:
            continue
        tile_ms = statistics.median(matching)
        for width, height in ((5240, 3912), (4032, 3024), (5184, 3888), (1920, 1080),
                              (512, 512), (1024, 1024)):
            nx, ny, count = tile_count(width, height)
            projections.append({
                "variant": variant, "width": width, "height": height, "tile": TILE,
                "overlap": OVERLAP, "stride": STRIDE, "tiles_x": nx, "tiles_y": ny,
                "tile_count": count, "measured_tile_median_ms": tile_ms,
                "projected_pure_inference_ms": tile_ms * count,
                "measurement_status": "PROJECTED FROM MEASURED 256x256 TILE TIME",
                "preprocessing_ms": "NOT MEASURED", "reconstruction_ms": "NOT MEASURED",
                "cold_initialization_ms": "see desktop_timing.csv model_load_ms",
                "thermal_cache_memory_io_caveat": True,
            })
    csv_write(args.output, projections)
    return 0


def command_blind(args: argparse.Namespace) -> int:
    from PIL import Image, ImageOps, ImageDraw
    rng = random.Random(args.seed)
    technical = args.results / "technical"
    destination = args.results / "blind"
    destination.mkdir(parents=True, exist_ok=True)
    manifest = {"seed": args.seed, "status": "DO NOT OPEN UNTIL REVIEW IS COMPLETE", "sets": []}
    for sample_dir in sorted(p for p in technical.iterdir() if p.is_dir()):
        names = [*VARIANTS, "mobile_tflite"]
        named = [(v, sample_dir / f"{v}.png") for v in names]
        named = [(v, p) for v, p in named if p.is_file()]
        rng.shuffle(named)
        codes = [f"{sample_dir.name}-{chr(65+i)}" for i in range(len(named))]
        tiles = []
        entries = []
        for code, (variant, source) in zip(codes, named):
            target = destination / f"{code}.png"
            target.write_bytes(source.read_bytes())
            image = Image.open(source).convert("RGB")
            canvas = ImageOps.expand(image, border=(0, 24, 0, 0), fill="white")
            ImageDraw.Draw(canvas).text((4, 4), code, fill="black")
            tiles.append(canvas)
            entries.append({"code": code, "variant": variant, "sha256": sha256(target)})
        if tiles:
            sheet = Image.new("RGB", (sum(i.width for i in tiles), max(i.height for i in tiles)), "white")
            x = 0
            for tile in tiles:
                sheet.paste(tile, (x, 0)); x += tile.width
            sheet.save(destination / f"{sample_dir.name}-contact-sheet.png")
        manifest["sets"].append({"sample": sample_dir.name, "entries": entries})
    json_write(args.results / "randomization-manifest.json", manifest)
    scoring = [{"sample": s["sample"], "code": e["code"], "noise_removal": "",
                "fine_detail_retention": "", "natural_texture": "", "color_stability": "",
                "artifacts": "", "overall_preference": ""}
               for s in manifest["sets"] for e in s["entries"]]
    csv_write(args.results / "blind-scoring-sheet.csv", scoring)
    reveal = args.results / "unblind.py"
    reveal.write_text("import json,sys\np=json.load(open(sys.argv[1],encoding='utf-8'))\n"
                      "[print(s['sample'],*(f\"{e['code']}={e['variant']}\" for e in s['entries'])) for s in p['sets']]\n",
                      encoding="utf-8", newline="\n")
    return 0


def command_self_test(_: argparse.Namespace) -> int:
    assert tile_count(256, 256) == (1, 1, 1)
    assert tile_count(512, 512) == (3, 3, 9)
    assert tile_count(5220, 3912) == (22, 17, 374)
    array = np.arange(TILE * TILE * 3, dtype=np.float32).reshape(TILE, TILE, 3)
    assert numeric_difference(array, array)["rmse"] == 0
    assert condition_from_iso(6400) == 1.0
    assert condition_from_iso(65536) == 65535 / 6400
    print("PASS")
    return 0


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="command", required=True)
    inventory = sub.add_parser("inventory")
    inventory.add_argument("--models", type=Path, required=True)
    inventory.add_argument("--output", type=Path, required=True)
    inventory.set_defaults(func=command_inventory)
    run = sub.add_parser("run")
    run.add_argument("--models", type=Path, required=True)
    run.add_argument("--inputs", type=Path, required=True)
    run.add_argument("--output", type=Path, required=True)
    run.add_argument("--devices", nargs="+", default=["cuda", "cpu"])
    run.add_argument("--precisions", nargs="+", default=["fp32", "fp16"])
    run.add_argument("--repeats", type=int, default=5)
    run.set_defaults(func=command_run)
    projection = sub.add_parser("projections")
    projection.add_argument("--timing", type=Path, required=True)
    projection.add_argument("--output", type=Path, required=True)
    projection.add_argument("--backend", default="pytorch_cuda")
    projection.add_argument("--precision", default="fp32")
    projection.set_defaults(func=command_projections)
    blind = sub.add_parser("blind")
    blind.add_argument("--results", type=Path, required=True)
    blind.add_argument("--seed", type=int, default=45173)
    blind.set_defaults(func=command_blind)
    test = sub.add_parser("self-test")
    test.set_defaults(func=command_self_test)
    return root


def main() -> int:
    args = parser().parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
