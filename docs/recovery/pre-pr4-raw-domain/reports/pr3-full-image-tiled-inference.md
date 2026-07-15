# PR 3: isolated full-image tiled inference

Date: 2026-07-14

## 1. Starting worktree state

The audit started on branch `main` at `d907fb2`. The worktree was already very dirty (approximately 345 modified and 18 untracked entries) and contained broad unrelated line-ending changes. No reset, checkout, clean, stash, mass format, staging, commit, or push was performed. PR 3 touched only isolated RAW AI source/test/documentation paths. The installed debug application was version code 10 while this worktree builds version code 6.

## 2. PR 1 and PR 2 components reused

PR 3 reuses `ModelAssetStore`, its SHA-256 checks, `CpuParityTest`, `RawAiController`, both model assets, `model_manifest.json`, `reference_manifest.json`, and the FP32 golden tensor. The existing Android-test LiteRT 2.16.1 dependency and CPU-only PR 2 configuration remain in use. There is one new test-session wrapper, not a second asset store. The pre-existing `app/src/main/cpp/raw_ai` scaffold remains inactive in this execution path; PR 3 adds no GPU, NNAPI, QNN, NPU, vendor SDK, or native inference integration.

No usable partial PR 3 tiler, blender, seam analyzer, full-image harness, or FP16 buffer implementation existed at the start.

## 3. Selective recovery snapshot created

`docs/recovery/pre-pr3-raw-ai/` contains only the verified PR 1/PR 2 Gradle snippet, manifests, relevant Kotlin/test files, PR 2 report, and a hash manifest. Large model/reference binaries were not duplicated; their hashes were recorded. The snapshot records, among others:

| Asset | SHA-256 |
|---|---|
| FP32 model | `0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806` |
| FP16 model | `03842593e1295f94e44d3cab6bc3f7fae2022941f0659d54233114398d1376b3` |

## 4. Conditioning investigation

Repository and locally available RawForge scripts, conversion tools, manifests, reports, and sample invocations were searched before defining the API.

| Source | Function/symbol | Observed value/formula | Interpretation | Confidence |
|---|---|---|---|---|
| `onnx/scripts/build_calibration_dataset.py` | calibration tensor creation/comment | Comment mentions `ISO / 6400.0`, but emitted `cond` is always `[[0.0]]` | Weak historical hint of normalized ISO; implementation does not exercise it | Low |
| `generate_reference_outputs.py` | inference inputs | `cond=0.0` | Known reference-generation condition | High |
| `generate_tflite_reference_outputs.py` | TFLite invocation | `cond=0.0` | Known converted-model reference condition | High |
| conversion scripts/manifests | exported contract | constant zero | Operational contract, not semantics | High |
| `reports/model_insertion_point.md` | original `InferenceWorkerRawpy.py` description | feeds `0.0` | Original inference behavior available locally | Medium-high |

No definitive upstream formula was found. The semantic meaning is unresolved. PR 3 keeps the proven operational default `cond=0.0` and does not invent an ISO mapping.

## 5. Conditioning sensitivity results

One fixed FP32 reference tile was run on the physical device. All outputs were finite. Differences are relative to `cond=0`.

| cond | output min | output max | output mean | max abs diff | mean abs diff | RMSE | changed elements |
|---:|---:|---:|---:|---:|---:|---:|---:|
| -1.00 | 0.260592908 | 0.789200068 | 0.511601543 | 0.028727174 | 0.003347144 | 0.004366626 | 100% |
| 0.00 | 0.265324771 | 0.787897289 | 0.510360809 | 0 | 0 | 0 | 0% |
| 0.25 | 0.265729487 | 0.787569046 | 0.510032870 | 0.007020235 | 0.000810324 | 0.001059010 | 99.9949% |
| 0.50 | 0.266148686 | 0.787240028 | 0.509698870 | 0.013936222 | 0.001611463 | 0.002106436 | 99.9985% |
| 1.00 | 0.267031372 | 0.786579251 | 0.509014786 | 0.027382672 | 0.003188233 | 0.004168621 | 100% |

