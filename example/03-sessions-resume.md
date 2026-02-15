# 03：Sessions + Resume（断点续聊）

目标：拿到首次运行的 `session_id`，下次带 `resumeSessionId` 续上（并自动携带 `previous_response_id`）。

```kotlin
import kotlinx.coroutines.runBlocking
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticOptions
import me.lemonhall.openagentic.sdk.runtime.OpenAgenticSdk
import me.lemonhall.openagentic.sdk.sessions.FileSessionStore
import okio.FileSystem
import okio.Path.Companion.toPath

fun main() = runBlocking {
  val cwd = System.getProperty("user.dir").replace('\\', '/').toPath()
  val store = FileSessionStore.system(System.getProperty("user.home") + "/.openagentic-sdk")

  val first = OpenAgenticSdk.run(
    prompt = "请总结一下：Kotlin coroutines 的结构化并发是什么？",
    options = OpenAgenticOptions(
      provider = /* OpenAIResponsesHttpProvider() */ TODO(),
      model = "gpt-4.1-mini",
      apiKey = System.getenv("OPENAI_API_KEY"),
      fileSystem = FileSystem.SYSTEM,
      cwd = cwd,
      projectDir = cwd,
      sessionStore = store,
    )
  )
  println("first sessionId=${first.sessionId}")

  val second = OpenAgenticSdk.run(
    prompt = "继续，给一个反例说明结构化并发解决了什么。",
    options = OpenAgenticOptions(
      provider = /* 同上 */ TODO(),
      model = "gpt-4.1-mini",
      apiKey = System.getenv("OPENAI_API_KEY"),
      fileSystem = FileSystem.SYSTEM,
      cwd = cwd,
      projectDir = cwd,
      sessionStore = store,
      resumeSessionId = first.sessionId,
    )
  )
  println("second final=${second.finalText}")
}
```

要点：

- `resumeSessionId` 不是“重放”，而是先读 `events.jsonl` 作为上下文，再接着跑新一轮。
- `resumeMaxEvents` / `resumeMaxBytes` 可用于限制 resume 读取量（防止超大 trace 拖慢启动）。

