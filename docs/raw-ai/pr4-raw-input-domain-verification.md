# PR 4 — RAW input-domain verification

> Superseded for the isolated model-domain path by `pr5-bayer-domain-preprocessor.md`. The production-integration block documented here remains in effect.

## 1. Starting repository state

PR 4 started on branch `main` at `d907fb2`, with 345 modified and 20 untracked entries already present. The initial `git diff --check` was PASS. No reset, clean, checkout, stash, revert, commit, or push was performed. The pre-existing dirty worktree remains authoritative and unrelated changes were not edited for this work.

## 2. Selective PR 1–PR 3 snapshot

`docs/recovery/pre-pr4-raw-domain/` contains the RAW-AI-only Kotlin, tests, Gradle file, manifests, PR 2/PR 3 reports, hashes, inventory, and focused command. Model/tensor binaries and unrelated application files are deliberately excluded. Snapshot status: PASS.

## 3. Production RAW pipeline call graph

The traced path is `MainViewModel` → `DefaultDeepSkyFrameDecoder` → `RawDecoder.decodeOrNull` → `DeepSkyNative.decodeOrf` → `deepsky_raw_jni.cpp` → bundled LibRaw 0.22.0.

The JNI path sets `output_bps=16`, `use_camera_wb=1`, `no_auto_bright=1`, and `gamm=(1,1)`, then calls `open_file`, `unpack`, `dcraw_process`, and `dcraw_make_mem_image`. It does not explicitly set `output_color`, demosaic quality, or flip; effective LibRaw defaults therefore apply (default sRGB output, Bayer AHD in this path, metadata orientation). The returned `full_rgb48` is HWC RGB uint16; later luma/preview downsampling is downstream. No lens correction, custom denoising, sharpening, or explicit tone curve was found in this decode call.

Consequently, the tested production candidate is precisely named `POST_LIBRAW_DCRAW_CAMERA_WB_LINEAR_SRGB_U16`: camera white balance and a camera-to-sRGB matrix have already been applied. Values exported by the bridge are uint16 divided by 65535.

## 4. Available sample inventory

The read-only bundled resource collection contains 1,324 ORF files, including a 12-file OM-5 Mark II different-ISO series. The machine-readable inventory is `docs/raw-ai/pr4-sample-inventory.json`. Exact maker compression mode was not exposed by rawpy; all selected inputs are genuine ORF RAW containers with 16-bit containers.

The OM-5 Mark II series is BLOCKED for authoritative RawHandler reference generation: rawpy 0.25.1 / LibRaw 0.21.4 reports a rank-zero RGB-to-XYZ matrix and RawHandler fails with a singular-matrix error. No substitute matrix was invented.

## 5. Selected sample set

Six supported OM-1 files were selected by authoritative metadata, not filename assumptions. All are 5220×3912, identity orientation, active area `(0,0,5220,3912)`, RGGB, white level 4095, and use the deterministic sensor-native crop `(2482,1828,256,256)`.

| File | ISO | SHA-256 prefix | Black levels | Reason |
|---|---:|---|---|---|
| `_7073568.ORF` | 500 | `9f3f70082d22` | 255,254,255,254 | Lowest supported ISO; nonuniform black levels |
| `_7072398.ORF` | 640 | `0e07856c4b03` | 255,255,255,255 | Low/medium and visualization case |
| `_7072463.ORF` | 1000 | `5837754f49c8` | 255,255,255,255 | Medium ISO |
| `_7072723.ORF` | 2000 | `7bc3c83e44fa` | 255,255,255,255 | High ISO midpoint |
| `_7072593.ORF` | 4000 | `862ae319d0e6` | 255,255,255,255 | Very high ISO |
| `_7073243.ORF` | 6400 | `6d2cea5c08e2` | 256,256,256,256 | Highest selected ISO; evidenced condition 1.0 |

They are not asserted to be spatially aligned exposures or clean/noisy pairs. No denoising-quality claim is made.