Sensitivity proves that the input affects output; it does not identify its meaning.

## 6. Full-image input contract

`LinearRgbImage` is width × height × 3, contiguous HWC `FloatArray`, RGB channel order, implicit batch one. Values are already-prepared linear floating point in the model's expected domain. The core neither clamps nor applies gamma/sRGB conversion, white balance, matrices, tone curves, premultiplication, alpha handling, or channel reordering. `Bitmap` is not the canonical representation.

## 7. Tile-planning algorithm

`TilePlanner` uses integer coordinates only. Tile size is 256; stride is `256-overlap`. An axis at most 256 pixels creates one origin at zero. Larger axes use the minimum deterministic sequence of stride-spaced origins plus a final origin at `size-256` when needed. Duplicate origins are rejected. The plan records grid coordinates, valid source/destination region, and right/bottom padding.

Structural validation checks positive dimensions, overlap/stride, arithmetic overflow, bounds, padding, tile counts, unique origins, exact output dimensions, complete pixel coverage, and positive blend coverage. Planner tests cover 1×1, 64×64, 255×255, 256×256, 257×256, 256×257, 257×257, 511×513, 512×512, 513×513, 1024×768, narrow/tall cases, and a structural 4032×3024 plan with overlaps 0/16/32/64.

## 8. Edge padding algorithm

Each edge tile extracts its valid source region into a reused 256×256×3 buffer. Artificial coordinates are mapped with deterministic reflect, replicate/edge, or zero padding. Only the valid output rectangle is reconstructed; padded output is discarded. Inputs are never stretched, resized, or cropped, and output dimensions exactly equal input dimensions.

## 9. Blend-window formula

The overlap window is a separable smoothstep ramp. For normalized overlap position `t`, `s(t)=t²(3-2t)`. Paired neighboring weights are `s(t)` and `1-s(t)`; outer image boundaries retain weight one. X and Y weights multiply. The implementation guarantees a strictly positive total source-pixel weight and tests the window separately.

## 10. Reconstruction algorithm

For each valid destination pixel and channel:

```text
weightedSum += modelOutput * (weightX * weightY)
weightSum   += weightX * weightY
output       = weightedSum / weightSum
```

FP32 `FloatArray` accumulation was chosen to control full-image memory. The exact identity, constant normalization, global placement, channel order, padding, odd dimensions, and edge-sensitive behavior are unit-tested. Tile input/output arrays are reused and no tile outputs are retained.

## 11. Memory design

The theoretical core estimate is 28 bytes/source pixel (input, weighted output, scalar weights) plus two `tileSize²×3×4` tile buffers. Arithmetic uses checked `Long` operations and a configurable maximum rejects oversized work before allocation. There are no boxed per-pixel values or nested float arrays. Debug visualization is separately allocated and clamps only during export.

For the 513×513 device validation: estimated core working set 8,941,596 bytes; heap before 10,835,168; peak observed heap 44,304,240; heap after 30,146,784; native heap 8,088,544. A 4032×3024 plan was structurally tested but model inference was not run on the 256 MB-class device because the core estimate is about 343 MB.

## 12. Progress API

`InferenceProgress` reports completed/total tiles, fraction, current index and origin, phase, and elapsed milliseconds. Phases are PREPARING, INFERENCING, BLENDING, FINALIZING, COMPLETED, CANCELLED, and FAILED. Successful fractions are bounded and monotonic, and COMPLETED/1.0 is emitted only after normalization. Cancellation/failure reports the number of fully blended tiles.

## 13. Cancellation design

The typed `FullImageCancellationException` is checked before preparation, before each tile, after tile inference, and before finalization. A deterministic unit and physical-device test cancels after two processor invocations: no later inference occurs, no valid partial image returns, CANCELLED occurs, COMPLETED does not, and the model session is closed and rejects use after close. Cancellation is not converted to a generic inference failure.

## 14. FP32 single-tile equivalence

On the SM-S936W, a 256×256, overlap-zero, one-tile full-image run was compared with the direct PR 2 session output:

