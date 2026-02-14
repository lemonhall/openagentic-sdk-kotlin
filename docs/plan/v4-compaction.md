# v4: Compaction（overflow + pruning）

## Checklist

- [ ] 新增事件：`user.compaction`、`tool.output_compacted`
- [ ] `wouldOverflow()`（usage totals + OpenCode >= 边界）
- [ ] `selectToolOutputsToPrune()`（protect/min_prune/turn guard/summary pivot）
- [ ] rebuild provider input：marker 渲染 + placeholder 替换
- [ ] compaction pass：tool-less 调用 + `assistant.message(is_summary=true)` pivot
- [ ] 单测：overflow 触发 + marker 渲染 + placeholder 生效