## 6. Authoritative RawForge preprocessing trace

Local upstream evidence is the bundled RawForge snapshot plus installed RawHandler 0.2.1. The exact `ShadowWeightedL1_super_light.onnx` registry entry specifies `Malvar2004`, not AHD; the current Android manifest label `RAWPY_AHD` is therefore stale for this model.

The executable path is:

1. rawpy `raw_image_visible` in sensor-native orientation.
2. Safe crop offsets normalize the CFA phase to RGGB; dimensions are made even.
3. For each CFA site, `(sample - black[channel]) / (white - black[channel])`.
4. Camera Bayer planes are transformed by the camera RGB→XYZ evidence into linear Rec.2020 before demosaic.
5. `colour_demosaicing_CFA_Bayer_Malvar2004` produces RGB.
6. Clip to `[0,1]`.
7. Convert HWC→batched model input and quantize to float16 in upstream inference.

For canonical comparison the exact float16 values are losslessly widened to float32. White-balance gains are unity; there is no gamma. Confidence is high because these operations are in executable upstream code and reproduced by the generator.

## 7. Rawpy effective parameters

RawForge does not use rawpy `postprocess`; it consumes the visible Bayer mosaic and performs normalization, matrix conversion, and Malvar demosaic itself. Therefore `use_camera_wb`, `use_auto_wb`, `gamma`, `no_auto_bright`, `output_color`, AHD selection, highlight mode, median passes, FBDD, and chromatic-aberration options are not rawpy-postprocess parameters in the authoritative path.

The independent production-equivalent diagnostic does use rawpy postprocess with `use_camera_wb=true`, `use_auto_wb=false`, `gamma=(1,1)`, `no_auto_bright=true`, `output_bps=16`, default sRGB output, default AHD, and metadata orientation, matching the Android JNI settings as closely as rawpy/LibRaw 0.21.4 permits.

## 8. `cond` evidence table

| Source | Symbol/use | Executable evidence | Confidence |
|---|---|---|---|
| RawForge model registry | `TreeNetDenoiseSuperLight` | conditioning enabled; `max_iso=65535`; no model-specific scale | High |
| RawForge `main.py` | inference default | condition source is `[iso, 0]` absent override | High |
| `InferenceWorker._build_conditioning` | model input | clamp ISO to max, divide first component by 6400, set second to zero, slice required inputs, cast float16 | High |
| PR 2/PR 3 local oracle | conversion/validation | always supplies `0.0` | High for parity baseline, not metadata semantics |

## 9. `cond` conclusion

For this model, the authoritative upstream inference formula is `min(ISO, 65535) / 6400.0`. This is not a guess based on sensitivity. `cond=0.0` remains the verified PR 2/PR 3 numerical oracle and was retained for baseline comparisons; the metadata formula was tested separately. The meaning is an ISO-derived conditioning scalar, not a user denoise-strength control.

## 10. Reference tensor format

The canonical file is contiguous little-endian float32, HWC, RGB, no batch dimension, 256×256×3, exactly 196,608 elements and 786,432 bytes. Readers validate dimensions and byte count. Manifests record source/tensor/model/script hashes, crop, orientation, active area, CFA, black/white levels, white-balance/matrix/gamma/clipping state, environment, statistics, and output hashes. No implicit transpose, rotation, clamp, or reshape occurs in readers.

## 11. Android debug tensor stages

`RawDomainDebugBridge.kt` and `raw_domain_debug_jni.cpp` expose one minimal, explicitly invoked stage: `POST_LIBRAW_DCRAW_CAMERA_WB_LINEAR_SRGB_U16`. The separate JNI invocation mirrors the existing production decoder settings and never changes production buffers or order. Kotlin is guarded by `BuildConfig.DEBUG`; native source is included only in debug CMake builds. Tests write tensor and JSON only to app-private cache and log the path. Normal app operation performs no extraction or write.

