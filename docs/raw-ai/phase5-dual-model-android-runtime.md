# Phase 5 — Dual-model Android RAW AI runtime

Status vocabulary: **PASS**, **FAIL**, **BLOCKED**, **NOT RUN**, **NOT AVAILABLE**.

## 1. Starting repository state

**PASS.** Work started on `main` in an already dirty worktree. Phase 5 preserved unrelated changes and touched only RAW-AI assets, runtime/tests, conversion tools, recovery documentation, and this report. No commit or push was made.

## 2. Verified commit state

**PASS.** Initial `HEAD` and `origin/main` were both `d0a14a2149bfa38f7d5e5ca777f8c912964d316c`. The initial targeted audit found no staged files. The repository-wide status included pre-existing CRLF/status noise and unrelated user changes.

## 3. Recovery snapshot

**PASS.** `docs/recovery/pre-phase5-dual-model-runtime/` contains a selective pre-edit copy of the RAW-AI files changed by Phase 5 plus immutable model hash references. Model binaries were not duplicated.

## 4. Existing SuperLight provenance

**PASS.** The authoritative checkpoint is `ShadowWeightedL1_super_light.pt`, 1,799,906 bytes, SHA-256 `e05e460e02fbeb745ab2984edf4f4898c5035f6a475183be4ee16f52226b45b1`. The authoritative dynamic ONNX is 835,451 bytes, SHA-256 `1684f549fec52812ffacc3020bfd61c5fde610e77460fcc03b2a7003f61e60dd`; the static-shape copy is SHA-256 `e63bfc013dd9fa6d359395e16ff2ebb65049400a7a53f373be504b186c8f6035`.

## 5. Existing SuperLight preservation

**PASS.** The packaged file was renamed to `rawforge_superlight_fp32.tflite`, but its 1,429,376 bytes and SHA-256 `0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806` are unchanged. Android golden parity, device tensor contract, deterministic execution, tiled reconstruction, and 5240×3912 full-resolution execution all passed after the registry/runtime changes.

## 6. Standard source provenance

**PASS.** The authoritative checkpoint is `ShadowWeightedL1.pt`, 20,264,214 bytes, SHA-256 `eb65f9703aef24f2bf35082a93e8caafd38d0953a2e6557e7c37b77d483fca05`, with 4,938,147 parameters. It was exported with PyTorch 2.11.0, opset 18, static NCHW image `[1,3,256,256]`, condition `[1,1]`, and NCHW output `[1,3,256,256]`. The FP32 ONNX used for conversion is 19,876,303 bytes, SHA-256 `9424fb8f90e9ec919d54693d7ee98ef283e43ea4d0356ca88ed82fadabb80638`.

## 7. Standard FP32 conversion

**PASS.** onnx2tf 2.6.3 produced the TensorFlow SavedModel and initial TFLite graph. A documented TensorFlow boundary wrapper preserves the required NCHW output, and a FlatBuffer object-model pass sets stable public tensor labels without changing graph computation. Final asset: 19,843,372 bytes, SHA-256 `918865821c35f7404c9fca8cb6cfb51360a210fb1b46f2ab4ad7ab3328434540`.

## 8. Standard conversion parity

**PASS.** Random-tensor FP32 ONNX versus final TFLite: maximum absolute error `7.152557e-7`, mean absolute error `9.395558e-8`, RMSE `1.199871e-7`, correlation `0.9999999999987623`; all 196,608 output values were finite.

## 9. FP16 reconversion result

**PASS (experiment completed; not packaged).** FP16-weight/FLOAT32-I/O candidates succeeded for both models. True FLOAT16 external I/O failed honestly: TensorFlow 2.21 rejects `inference_input_type`/`inference_output_type` other than FLOAT32 for this conversion mode, and converting a FLOAT16 ONNX through onnx2tf still emitted FLOAT32 external tensors. The earlier true-FP16 SuperLight artifact was removed from assets and remains recorded only by recovery hash.

## 10. Dual-model manifest

