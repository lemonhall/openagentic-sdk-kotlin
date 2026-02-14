# v1: core parity（Kotlin SDK 核心模块对齐）

## Goal

交付一个可在 Android/Kotlin 项目中复用的**纯 Kotlin**核心 SDK：具备 sessions/events/tools/skills 的最小闭环，并通过确定性单元测试固化口径。

## PRD Trace

- PRD：`docs/prd/PRD-0001-kotlin-core-agent-sdk.md`
- Req IDs：REQ-0001-001 ~ REQ-0001-008

## Scope

- 做：实现 PRD 中列出的最小事件模型、FileSessionStore、skills 解析+索引、tool registry、runtime tool loop、allowed tools。
- 不做：CLI/Gateway/MCP/Hooks/compaction/真实网络 E2E。

## Acceptance（硬）

- `.\gradlew.bat test` exit code = 0
- tests 覆盖 REQ-0001-001 ~ 008，并包含真实文件 I/O（临时目录）与 `events.jsonl` 内容断言。

## Files（预期新增/修改）

- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradlew`
- `gradlew.bat`
- `src/main/kotlin/**`
- `src/test/kotlin/**`

## Steps（Strict，按顺序）

### 1) TDD Red：写核心测试（先红）

- 建立 Gradle Kotlin/JVM library 工程骨架（为跑测试所必需）
- 按 PRD 将下列测试先写出来并跑到红：
  - `EventSerializationTest`（REQ-0001-001）
  - `FileSessionStoreTest`（REQ-0001-002）
  - `EventsJsonlExcludesDeltasTest`（REQ-0001-003）
  - `SkillParserTest`（REQ-0001-004）
  - `SkillIndexTest`（REQ-0001-005）
  - `ToolRegistryTest`（REQ-0001-006）
  - `RuntimeToolLoopTest`（REQ-0001-007）
  - `ToolNotAllowedTest`（REQ-0001-008）

**验证命令**：`.\gradlew.bat test`（预期：失败，缺实现/缺类）

### 2) TDD Green：实现最小可用 SDK（跑到绿）

- 逐个补齐实现（events/serialization/sessions/skills/tools/runtime），以测试驱动推进
- 所有测试跑到绿

**验证命令**：`.\gradlew.bat test`（预期：通过）

### 3) Refactor：最小重构（仍绿）

- 统一命名、包结构、错误类型；保持 API 简洁
- 不引入额外功能（避免 scope creep）

### 4) Review：回顾差异

- 更新 `docs/plan/v1-index.md` 的“差异列表”
- 如发现 PRD/口径需要改动：新增 ECN（v1 若无则保持空）

## Risks

- wrapper/依赖下载慢：Gradle wrapper 固定版本；必要时后续补代理文档。
- 序列化口径不一致：以测试为唯一锚点，先改测试再改实现。

