# v6: Offline E2E Checklist Closure（对齐命名 + 补齐剩余高频坑）

参照：`reference/openagentic-sdk-python/kotlin-sdk-core-e2e-test-checklist.md`

## Goal

在 v5（robustness）基础上，继续“把 checklist 全部补齐”，但 v6 的重点是两件事：

1. **把 checklist 推荐的 `offline_*` 用例命名与 Kotlin 侧用例对齐**（防止追溯漂移）
2. 补齐 v5 仍未覆盖或只覆盖了“单点”的高频坑：provider 错误分类、runtime 结构化 error、tools args 与安全边界、compaction/allowed_tools/permission 跨回合不变量

## Non-goals

- 不引入真实网络/真实 LLM 作为 PR 门禁（online contract 仍可作为 nightly）
- 不扩展 CLI/TTY 的交互式 E2E

## Gap Check（自动扫描结果口径）

基于 checklist 末尾的“推荐用例名清单”（`offline_*`），当前 Kotlin 侧存在大量同义用例但**命名不一致**。

v6 将把这些名字对齐为“同名薄包装（推荐）”或“重命名（可选）”：

- 同名薄包装：保留现有测试函数，新增一个同名函数调用现有 helper/用例，降低改动风险
- 重命名：在不影响可读性的前提下，把现有用例名改成 checklist 推荐名

## Acceptance（硬）

- `.\gradlew.bat test` exit code = 0
- checklist 推荐的 `offline_*` 用例名：**每个都能在 Kotlin 侧定位到**（允许 1 个测试文件覆盖多个用例）
- 新增/补齐的用例：每类 gap 至少 1 个“核心用例” + 1 个“边界/异常用例”
- 失败可解释：输出 `session_id` + `seq`（或行号）+ `tool_use_id/call_id`（如适用）+ `phase`（provider/tool/hook/session）

## Work Items（塔山：红→绿→重构→门禁）

### W1：Checklist 命名对齐（追溯可维护）

- Tests（Red）：
  - 新增 `OfflineChecklistNamesTest`（或等价）：
    - 通过静态扫描确保“计划追溯矩阵中提到的用例名”在 `src/test/kotlin` 中存在
- Impl（Green）：
  - 为以下类别创建/补齐同名 `offline_*` 测试入口（优先薄包装）：
    - events：`offline_events_*`
    - loop：`offline_loop_*`
    - tool args：`offline_tool_args_*`
    - sessions：`offline_session_*`
    - permissions/hooks/allowed_tools：`offline_permission_*` / `offline_hooks_*` / `offline_allowed_tools_*`

### W2：Provider 错误模拟 + 错误分类门禁

- Tests（Red）：
  - `offline_provider_invalid_json_response_is_handled`
  - `offline_provider_timeout_is_classified`
  - `offline_provider_rate_limit_backoff_uses_fake_clock`
- Impl（Green）：
  - `ScriptedResponsesProvider` 扩展：
    - 支持注入 provider 异常（timeout/rate-limit/http-error）
    - 支持“响应变形器”（字段顺序/空白/可选字段缺失）以覆盖解析鲁棒性
  - 如涉及 backoff/timeout：引入 `DeterministicClock` seam，测试使用 fake clock

### W3：Runtime 结构化 error event（失败可解释）

- Tests（Red）：
  - `offline_loop_unhandled_exception_becomes_error_event`
  - `offline_loop_timeout_provider_vs_tool_classification`
- Impl（Green）：
  - 新增事件类型（建议）：`runtime.error`
    - 字段：`phase`（provider/tool/hook/session）、`error_type`、`error_message`、`tool_use_id`（如适用）
  - `OpenAgenticSdk.query()` 捕获未处理异常：
    - 先写 `runtime.error`（保证落盘可定位）
    - 再决定：返回 `Result(stop_reason="error")` 或重新抛出（策略写入测试）

### W4：Tool args 边界（不 crash + 结构化错误）

- Tests（Red）：
  - `offline_tool_args_missing_field`
  - `offline_tool_args_wrong_type`
  - `offline_tool_args_unknown_properties`
  - `offline_tool_args_json_string_instead_of_object`
- Impl（Green）：
  - 对核心工具（Read/Write/Edit/Glob/Grep/WebFetch/WebSearch/NotebookEdit 等）形成一致策略：
    - 输入解析失败 → `ToolResult(is_error=true, error_type=..., error_message=...)`
    - 不允许抛出未捕获异常导致 loop 直接崩溃

### W5：Security Invariants（默认拒绝/不可绕过）

- Tests（Red）：
  - `offline_security_path_traversal_blocked`
  - `offline_security_symlink_escape_blocked`
  - `offline_security_ssrf_blocked_default`
  - `offline_security_control_chars_do_not_break_jsonl`
  - `offline_security_command_injection_not_possible`
- Impl（Green）：
  - 对工具的路径规范化与 sandbox 根目录策略补齐（如已有则写测试锁死）
  - 对输出中控制字符/日志注入的处理策略写入测试（不破坏 JSONL）

### W6：Compaction / AllowedTools / Permission 跨回合不变量

- Tests（Red）：
  - `offline_loop_zero_tool_calls`
  - `offline_loop_multi_tool_calls_serial`
  - `offline_loop_max_tool_calls_fuse`
  - `offline_allowed_tools_enforced_across_turns`
  - `offline_allowed_tools_preserved_after_compaction`
  - `offline_permission_scope_precedence`
  - `offline_permission_default_deny_on_schema_parse_error`
- Impl（Green）：
  - 明确 maxSteps fuse 的 stopReason 与落盘证据（Result/错误事件）
  - compaction 后 allowed_tools/permission gate 的策略保持一致（写入门禁）

## Risks

- “命名对齐”会引发大量重命名/文件调整：优先用“同名薄包装”降低 churn
- Provider 错误分类涉及行为承诺：必须先在 v6 文档中冻结策略再实现
- 安全边界测试可能暴露现有工具策略不一致：需要小步收敛，避免一次性大改

## Verification（命令口径）

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.e2e.*"
```

