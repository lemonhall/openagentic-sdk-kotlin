# v2 Index: Android-ready IO + Permissions + Resume（PRD-0002）

## 愿景

- PRD：`docs/prd/PRD-0002-kotlin-android-io-permissions-resume.md`

## 里程碑

### M2：Android-ready IO（Okio）

- **DoD**
  - `src/main/kotlin` 下无 `java.nio.file` import
  - `.\gradlew.bat test` 全绿
- **状态**：done
  - **证据**：`2026-02-14` 通过 `.\gradlew.bat test`；且 `src/main/kotlin` 无 `java.nio.file` import

### M3：Permissions + AskUserQuestion

- **DoD**
  - `PermissionGate` 为 `suspend` API
  - `AskUserQuestion` tool：必须产出 `user.question` + tool.result(output=answer Json)
  - 新增测试覆盖 allow/deny
  - `.\gradlew.bat test` 全绿
- **状态**：done
  - **证据**：`2026-02-14` 通过 `.\gradlew.bat test`（含 Permission prompt deny / AskUserQuestion）

### M4：Sessions Resume

- **DoD**
  - `resumeSessionId` 可从历史 `result.response_id` 推导 `previousResponseId`
  - append-only：旧 events 不丢，新 events 追加
  - `.\gradlew.bat test` 全绿
- **状态**：done
  - **证据**：`2026-02-14` 通过 `.\gradlew.bat test`（`resumeSessionId` 推导 `previousResponseId` + append-only）

## 计划索引

- `docs/plan/v2-android-io.md`
- `docs/plan/v2-permissions-ask.md`
- `docs/plan/v2-sessions-resume.md`

## 差异列表

- v2 仍未覆盖（建议进入 v3 计划）：
  - Tools 全量对齐（Write/Edit/Glob/Grep/Skill/SlashCommand/TodoWrite/Task/WebFetch 等的完整语义与安全边界）
  - Hooks 最小集之外的完整对齐（pre/post model、消息改写、阻断策略等）
  - Compaction（marker + tool output pruning + placeholder）与相关事件
  - Provider 协议多形态（legacy messages 与 responses input 的兼容层、tool schemas）
