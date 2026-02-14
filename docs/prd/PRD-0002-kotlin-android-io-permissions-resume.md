# PRD-0002: Kotlin SDK v2（Android-ready IO + Human-in-the-loop + Resume）

## Vision

在 v1 最小闭环基础上，把 Kotlin SDK 核心模块推进到**可在 Android 作为逻辑层依赖**的形态：

- 核心库不依赖 `java.nio.file.*`（改用 Okio FileSystem/Path 或可注入 FS）
- 支持 human-in-the-loop（权限门 + 提问/回答）但不涉及任何 UI
- 支持 session resume（从 `events.jsonl` 重建输入并继续 append-only）

## 背景与参考

- v1 产物：`docs/prd/PRD-0001-kotlin-core-agent-sdk.md`
- Python 参考（核心模块定义与测试口径）：`reference/openagentic-sdk-python/AGENTS.md`

## 范围（Scope）

### v2 In-scope

- **Android-ready IO**
  - 主源码（`src/main`）移除对 `java.nio.file.Path/Files` 的直接依赖
  - I/O 使用 `okio.FileSystem` + `okio.Path`
- **Human-in-the-loop（逻辑层）**
  - `PermissionGate`：以 `suspend` 形式批准/拒绝 tool use
  - `UserAnswerer`：以 `suspend` 形式回答 `UserQuestion`，回答为通用 `JsonElement`
  - `AskUserQuestion` 工具：tool call → `user.question` 事件 → tool.result（含 answer）
- **Sessions Resume（append-only）**
  - `options.resumeSessionId`：读取历史 `events.jsonl` 重建 provider input，并继续写入同一 session
  - 从历史 `result.response_id` 推导 `previous_response_id`（Responses 风格）

### v2 Non-goals

- UI（弹窗/对话框/聊天界面）
- hooks 全量对齐、compaction、slash commands、MCP 等
- 内置 tools 的“大而全”（v3 规划）

## 需求（Requirements）

### REQ-0002-001：主源码移除 java.nio 依赖
- **验收**：
  - `src/main/kotlin` 下无 `java.nio.file` import（允许测试代码使用）

### REQ-0002-002：UserQuestion 事件 + UserAnswerer（Json）
- **验收**：
  - `AskUserQuestion` 工具可产出 `user.question` 事件，并将 `UserAnswerer` 返回的 `JsonElement` 写入 tool.result.output

### REQ-0002-003：PermissionGate（suspend）+ prompt 语义
- **验收**：
  - tool call 在执行前必须经过 gate
  - prompt 模式：产出 `user.question`，并基于 answer 判断 allow/deny

### REQ-0002-004：Sessions Resume
- **验收**：
  - 对同一 session：先 run 一次产出 `result.response_id=resp_1`，再 resume 并继续对话时，provider 的 `previousResponseId` 为 `resp_1`
  - 事件落盘保持 append-only（历史事件仍在，新事件追加在后）

## 测试资产（核心）

v2 的核心验收以 Kotlin tests 为锚（确定性），命令：

```powershell
.\gradlew.bat test
```

