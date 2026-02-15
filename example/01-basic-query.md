# 01：最小闭环（Responses provider + query）

目标：用 `OpenAIResponsesHttpProvider` 跑一轮 query，并拿到 `Result` + `session_id`。

```kotlin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.lemonhall.openagentic.sdk.providers.OpenAIResponsesHttpProvider
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import me.lemonhall.openagentic.sdk.tools.ToolRegistry
import okio.FileSystem
import okio.Path.Companion.toPath

fun main() = runBlocking {
  val apiKey = System.getenv("OPENAI_API_KEY")?.trim().orEmpty()
  require(apiKey.isNotEmpty()) { "missing OPENAI_API_KEY" }

  val cwd = System.getProperty("user.dir").replace('\\', '/').toPath()
  val store = FileSessionStore.system(System.getProperty("user.home") + "/.openagentic-sdk")

  val options = OpenAgenticOptions(
    provider = OpenAIResponsesHttpProvider(),
    model = System.getenv("MODEL")?.ifBlank { null } ?: "gpt-4.1-mini",
    apiKey = apiKey,
    fileSystem = FileSystem.SYSTEM,
    cwd = cwd,
    projectDir = cwd,
    tools = ToolRegistry(),           // 先不注册工具也能跑纯对话
    sessionStore = store,
  )

  val events = OpenAgenticSdk.query("用一句话解释什么是 tool loop。", options).toList()
  val result = events.last { it.type == "result" }
  println(result)
}
```

要点：

- `session_id` 会在 `system.init` 事件里出现（新 session），以及 `result.session_id`（结束时）。
- SDK 会把事件落到 `~/.openagentic-sdk/sessions/<session_id>/events.jsonl`（可用于 resume）。