| Metric | Result |
|---|---:|
| elements | 196,608 |
| max absolute error | 0 |
| mean absolute error | 0 |
| RMSE | 0 |
| largest-error coordinate | NCHW index 0 (tie; all zero) |

The outputs are bit-identical. This independently preserves the earlier PR 2 golden parity result (max `3.5762786865234375e-7`, mean `4.496723704505712e-8`, RMSE `6.30288363784565e-8`).

## 15. FP32 full-image correctness

The physical-device seam case is 513×513 (789,507 HWC elements, nine tiles for overlap 16/32). Every tested output had 789,507 finite elements and zero NaN, positive infinity, or negative infinity. Reflect/32, for example, produced min 0, max 1, mean 0.500865297, standard deviation 0.268033283, 0% below zero, 0% above one, 0.979345% exactly zero, and 1.576047% exactly one. Per-channel metrics are computed by the harness and logged with each run; no inference output is clamped.

Synthetic identity reconstruction is exact across tested shapes, overlaps, and reflect/replicate/zero padding. Constant normalization and coordinate placement pass. Progress monotonicity and memory rejection pass.

## 16. Identity reconstruction tests

Fast JVM tests cover planner structure, identity processor, known reflect/replicate/zero padding values, smoothstep normalization, local-coordinate placement, odd dimensions, progress, typed cancellation, memory rejection, seam formulas, and edge-sensitive overlap behavior. IEEE binary16 tests cover signed zero, ±1, 0.5, 65504, minimum normal/subnormal behavior, ±infinity, and NaN.

## 17. Seam-analysis methodology

For every nonzero tile X origin, vertical discontinuity is `abs(output[y,x,c]-output[y,x-1,c])`; horizontal boundaries are analogous. All other adjacent horizontal/vertical differences form the interior baseline. The report computes boundary count, mean, maximum, RMSE, boundary-mean/interior-mean ratio, and per-channel mean/max/RMSE. The 513×513 sample has two vertical and two horizontal boundary lines per configuration.

## 18. Seam-analysis results

All configurations use the real FP32 model, one CPU thread, smoothstep blending, and `cond=0`.

| padding / overlap | boundary mean | max | RMSE | interior mean | seam/interior |
|---|---:|---:|---:|---:|---:|
| reflect / 0 | 0.050431679 | 0.757478558 | 0.100596742 | 0.042162166 | 1.196135885 |
| reflect / 16 | 0.040974185 | 0.503889352 | 0.065653066 | 0.042145110 | 0.972216838 |
| reflect / 32 | 0.041276072 | 0.442297071 | 0.065661867 | 0.042177878 | 0.978618979 |
| replicate / 0 | 0.056632778 | 0.757478558 | 0.112691790 | 0.042165312 | 1.343112989 |
| replicate / 16 | **0.040735034** | 0.452487379 | **0.065352224** | 0.042275770 | **0.963555101** |
| replicate / 32 | 0.041073994 | **0.442022473** | 0.065515753 | 0.042376212 | 0.969270068 |

Replicate/16 has the best aggregate mean, RMSE, and ratio; replicate/32 has a slightly smaller maximum jump. Replicate/16 per-channel mean/max/RMSE: R 0.040033800/0.309441775/0.062491958, G 0.039489171/0.452487379/0.067391219, B 0.042682131/0.350452721/0.066075095. Measurements show reduced aggregate discontinuity, not eliminated seams.

## 19. FP16 tensor contract

The asset hash is verified and explicit IEEE-754 binary16 encode/decode utilities and tests were added (little-endian direct buffers, correct byte counts, signed zero/subnormal/NaN/infinity behavior, no bfloat16). However, LiteRT Java 2.16.1 exposes no `DataType.FLOAT16` enum, and this true FP16 model cannot be prepared by the CPU kernels on the device.

Exact interpreter error:

```text
tensorflow/lite/kernels/conv.cc:361 input_type == kTfLiteFloat32 || input_type == kTfLiteUInt8 || input_type == kTfLiteInt8 || input_type == kTfLiteInt16 was not true. Node number 5 (CONV_2D) failed to prepare.
```

