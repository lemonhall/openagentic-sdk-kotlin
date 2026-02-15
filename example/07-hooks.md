# 07：HookEngine（消息改写 / 工具入参改写 / 隔离异常）

目标：演示常见 hook 用法：

- BeforeModelCall：给最后一条 user prompt 加前缀
- PreToolUse：给工具入参打补丁（比如默认加 `include_hidden=false`）

```kotlin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.lemonhall.openagentic.sdk.hooks.*

val engine = HookEngine(
  enableMessageRewriteHooks = true,
  beforeModelCall = listOf(
    HookMatcher(
      name = "prefix-user",
      toolNamePattern = "*",
      hook = { payload ->
        val input = payload["input"]?.jsonArray?.mapNotNull { it.jsonObject } ?: emptyList()
        val lastUser =
          input.asReversed()
            .firstOrNull { it["role"]?.jsonPrimitive?.content == "user" }
            ?.get("content")?.jsonPrimitive?.content
            .orEmpty()
        HookDecision(
          overridePrompt = "[请用中文回答]\n$lastUser",
          action = "rewrite_prompt",
        )
      }
    )
  ),
  preToolUse = listOf(
    HookMatcher(
      name = "grep-defaults",
      toolNamePattern = "Grep",
      hook = { payload ->
        val toolInput = payload["tool_input"] as? JsonObject ?: buildJsonObject { }
        HookDecision(
          overrideToolInput = buildJsonObject {
            for ((k, v) in toolInput) put(k, v)
            if (!toolInput.containsKey("include_hidden")) put("include_hidden", JsonPrimitive(false))
          },
          action = "patch_args",
        )
      }
    )
  ),
)
```

注意：

- hook 抛异常不会炸整个 loop：SDK 会写 `hook.event(error_type/error_message)` 并继续（v6 用例已锁死）。
- 如果你希望 hook 能“阻断执行”，返回 `HookDecision(block=true, blockReason=...)`。