## 12. Orientation and crop alignment

All selected metadata says horizontal/identity. Python and Android use visible-active-area coordinates before display rotation. The crop is `(2482,1828)` with size 256×256. The comparison tool evaluates identity, rotations 90/180/270, horizontal/vertical flips, transpose, and transverse; identity follows both metadata and call-path evidence. It also performs the bounded ±4 pixel search only after orientation selection.

## 13. CFA and active-area validation

Both decoders report RGGB, active-area origin `(0,0)`, even parity, and 5220×3912. The native bridge records raw/processed dimensions, margins, CFA, flip, black levels, white level, and camera-WB gains. All six device tests assert RGGB and finite HWC RGB. Because every selected file is RGGB/identity, non-RGGB camera support is not claimed.

## 14. Stage-by-stage comparison metrics

Python RawForge-domain vs Python production-equivalent LibRaw 0.21.4, without clamping before comparison:

| ISO | Max abs | Mean abs | RMSE | Correlation | >0.01 |
|---:|---:|---:|---:|---:|---:|
| 500 | 0.693191 | 0.042828 | 0.084736 | 0.976502 | 62.85% |
| 640 | 0.058840 | 0.016791 | 0.021172 | 0.810725 | 64.08% |
| 1000 | 0.061708 | 0.016137 | 0.020108 | 0.972096 | 63.50% |
| 2000 | 0.169225 | 0.008243 | 0.011588 | 0.985394 | 29.35% |
| 4000 | 0.256544 | 0.015392 | 0.025823 | 0.990277 | 45.51% |
| 6400 | 0.186682 | 0.014875 | 0.021419 | 0.903078 | 49.96% |

Actual Android LibRaw 0.22.0 vs RawForge input RMSE/correlation were: ISO 500 `0.090198/0.971830`, 640 `0.018150/0.842264`, 1000 `0.015835/0.972135`, 2000 `0.016176/0.972137`, 4000 `0.028494/0.983651`, and 6400 `0.019251/0.925054`.

Android vs the Python production-equivalent stage had RMSE `0.013987, 0.004139, 0.006259, 0.011789, 0.014963, 0.005713`; correlations were `0.997622, 0.994149, 0.994451, 0.992089, 0.996186, 0.992558`. This is close in stage/geometry but not bit parity because Android bundles LibRaw 0.22.0 while rawpy reports 0.21.4.

## 15. Spatial-offset investigation

The Python ±4 search selected `(dx,dy)=(0,0)` for every sample. The independent Android test also selected `(0,0)` for all six against the production-equivalent reference. The mismatch is not an active-area off-by-one, orientation, or crop-origin error.

## 16. Difference-attribution results

Per-channel affine fitting was the best bounded diagnostic candidate, reducing Python-domain RMSE as follows: ISO 500 `0.084736→0.019960` (76.4%), 640 `0.021172→0.003028` (85.7%), 1000 `0.020108→0.003552` (82.3%), 2000 `0.011588→0.009106` (21.4%), 4000 `0.025823→0.015618` (39.5%), 6400 `0.021419→0.010580` (50.6%).

Clipping explains 0%; channel permutations worsen results; global scale is materially weaker. Affine/color scaling helps because the compared pipelines intentionally differ in WB and working-space matrix, but it does not fully explain edge/content residuals from different demosaicing and ordering. No fitted correction was applied to production or reference data.

## 17. AHD implementation comparison

Android uses bundled LibRaw’s default Bayer AHD path after camera WB/color processing. The authoritative model path uses `colour_demosaicing` Malvar2004 after per-CFA normalization and camera→linear-Rec.2020 Bayer-plane conversion. These are different algorithms and different operation orders; AHD numerical equivalence is neither expected nor observed. High maxima and residual error after affine fitting show content/edge-dependent differences beyond a fixed matrix. Smooth/gradient/saturation masks are available in the comparison tool, but a complete region-stratified cross-camera study is NOT RUN and remains a limitation.

