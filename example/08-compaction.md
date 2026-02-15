# 08：Compaction（自动压缩 / 输出裁剪）

目标：在 token 逼近上限时自动触发 compaction（总结 + 可选 prune 工具输出）。

```kotlin
import me.lemonhall.openagentic.sdk.compaction.CompactionOptions

val compaction = CompactionOptions(
  auto = true,
  prune = true,
  contextLimit = 120_000, // 你的模型上下文上限（按你的产品口径设置）
  globalOutputCap = 4096,
  reserved = 20_000,
)

OpenAgenticOptions(
  // ...
  compaction = compaction
)
```

可观察现象：

- trace 中会出现 `user.compaction`（auto=true）与 `assistant.message(is_summary=true)`。
- 如果启用了 prune，并且达到门槛，会出现 `tool.output_compacted`，旧 tool output 被替换为 placeholder（防上下文爆炸）。

