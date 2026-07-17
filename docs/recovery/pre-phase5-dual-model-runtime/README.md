# Pre-Phase 5 selective recovery snapshot

Created before Phase 5 dual-model runtime edits. This snapshot intentionally contains only
RAW-AI source/configuration files that Phase 5 may change. Model binaries are not duplicated.

## Immutable model references at snapshot time

| Asset | Bytes | SHA-256 |
|---|---:|---|
| `app/src/main/assets/raw_ai/model_fp32.tflite` | 1,429,376 | `0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806` |
| `app/src/main/assets/raw_ai/model_fp16.tflite` | 817,936 | `03842593e1295f94e44d3cab6bc3f7fae2022941f0659d54233114398d1376b3` |

The FP32 asset is the known-working SuperLight artifact that Phase 5 must preserve byte-for-byte.
The FP16-I/O artifact is recorded only for recovery and is not eligible for Phase 5 packaging.
