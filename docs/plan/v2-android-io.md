# v2: Android-ready IO（Okio）

## Goal

让核心库（src/main）不依赖 `java.nio.file.*`，可作为 Android 逻辑层依赖。

## PRD Trace

- PRD-0002 / REQ-0002-001

## Acceptance

- `src/main/kotlin` 下无 `java.nio.file` import
- `.\gradlew.bat test` 全绿

## Steps

1. TDD Red：补/改测试以覆盖新 IO 抽象
2. Green：用 Okio 重构 `sessions`、`tools`、`runtime` 的路径与文件读写
3. Refactor：收敛 API，避免泄露 platform 细节

