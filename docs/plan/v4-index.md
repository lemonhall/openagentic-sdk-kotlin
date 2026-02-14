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

## 计划索引

- `docs/plan/v4-compaction.md`
- `docs/plan/v4-tools.md`
- `docs/plan/v4-provider.md`
- `docs/plan/v4-cli-chat.md`

