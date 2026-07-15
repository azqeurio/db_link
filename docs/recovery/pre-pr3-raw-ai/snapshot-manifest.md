# Pre-PR 3 RAW-AI recovery snapshot

Created before PR 3 implementation from branch `main` at `d907fb2`.

This directory contains copies of the verified PR 2 Gradle configuration, manifests, model store, controller, CPU parity test, and PR 2 report. Large model/reference binaries are intentionally not duplicated; their recovery hashes are:

- `model_fp32.tflite`: `0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806`
- `model_fp16.tflite`: `03842593e1295f94e44d3cab6bc3f7fae2022941f0659d54233114398d1376b3`
- `input_tile_01.bin`: `24f4e8429bfe3528558cc91468222df5b89890771587b20ca2f90ae05a39c585`
- `condition_01.bin`: `df3f619804a92fdb4057192dc43dd748ea778adc52bc498ce80524c014b81119`
- `output_fp32_01.bin`: `418e515291ea8d11a1f131fa1e43a20de7dfa30e92f247bdea435a39974e13d2`
- `reference_manifest.json`: `8b3ea2407e727ceeef30d929946a926938ee997ba832b00cecea20ebf81c8ec7`

No unrelated application files are included.
