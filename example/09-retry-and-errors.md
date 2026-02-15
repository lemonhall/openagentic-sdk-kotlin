# 09：429 Retry-After / backoff 上限 / runtime.error

目标：展示 v6/v7 的“可解释失败”与“确定性退避”：

- provider 抛 `ProviderRateLimitException(retryAfterMs=...)` → SDK 按 `providerRetry` 重试
- retryAfterMs 会被 `maxBackoffMs` 上限限制（v7）
- 未捕获异常会落 `runtime.error`，最终 `Result.stop_reason="error"`（v6）

```kotlin
import me.lemonhall.openagentic.sdk.runtime.ProviderRetryOptions

val retry = ProviderRetryOptions(
  maxRetries = 2,
  initialBackoffMs = 200,
  maxBackoffMs = 2_000,     // 上限（避免极端 retry-after 拉长等待）
  useRetryAfterMs = true,   // 优先用 provider 的 retryAfterMs
)

OpenAgenticOptions(
  // ...
  providerRetry = retry
)
```

调试建议：

- 如果你在 trace 里看到了 `runtime.error(phase="provider")`：优先检查 provider 侧返回/解析（HTTP 状态、JSON、SSE）。
- 如果是 `runtime.error(phase="session")`：优先检查 sessions/tools/hooks 自己抛出的异常。