**PASS.** Manifest contract version 3 contains exactly `standard` and `superlight`; it records source checkpoint identity, asset hash, stable ID, variant-specific tensor indices, shared preprocessing/condition/tiling, CPU-only validation, `production_enabled=false`, `automatic_model_selection=false`, and `silent_model_fallback=false`.

## 11. Model IDs

**PASS.** The only enum values are `RAWFORGE_STANDARD_FP32` and `RAWFORGE_SUPERLIGHT_FP32`. Unit tests assert this exact two-item registry.

## 12. Model-store changes

**PASS.** Assets are staged into SHA-addressed app-private directories. Different models cannot collide because the destination includes the full model SHA-256 and filename. Standard and SuperLight staged paths observed on-device were distinct.

## 13. Runtime architecture

**PASS.** `RawAiController.initialize(modelId)` stages and opens exactly the requested model. Requests also carry a model ID and fail with `EXPLICIT_MODEL_SELECTION_REQUIRED` if it differs from the initialized session. Native LiteRT/TFLite resolves `input`, `cond`, and `output` by stable tensor name, then validates shapes and FLOAT32 dtypes; graph-specific tensor indices are no longer assumed.

## 14. Legacy-vs-LiteRT parity

**PASS.** The Android oracle remains `org.tensorflow:tensorflow-lite:2.16.1`, CPU only, NNAPI off, XNNPACK explicitly off in instrumentation. Desktop inspection/comparison used ai-edge-litert 2.1.2. Across the 12 ISO tiles, desktop-versus-device maximum error was `1.79e-7` for SuperLight and `1.49e-7` for Standard; minimum correlation rounded to `1.0`.

## 15. Shared Bayer preprocessing

**PASS.** Both model IDs consume the same `rawforge-bayer-rec2020-malvar-v1` tensor: visible Bayer active area, per-CFA black subtraction/normalization, camera RGB→linear Rec.2020 on Bayer planes, Malvar2004, unity WB, clamp, and binary16 round-trip before widened FLOAT32 input. OM-5 Mark II used the explicitly authorized OM-5 color-matrix alias.

## 16. Shared condition handling

**PASS.** Kotlin and native paths use `min(ISO,65535)/6400`; invalid negative/non-finite ISO is rejected at the Kotlin boundary. ISO 200–25600 produced conditions `0.03125` through `4.0`.

## 17. Shared tiling and streaming

**PASS.** Both models use the same planner, extraction, overlap blending, bounded-row reconstruction, progress, cancellation, and output sink. Full ORF runs used tile 256, overlap 16, stride 240, replicate padding, 22×17 = 374 tiles, and at most 256 retained reconstruction rows.

## 18. No-silent-fallback behavior

**PASS.** No automatic model selection or silent fallback was introduced. A mismatched request fails; it does not substitute SuperLight. CPU is the only backend policy. GPU, NNAPI, QNN, and NPU were not enabled.

## 19. Cache identity

**PASS.** Staged-model cache identity includes the model SHA-256. Output names include model key. The switching test executed SuperLight→Standard→SuperLight and reproduced the first SuperLight output exactly (`max=0.0`) while Standard differed (`max=0.3560691`).

## 20. Single-tile device results

**PASS.** SM-S936W, Android 16/API 36, ARM64. Both contracts were exactly `input [1,256,256,3] FLOAT32`, `cond [1,1] FLOAT32`, `output [1,3,256,256] FLOAT32`. Standard repeated exactly (`max=0.0`); Standard versus SuperLight on the regression tile differed as expected (`max=0.3670241`, mean `0.0781732`).

| Model | Precision | Condition | Desktop median across ISO tiles | Phone typical median across ISO tiles | Desktop/device parity | Model memory indicator |
|---|---|---:|---:|---:|---|---:|
| SuperLight | FP32 | ISO/6400 | 155.54 ms | ~54 ms (scheduler outliers present) | max `1.79e-7` | native heap ~58.7 MB in 1-thread matrix |
| Standard | FP32 | ISO/6400 | 552.32 ms | 181–247 ms | max `1.49e-7` | native heap ~186.2 MB in 1-thread matrix |

