# v7 Index: 对齐 opencode-tests-checklist（工具边界 + Retry-After + 截断标记）

参照：`docs/checklist/opencode-tests-checklist.md`

## 愿景（v7）

把源头项目（opencode）的“核心测试套件思想”落到本仓库的 Kotlin SDK 上：**同类能力同类门禁**，尤其是工具（Read/List/Grep/Bash）与 provider retry 这两块的“边界/异常/可解释性”。

v7 的交付目标（PR 门禁口径）：

- Tools：
  - `Read`/`List`：截断/分页/offset 越界等边界行为稳定、可解释，并且**不会误报 truncated**
  - `Grep`：CRLF/Mixed 行尾稳定；`include_hidden`（默认包含 hidden）行为可测
  - `Bash`：stdout/stderr/exit code/timeout/超大输出落盘的契约可测（在没有真实网络/LLM 的前提下）
- Provider Retry：
  - `Retry-After` 解析更健壮（seconds / ms / http-date）
  - Runtime rate-limit backoff **有上限**（避免被异常 header/fixture 拉成超长 sleep）

## Non-goals（本仓库尚无对应能力，v7 不硬造）

- opencode 的 agent 列表/禁用/合并策略（本仓库没有 agent registry 概念）
- scheduler scope（本仓库无 scheduler）
- 存储迁移（本仓库无 migration 管线/版本化 schema）
- structured output（本仓库未实现 structured output 语义）
- revert+compact 状态机（本仓库未实现 revert）

## 计划索引

- `docs/plan/v7-opencode-tests-checklist-closure.md`

## 验证命令（门禁口径）

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.tools.*"
.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.providers.*"
```

