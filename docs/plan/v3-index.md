# v3 Index: Tools + Hooks + Provider Parity（PRD-0003）

## 愿景

- PRD：`docs/prd/PRD-0003-v3-tools-hooks-provider-parity.md`

## 里程碑

### M5：Tools 全量对齐（含安全边界）

- **DoD（硬）**
  - Tools：Read/Write/Edit/Glob/Grep/Skill/SlashCommand/TodoWrite/Task/WebFetch 全部实现
  - 每个 tool ≥ 1 个正常用例 + ≥ 1 个异常/边界用例
  - 路径安全：禁止 `../` 逃逸（强制 under project root）
  - `.\gradlew.bat test` exit code = 0
- **状态**：todo

### M6：Hooks 完整对齐（核心 hook points）

- **DoD（硬）**
  - Hook points：UserPromptSubmit、Before/AfterModelCall、Pre/PostToolUse
  - 支持：消息改写、阻断策略、结构化事件落盘（HookEvent）
  - `.\gradlew.bat test` exit code = 0
- **状态**：todo

### M7：Provider 多协议兼容 + tool schemas

- **DoD（硬）**
  - LegacyProvider 与 ResponsesProvider 两条路径均可跑通（含 tool loop）
  - tool schemas 生成覆盖 v3 全量 tools，并对齐参考口径（参数名/alias/required）
  - `.\gradlew.bat test` exit code = 0
- **状态**：todo

### M8：最小 CLI（体验与验收）

- **DoD（硬）**
  - 提供一个命令行入口：能指定 provider+model、输入 prompt、打印事件流与最终结果
  - 默认不跑真网络；能用 fake provider 离线演示 tools/hook/resume
- **状态**：todo

## 计划索引

- `docs/plan/v3-tools.md`
- `docs/plan/v3-hooks.md`
- `docs/plan/v3-provider-compat.md`
- `docs/plan/v3-cli.md`

## 差异列表

- v3 完成后回填；v4 计划包含 compaction。