## 21. All-ISO device results

**PASS.** All 24 model/sample combinations completed, produced finite output, and recorded unique SHA-256 values.

| ISO | SuperLight median ms | Standard median ms |
|---:|---:|---:|
| 200 | 54.153 | 184.442 |
| 400 (`_7110218`) | 967.005* | 180.740 |
| 400 (`_7110219`) | 53.288 | 246.517 |
| 800 | 52.891 | 240.675 |
| 1000 | 53.815 | 237.054 |
| 2000 | 57.487 | 240.214 |
| 4000 | 53.016 | 238.492 |
| 6400 | 53.283 | 243.242 |
| 8000 | 54.273 | 224.566 |
| 10000 | 219.374* | 225.931 |
| 12800 | 140.894* | 230.668 |
| 25600 | 60.520 | 199.562 |

`*` OS/thermal/scheduler outlier: the paired duplicate ISO 400 sample and most SuperLight rows were near 53–60 ms; values are retained rather than filtered.

## 22. Tiled device results

**PASS.** Times below are measured, not projected, with the explicit one-thread regression runtime.

| Model | Resolution | Tiles | Pure inference ms | Total ms | Peak JVM heap |
|---|---:|---:|---:|---:|---:|
| Standard | 512×512 | 9 | 5,459.95 | 5,540.48 | 26,564,656 |
| Standard | 1024×1024 | 25 | 15,124.70 | 15,393.66 | 48,918,656 |
| Standard | 1920×1080 | 45 | 27,272.86 | 27,788.94 | 73,805,952 |
| Standard | 2048×1536 | 63 | 38,193.07 | 38,948.50 | 102,781,056 |
| SuperLight | 512×512 | 9 | 1,220.30 | 1,299.85 | 119,099,552 |
| SuperLight | 1024×1024 | 25 | 3,415.92 | 3,688.40 | 131,686,560 |
| SuperLight | 1920×1080 | 45 | 6,191.81 | 6,710.90 | 73,805,952 |
| SuperLight | 2048×1536 | 63 | 8,669.63 | 9,417.59 | 102,785,152 |

## 23. Full-resolution SuperLight result

**PASS.** Actual `_7110224.ORF`, ISO 6400, 5240×3912: 374 tiles, 3912 emitted rows, 245,986,560 output bytes, total 41,530.37 ms, pure inference 27,502.27 ms, SHA-256 `0b63d1496f2e16100174e23304d2bda42c04d687af8570bbbfdae6d76bf886f9`.

## 24. Full-resolution Standard result

**PASS.** Same actual ORF and tensor: 374 tiles, 3912 emitted rows, 245,986,560 output bytes, total 113,551.02 ms, pure inference 97,779.34 ms, SHA-256 `e40903bcda70829c952c953820047f3546d82174abba914d978779fd3e11d11c`.

| Model | Source RAW | ISO | Dimensions | Tiles | Cold total | Warm full total | Pure inference | Preprocessing | Output writing | Peak memory indicator | Thermal change | Output SHA-256 |
|---|---|---:|---:|---:|---:|---|---:|---|---|---:|---|---|
| SuperLight | `_7110224.ORF` | 6400 | 5240×3912 | 374 | 41.53 s | NOT RUN | 27.50 s | desktop pre-generated; timed separately NOT RUN | included in total, not isolated | JVM after input/run 275,226,864; reconstruction estimate 23,035,904 | session-wide battery rose; no per-run baseline | `0b63…86f9` |
| Standard | `_7110224.ORF` | 6400 | 5240×3912 | 374 | 113.55 s | NOT RUN | 97.78 s | desktop pre-generated; timed separately NOT RUN | included in total, not isolated | JVM after input/run 275,226,864; reconstruction estimate 23,035,904 | session-wide battery rose; no per-run baseline | `e409…d11c` |

## 25. Model-switching result

**PASS.** SuperLight→Standard→SuperLight used new interpreters and SHA-scoped staging. The two SuperLight outputs were bit-exact; Standard remained distinct. No stale tensor buffer or output cache was observed.

