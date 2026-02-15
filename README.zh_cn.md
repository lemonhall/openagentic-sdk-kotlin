# openagentic-sdk-kotlin

一个面向 **Android** 的 **纯 Kotlin Agent SDK 核心**：包含 runtime tool loop、sessions、events、tools、skills、permissions/HITL、hooks 等基础能力。

本仓库的取向：

- Runtime/tool-loop 核心能力稳定可复用
- 离线 E2E 门禁（确定性、适合 PR）强化安全与鲁棒性
- 提供最小 CLI 便于本地验证

## 快速开始

### 运行测试（Windows PowerShell）

```powershell
.\gradlew.bat test
```

### 运行 CLI

CLI 从项目根目录 `.env` 读取：

- `OPENAI_API_KEY`
- `OPENAI_BASE_URL`（可选）
- `MODEL`（可选）

```powershell
.\gradlew.bat run --args="chat"
```

## 示例

面向“人类读者”的 SDK 用法示例在：`example/README.md`。

## OpenAgentic SDK 家族（兄弟项目）

本 Kotlin 仓库属于一个小型“SDK 家族”：大家共享同一套总体思想（可追溯的 sessions/events、默认安全的 tools、确定性的测试门禁），但分别面向不同的运行环境与产品形态。

### 1）`openagentic-sdk`（Python，最早的源头）

- 仓库：`https://github.com/lemonhall/openagentic-sdk`
- 家族最早的项目，也是**测试套件最强壮**的版本（据描述约 369 个测试）。
- 工具最全面，同时包含 CLI 工具等配套。
- 具备 TUI 的 sessions 观察窗（dashboard）用于查看 trace。

### 2）`openagentic-sdk-ts`（TypeScript，沙盒优先）

- 仓库：`https://github.com/lemonhall/openagentic-sdk-ts`
- 基于 Python 版本衍生，重点是**完整的沙盒系统**。
- 提供**浏览器内沙盒**；在 Linux 和浏览器环境下都非常安全。

基于 TS 项目进一步发展出的侧边栏助手：

- `WebMCP_Sidecar`：`https://github.com/lemonhall/WebMCP_Sidecar`
- 支持 WebMCP 的全功能浏览器侧边栏 AI 助手。

### 3）`openagentic-sdk-gdscript`（Godot 4.6，最复杂的一支）

- 仓库：`https://github.com/lemonhall/openagentic-sdk-gdscript`
- 面向 Godot 4.6 的 SDK，包含完整的 **VR Office（虚拟办公室）**、2D demo 等。
- 内置一整套 GDScript 的 **IRC client SDK**。
- 实现基于 IRC 的 RPC 协议，并在其上支持类似 OpenClaw 的多机架构。

### 4）`openagentic-sdk-kotlin`（本仓库，面向 Android）

- 仓库：`https://github.com/lemonhall/openagentic-sdk-kotlin`
- 基于 Kotlin 的完整 Agent SDK 架构，主要面向 Android 的可复用核心。

### 未来计划（凑齐约 6 兄弟）

- Swift（Apple 平台）
- Rust（系统侧 + CLI/嵌入式方向）

功能分布未来可能会演进，但核心目标一致：**安全的 Agent runtime + 强可追溯测试**。

