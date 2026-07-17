#!/usr/bin/env python3
"""Finalize stable RawForge tensor names without changing graph computation."""

from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path

import flatbuffers


def load_schema(path: Path):
    spec = importlib.util.spec_from_file_location("rawforge_tflite_schema", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load schema: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    schema = load_schema(args.schema)
    model = schema.Model.GetRootAsModel(args.input.read_bytes(), 0)
    editable = schema.ModelT.InitFromObj(model)
    graph = editable.subgraphs[0]

    found: set[str] = set()
    for index in graph.inputs:
        tensor = graph.tensors[index]
        shape = list(tensor.shape)
        if shape == [1, 256, 256, 3]:
            tensor.name = b"input"
            found.add("input")
        elif shape == [1, 1]:
            tensor.name = b"cond"
            found.add("cond")
    if len(graph.outputs) != 1:
        raise ValueError(f"Expected one output, found {len(graph.outputs)}")
    output = graph.tensors[graph.outputs[0]]
    if list(output.shape) != [1, 3, 256, 256]:
        raise ValueError(f"Expected NCHW output, found {list(output.shape)}")
    output.name = b"output"
    found.add("output")
    if found != {"input", "cond", "output"}:
        raise ValueError(f"Incomplete tensor contract: {sorted(found)}")

    builder = flatbuffers.Builder(0)
    root = editable.Pack(builder)
    builder.Finish(root, file_identifier=b"TFL3")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(builder.Output())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