## 26. Cancellation result

**PASS.** Cancellation after the second tile raised `FullImageCancellationException`, emitted `CANCELLED`, did not emit `COMPLETED`, and processed exactly two tiles.

## 27. Thermal results

**PASS with caveat.** After the sustained full-resolution and size-matrix workload, Thermal Status was `0` (no throttling), current HAL temperatures were AP 41.0°C, battery 38.8°C, PA 40.8°C, skin 37.8°C. Battery logs across the broader, wirelessly charging test session rose from about 29.8°C to a peak near 39.7°C, so this is not a controlled ambient thermal experiment. One-thread tile medians stayed roughly 605–609 ms Standard and 135–140 ms SuperLight across increasing image sizes; no clear sustained slowdown appeared.

## 28. Memory results

**PASS.** Full-resolution input occupies 245,986,560 bytes. Bounded reconstruction estimate is 23,035,904 bytes and peak retained rows is 256. Observed JVM heap after full input/run was about 275.2 MB, with native heap about 8.1 MB in the 4-thread full-resolution test. Full output was streamed to disk and not retained as a second full-image float array.

## 29. APK/AAB size impact

**PASS.** New RAW-AI model bytes total 21,272,748. This is +19,843,372 bytes versus the previous SuperLight-only FP32 deployable model, or +19,025,436 bytes versus the previous FP32+broken-FP16 asset pair. Release APK is 55,232,763 bytes; release AAB is 39,664,967 bytes. A directly comparable pre-Phase-5 APK/AAB was **NOT AVAILABLE**.

## 30. Files changed

**PASS.** Changes include two renamed/new model assets and manifest; model ID/condition/controller/request code; native name-based tensor resolution and ISO condition fix; Android dual-model, switching, ISO, tiled, streaming/full-resolution tests; ONNX export/onnx2tf wrapper/FlatBuffer finalizer/benchmark tools; full-reference generation options; selective recovery snapshot; and this report. No editor, export, gallery, thumbnail, WorkManager, GPU, NNAPI, QNN, or NPU integration was changed.

## 31. Build results

