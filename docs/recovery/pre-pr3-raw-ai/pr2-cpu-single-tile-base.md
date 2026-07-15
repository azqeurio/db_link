# PR 2: CPU single-tile base and LiteRT parity

## Starting repository state

The audit began on branch `main` at `d907fb2`. The worktree already contained extensive unrelated application changes and untracked files; they were preserved. PR 1 assets were present and internally consistent: both model hashes, all reference hashes, shapes, dtypes, byte counts, and little-endian encoding match their manifests.

Partial PR 2 work included model assets and manifests, golden Android test assets, `RawAiController`, native runtime/backend scaffolding, TensorFlow Lite dependencies, and `CpuParityTest`. The original test did not bind the condition asset, did not query runtime tensor metadata, lacked non-finite/per-channel/determinism metrics, and called an incomplete production/native path. The native path also treated flatbuffer tensor IDs from the manifest as interpreter I/O indices and included GPU work outside PR 2 scope.

## Reused and changed work

Reused without regeneration:

- `app/src/main/assets/raw_ai/model_fp32.tflite`
- `app/src/main/assets/raw_ai/model_fp16.tflite` (preserved but not executed)
- `app/src/main/assets/raw_ai/model_manifest.json`
- all `app/src/androidTest/assets/raw_ai/reference/` files
- the existing package and `CpuParityTest` location
- the existing versioned internal-storage convention

Implemented:

- `ModelAssetStore` now performs SHA-versioned, app-internal staging with valid-file reuse, a `.partial` copy, pre-install and post-install SHA-256 checks, and clear failures.
- `RawAiController` reuses the same hardened model store.
- `CpuParityTest` performs real multi-input/multi-output Android inference directly through the selected Java runtime, compares native NCHW output, validates every binary and tensor contract field, reports all required metrics, and checks consecutive outputs.
- GPU dependencies and the incomplete native RAW-AI backend were removed from the active PR 2 build path. Its source scaffolding remains preserved for later work.

## Runtime and storage

- Runtime artifact: `org.tensorflow:tensorflow-lite:2.16.1` (the repository's already-selected LiteRT/TFLite runtime)
- Test dependency scope: `androidTestImplementation`
- CPU threads: 1
- NNAPI: disabled
- XNNPACK: disabled
- Explicit delegates: none
- Tested ABI/device: arm64-v8a, Samsung SM-S936W, Android API 36
- Internal path: `/data/user/0/dev.dblink.debug/files/raw_ai/models/<sha256>/model_fp32.tflite`
- Model size: 1,429,376 bytes
- Model SHA-256: `0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806`

The model copy is reused only when its SHA-256 is valid. Copying occurs through a temporary `.partial` file, which is never accepted as the installed model.

## Tensor and reference contract

| I/O slot | Name | Shape | Layout | Dtype |
|---|---|---|---|---|
| input 0 | `input` | `[1, 256, 256, 3]` | NHWC | FLOAT32 |
| input 1 | `cond` | `[1, 1]` | scalar condition | FLOAT32 |
| output 0 | `output` | `[1, 3, 256, 256]` | NCHW | FLOAT32 |

The three tile-01 assets are little-endian raw IEEE-754 float32. Input and output each contain 196,608 elements / 786,432 bytes; condition contains one element / 4 bytes. The runtime output is compared directly in NCHW order with no image conversion, transpose, clamp, color processing, or quantization.

The condition value is `0.0`. This reproduces the `constant_zero` contract; CPU parity does not semantically prove what `cond` means.

## Thresholds

- actual NaN, positive infinity, and negative infinity counts: 0
- maximum absolute error: `<= 1e-4`
- mean absolute error: `<= 1e-5`
- RMSE: `<= 1e-5`
- run-to-run maximum absolute difference: `<= 1e-7`
- run-to-run mean absolute difference: `<= 1e-8`

## Android parity result

The instrumented test passed on the connected SM-S936W. One warm-up was separated from two consecutive measured/reference runs.

| Metric | Result |
|---|---:|
| element count | 196,608 |
| finite actual elements | 196,608 |
| NaN / +Inf / -Inf | 0 / 0 / 0 |
| max absolute error | 3.5762786865234375e-7 |
| mean absolute error | 4.496723704505712e-8 |
| RMSE | 6.30288363784565e-8 |
| reference min / max / mean | 0.26532474160194397 / 0.7878972291946411 / 0.5103608072806006 |
| actual min / max / mean | 0.26532477140426636 / 0.7878972887992859 / 0.5103608088913157 |
| largest-error flat index | 9,904 |
| largest-error NCHW coordinate | `[0, 0, 38, 176]` |
| reference / actual at largest error | 0.5492414 / 0.54924107 |
| measured inference duration | 115 ms |

Per-channel results:

| Channel | Max absolute error | MAE | RMSE |
|---:|---:|---:|---:|
| 0 | 3.5762786865234375e-7 | 5.2779341785935685e-8 | 7.156437210439936e-8 |
| 1 | 3.5762786865234375e-7 | 3.921149982488714e-8 | 5.632492813521372e-8 |
| 2 | 2.980232238769531e-7 | 4.291086952434853e-8 | 6.019921728338998e-8 |

Determinism was bit-identical: run-to-run maximum and mean absolute differences were both `0.0`.

## Commands and results

- `:app:compileDebugKotlin`: PASS
- `:app:compileDebugAndroidTestKotlin`: PASS
- `:app:testDebugUnitTest`: PASS
- `:app:lintDebug`: PASS
- `:app:assembleDebug`: PASS
- `:app:assembleDebugAndroidTest`: PASS
- `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.dblink.core.rawai.CpuParityTest`: initial Gradle install attempt BLOCKED by `INSTALL_FAILED_VERSION_DOWNGRADE` (device had version code 10; worktree is 6)
- Manual install with `adb install -r -d`, followed by `adb shell am instrument -w -r -e class dev.dblink.core.rawai.CpuParityTest dev.dblink.debug.test/androidx.test.runner.AndroidJUnitRunner`: PASS, 1 test

The build used in-process, non-incremental Kotlin compilation to avoid a pre-existing Windows daemon fallback/path-encoding failure for the Korean workspace path.

## Limitations and PR 3 boundary

This PR does not integrate the model into the production RAW editing pipeline. It does not validate FP16, GPU/NPU execution, full-image tiling, overlap/blending, performance, UI, export, camera transfer, RAW color science, or conditioning semantics. The preserved native/backend scaffolding is not an active or validated production runtime.

PR 3 should start from a separately reviewed production integration design, consuming this proven FP32 CPU contract and model store without changing the reference comparison path.
