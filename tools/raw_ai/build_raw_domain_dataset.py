#!/usr/bin/env python3
"""Build or aggregate an auditable multi-size RAW-domain reference dataset."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-dir", type=Path, required=True)
    parser.add_argument("--reference-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--crop-sizes", type=int, nargs="+", default=[256, 512, 1024])
    parser.add_argument("--camera-alias", choices=("OM-5",))
    parser.add_argument("--generate", action="store_true")
    args = parser.parse_args()

    raw_files = sorted(args.raw_dir.resolve().glob("*.ORF"))
    if not raw_files:
        raise FileNotFoundError(f"No ORF files found in {args.raw_dir}")
    generator = Path(__file__).with_name("generate_raw_domain_reference.py")
    samples: dict[str, dict] = {}

    for size in args.crop_sizes:
        size_dir = args.reference_dir.resolve() / str(size)
        size_dir.mkdir(parents=True, exist_ok=True)
        for raw_file in raw_files:
            manifest_path = size_dir / f"{raw_file.stem}.manifest.json"
            if args.generate:
                command = [
                    sys.executable,
                    str(generator),
                    str(raw_file),
                    "--output-dir",
                    str(size_dir),
                    "--crop",
                    "center",
                    "--crop-size",
                    str(size),
                ]
                if args.camera_alias:
                    command += ["--camera-alias", args.camera_alias]
                subprocess.run(command, check=True)
            if not manifest_path.is_file():
                raise FileNotFoundError(f"Missing reference manifest: {manifest_path}")
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            source = manifest["source"]
            sample = samples.setdefault(
                source["file_name"],
                {
                    "file_name": source["file_name"],
                    "sha256": source["sha256"],
                    "camera_make": source["camera_make"],
                    "camera_model": source["camera_model"],
                    "effective_camera_model": source["color_matrix_policy"]["effective_model"],
                    "iso": int(source["iso"]),
                    "exposure_time": source["exposure_time"],
                    "dimensions": [source["visible_width"], source["visible_height"]],
                    "cfa_pattern": source["cfa_pattern"],
                    "black_levels": source["black_levels"],
                    "white_level": source["white_level"],
                    "color_matrix_policy": source["color_matrix_policy"],
                    "crops": [],
                },
            )
            if sample["sha256"] != source["sha256"]:
                raise ValueError(f"Source hash changed across manifests: {source['file_name']}")
            tensors = {entry["stage"]: entry for entry in manifest["tensors"]}
            sample["crops"].append(
                {
                    **manifest["crop"],
                    "rawforge_tensor": {
                        "sha256": tensors["rawforge_malvar_linrec2020"]["sha256"],
                        "byte_count": tensors["rawforge_malvar_linrec2020"]["byte_count"],
                    },
                    "production_diagnostic_tensor": {
                        "sha256": tensors["android_libraw_dcraw_equivalent"]["sha256"],
                        "byte_count": tensors["android_libraw_dcraw_equivalent"]["byte_count"],
                    },
                }
            )

    dataset = {
        "schema_version": 1,
        "status": "FROZEN_REFERENCE_HASHES",
        "pair_status": "ISO SERIES WITHOUT A CLEAN GROUND-TRUTH PAIR",
        "source_policy": "Original RAW files remain external and are never committed",
        "camera_alias_policy": {
            "source_model": "OM-5MarkII",
            "effective_model": "OM-5",
            "authorization": "explicit project owner approval",
            "matrix_source": "LibRaw 0.22.0 colordata.cpp OM-5 entry",
        },
        "contract": {
            "coordinate_space": "visible active area, sensor-native identity orientation",
            "preprocessing": "per-CFA normalization; camera Bayer planes to linear Rec.2020; Malvar2004; clip [0,1]; float16 round-trip; HWC",
            "condition": "min(ISO,65535)/6400",
            "crop_sizes": args.crop_sizes,
        },
        "sample_count": len(samples),
        "samples": sorted(samples.values(), key=lambda item: (item["iso"], item["file_name"])),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(dataset, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"output": str(args.output), "samples": len(samples), "crops": len(samples) * len(args.crop_sizes)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