## 20. FP16 parity results or exact block

**BLOCKED / UNSUPPORTED.** There is no `output_fp16_01.bin` Android-test golden, and the actual FP16 model fails at interpreter creation with the CPU-kernel error above. No alternate FP32 model, silent conversion, or delegate was substituted. Therefore FP16 element/error/determinism parity is not claimed.

## 21. FP32 versus FP16 comparison

**BLOCKED / UNSUPPORTED.** Because true FP16 single-tile execution is unavailable, full-image accuracy, PSNR, clipped percentages, timings, and speed classification cannot be measured honestly. The benchmark records FP16 as unsupported with the exact reason rather than inferring that it is faster or slower.

## 22. Benchmark matrix

Physical-device explicit benchmark; FP32, one CPU thread, overlap 32, reflect padding, smoothstep, warm-up 1, cancellation supported. Model load was 10.661 ms and warm-up 168.252 ms. Small/medium rows used two measured runs and report the minimum complete run; large rows used one to limit heating.

| size | tiles/runs | total ms | extract ms | inference ms | blend ms | finalize ms | tile mean/median/p95 ms | MP/s | peak heap | status |
|---|---:|---:|---:|---:|---:|---:|---|---:|---:|---|
| 256×256 | 1/2 | 131.149 | 3.275 | 89.758 | 3.222 | 34.789 | 89.758/89.758/89.758 | 0.500 | 17,078,128 | PASS |
| 512×512 | 9/2 | 938.472 | 27.563 | 864.429 | 32.058 | 13.979 | 96.048/88.769/121.269 | 0.279 | 31,827,824 | PASS |
| 1024×1024 | 25/2 | 3309.472 | 99.809 | 3066.585 | 85.722 | 55.882 | 122.663/122.113/124.911 | 0.317 | 54,759,648 | PASS |
| 1920×1080 | 45/1 | 5998.131 | 180.504 | 5533.427 | 171.475 | 110.603 | 122.965/122.763/125.899 | 0.346 | 79,679,712 | PASS |
| 2048×1536 | 63/1 | 8196.652 | 248.755 | 7536.033 | 253.288 | 155.618 | 119.620/122.170/124.853 | 0.384 | 102,781,056 | PASS |
| FP16 | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | BLOCKED |

Native heap during the matrix was approximately 58.7 MB. These are not laboratory-grade results; thermal status and unrelated background load were not controlled. A 1/2/4-thread comparison was optional and was **NOT RUN**; one thread remains the correctness baseline.

## 23. Device information

| Field | Value |
|---|---|
| Manufacturer/model | Samsung SM-S936W |
| Android/API | Android 16 / API 36 |
| ABI | arm64-v8a (device reports ABI list; test used arm64-v8a) |
| available processors | 8 |
| memory class | 256 MB |
| charging | true |
| thermal status | not captured |

## 24. Build results

| Command/check | Status | Evidence |
|---|---|---|
| initial repository audit commands | PASS | branch/log/status/stat/name-status/check inspected |
| focused `:app:testDebugUnitTest --tests 'dev.dblink.core.rawai.*'` | PASS | build successful |
| `:app:compileDebugKotlin` | PASS | comprehensive gate |
| `:app:compileDebugAndroidTestKotlin` | PASS | comprehensive gate |
| `:app:testDebugUnitTest` | PASS | comprehensive gate |
| `:app:lintDebug` | PASS | report generated; no lint failure |
| `:app:assembleDebug` | PASS | APK built |
| `:app:assembleDebugAndroidTest` | PASS | test APK built |
| complete six-task Gradle gate | PASS | 98 actionable tasks, build successful in 4m21s |
| first complete-gate attempt | FAIL | transient Windows mapped-section lock on staged `libraw.h`; investigated, daemons stopped, staging task and complete retry passed |
| normal `connectedDebugAndroidTest` | BLOCKED / NOT CLAIMED | installed version code 10 exceeds worktree version code 6 |
| `adb install -r -d` app + `adb install -r` test | PASS | both streamed installs succeeded; no uninstall/data clear |
| direct `FullImageValidationTest` | PASS | physical device, `OK (3 tests)`, 10.116 s final rerun |
| direct `FullImageBenchmarkTest` | PASS | physical device, `OK (1 test)`, 24.668 s |
| final `git diff --check` | PASS | no whitespace errors; existing line-ending warnings are not diff errors |

