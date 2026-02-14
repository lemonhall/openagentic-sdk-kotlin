# PRD-0001: Kotlin 版 OpenAgentic SDK 核心模块对齐（v1）

## Vision

为未来 Android 应用提供一个**纯 Kotlin**的 Agent SDK 核心库，核心能力与 Python 参考项目 `openagentic-sdk` 对齐：支持多轮会话（sessions）、回合事件（turn/events）、工具系统（tools）、技能系统（skills），并具备可验证的自动化测试资产。

## 背景与参考

- 参考实现（Python）：`reference/openagentic-sdk-python/`（仅用于对齐，不作为 Kotlin SDK 的发布内容）
- 参考“核心模块定义”：`reference/openagentic-sdk-python/AGENTS.md` 的“核心模块定义（默认优先级最高）”

## 目标用户

- Android/Kotlin 工程中需要“可嵌入、可持久化、可测试”的 Agent Runtime 核心能力的开发者。

## 范围（Scope）

本 PRD 仅覆盖“核心中的核心”的 Kotlin 最小闭环：

- **Sessions / Resume**
  - 基于 `events.jsonl` 的 append-only 会话落盘
  - 读取并用于“恢复/重建模型输入”的基础能力
- **Turns / Events**
  - 结构化事件模型（user/assistant/tool/use/result 等）
  - JSON 序列化/反序列化（roundtrip）
- **Tools**
  - Tool 定义、ToolContext、ToolRegistry
  - ToolRunner：执行 tool_call → 产生 tool.use / tool.result 事件
  - 允许工具白名单（allowed tools）与禁止工具错误（ToolNotAllowed）
- **Skills**
  - SKILL.md 解析（frontmatter + title + summary + checklist）
  - skills 索引：项目 `.claude/skills/**/SKILL.md` + 全局 `$OPENAGENTIC_SDK_HOME/skills/**/SKILL.md`
  - 项目技能覆盖全局技能（同名优先级）
- **Runtime Core（最小 tool loop）**
  - Provider 输出 tool_calls → 运行 tool → 将 tool 输出回填为 function_call_output → 再次调用 provider
  - previous_response_id 线程化（对齐 Python 的 Responses 风格）

## 非目标（Non-goals）

- CLI（`oa`）/ REPL / UI renderer
- Gateway / MCP / Remote tools surface
- HookEngine / compaction / slash command / task tool / webfetch 等高级链路（后续迭代）
- 真网络 E2E（v1 只做确定性的单元/集成测试）

## 需求（Requirements）

### REQ-0001-001：事件模型与 JSON Roundtrip
- **描述**：实现最小事件集（system.init、user.message、assistant.message、tool.use、tool.result、result；可扩展）。
- **验收**：
  - 对 `SystemInit`、`Result` 进行 JSON dumps/loads roundtrip 后对象相等（字段一致）。
  - 验证命令：`./gradlew test`（Windows：`.\gradlew.bat test`）。

### REQ-0001-002：FileSessionStore（events.jsonl）写入与读取
- **描述**：支持创建会话目录、写入 `events.jsonl`（append-only），并可读取为事件列表。
- **验收**：
  - 写入后文件存在且非空；读取事件数量与类型正确。

### REQ-0001-003：events.jsonl 不落 assistant.delta
- **描述**：对齐参考项目的关键约束：`events.jsonl` 不应持久化 streaming delta（避免膨胀）。
- **验收**：
  - 在“provider 流式输出 delta”的情况下，落盘 `events.jsonl` 不包含 `assistant.delta` 事件。

### REQ-0001-004：Skill Markdown 解析
- **描述**：解析 `SKILL.md`：
  - frontmatter（仅支持顶层 `--- ... ---`，`key: value`）
  - title（`# xxx`）
  - summary（title 后第一段）
  - checklist（`## Checklist` 下 `-`/`*` 列表）
- **验收**：
  - 解析出的 `name/summary/checklist` 正确；
  - frontmatter 的 `name/description` 优先于标题。

### REQ-0001-005：Skill Index（全局 + 项目 + 覆盖优先级）
- **描述**：索引技能：
  - 项目：`<projectDir>/.claude/skills/**/SKILL.md`（及兼容 `skill/`）
  - 全局：`$OPENAGENTIC_SDK_HOME/skills/**/SKILL.md`（及兼容 `skill/`）
  - 同名冲突：项目覆盖全局。
- **验收**：
  - 能索引项目技能；
  - 能索引全局技能（通过设置环境变量模拟）；
  - 冲突时选择项目版本。

### REQ-0001-006：Tool + ToolRegistry
- **描述**：Tool 必须有非空 `name`；ToolRegistry 可 register/get/names（排序）。
- **验收**：
  - 注册匿名/空名 tool 抛错；
  - 能按 name 获取 tool；names 按字典序稳定输出。

### REQ-0001-007：Runtime Tool Loop（Fake Provider）
- **描述**：实现最小运行时：
  1) provider 第一次返回 tool_calls
  2) runtime 执行工具并产生 `tool.use` + `tool.result`
  3) runtime 第二次调用 provider，携带 `previous_response_id` 与 function_call_output
  4) provider 返回 assistant_text，runtime 产出 `result`
- **验收**：
  - provider 被调用 2 次；
  - 第二次调用带 `previous_response_id=resp_1`；
  - events 包含 `tool.use`、`tool.result`、`result`。

### REQ-0001-008：ToolNotAllowed（allowed tools）
- **描述**：当 options 里声明 allowed tools 且 tool_call 不在白名单中时：
  - 不执行 tool
  - 直接产出 `tool.result`（`is_error=true`、`error_type=ToolNotAllowed`）
- **验收**：
  - 单测断言 error_type 与落盘/事件类型正确。

## 里程碑与交付物

- v1：完成上述 REQ-0001-001 ~ REQ-0001-008，且 `gradlew test` 全绿。

## 风险与缓解

- **Gradle/依赖下载不稳定**：使用 Gradle Wrapper 固化版本；必要时支持国内代理环境（后续）。
- **事件/序列化口径漂移**：以测试作为“口径锚点”，所有变更必须先改测试再改实现。

