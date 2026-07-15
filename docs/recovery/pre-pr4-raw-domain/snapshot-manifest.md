# Pre-PR4 RAW AI selective recovery snapshot

Created 2026-07-14 from branch `main` at `d907fb2` before PR4 implementation.

Scope is deliberately limited to PR1–PR3 RAW AI Kotlin, JVM tests, Android tests, JSON manifests, the app Gradle file, and the PR2/PR3 reports. Model and tensor binaries are not duplicated.

Verified binary hashes:

- FP32 model: `0efe3fd811cb8691e6347021fbb147fd81282952145274460d1238da58715806`
- FP16 model: `03842593e1295f94e44d3cab6bc3f7fae2022941f0659d54233114398d1376b3`
- Reference manifest snapshot: `8b3ea2407e727ceeef30d929946a926938ee997ba832b00cecea20ebf81c8ec7`

The focused pre-PR4 command was:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'dev.dblink.core.rawai.*' --no-daemon
```

PR3 final status was PASS for FP32 CPU and BLOCKED/UNSUPPORTED for true FP16 CPU execution. The complete file inventory is the directory tree adjacent to this manifest; no unrelated app files are present.
