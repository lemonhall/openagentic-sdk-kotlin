# v5: Offline E2E Robustness（Close the Checklist Gaps）

参照：`reference/openagentic-sdk-python/kotlin-sdk-core-e2e-test-checklist.md`

## Goal

在 v4（Offline hard invariants）基础上，把 checklist 中“强烈建议/高频坑”的缺口全部补齐，并固化为 PR 级门禁：

- **Unknown event / schema evolution** 不会导致 `events.jsonl` 读取崩溃
- **stream 半包/粘包** 的解析被离线测试锁死（不靠真实网络）
- **取消/中断** 不产生半行 JSONL，且可恢复
- **fuzz/chaos/并发** 覆盖 Kotlin/Windows 高频坑
- **secrets 不落盘**（trace contract）

## PRD Trace

本轮为测试/门禁建设（基础设施），追溯到既有 PRD 的验收锚点：

- PRD-0001：events/sessions/runtime loop
- PRD-0002：permissions/resume（取消/恢复语义）
- PRD-0003：hooks/provider 适配层边界
- PRD-0004：responses/stream 解析路径

## Scope / Non-goals

- In-scope：
  - Offline hard invariants 的“鲁棒性扩展”：unknown event、normalizer/strict、stream parser、cancel、fuzz、secrets、并发 smoke
  - 允许对生产代码做**必要的小改动**，以满足可测性与稳定性（例如：抽取 parser、注入 clock/seed 的 seam）
- Non-goals：
  - 引入真实网络/真实 LLM 的在线 E2E 作为 PR 门禁
  - CLI/TTY E2E（仍不测）

## Current（v4 已实现的门禁资产）

- offline e2e：`src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineHardInvariantsTest.kt`
- session store：坏行/截断策略已被测试锁死（但 unknown event 仍未处理）

## Acceptance（硬）

- 命令：`.\gradlew.bat test`
  - exit code = 0
  - 同机重复运行 3 次（手动/CI）结果一致（零 flake）
- 新增测试：
  - 新增用例 ≥ 25（含 fuzz/chaos/stream/unknown event/并发/secrets）
  - 每类缺口至少 1 个“核心用例” + 1 个“边界/异常用例”
- 失败可解释：
  - 任何断言失败必须输出 `session_id` + `seq`（或行索引）+ `tool_use_id/call_id`（如适用）

## 设计选择（本轮需先定死）

### D1：Unknown event type 的策略（必须二选一）

- 方案 A（推荐）：解析为 `UnknownEvent(type, raw)`，允许 roundtrip（不丢字段），避免读历史 trace 时崩溃。
- 方案 B：读取时直接 skip unknown，并记录诊断事件（会丢失原事件内容，慎用）。

本轮选择：A。

### D2：events.jsonl 坏行策略（保持 v4 口径）

- 中间坏行：fail-fast（抛 `IllegalStateException`，含 session_id/行号）
- 尾部坏行/截断行：忽略（read）+ append 前修复尾部（write）

### D3：stream parser 的可测性策略

- 抽取纯函数/可注入 reader 的解析器：
  - 输入：字节/行片段流（可模拟半包/粘包）
  - 输出：`ProviderStreamEvent` 序列 + 最终 Completed/Failed
- HTTP 只负责“拿到字节流”，解析与组装由 parser 负责

### D4：fuzz/chaos 的“PR 级可接受成本”

- 不引入新依赖也可先做：固定 seed 的随机生成 + case 数控制（≥ 200）
- 每个 fuzz 用例失败时必须打印 seed + case index

## Work Items（按塔山循环：红→绿→重构→门禁）

### W1：UnknownEvent + EventJson 不崩溃

- Tests（Red）：
  - `offline_events_unknown_type_is_parsed_as_unknown_event`
  - `offline_events_unknown_type_roundtrip_preserves_raw_fields`
- Impl（Green）：
  - 新增 `UnknownEvent` 模型（实现 `Event`）
  - `EventJson.fromJsonObject()` 遇到未知 `type` 返回 `UnknownEvent`
  - `EventJson.dumps()` 支持 `UnknownEvent`（按 raw 输出）

### W2：Trace Normalizer + Strict gate

- Tests（Red）：
  - `offline_trace_normalizer_ignores_ts_and_duration`
  - `offline_trace_strict_missing_tool_result_fails_with_session_and_seq`
- Impl（Green）：
  - `TraceNormalizer`（test 侧即可）：规范化 JSON（排序 key、移除噪声字段、保留核心字段）
  - `TraceStrictAsserts`：最小证据链（tool.use ↔ tool.result）、seq 单调、禁止 delta、禁止 secrets

### W3：Streaming parser（半包/粘包/变形）

- Tests（Red）：
  - `offline_provider_stream_parse_half_packet`
  - `offline_provider_stream_parse_sticky_packets`
  - `offline_provider_stream_data_multiline_join`
  - `offline_provider_stream_ends_without_completed_is_failed`
- Impl（Green）：
  - 抽取 `SseLineParser`（或等价）到可单测的位置
  - `OpenAIResponsesHttpProvider.stream()` 复用抽取后的解析器

### W4：Cancellation / No partial JSONL

- Tests（Red）：
  - `offline_cancel_during_provider_call_does_not_write_partial_jsonl`
  - `offline_cancel_during_tool_run_does_not_write_partial_jsonl`
  - `offline_cancel_then_resume_can_continue_seq_monotonic`
- Impl（Green）：
  - 明确“取消事件是否落盘”的策略（如需：新增 `runtime.cancelled` 事件；如不需：至少保证无损坏）
  - 若发现写入路径可被打断：采用“先写临时文件再原子 rename”的写策略（仅在必要时引入）

### W5：Fuzz（events.jsonl / tool args）

