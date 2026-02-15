# 10：Task tool（注入 taskRunner）

SDK 内置一个 `Task` 工具：模型可以请求“把某个子任务交给另一个 agent/执行器去跑”，SDK 通过你注入的 `taskRunner` 来执行。

## 1) 注入 taskRunner

```kotlin
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.lemonhall.openagentic.sdk.runtime.TaskRunner

val runner = TaskRunner { agent, prompt, context ->
  // 这里你可以接入：另一个 OpenAgenticSdk 实例、或者你自己的 pipeline
  buildJsonObject {
    put("agent", JsonPrimitive(agent))
    put("prompt", JsonPrimitive(prompt))
    put("session_id", JsonPrimitive(context.sessionId))
    put("tool_use_id", JsonPrimitive(context.toolUseId))
    put("result", JsonPrimitive("stubbed"))
  }
}
```

## 2) 在 options 里启用

```kotlin
OpenAgenticOptions(
  // ...
  taskRunner = runner,
)
```

## 3) 运行时行为（你会在 trace 里看到）

- `tool.use(name="Task")`：包含 `agent` 与 `prompt`
- `tool.result`：输出是你 `taskRunner` 返回的 JSON（或错误）

