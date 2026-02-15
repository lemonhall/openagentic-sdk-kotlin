# v7: opencode-tests-checklist Closure（对齐工具边界 + Retry-After）

参照：`docs/checklist/opencode-tests-checklist.md`

## Goal

对比源头项目 opencode 的测试清单与本仓库现有测试套件，补齐“同类能力”的关键门禁：

- Tools：`Read/List/Grep/Bash` 的边界/异常/截断契约
- Provider Retry：`Retry-After` 解析与 backoff 上限

## Acceptance（硬）

- `.\gradlew.bat test` exit code = 0
- 新增测试覆盖：
  - Read：offset 越界必报错；大文件/大行截断有标记
  - List：`truncated` 不误报（仅在确实被截断时为 true）
  - Grep：CRLF/Mixed 行尾稳定；`include_hidden=false` 不会扫 hidden
  - Bash：timeout/killed、超大输出落盘（full_output_file_path）可测
  - Retry-After：seconds/ms/http-date 解析可测；超大 retryAfter/backoff 有上限

## Diff（opencode checklist vs Kotlin 当前基线）

### 已有（v6 基线已覆盖/部分覆盖）

- provider：429 rate-limit backoff 使用 coroutine-test 虚拟时钟（`offline_provider_rate_limit_backoff_uses_fake_clock`）
- tools：基础 Read/List/Grep 单测；runtime 工具 loop/权限/安全边界离线门禁

### 主要缺口（v7 解决）

- Read：截断无显式标记；offset 越界不报错；超长单行无截断
- List：`truncated` 目前按 `count>=limit` 计算，存在误报风险
- Grep：缺 `include_hidden` 行为门禁；缺 CRLF/Mixed 行尾回归用例
- Bash：缺单测覆盖（stdout/stderr/exit/timeout/落盘）
- Retry-After：provider 仅支持秒数；不支持 http-date / ms；runtime 未对 retryAfter 进行上限约束

## Work Items（红→绿→门禁）

### W1：ReadTool 边界契约（offset 越界 + 截断标记 + 超长行）

- Tests：
  - `ReadToolTest.readOffsetOutOfRangeFailsWithMessage`
  - `ReadToolTest.readMarksTruncationForLargeFile`
  - `ReadToolTest.readTruncatesVeryLongSingleLine`
- Impl：
  - `ReadTool` 输出增加：`truncated`、`bytes_returned`、`file_size`（尽量不破坏现有字段）
  - offset 越界：抛出 `IllegalArgumentException`，消息包含 total_lines 与 offset 范围
  - 单行长度上限：超长行截断并加 `…(truncated)` 标记

### W2：ListTool truncated 不误报（limit+1 探测）

- Tests：
  - `ListToolTest.listTruncatedFalseWhenExactlyLimitAndNoMore`
  - `ListToolTest.listTruncatedTrueWhenMoreThanLimit`
- Impl：
  - `ListTool` 内部收集时使用 `limit+1` 探测是否还有更多条目；仅在确实超过 limit 时 `truncated=true`

### W3：GrepTool hidden/行尾门禁

- Tests：
  - `GrepToolTest.grepHandlesCrlfAndMixedLineEndings`
  - `GrepToolTest.grepCanExcludeHiddenWhenIncludeHiddenFalse`
  - `GrepToolTest.grepNoMatchesIsNotError`
- Impl：
  - `GrepTool` 新增参数：`include_hidden`（默认 true）；false 时跳过任意路径段以 `.` 开头的文件/目录

### W4：BashTool 单测门禁（timeout + 超大输出落盘）

- Tests：
  - `BashToolTest.bashCapturesStdoutStderrAndExitCode`
  - `BashToolTest.bashTimeoutSetsKilledAndExitCode137`
  - `BashToolTest.bashLargeOutputIsTruncatedAndWrittenToFile`
- Impl：
  - 若测试环境缺少 `bash`：测试用 assumption 自动跳过（避免在无 bash 的平台上误伤）

### W5：Retry-After 解析 + backoff 上限（防“超长 sleep”）

- Tests：
  - `OpenAIResponsesHttpProviderRetryAfterTest.parseRetryAfterSecondsAndMs`
  - `OpenAIResponsesHttpProviderRetryAfterTest.parseRetryAfterHttpDate`
  - `OfflineChecklistAlignedCompactionProviderSecurityTest.offline_provider_rate_limit_backoff_is_capped_by_maxBackoff`
- Impl：
  - `parseRetryAfterMs(...)` 支持 seconds / `123ms` / RFC1123 http-date（过去时间忽略）
  - `callWithRateLimitRetry()`：等待时间 `waitMs` 受 `maxBackoffMs` 上限约束

## 追溯矩阵（Checklist → Tests/Impl）

| opencode checklist | Kotlin 侧实现/测试 |
|---|---|
| Read：offset 越界报错、截断标记、超长单行截断 | `src/main/kotlin/me/lemonhall/openagentic/sdk/tools/ReadTool.kt`；`src/test/kotlin/me/lemonhall/openagentic/sdk/tools/ReadToolTest.kt` |
| Read：图片读取不误判 | 继续由 `ReadToolTest.readReturnsBase64ForPngLikeFiles` 覆盖 |
| Grep：CRLF/Mixed 行尾 | `src/test/kotlin/me/lemonhall/openagentic/sdk/tools/GrepToolTest.kt` |
| Grep：hidden 行为 | `src/main/kotlin/me/lemonhall/openagentic/sdk/tools/GrepTool.kt`；`src/test/kotlin/me/lemonhall/openagentic/sdk/tools/GrepToolTest.kt` |
| Bash：stdout/stderr/exit/timeout/截断落盘 | `src/main/kotlin/me/lemonhall/openagentic/sdk/tools/BashTool.kt`；`src/test/kotlin/me/lemonhall/openagentic/sdk/tools/BashToolTest.kt` |
| Retry：Retry-After（ms/seconds/http-date）+ 上限 | `src/main/kotlin/me/lemonhall/openagentic/sdk/providers/OpenAIResponsesHttpProvider.kt`；`src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt`；对应测试见上 |

## 验证命令

```powershell
.\gradlew.bat test
```

