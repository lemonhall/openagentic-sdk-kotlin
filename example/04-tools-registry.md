# 04：注册工具 + Tool schema（给模型的工具说明）

目标：注册工具到 `ToolRegistry`，并让 SDK 生成 OpenAI tool schema（Responses/Legacy 两种协议会生成不同形态）。

```kotlin
import me.lemonhall.openagentic.sdk.tools.*

val tools = ToolRegistry(
  listOf(
    ReadTool(),
    ListTool(),
    GrepTool(),
    GlobTool(),
    BashTool(),
    WebFetchTool(),
    WebSearchTool(),
    SlashCommandTool(),
    SkillTool(),
    NotebookEditTool(),
    TodoWriteTool(),
  )
)
```

权限与安全提示：

- 默认建议配 `PermissionGate.default(...)`（安全工具默认允许，其它工具走 HITL 提示）。
- `Read/List/Grep/Glob` 的路径会被限制在 `projectDir`（或 `cwd`）之下，并阻止 symlink escape（v6 已加门禁）。

你通常会在构造 `OpenAgenticOptions` 时注入：

```kotlin
OpenAgenticOptions(
  tools = tools,
  allowedTools = setOf("Read","Grep","Glob","List"), // 可选：再加一层白名单
  // ...
)
```

