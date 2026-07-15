# Pre-PR 5 three-model evaluation recovery snapshot

This evaluation-only snapshot anchors the PR 1–PR 4 RAW-AI state before candidate testing. The
working tree was already broadly dirty and every RAW-AI directory below was untracked as a unit;
no reset, clean, checkout, stash, revert, stage, commit, or push was performed.

The authoritative recoverable text snapshots from the prior boundaries remain in:

- `docs/recovery/pre-pr3-raw-ai/`
- `docs/recovery/pre-pr4-raw-domain/`

The current PR 4 boundary is additionally anchored by these immutable artifacts:

- `docs/raw-ai/pr4-raw-input-domain-verification.md`
- `docs/raw-ai/pr4-sample-inventory.json`
- `%TEMP%/raw-ai-pr4/*/*.manifest.json` and their SHA-256-verified float tensors

Relevant current source scope:

- `app/src/main/assets/raw_ai/` (models are referenced, not duplicated)
- `app/src/main/java/dev/dblink/core/rawai/`
- `app/src/test/java/dev/dblink/core/rawai/`
- `app/src/androidTest/java/dev/dblink/core/rawai/`
- `app/src/main/cpp/raw_ai/`
- `tools/raw_ai/`
- `docs/raw-ai/`

The two active model hashes at snapshot time were:

- FP32: `0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806`
- FP16: `03842593e1295f94e44d3cab6bc3f7fae2022941f0659d54233114398d1376b3`

Candidate checkpoints and generated comparisons are deliberately excluded and live only under
`%TEMP%/rawforge-three-model-test/`. Normal application assets and manifests were not changed.
