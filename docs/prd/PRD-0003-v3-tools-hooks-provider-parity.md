# PRD-0003: Kotlin SDK v3（Tools 全量对齐 + Hooks 完整对齐 + Provider 多协议兼容）

## Vision

在 Kotlin SDK 中一次性交付与 Python 参考项目 `openagentic-sdk` 对齐的核心能力（除 compaction 外）：

1) Tools **全量对齐**（Write/Edit/Glob/Grep/Skill/SlashCommand/TodoWrite/Task/WebFetch…）
2) Hooks **尽快完整对齐**（pre/post model、消息改写、阻断策略等）
3) Provider **多协议兼容**（legacy messages vs responses input、tool schemas），可连接真实 LLM 进行验收

> Compaction（marker + tool output pruning + placeholder）明确延后到 v4。

## 背景与参考

- Python 参考仓库：`reference/openagentic-sdk-python/`
- 核心模块定义：`reference/openagentic-sdk-python/AGENTS.md`（Runtime Core / Tools / Skills / Hooks / Sessions）
- v2 已完成：Okio I/O、PermissionGate + AskUserQuestion、Sessions resume（`docs/plan/v2-index.md`）

## 范围（Scope）

### v3 In-scope

- **Tools（全量）**
  - 以 Python 参考的 tool schemas 与行为为锚，完成以下工具：
    - Read / Write / Edit / Glob / Grep
    - Skill（加载 SKILL.md）
    - SlashCommand（加载并渲染命令模板）
    - TodoWrite（会话 TODO 列表落盘/事件）
    - Task（子代理调用：以可注入 runner 形式实现；未配置时返回结构化错误）
    - WebFetch（HTTP(S) fetch；含基础安全边界与可测试的行为口径）
- **Hooks（完整对齐优先核心点）**
  - Hook points：
    - UserPromptSubmit（用户输入提交时）
    - BeforeModelCall / AfterModelCall
    - PreToolUse / PostToolUse
  - 能力：
    - 消息改写（例如：在 system/developer 注入提示、或改写 user prompt）
    - 阻断策略（block tool use / block model call）
    - 结构化 HookEvent 事件落盘（可选但建议对齐）
- **Provider 多协议兼容**
  - 兼容两类 provider：
    - Legacy（messages[]）
    - Responses（input[] + previous_response_id）
  - tool schemas：
    - 按 OpenAI 兼容 function schema 输出（与参考项目口径对齐）
  - runtime 选择协议：
    - 自动检测 provider 类型（或显式 options 指定），并正确构建 messages/input 与 previous_response_id 线程化

### v3 Non-goals

- Compaction（v4）
- CLI/REPL（在 v3 完成后立即做一个最小 CLI，用于体验与验收）

## 需求（Requirements）

### REQ-0003-001：Tools 全量对齐（功能 + 安全边界 + 测试）
- **验收**：
  - 每个 tool 都有单测覆盖：正常/异常/边界
  - 路径安全：tool 文件路径必须在 project root 下（禁止 traversal）
  - `.\gradlew.bat test` 全绿

### REQ-0003-002：Hooks 完整对齐（核心 hook points）
- **验收**：
  - Hook 可改写 prompt/messages
  - Hook 可阻断 tool use，并返回结构化错误（error_type=HookBlocked 或等价）
  - `.\gradlew.bat test` 全绿

### REQ-0003-003：Provider 多协议兼容 + tool schemas
- **验收**：
  - LegacyProvider：使用 messages[] 调用；能跑通 tool loop
  - ResponsesProvider：使用 input[] + previous_response_id；能跑通 tool loop
  - tool schemas 输出字段与参考口径一致（名称/参数字段/常见 alias）
  - `.\gradlew.bat test` 全绿

## 验证命令

```powershell
.\gradlew.bat test
```