**PASS.** `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, native CMake builds for arm64-v8a/x86_64/armeabi-v7a/x86, `assembleRelease`, lint vital, R8, and `bundleRelease` passed. Release APK SHA-256: `51924f52eba33a3ba215fa95d5357ded45aa27b098a534258161adf1d0799fe8`; AAB SHA-256: `de01018927478dda41d69aa840dcdfab3d404e4fc43db588bd741ccab79f4779`.

## 32. Android test results

**PASS.** Golden parity 1/1; full-image validation 3/3; explicit switching 1/1; size benchmark 1/1; 24/24 all-ISO model/sample runs; SuperLight full-resolution 1/1; Standard full-resolution 1/1. Device: Samsung SM-S936W, Android 16/API 36.

## 33. Remaining risks

- **NOT RUN:** controlled unplugged ambient thermal trial and a second warm full-resolution pass.
- **NOT RUN:** Android FP16 candidates, because true FP16 external I/O did not produce a valid candidate and FP16-weight models were deliberately not packaged.
- **NOT AVAILABLE:** a clean ground-truth RAW/noise pair and actual OM-1 files. OM-5 Mark II was explicitly treated as OM-5 as authorized.
- **Risk:** the test full-resolution API still retains the full input FloatArray; reconstruction/output are bounded, but a future file-backed input tile source would reduce peak JVM heap.
- **Risk:** onnx2tf optimizes its isolated input copy in place; authoritative ONNX files must remain outside the conversion output directory and hash-checked before/after copying.

## 34. Phase 5 completion status

**PASS for FP32 dual-model Phase 5.** Standard and SuperLight are both preserved as supported Android model choices.

Standard is the explicit High Quality model.

SuperLight is the explicit Fast model.

No automatic model selection or silent fallback was introduced.

Phase 5 does not connect RAW AI to the normal editor or export pipeline.

Light and Heavy are not included in Android application assets.

Exactly two RAW-AI TFLite assets were found in debug APK, release APK, and release AAB; forbidden Heavy/Light/FP16/model_fp candidates in the RAW-AI subtree: zero.

## 35. Recommended Phase 6 entry point

Keep FP32 CPU as the immutable oracle. First add a file-backed/random-access input tile source so full RAW preprocessing and inference do not retain a 246 MB FloatArray. Then run controlled unplugged thermal/warm-full-resolution trials. Only after those pass should a separate explicit backend experiment evaluate modern LiteRT acceleration; do not alter model IDs or add fallback.

## onnx2tf SuperLight Reconstruction

**PASS.** The isolated static ONNX reconstruction produced a finalized FP32 candidate SHA `9b6a9031666bb0958943c6e716b65a8066c211940400729ee884713ff2276f5d` (1,370,188 bytes). It was not substituted for the known-good asset. Versus the preserved asset: max `0.000240922`, mean `0.000125757`, RMSE `0.000127323`, correlation `0.9999999867`.

## onnx2tf Standard Conversion

**PASS.** FP32 ONNX→onnx2tf SavedModel→explicit NCHW boundary wrapper→TFLite→stable tensor-label finalization. The boundary wrapper is explicit because default onnx2tf 2.6.3 optimized the public output to NHWC and positionally mismatched copied input labels; that default candidate was rejected.

## FP32 Conversion Matrix

| Model | Source ONNX SHA-256 | onnx2tf | Mode | TFLite SHA-256 | Bytes | Contract | Desktop parity | Android |
|---|---|---|---|---|---:|---|---|---|
| SuperLight preserved | `1684…06dd` authoritative | historical + reconstructed 2.6.3 audit | existing FP32 | `0efe…806` | 1,429,376 | NHWC/cond→NCHW FP32 | golden PASS | PASS |
| SuperLight reconstructed | static `e63b…6035` | 2.6.3 | FP32 candidate | `9b6a…6f5d` | 1,370,188 | NHWC/cond→NCHW FP32 | PASS with measured delta | NOT PACKAGED |
| Standard | `9424…0638` | 2.6.3 | FP32 | `9188…4540` | 19,843,372 | NHWC/cond→NCHW FP32 | max `7.15e-7` PASS | PASS |

## FP16 Conversion Matrix

| Model | FP16 mode | Desktop status | Android status | Single-tile parity | Full-resolution | Measured speed difference |
|---|---|---|---|---|---|---|
| SuperLight | FP16 weights, FLOAT32 I/O, SHA `fa1b…8d0e`, 761,236 B | PASS | NOT RUN / not packaged | vs reconstructed FP32 max `0.000525147` | NOT RUN | NOT RUN |
| Standard | FP16 weights, FLOAT32 I/O, SHA `ec4e…1b9f`, 9,997,828 B | PASS | NOT RUN / not packaged | vs FP32 max `0.000466049` | NOT RUN | NOT RUN |
| SuperLight | true FLOAT16 external I/O | FAIL converter; old broken artifact excluded | NOT RUN | NOT AVAILABLE | NOT RUN | NOT AVAILABLE |
| Standard | true FLOAT16 external I/O | FAIL: onnx2tf emitted FLOAT32 external I/O; TF converter rejected requested FLOAT16 I/O | NOT RUN | NOT AVAILABLE | NOT RUN | NOT AVAILABLE |

## Desktop Conversion Parity

**PASS.** Standard ONNX/TFLite metrics are in section 8. Across all actual ISO tiles, desktop/mobile parity remained below `1.8e-7` maximum error for both packaged models.

## Android Single-Tile Conversion Parity

**PASS.** Both final contracts, finite output, determinism, and distinct model behavior were verified on SM-S936W. SuperLight also passed the preserved golden oracle.

## Android Multi-Tile Results

**PASS.** See section 22. Results are actual device measurements; no projected value is mixed into the measured columns.

## Full-Resolution Test Inputs

**PASS.** `_7110224.ORF`, SHA-256 `e57bcf05b6031628812659ce5f8698e6f4e6c9848ee8b3356e0539471168422`, OM Digital Solutions OM-5MarkII treated as OM-5, ISO 6400, 1/125 s, 25 mm, visible RGGB 5240×3912. Generated authoritative input is 245,986,560 bytes, SHA-256 `9f92eaf1f147871be52e4e4d68490dac018c3b651d9bc6130cc26a286e68386e`.

## Full-Resolution SuperLight Results

**PASS.** See sections 23 and 28.

## Full-Resolution Standard Results

**PASS.** See sections 24 and 28.

## Full-Resolution FP16 Results

**NOT RUN.** FP16 candidates are conversion experiments only and were intentionally excluded from Android assets.

## Cold-versus-Warm Results

**PARTIAL.** Model load plus first full execution is measured as cold total. Warm per-tile series is measured in the single-tile and size matrices. A second complete warm 20.5MP pass was **NOT RUN** and is not inferred from projections.

## Thermal Throttling

**PASS with caveat.** Thermal Status remained 0 and size-matrix tile medians remained stable. The phone was wirelessly charging, so the observed battery-temperature rise is descriptive, not a controlled causal result.

## Measured-versus-Projected Timing

All reported Android resolutions and both 20.5MP runs are measured. No projection is used as a substitute. Desktop and phone timing columns remain separate because runtime/thread/backend configurations differ.

## Complete Output Artifact List

External, intentionally uncommitted validation workspace:

- `onnx/validation/phase5/device/iso_tiles/`: 24 Android JSON reports and 24 complete NCHW float32 outputs.
- `onnx/validation/phase5/desktop/iso_tiles/`: 24 desktop outputs, timing CSV, desktop/device parity JSON, Standard/SuperLight difference JSON.
- `onnx/validation/phase5/full_resolution/`: actual ORF manifest and complete 5240×3912 input tensor.
- `onnx/validation/phase5/full_resolution/device/`: two complete 245,986,560-byte HWC outputs, comparison JSON, two display previews, and absolute-difference preview.
- `onnx/validation/phase5/device/full_image_benchmark.json`: ten measured size/model rows.
- `C:/rawforge_phase5/`: isolated conversion matrix, SavedModels, ONNX copies, logs, and rejected candidates.

## Exact command patterns

```powershell
# onnx2tf conversion
python tools/raw_ai/run_onnx2tf.py -i standard.onnx -o standard -coion -tb tf_converter -v info

