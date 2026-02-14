# v4 Index: Compaction + More Tools + Real CLI Chat（PRD-0004）

- PRD：`docs/prd/PRD-0004-v4-compaction-more-tools-real-cli.md`

## 里程碑

### M9：Compaction（overflow + pruning）
- DoD：
  - `user.compaction` / `tool.output_compacted` 事件落盘
  - overflow 自动 compaction pass + summary pivot + continue
  - placeholder 替换旧 tool.result 输出

### M10：Tools 补齐（List/Bash/WebSearch/NotebookEdit/lsp）
- DoD：
  - 新工具实现 + schema + 单测
  - 路径/网络/执行类工具具备安全边界（配合 PermissionGate）

### M11：Responses tools schema + 可选流式
- DoD：
  - responses tools schema 形状对齐 Python
  - OpenAI Responses provider 支持 `store` + `stream`

### M12：CLI Chat（真体验）
- DoD：
  - 交互式 REPL（多轮 + resume + tools + permissions）
  - `--stream` 可选，体验上可持续输出

### M13：Offline E2E（Hard Invariants / Core）
- DoD：
  - PR 级门禁：`.\gradlew.bat test` 全绿且零 flake
  - 新增 offline hard invariants 用例（至少覆盖 events/jsonl、sessions/resume、hooks、permission、compaction）
  - 用例断言以 trace/events 为主，不依赖最终自然语言
  - 证据（当前实现）：`src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineHardInvariantsTest.kt`（15 用例）

## 计划索引

- `docs/plan/v4-compaction.md`
- `docs/plan/v4-tools.md`
- `docs/plan/v4-provider.md`
- `docs/plan/v4-cli-chat.md`
- `docs/plan/v4-offline-e2e.md`