- Tests（Red）：
  - `fuzz_events_jsonl_mixed_corruption_policy_is_stable(seed)`
  - `fuzz_tool_args_random_json_does_not_crash(seed)`
- Impl（Green）：
  - 生成器（seeded）：JsonElement 随机树、随机插入截断/控制字符/超长行
  - 断言：不 crash；策略行为固定（skip/fail-fast）且可解释

### W6：Secrets + Concurrency smoke

- Tests（Red）：
  - `offline_trace_does_not_contain_secrets_blacklist`
  - `offline_concurrent_sessions_do_not_cross_contaminate`
- Impl（Green）：
  - secrets 黑名单断言（test 侧）：`authorization`/`api_key`/`device_token`/`Bearer ` 等
  - 并发 smoke：并行跑 N 个 session，验证落盘隔离与 seq 单调

## Files（预期会新增/改动）

- 新增/改动（生产）：
  - `src/main/kotlin/me/lemonhall/openagentic/sdk/sessions/EventJson.kt`
  - `src/main/kotlin/me/lemonhall/openagentic/sdk/events/Events.kt`（UnknownEvent / 或 HookEvent 扩展已做则保持）
  - `src/main/kotlin/me/lemonhall/openagentic/sdk/providers/OpenAIResponsesHttpProvider.kt`（抽 parser）
  - （可选）新增 `src/main/kotlin/me/lemonhall/openagentic/sdk/providers/SseParser.kt`
- 新增（测试）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineHardInvariantsRobustnessTest.kt`
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/testing/TraceNormalizer.kt`
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/testing/TraceStrictAsserts.kt`
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/testing/SseTestHarness.kt`

## Verification（命令口径）

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.e2e.*"
```

## Risks

- UnknownEvent 策略属于“兼容性承诺”：一旦引入，后续不能轻易移除（但收益很大：读历史 trace 不炸）
- stream parser 抽取需避免行为漂移：必须用离线 fixture 锁住输入输出
- fuzz/chaos 容易变慢：必须固定 case 数与 seed，确保 PR 可承受

## 追溯矩阵（Checklist → Tests）

### Unknown event / schema evolution

- Unknown event 不崩溃（解析为保底结构）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineHardInvariantsRobustnessTest.kt`
    - `offline_events_unknown_type_is_parsed_as_unknown_event`
    - `offline_events_unknown_type_roundtrip_preserves_raw_fields`
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineFuzzTest.kt`
    - `fuzz_events_jsonl_mixed_corruption_policy_is_stable_seeded`（含 unknown type 分支）

### Trace normalizer / strict gate

- normalizer 忽略噪声字段（`ts`/`duration_ms`）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineTraceContractTest.kt`
    - `offline_trace_normalizer_ignores_ts_and_duration`
- strict gate（最小证据链 + 可解释失败消息）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineTraceContractTest.kt`
    - `offline_trace_strict_missing_tool_result_fails_with_session_and_seq`
    - `offline_trace_strict_orphan_tool_result_fails_with_session_and_seq`
    - `offline_trace_strict_assistant_delta_is_forbidden`
    - `offline_trace_strict_seq_must_be_increasing`

### Streaming parser（半包/粘包/多行 data / EOF）

- OpenAI Responses SSE：半包/粘包/多行 data 拼接/无 completed 失败路径：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/providers/OpenAIResponsesSseDecoderTest.kt`
    - `offline_provider_stream_parse_half_packet`
    - `offline_provider_stream_parse_sticky_packets`
    - `offline_provider_stream_data_multiline_join`
    - `offline_provider_stream_ends_without_completed_is_failed`
    - `offline_provider_stream_error_event_is_failed_and_no_completed`
- SSE framing（空行分帧/EOF flush/忽略注释与非 data 字段）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/providers/SseEventParserTest.kt`
    - `sse_parser_emits_on_blank_line`
    - `sse_parser_end_of_input_flushes_last_event`
    - `sse_parser_ignores_comments_and_other_fields`

### Cancellation / No partial JSONL / Resume

- provider call 取消不写坏 JSONL：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineCancellationAndConcurrencyTest.kt`
    - `offline_cancel_during_provider_call_does_not_write_partial_jsonl`
- tool run 取消不吞并（不落 `tool.result`）且不写半行：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineCancellationAndConcurrencyTest.kt`
    - `offline_cancel_during_tool_run_does_not_write_partial_jsonl`
- task runner 取消不吞并（不落 `tool.result`）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineCancellationAndConcurrencyTest.kt`
    - `offline_cancel_during_task_runner_does_not_write_tool_result`
- cancel → resume：seq 可继续单调增长：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineCancellationAndConcurrencyTest.kt`
    - `offline_cancel_then_resume_can_continue_seq_monotonic`

### Fuzz / Chaos（PR 级固定 seed）

- events.jsonl fuzz（mid-file fail-fast + tail truncate ignore/repair）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineFuzzTest.kt`
    - `fuzz_events_jsonl_mixed_corruption_policy_is_stable_seeded`
- tool args fuzz（parseArgs 不 crash）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/providers/ToolArgsFuzzTest.kt`
    - `fuzz_tool_args_random_json_does_not_crash_seeded`
- tool args 解析边界（valid/invalid）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/providers/OpenAIResponsesParsingTest.kt`
    - `parse_args_valid_json_object_is_returned`
    - `parse_args_invalid_json_is_wrapped_as_raw`

### Secrets + Concurrency smoke

- trace 不包含敏感信息（黑名单断言）：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineSecretsTest.kt`
    - `offline_trace_does_not_contain_secrets_blacklist`
- 并发 smoke：多 session 并发不串线：
  - `src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/OfflineCancellationAndConcurrencyTest.kt`
    - `offline_concurrent_sessions_do_not_cross_contaminate`