## 18. Different-ISO results

Six ISO values (500–6400) were processed independently. All reference/device tensors and both `cond=0` and evidenced ISO conditions were finite. The 12-file OM-5 Mark II ISO series is BLOCKED for the reason in section 4. Different files are not treated as paired exposures, and output changes are not interpreted as denoising quality.

## 19. Model-output comparison

Using the verified FP32 model, one CPU thread, delegates disabled, and `cond=0`, RawForge-input vs Python production-equivalent-input output RMSE was `0.081727, 0.020851, 0.019873, 0.008908, 0.021962, 0.019067` for ISO 500, 640, 1000, 2000, 4000, 6400. Corresponding input/output correlations were respectively `0.978677, 0.909228, 0.998132, 0.991237, 0.993391, 0.919101` for outputs.

On Android 0.22.0, RawForge-input vs actual-Android-input output RMSE was `0.087938, 0.017761, 0.015411, 0.014688, 0.025842, 0.017097`. With the evidenced ISO condition it was `0.087931, 0.017709, 0.015341, 0.014695, 0.025755, 0.016801`. The condition change does not remove the domain mismatch. No quality judgment is possible without a proven clean target.

## 20. Visual debug output locations

Numerical artifacts are under `%TEMP%/raw-ai-pr4/<sample>/`. For `_7072398`, `visuals/` contains reference, actual, absolute input difference, per-channel differences, high-error mask, and clipping mask; `output-visuals/` contains the analogous model-output images. PPM rendering uses fixed `[0,1]` display clipping and an explicitly supplied difference scale; numeric binaries remain unchanged. Android artifacts remain in app-private `cache/raw_ai_pr4/<sample>/` and are retrievable with `run-as`.

## 21. Input-domain compatibility classification

| Candidate stage | Decision | Reason |
|---|---|---|
| `POST_LIBRAW_DCRAW_CAMERA_WB_LINEAR_SRGB_U16` | **INCOMPATIBLE** | Same pixels/layout/orientation, but wrong demosaic, camera WB applied, sRGB rather than linear Rec.2020, different operation order, and material input/model-output residuals |
| Python/rawpy production-equivalent diagnostic | **INCOMPATIBLE** | Reproduces the conceptual Android stage but not the authoritative model contract |
| Proposed normalized Bayer→linear Rec.2020→Malvar stage | **INCONCLUSIVE** on Android | Authoritative Python contract is proven; no Android implementation exists in PR 4 |

Production integration is blocked. The smallest aggregate RMSE is not used to override the contract evidence.

## 22. Proposed future pipeline contract

Proposal only: visible active area; normalize CFA phase to RGGB by documented safe crop; even dimensions; per-site `(sample-black_c)/(white-black_c)`; camera RGB→linear Rec.2020 on Bayer planes; Malvar2004 demosaic; unity WB; no gamma; clip `[0,1]`; RGB HWC; model input float16 as upstream (or a separately revalidated FP32 representation); tile 256; overlap 16; replicate padding; separable smoothstep; condition `min(ISO,65535)/6400` with `cond=0` retained as oracle mode.

Uncertainties for a future Android implementation: exact matrix construction parity, float16 rounding point, non-RGGB phase/orientation coverage, unsupported-camera matrix policy, highlight/border behavior, and production decoder memory. This contract is not activated.

## 23. Streaming reconstruction architecture

`StreamingFullImageInferenceEngine` preserves PR 3 row-major tile order and exact smoothstep accumulation. It retains weighted RGB and scalar weights for at most 256 rows, reuses one input and one output tile, and emits rows before the next tile-row origin only after no future tile can touch them. It detects invalid finalization order/weights, reports progress, supports cancellation, retains no tiles, has no full-height weight buffer, and does not create a second full-image output. The sink owns persistence and must copy a row if retained.

## 24. Streaming equivalence results

