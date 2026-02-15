# 05：PermissionGate（DEFAULT / PROMPT）+ userAnswerer

目标：演示 HITL（人类在环）如何接入：SDK 通过 `UserQuestion` 把“是否允许某工具”交给你的 UI/业务层回答。

## DEFAULT：安全工具默认放行，其它工具需要 answerer

```kotlin
import kotlinx.serialization.json.JsonPrimitive
import me.lemonhall.openagentic.sdk.events.UserQuestion
import me.lemonhall.openagentic.sdk.permissions.UserAnswerer
import me.lemonhall.openagentic.sdk.permissions.PermissionGate

val gate = PermissionGate.default(
  userAnswerer = UserAnswerer { q: UserQuestion ->
    // 这里只是示例：真实场景请把 q.prompt/q.choices 展示给用户
    JsonPrimitive("no") // 默认拒绝（更安全）
  }
)
```

## PROMPT：所有“非 AskUserQuestion”工具都提示

```kotlin
val gate = PermissionGate.prompt(
  userAnswerer = UserAnswerer { q -> JsonPrimitive("yes") } // 示例：自动同意（请谨慎）
)
```

推荐实践：

- UI 层用 `UserQuestion` 事件驱动一个“确认弹窗”。
- 记录审计：把每次允许/拒绝写入你的业务日志（SDK trace 里也会有 `tool.use/tool.result`）。