# NCHW contract wrapper and stable tensor labels
python tools/raw_ai/wrap_onnx2tf_saved_model.py --saved-model standard --output-dir standard_wrapped --stem rawforge_standard
python tools/raw_ai/finalize_tflite_contract.py --schema standard/schema_generated.py --input rawforge_standard_fp32.tflite --output rawforge_standard_fp32.tflite

# desktop inference/parity
python tools/raw_ai/benchmark_dual_tflite.py --manifest app/src/main/assets/raw_ai/model_manifest.json --inputs validation/pr5_om5mkii/256 --assets app/src/main/assets/raw_ai --device-results validation/phase5/device/iso_tiles --output validation/phase5/desktop/iso_tiles

# ADB installation
adb install -r -d app-debug.apk
adb install -r app-debug-androidTest.apk

# instrumentation and full-resolution execution
adb shell am instrument -w -r -e class dev.dblink.core.rawai.FullImageValidationTest dev.dblink.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class dev.dblink.core.rawai.StreamingInferenceDeviceTest#fullResolutionExternalRawForgeTensor -e inputPath /data/user/0/dev.dblink.debug/files/raw_ai/phase5_full_input.bin -e model standard -e width 5240 -e height 3912 -e iso 6400 dev.dblink.debug.test/androidx.test.runner.AndroidJUnitRunner

# artifact retrieval (binary-safe)
adb exec-out run-as dev.dblink.debug cat files/raw_ai/phase5_full/standard_5240x3912.f32le.bin
```

TFLite inspection used ai-edge-litert `Interpreter.get_input_details()` / `get_output_details()` and SHA-256 verification before acceptance.