Fast JVM tests cover 256², 257², 512², 513², 1024×768, 1920×1080, an edge-sensitive processor, cancellation after tile 2, and memory projections. Every deterministic case is bit-identical to the PR 3 full-buffer oracle (`max=mean=RMSE=0`).

Physical-device FP32 inference at 513×513, overlap 32/reflect, 9 tiles was also bit-identical: max/mean/RMSE `0/0/0`, 513 rows emitted, peak 256 rows, streaming reconstruction estimate 3,674,112 bytes versus full-buffer estimate 8,941,596 bytes, and streaming time 980.26 ms. Observed Java heap was 10,781,920 before, 18,315,120 after full-buffer, and 25,200,496 after the subsequent streaming run; native heap was 58,678,512. Heap snapshots include retained test arrays and are not isolated peaks.

## 25. Memory projections

Reconstruction-only formula is `width × 256 × 16 + 2 × 256 × 256 × 3 × 4`. It projects 18,087,936 bytes at width 4032 and 22,806,528 bytes at width 5184, independent of height. The input-domain source image/sink, LibRaw full RGB48 allocation, interpreter arena, native decoder memory, and debug tensor export are separate and not solved by this prototype. A risky full-resolution device inference was NOT RUN.

## 26. Device information

Samsung SM-S936W; Android 16/API 36; arm64-v8a; Dalvik heap growth limit 256 MB and heap size 512 MB; app `dev.dblink.debug`; test app `dev.dblink.debug.test`; installed/worktree app version code 6 after fallback install; version `0.2.5-debug`; Android LibRaw `0.22.0-Release`. The pre-test installed version was 10 while worktree is 6, so `adb install -r -d` plus direct instrumentation was used. Normal Gradle connected tests are NOT claimed.

## 27. Build and test results

Final gate status is recorded after execution:

| Command | Status |
|---|---|
| `:app:compileDebugKotlin` | PASS |
| `:app:compileDebugAndroidTestKotlin` | PASS |
| `:app:testDebugUnitTest` | PASS |
| `:app:lintDebug` | PASS |
| `:app:assembleDebug` | PASS |
| `:app:assembleDebugAndroidTest` | PASS |
| focused streaming JVM test | PASS |
| six direct RAW-domain device tests | PASS |
| real-model streaming device test | PASS |
| `git diff --check` | PASS |

## 28. Known limitations

Only OM-1/RGGB/identity samples reached full reference/device validation. Python and Android use different LibRaw releases. The debug bridge observes the exact existing postprocess stage but does not expose earlier unpack/normalized-Bayer taps because production has no such model-ready intermediate. Region-stratified AHD metrics and full-resolution inference are NOT RUN. Source decoder and output sink memory remain production design problems. Visualizations cover one representative sample. The current checked-in model manifest’s AHD label must not be treated as authoritative.

## 29. Production integration blocks

The current Android stage is not direct model input: it has camera WB, linear sRGB conversion, AHD, and different ordering from the authoritative unity-WB/linear-Rec.2020/Malvar model domain. A separate Android model-domain preprocessor, broader camera/CFA/orientation validation, explicit unsupported-camera behavior, full pipeline memory design, and contract-manifest correction must be implemented and revalidated before editor/export integration.

PR 4 does not enable RawForge inference in the production RAW editor.

No production RAW color-science or pipeline-order change was made.

The PR 3 FP32 CPU path remains the numerical oracle.

## 30. Recommended PR 5 entry point

Start with an isolated Android implementation of the proposed Bayer-domain contract: expose decoded visible mosaic plus metadata, implement per-CFA normalization, camera→linear-Rec.2020 Bayer transform, and Malvar2004 with deterministic fixtures. Prove byte/numeric parity against the six Python references plus added non-RGGB/oriented supported samples before considering any production connection. In parallel, design a bounded source/sink pipeline because reconstruction alone is now bounded but LibRaw/source/output memory is not.
