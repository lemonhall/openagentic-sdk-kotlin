# PRD-0004: Kotlin SDK v4（Compaction + Tools 补齐 + 真 CLI Chat / Responses API）

## Vision

在 v3 基础上，把 Kotlin 版 SDK 提升到“可真实体验/可真实验收”的状态：

1) **Compaction（自动 overflow + tool output pruning）**与 Python 参考工程对齐  
2) **Tools 补齐**：对齐 Python 默认工具集中 v3 尚未覆盖的工具（List/Bash/WebSearch/NotebookEdit/lsp）  
3) **真 CLI Chat**：提供一个可交互 REPL，真实对接 OpenAI Responses API（支持多轮、resume、权限门、tools、可选流式）

> v4 仍不追求 MCP / Gateway / Web UI；目标是“核心 SDK + CLI smoke 体验”。

## 背景与参考

- Python 参考仓库：`reference/openagentic-sdk-python/`
- 核心模块定义：`reference/openagentic-sdk-python/AGENTS.md`
- 关键参考实现：
  - Compaction：`reference/openagentic-sdk-python/openagentic_sdk/compaction.py`
  - Compaction driver：`reference/openagentic-sdk-python/openagentic_sdk/runtime_core/provider_input.py`
  - Default tools：`reference/openagentic-sdk-python/openagentic_sdk/tools/`
  - Responses tools schema：`reference/openagentic-sdk-python/openagentic_sdk/tools/openai_responses.py`

## 范围（Scope）

### v4 In-scope

#### 1) Compaction（core）

- **overflow auto-compaction**
  - 触发条件：`would_overflow(compaction, usage)` 与 OpenCode/Python 参考一致（>= 边界）
  - 触发后：写入 `user.compaction` marker；执行“无工具 compaction pass”；落盘 `assistant.message(is_summary=true)` 作为 pivot；重建 provider input；追加 `Continue if you have next steps` 并继续循环
- **tool output pruning（placeholder）**
  - 以 append-only 事件 `tool.output_compacted` 标记旧 tool.result 输出被 compact
  - rebuild provider input 时：被标记的 tool.result 输出替换为 placeholder（不破坏落盘原始内容）

#### 2) Tools 补齐（core）

- `List`：目录树（带 ignore + limit），用于 `@dir` 注入等场景
- `Bash`：bash/sh 命令执行（严格权限门 + 输出截断 + 可选写 full output 到文件）
- `WebSearch`：Tavily（有 key）/ DuckDuckGo HTML fallback（无 key），支持 allowed/blocked domains
- `NotebookEdit`：`.ipynb` cell insert/replace/delete（稳定 source 归一化）
- `lsp`：基于 OpenCode 风格配置的 stdio LSP client（至少可用 + 受权限门保护）

#### 3) Provider & schemas（responses）

- Responses 协议下 tools schema 形状对齐 Python（`{type,name,description,parameters}`）
- OpenAI Responses provider 支持：
  - `store`（对话存储）可配置；compaction pass 强制 `store=false`
  - 可选 **SSE 流式**：CLI 可边收边打印

#### 4) CLI Chat（体验）

- 提供 `chat` 子命令：
  - 多轮输入（REPL）
  - 支持 `--resume <session_id>`
  - 默认权限门模式 `default`（安全工具自动放行，其余 prompt）
  - 可选 `--stream`（若 provider 支持）
  - 打印 session_id、assistant 输出、tool.use/tool.result（可简洁）

### v4 Non-goals

- Compaction 的 token 精确计数（仅使用与 Python 相同的轻量估算/usage totals）
- 完整 CLI e2e（可留给后续迭代；v4 先以单测 + 手工体验为主）

## 需求（Requirements）

### REQ-0004-001：Compaction overflow + pruning 对齐
- **验收**：
  - `user.compaction` 与 `tool.output_compacted` 事件类型落盘可回放
  - overflow 触发后能自动总结并继续对话
  - rebuild input 时 placeholder 生效（不影响落盘原始 tool.result）

### REQ-0004-002：Tools 补齐 + schema 对齐
- **验收**：
  - List/Bash/WebSearch/NotebookEdit/lsp 均可被 registry 注册并可调用
  - OpenAI Responses tools schema 形状正确
  - 每个新增工具都有基本单测（正常/异常/边界）

### REQ-0004-003：真 CLI Chat 可用（Responses API）
- **验收**：
  - `.\gradlew.bat run --args="chat --help"` 可跑
  - `chat` 模式可真实请求 Responses API（需要 `OPENAI_API_KEY`）
  - tool loop / permission gate / sessions resume / compaction 不互相打架

## 验证命令

```powershell
.\gradlew.bat test
.\gradlew.bat run --args="chat --help"
```