Commands:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.dblink.core.rawai.*' --no-daemon
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=1
adb install -r -d app-debug.apk
adb install -r app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.dblink.core.rawai.FullImageValidationTest dev.dblink.debug.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class dev.dblink.core.rawai.FullImageBenchmarkTest dev.dblink.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## 25. Test commands

Fast tests are the focused `dev.dblink.core.rawai.*` JVM command above. Normal instrumented correctness is `FullImageValidationTest`. The expensive benchmark is isolated in `FullImageBenchmarkTest` and only runs when named explicitly. Device identity was inspected with `adb devices -l` and the requested manufacturer/model/release/SDK/ABI properties.

## 26. Generated debug output paths

The supplementary reconstructed FP32 image is a PPM at:

```text
/data/user/0/dev.dblink.debug/cache/raw_ai_pr3/pr3_fp32_reflect_overlap32.ppm
```

It was 789,522 bytes on the device. It is app-private, debug-only, and was not committed. Numerical seam and tensor metrics remain the primary validation.

## 27. Recommended isolated validation configuration

Use FP32, one CPU thread, 256×256 tiles, 16-pixel overlap, stride 240, replicate padding, separable smoothstep blending, `cond=0.0`, reused tile buffers, Float weighted accumulation, checked full-size allocations, and an explicit device-appropriate memory ceiling. This is the **recommended isolated validation configuration**, not a production default. It is selected because replicate/16 had the best measured aggregate seam mean, RMSE, and seam/interior ratio with less overlap work than 32.

## 28. Known limitations

- Conditioning semantics remain unresolved; only constant zero is operationally proven.
- True FP16 CPU execution is unsupported in the selected LiteRT runtime/model combination.
- The test input is an already-prepared linear tensor; current RAW-pipeline domain equivalence is unproven.
- 4032×3024 was planner-tested but not inferred on this 256 MB memory-class device.
- Seam results use one deterministic synthetic tensor and one device; they show improvement, not elimination.
- Benchmarks are subject to mobile thermal/background variance; thermal state was not captured.
- The Float accumulator's impact versus Double was not separately benchmarked.
- Multi-thread, delegates, GPU/NPU, queues, WorkManager, and production lifecycle behavior are outside scope.

## 29. Explicit production-integration exclusion

**PR 3 does not integrate RawForge inference into the production RAW editor pipeline.**

No editor UI, Detail control, export, camera transfer/download, thumbnail/library rendering, LibRaw/DCP/color pipeline, WorkManager, notification, queue, batch, or user-facing model setting was connected. Existing app behavior is unchanged outside isolated RAW AI validation code.

**Passing full-image validation does not prove that the current app RAW pipeline produces the exact input domain expected by the model.**

## 30. Recommended PR 4 entry point

Begin with an evidence-only RAW-domain bridge: characterize the real app pipeline tensor against the model's training/export preprocessing and resolve `cond` semantics from authoritative upstream material. Preserve this isolated FP32 configuration as the oracle. Only after domain equivalence is numerically established should PR 4 evaluate a debug-only integration boundary and separately choose a supported FP16/delegate/runtime path. Do not start production UI, queues, WorkManager, or hardware acceleration from the PR 3 core without those gates.

## Completion statement

PR 3 is complete for the supported FP32 CPU scope: deterministic arbitrary-size tiling, exact reconstruction tests, bit-identical single-tile wrapping, real-device full-image inference, seam measurement, progress, cancellation, memory accounting, and benchmarking all pass. FP16 was implemented and tested at the conversion layer but is explicitly blocked at true model execution by the runtime CPU kernel; no FP16 parity claim is made.
