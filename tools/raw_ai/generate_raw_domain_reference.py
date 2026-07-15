#!/usr/bin/env python3
"""Generate independent RawForge and production-LibRaw-domain crop references.

Numeric files are contiguous little-endian float32 HWC with no batch dimension. The RawForge
branch deliberately uses the installed upstream RawHandler package and colour-demosaicing code;
it shares no preprocessing implementation with Android.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import platform
import sys
from pathlib import Path

import exifread
import numpy as np
import rawpy
from colour_demosaicing import demosaicing_CFA_Bayer_Malvar2004
from RawHandler.utils import (
    make_colorspace_matrix,
    pixel_shuffle,
    pixel_unshuffle,
    safe_crop,
    transform_colorspace_to_rggb,
)


# LibRaw 0.22.0 src/tables/colordata.cpp, OM Digital Solutions "OM-5".
# Rawpy exposes the table as a 4x3 camera RGB/XYZ matrix with a zero fourth row
# for Bayer RGB cameras.  OM-5 Mark II files currently decode in rawpy/LibRaw
# 0.21.4 with a rank-zero matrix, so the alias must be explicitly requested.
OM5_RGB_XYZ_MATRIX = np.array(
    [
        [11896, -5110, -1076],
        [-3181, 11378, 2048],
        [-519, 1224, 5166],
        [0, 0, 0],
    ],
    dtype=np.float64,
) / 10000.0


def normalized_camera_model(value: str | None) -> str:
    return "".join(character for character in (value or "").upper() if character.isalnum())


def resolve_rgb_xyz_matrix(
    decoder_matrix: np.ndarray,
    camera_model: str | None,
    camera_alias: str | None,
) -> tuple[np.ndarray, dict]:
    matrix = np.asarray(decoder_matrix, dtype=np.float64)
    if matrix.shape != (4, 3):
        raise ValueError(f"Expected a 4x3 RGB/XYZ matrix, found {matrix.shape}")
    if np.linalg.matrix_rank(matrix[:3]) == 3:
        return matrix, {
            "status": "DECODER_MATRIX",
            "source_model": camera_model,
            "effective_model": camera_model,
            "matrix_source": "rawpy decoder metadata",
        }

    source = normalized_camera_model(camera_model)
    alias = normalized_camera_model(camera_alias)
    if source == "OM5MARKII" and alias == "OM5":
        return OM5_RGB_XYZ_MATRIX.copy(), {
            "status": "EXPLICIT_CAMERA_ALIAS_FALLBACK",
            "source_model": camera_model,
            "effective_model": "OM-5",
            "matrix_source": "LibRaw 0.22.0 colordata.cpp OM-5 entry",
            "reason": "rawpy/LibRaw 0.21.4 returns a rank-zero matrix for OM-5 Mark II",
        }

    raise ValueError(
        f"Decoder returned a rank-deficient RGB/XYZ matrix for {camera_model!r}. "
        "An explicitly supported --camera-alias is required; no matrix was guessed."
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def stats(tensor: np.ndarray) -> dict:
    flat = tensor.reshape(-1).astype(np.float64)
    finite = np.isfinite(flat)
    channel = []
    for index, name in enumerate("RGB"):
        values = tensor[:, :, index].astype(np.float64)
        channel.append(
            {
                "name": name,
                "minimum": float(np.min(values)),
                "maximum": float(np.max(values)),
                "mean": float(np.mean(values)),
                "standard_deviation": float(np.std(values)),
            }
        )
    return {
        "element_count": int(flat.size),
        "finite_count": int(finite.sum()),
        "nan_count": int(np.isnan(flat).sum()),
        "positive_infinity_count": int(np.isposinf(flat).sum()),
        "negative_infinity_count": int(np.isneginf(flat).sum()),
        "minimum": float(np.min(flat)),
        "maximum": float(np.max(flat)),
        "mean": float(np.mean(flat)),
        "standard_deviation": float(np.std(flat)),
        "percentiles": {str(p): float(np.percentile(flat, p)) for p in (0, 1, 5, 50, 95, 99, 100)},
        "below_zero_percent": float(np.mean(flat < 0) * 100.0),
        "above_one_percent": float(np.mean(flat > 1) * 100.0),
        "equal_zero_percent": float(np.mean(flat == 0) * 100.0),
        "equal_one_percent": float(np.mean(flat == 1) * 100.0),
        "per_channel": channel,
    }


def exif_value(tags: dict, name: str) -> str | None:
    value = tags.get(name)
    return str(value).strip() if value is not None else None


def metadata(raw_path: Path, raw: rawpy.RawPy) -> dict:
    with raw_path.open("rb") as stream:
        tags = exifread.process_file(stream, details=False)
    sizes = raw.sizes
    pattern = "".join("RGBG"[int(value)] for value in raw.raw_pattern.flatten())
    return {
        "file_name": raw_path.name,
        "file_type": "Olympus/OM SYSTEM ORF RAW" if raw_path.suffix.lower() == ".orf" else raw_path.suffix,
        "byte_count": raw_path.stat().st_size,
        "sha256": sha256(raw_path),
        "camera_make": exif_value(tags, "Image Make"),
        "camera_model": exif_value(tags, "Image Model"),
        "iso": exif_value(tags, "EXIF ISOSpeedRatings"),
        "exposure_time": exif_value(tags, "EXIF ExposureTime"),
        "aperture": exif_value(tags, "EXIF FNumber"),
        "focal_length": exif_value(tags, "EXIF FocalLength"),
        "orientation": exif_value(tags, "Image Orientation"),
        "raw_width": sizes.raw_width,
        "raw_height": sizes.raw_height,
        "visible_width": sizes.width,
        "visible_height": sizes.height,
        "processed_width": sizes.iwidth,
        "processed_height": sizes.iheight,
        "active_area": {
            "left": sizes.left_margin,
            "top": sizes.top_margin,
            "width": sizes.width,
            "height": sizes.height,
        },
        "cfa_pattern": pattern,
        "active_area_parity": [sizes.left_margin % 2, sizes.top_margin % 2],
        "black_levels": list(map(int, raw.black_level_per_channel)),
        "white_level": int(raw.white_level),
        "bit_depth_container": 16,
        "compression": "decoder-reported ORF; exact maker compression mode not exposed by rawpy",
        "camera_white_balance": [float(value) for value in raw.camera_whitebalance],
        "rgb_xyz_matrix": np.asarray(raw.rgb_xyz_matrix).astype(float).tolist(),
    }


def crop_rect(width: int, height: int, size: int, name: str, explicit_x: int | None, explicit_y: int | None) -> tuple[int, int, int, int]:
    if size <= 0 or size > width or size > height:
        raise ValueError(f"Invalid crop size {size} for {width}x{height}")
    margin = 32
    if explicit_x is not None or explicit_y is not None:
        if explicit_x is None or explicit_y is None:
            raise ValueError("Both --x and --y are required")
        x, y = explicit_x, explicit_y
    elif name == "center":
        x, y = (width - size) // 2, (height - size) // 2
    elif name == "top-left":
        x, y = margin, margin
    elif name == "top-right":
        x, y = width - size - margin, margin
    elif name == "bottom-left":
        x, y = margin, height - size - margin
    elif name == "bottom-right":
        x, y = width - size - margin, height - size - margin
    else:
        raise ValueError(f"Unsupported crop name: {name}")
    x -= x % 2
    y -= y % 2
    if x < 0 or y < 0 or x + size > width or y + size > height:
        raise ValueError(f"Crop {(x, y, size, size)} outside {width}x{height}")
    return x, y, size, size


def rawforge_tensor(
    raw_path: Path,
    rect: tuple[int, int, int, int],
    rgb_xyz_matrix: np.ndarray,
) -> np.ndarray:
    x, y, width, height = rect
    with rawpy.imread(str(raw_path)) as raw:
        mosaic = raw.raw_image_visible
        pattern = "".join("RGBG"[int(value)] for value in raw.raw_pattern.flatten())
        offsets = {"RGGB": (0, 0), "BGGR": (1, 1), "GBRG": (0, 1), "GRBG": (1, 0)}
        if pattern not in offsets:
            raise ValueError(f"Unsupported Bayer pattern: {pattern}")
        dx, dy = offsets[pattern]
        mosaic = safe_crop(mosaic, dx=dx, dy=dy)
        mosaic = mosaic[: mosaic.shape[0] - mosaic.shape[0] % 2, : mosaic.shape[1] - mosaic.shape[1] % 2]
        crop = np.expand_dims(mosaic[y : y + height, x : x + width], axis=0).astype(np.float32)

        channel_map = np.zeros_like(crop, dtype=np.int8)
        channel_map[0, 0::2, 0::2] = 0
        channel_map[0, 0::2, 1::2] = 1
        channel_map[0, 1::2, 0::2] = 3
        channel_map[0, 1::2, 1::2] = 2
        black = np.asarray(raw.black_level_per_channel, dtype=np.float32)
        white = float(raw.white_level)
        for channel in range(4):
            mask = channel_map == channel
            crop[mask] = (crop[mask] - black[channel]) / (white - black[channel])

        camera_to_xyz = np.linalg.inv(np.asarray(rgb_xyz_matrix[:3], dtype=np.float64))
        camera_to_rec2020 = make_colorspace_matrix(camera_to_xyz, colorspace="lin_rec2020")
        rggb_transform = transform_colorspace_to_rggb(camera_to_rec2020)
        rggb = pixel_unshuffle(crop, 2)
        transformed = (rggb_transform @ rggb.reshape(4, -1)).reshape(rggb.shape)
        transformed_mosaic = pixel_shuffle(transformed, 2)
        chw = demosaicing_CFA_Bayer_Malvar2004(transformed_mosaic).transpose(2, 0, 1)
        chw = np.clip(chw, 0, 1)
    # RawForge expands this CHW array and casts to float16 before inference. Preserve those exact
    # quantized values while exporting the canonical float32 interchange representation.
    return np.ascontiguousarray(chw.astype(np.float16).astype(np.float32).transpose(1, 2, 0))


def libraw_dcraw_tensor(raw: rawpy.RawPy, rect: tuple[int, int, int, int]) -> np.ndarray:
    x, y, width, height = rect
    image_u16 = raw.postprocess(
        output_bps=16,
        use_camera_wb=True,
        no_auto_bright=True,
        gamma=(1, 1),
        user_flip=None,
    )
    crop = image_u16[y : y + height, x : x + width, :3]
    return np.ascontiguousarray(crop.astype(np.float32) / 65535.0)


def run_tflite(model_path: Path, tensor: np.ndarray, condition: float) -> np.ndarray:
    try:
        import tensorflow as tf
        Interpreter = tf.lite.Interpreter
        interpreter_kwargs = {"experimental_op_resolver_type": tf.lite.experimental.OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES}
    except ImportError:
        from tflite_runtime.interpreter import Interpreter
        interpreter_kwargs = {}
    # model_content also avoids Windows runtime failures on non-ASCII workspace paths.
    interpreter = Interpreter(model_content=model_path.read_bytes(), num_threads=1, **interpreter_kwargs)
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    image_detail = next(item for item in inputs if "input" in item["name"])
    cond_detail = next(item for item in inputs if "cond" in item["name"])
    interpreter.set_tensor(image_detail["index"], tensor[np.newaxis].astype(np.float32))
    interpreter.set_tensor(cond_detail["index"], np.array([[condition]], dtype=np.float32))
    interpreter.invoke()
    return np.ascontiguousarray(interpreter.get_tensor(interpreter.get_output_details()[0]["index"]).astype(np.float32))


def write_tensor(output_dir: Path, stem: str, stage: str, tensor: np.ndarray, contract: dict) -> dict:
    path = output_dir / f"{stem}.{stage}.f32le.bin"
    little = np.ascontiguousarray(tensor.astype("<f4", copy=False))
    little.tofile(path)
    return {
        "file": path.name,
        "stage": stage,
        "width": int(tensor.shape[1]),
        "height": int(tensor.shape[0]),
        "channels": int(tensor.shape[2]),
        "layout": "HWC",
        "dtype": "float32",
        "endianness": "little",
        "channel_order": "RGB",
        "element_count": int(tensor.size),
        "byte_count": path.stat().st_size,
        "sha256": sha256(path),
        "contract": contract,
        "statistics": stats(tensor),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--crop", choices=("center", "top-left", "top-right", "bottom-left", "bottom-right"), default="center")
    parser.add_argument("--crop-size", type=int, default=256)
    parser.add_argument("--x", type=int)
    parser.add_argument("--y", type=int)
    parser.add_argument("--model", type=Path)
    parser.add_argument(
        "--camera-alias",
        choices=("OM-5",),
        help="Explicit camera color-matrix fallback; currently supports OM-5 Mark II as OM-5",
    )
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    raw_path = args.raw.resolve()
    script_path = Path(__file__).resolve()
    with rawpy.imread(str(raw_path)) as raw:
        source = metadata(raw_path, raw)
        effective_matrix, matrix_policy = resolve_rgb_xyz_matrix(
            raw.rgb_xyz_matrix,
            source["camera_model"],
            args.camera_alias,
        )
        source["effective_rgb_xyz_matrix"] = effective_matrix.astype(float).tolist()
        source["color_matrix_policy"] = matrix_policy
        rect = crop_rect(raw.sizes.iwidth, raw.sizes.iheight, args.crop_size, args.crop, args.x, args.y)
        authoritative = rawforge_tensor(raw_path, rect, effective_matrix)
        production = libraw_dcraw_tensor(raw, rect)

    stem = raw_path.stem
    crop = {
        "coordinate_space": "visible-active-area, sensor orientation, before display rotation",
        "name": args.crop,
        "x": rect[0],
        "y": rect[1],
        "width": rect[2],
        "height": rect[3],
        "orientation_transform": "identity",
    }
    manifest = {
        "schema_version": 1,
        "environment": {
            "python": sys.version,
            "platform": platform.platform(),
            "rawpy": rawpy.__version__,
            "rawpy_libraw": list(rawpy.libraw_version),
            "numpy": np.__version__,
            "RawHandler": importlib.metadata.version("rawhandler"),
            "colour_demosaicing": importlib.metadata.version("colour-demosaicing"),
            "script_sha256": sha256(script_path),
        },
        "source": source,
        "crop": crop,
        "tensors": [],
        "conditioning": {
            "oracle": 0.0,
            "upstream_formula": "min(ISO, 65535) / 6400.0",
            "upstream_iso_value": float(source["iso"]) if source["iso"] else None,
        },
    }
    manifest["tensors"].append(
        write_tensor(
            args.output_dir,
            stem,
            "rawforge_malvar_linrec2020",
            authoritative,
            {
                "active_area": "rawpy raw_image_visible; CFA phase normalized to RGGB by safe crop",
                "black_level": "per-CFA (sample-black)/(white-black)",
                "demosaic": "colour_demosaicing_CFA_Bayer_Malvar2004",
                "white_balance": "unity",
                "color_matrix": "camera RGB to linear Rec.2020 applied on Bayer planes before demosaic",
                "gamma": "none",
                "clipping": "clip [0,1] before float16 model-input quantization",
                "upstream_model_dtype": "float16; exported values losslessly widened to float32",
            },
        )
    )
    manifest["tensors"].append(
        write_tensor(
            args.output_dir,
            stem,
            "android_libraw_dcraw_equivalent",
            production,
            {
                "libraw_parameters": {
                    "output_bps": 16,
                    "use_camera_wb": True,
                    "no_auto_bright": True,
                    "gamma": [1, 1],
                    "output_color": "implicit LibRaw default sRGB",
                    "demosaic": "implicit LibRaw default AHD",
                    "user_flip": "metadata default; sample orientation is identity",
                },
                "scale": "processed uint16 / 65535",
            },
        )
    )

    if args.model:
        model = args.model.resolve()
        manifest["model"] = {"file_name": model.name, "sha256": sha256(model)}
        iso = float(source["iso"]) if source["iso"] else 0.0
        for input_name, tensor in (("rawforge", authoritative), ("android_equivalent", production)):
            for cond_name, cond in (("cond0", 0.0), ("iso_over_6400", min(iso, 65535.0) / 6400.0)):
                output = run_tflite(model, tensor, cond)[0].transpose(1, 2, 0)
                manifest["tensors"].append(
                    write_tensor(args.output_dir, stem, f"output_{input_name}_{cond_name}", output, {"condition": cond, "model_output_source_layout": "NCHW"})
                )

    manifest_path = args.output_dir / f"{stem}.manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8", newline="\n")
    print(json.dumps({"manifest": str(manifest_path), "source": source, "crop": crop, "tensor_count": len(manifest["tensors"])}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
