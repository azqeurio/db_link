# PR 6: OM-5 Mark II ISO model comparison

## Decision summary

The 12 supplied OM-5 Mark II files were evaluated at ISO 200, 400, 400, 800, 1000, 2000, 4000, 6400, 8000, 10000, 12800, and 25600. Heavy, Standard, Light, and SuperLight TorchScript models processed the same authoritative 256x256 center crop on an RTX 3060 and desktop CPU. The phone-portable SuperLight TFLite FP32 model processed the same inputs on a Samsung SM-S936W running Android 16.

Standard is the best initial desktop-quality candidate: it retained the most gradient and Laplacian energy while producing the smallest mean input-relative residual among the four models. Heavy was approximately twice the size and compute cost without a measurable advantage on this dataset. SuperLight remains the practical phone candidate because the TFLite port reproduces the upstream output closely. Light is an intermediate option but did not establish a clear speed or quality niche in this run.

This is not an absolute denoising-quality ranking. The ISO series has no clean reference, and noise and real fine detail are inseparable in the input-relative metrics. Final selection should use the generated blind sheets, then be confirmed on aligned noisy/clean pairs.

## Model and performance comparison

All tile times are warm medians across the 12 samples. Desktop CUDA measurements use an NVIDIA GeForce RTX 3060. CPU values use desktop PyTorch FP32. Full-image estimates multiply the best measured desktop tile time, or the measured phone time, by 374 tiles for a 5240x3912 image with 256px tiles and 16px overlap. They exclude preprocessing, blending, file I/O, cold initialization, and thermal throttling.

| Candidate | Artifact size | Parameters | CUDA FP32 | CUDA FP16 | Desktop CPU FP32 | Estimated full image | Mean residual RMSE | Gradient energy retained | Interpretation |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Heavy (Deep24 candidate) | 43.96 MB | 10.742 M | 25.44 ms | 27.59 ms | 124.54 ms | 9.52 s desktop | 0.010312 | 49.1% | High cost; no demonstrated advantage over Standard |
| Standard | 20.26 MB | 4.938 M | 14.79 ms | 14.63 ms | 61.62 ms | 5.47 s desktop | 0.010286 | 51.9% | Best measured detail/change balance |
| Light | 3.15 MB | 0.657 M | 10.68 ms | 13.74 ms | 29.33 ms | 3.99 s desktop | 0.010787 | 45.4% | Stronger smoothing; intermediate size |
| SuperLight | 1.80 MB | 0.319 M | 11.47 ms | 14.06 ms | 26.82 ms | 4.29 s desktop | 0.010923 | 44.7% | Smallest upstream model; strongest average smoothing |
| Mobile TFLite FP32 (SuperLight port) | 1.43 MB | same source family | n/a | n/a | 78.74 ms on phone | 29.45 s phone CPU | tracks SuperLight | tracks SuperLight | Valid phone baseline; all 12 ISO cases passed |
| Mobile TFLite FP16 | 0.82 MB | same source family | n/a | n/a | not runnable | not available | n/a | n/a | Invalid for the current CPU interpreter; CONV_2D rejects float16 input |

On this RTX 3060, autocast FP16 did not improve 256x256 tile latency. The models are small enough that conversion and launch overhead dominate; FP16 should not be selected solely from file size or theoretical throughput.

## Input-relative change by ISO

Higher residual RMSE means the model changed the input more. It may represent more noise removal, more lost detail, or both. The two ISO 400 samples are averaged.

| ISO | Heavy | Standard | Light | SuperLight |
|---:|---:|---:|---:|---:|
| 200 | 0.003194 | 0.003542 | 0.004113 | 0.004682 |
| 400 | 0.004329 | 0.004571 | 0.005386 | 0.005640 |
| 800 | 0.005481 | 0.005625 | 0.006745 | 0.006669 |
| 1000 | 0.005876 | 0.005956 | 0.006742 | 0.006866 |
| 2000 | 0.007397 | 0.007359 | 0.008141 | 0.008118 |
| 4000 | 0.009635 | 0.009524 | 0.010222 | 0.010410 |
| 6400 | 0.011794 | 0.011693 | 0.012030 | 0.012009 |
| 8000 | 0.013217 | 0.013154 | 0.013348 | 0.013092 |
| 10000 | 0.015078 | 0.014814 | 0.014952 | 0.015115 |
| 12800 | 0.017221 | 0.017012 | 0.016831 | 0.017144 |
| 25600 | 0.026194 | 0.025611 | 0.025549 | 0.025687 |

The change increases smoothly with ISO-derived conditioning. At low ISO, Light and SuperLight alter the input more than Heavy and Standard. Above ISO 8000, the four candidates converge, so visual texture and artifact inspection matters more than the aggregate residual magnitude.

## Mobile-port verification

The TFLite FP32 model was tested directly through the Android TensorFlow Lite interpreter with four CPU threads and no NNAPI or XNNPACK delegate. All 12 inputs completed with finite output.

- Phone warm tile median: 78.74 ms; mean: 80.75 ms.
- Phone versus desktop TFLite: maximum absolute error at most `1.79e-7`; mean RMSE `7.43e-9`.
- Phone TFLite versus upstream SuperLight CUDA FP32: maximum absolute error at most `3.68e-4`; mean RMSE `2.70e-5`; mean correlation `0.999999985`.
- The FP16 asset failed on desktop and Android CPU with the same TensorFlow Lite `CONV_2D` float16-input allocation error. It is not a valid accelerated fallback in the current runtime.

This proves the isolated TFLite interpreter path, not production editor integration. `production_enabled` remains false, and the native `RawAiEngine` still supplies the old constant-zero condition instead of the tested ISO formula.

## Review artifacts

The external result workspace contains 12 five-way blind contact sheets, named outputs, full float32 tensors, hashes, timings, pairwise differences, projections, and the phone outputs. The randomization manifest should remain closed until visual scores are entered in `blind-scoring-sheet.csv`.

Suggested review order:

1. Score blind sheets at ISO 200, 800, 4000, 12800, and 25600 for noise, fine detail, natural texture, color stability, and artifacts.
2. If Standard and Heavy are visually tied, remove Heavy from further work.
3. Compare Standard against Mobile/SuperLight for the desired desktop-versus-phone product split.
4. Acquire aligned clean/noisy pairs or repeated tripod bursts before making a final quality claim.

## Recommended next implementation gate

Do not enable production yet. First remove or reconvert the invalid FP16 asset, wire `min(ISO,65535)/6400` through the production engine, run bounded full-resolution preprocessing/inference/blending on the phone, and repeat the thermal/memory measurement. The current 29.45-second phone value is a pure-inference projection rather than end-to-end export time.
