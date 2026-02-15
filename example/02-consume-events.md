# 02：消费事件流（delta/tool/result/error）

目标：展示“边跑边消费”的写法；特别是如何区分：

- `assistant.delta`：流式文本增量（如果 provider/协议产生）
- `tool.use` / `tool.result`：工具调用与结果
- `runtime.error`：结构化错误（v6 起保证落盘）
- `result`：最终结果

```kotlin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import me.lemonhall.openagentic.sdk.events.*
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk

fun main() = runBlocking {
  val events = OpenAgenticSdk.query(prompt = "hi", options = /* 见 01 */ TODO())

  events.collect { e ->
    when (e) {
      is SystemInit -> println("session=${e.sessionId}")
      is AssistantDelta -> print(e.textDelta)
      is AssistantMessage -> println("\nASSISTANT: ${e.text}")
      is ToolUse -> println("TOOL USE: ${e.name} id=${e.toolUseId} input=${e.input}")
      is ToolResult -> println("TOOL RESULT: id=${e.toolUseId} error=${e.isError} out=${e.output}")
      is RuntimeError -> println("RUNTIME ERROR: phase=${e.phase} type=${e.errorType} msg=${e.errorMessage}")
      is Result -> println("DONE stop_reason=${e.stopReason} response_id=${e.responseId}")
      else -> println("EVENT ${e.type}")
    }
  }
}
```

建议：

- UI 层通常只关心 `AssistantDelta/AssistantMessage`（显示）、`ToolUse/ToolResult`（调试面板）、`RuntimeError`（错误提示）、`Result`（收尾）。

