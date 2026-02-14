# v4: Offline E2E（Hard Invariants / Core）

参照：`reference/openagentic-sdk-python/kotlin-sdk-core-e2e-test-checklist.md`

## Goal

把 Kotlin SDK 的“核心模块回归”与“模型随机性/网络波动”拆开：建立一套 **离线、确定性、零 flake** 的 Offline E2E（hard invariants）作为 PR 级门禁，优先用 `events.jsonl`/trace 断言，而不是最终自然语言。

## PRD Trace

这是测试资产/门禁建设（基础设施），追溯到现有 PRD 的验收口径锚点：

- PRD-0001：events/sessions/tools/skills/runtime loop
- PRD-0002：permissions + AskUserQuestion + resume
- PRD-0003：hooks + provider 兼容（离线模拟即可）
- PRD-0004：compaction + tool output pruning + responses/stream（离线解析）

## Scope / Non-goals

- In-scope：Offline hard invariants（fake/scripted provider + 真 tool runner + 真 permission gate + 真 session store）。
- Non-goals：
  - 真网络/真 LLM 的在线 E2E（nightly/手动再做）
  - CLI 交互式 E2E（TTY/ConPTY/WSL PTY）

## Current 状态（对照 checklist 的“测试覆盖”快照）

已覆盖（代表性用例）：

- events：`EventSerializationTest`、`EventsJsonlExcludesDeltasTest`（不落 delta）、`FileSessionStoreTest`
- runtime/tool loop：`RuntimeToolLoopTest`、`LegacyProviderToolLoopTest`
- sessions/resume：`SessionsResumeTest`
- allowed_tools：`ToolNotAllowedTest`
- permission/human-in-the-loop：`AskUserQuestionToolTest`、`PermissionGatePromptTest`
- hooks：`HooksTest`
- compaction/pruning：`CompactionOverflowLegacyTest`、`ToolOutputPruningPlaceholderTest`
- tools：`ReadToolTest`/`WriteToolTest`/`Edit*`/`GlobToolTest`/`GrepToolTest`/`WebFetchToolTest`/`WebSearchToolTest`/`NotebookEditToolTest`…

明显缺口（建议优先补齐）：

- `events.jsonl` 鲁棒性：unicode/换行边界、坏行/截断行恢复策略、`seq` 单调性断言
- hooks：preToolUse 改写参数、hook 顺序稳定、hook 异常隔离（可解释落盘）
- permission gate：prompt 缺少 answerer 的 fail-fast、deny 的可审计原因/策略来源（至少落盘到 tool.result）
- 取消/中断：中途取消不产生半行 JSONL
- provider（离线）：stream parser 基本用例 + 半包/粘包（如实现了 stream）

## Acceptance（硬）

- `.\gradlew.bat test` 退出码为 0（Windows PowerShell）
- Offline hard invariants 套件新增 ≥ 15 个用例（逐步对齐 checklist 4.1 的命名清单）
- 每个用例都满足：
  - Given/When/Then 结构
  - 断言 `events.jsonl`（或 query 返回的 events）中的关键事件序列与因果（`tool.use` ↔ `tool.result`、`tool_use_id`）
  - 断言“不落 delta”
  - 失败输出包含 `session_id` + 关键 `seq`/索引（便于定位）

## Files（预期会改动/新增）

- 新增（测试夹具）：`src/test/kotlin/me/lemonhall/openagentic/sdk/testing/*`
  - `ScriptedProvider`（responses/legacy 两种脚本都可）
  - `ToolTestDoubles`（成功/失败/慢/大输出/副作用）
  - `TraceAsserts`（call_id 对齐、seq 单调、无 delta、最小证据链）
- 新增（offline e2e 测试包）：`src/test/kotlin/me/lemonhall/openagentic/sdk/e2e/*`
- 可能需要小改（为满足鲁棒性验收）：
  - `src/main/kotlin/me/lemonhall/openagentic/sdk/sessions/FileSessionStore.kt`（坏行/截断行策略）
  - `src/main/kotlin/me/lemonhall/openagentic/sdk/sessions/EventJson.kt`（未知事件类型策略：skip/保底结构）

## Steps（Strict / TDD）

1. **Analysis**：把 checklist 4.1 中优先级最高的 15 条落成“用例清单”，并在本文件固定命名（避免漂移）。
2. **TDD Red（events.jsonl 鲁棒性）**：
   - 新增 `offline_events_jsonl_roundtrip_unicode`、`offline_events_seq_monotonic`、`offline_session_truncated_line_recovery_policy` 的失败测试
3. **TDD Green（最小实现）**：
   - 实现/调整 `FileSessionStore.readEvents()` 与 `inferNextSeq()` 的坏行策略（skip + 诊断信息 或 fail-fast + 明确异常；二选一，写进测试）
4. **TDD Red/Green（hooks/permission）**：
   - hooks：补 `offline_hooks_pre_tool_use_mutates_args`、`offline_hooks_order_is_stable`、`offline_hooks_exception_is_recorded_and_isolated`
   - permission：补 `offline_permission_prompt_no_answerer_fails_fast`、`offline_permission_deny_records_reason`
5. **TDD Red/Green（取消/中断 & provider stream）**：
   - `offline_loop_cancel_mid_run_no_partial_jsonl`
   - 若实现了 stream：`offline_provider_stream_parse_half_packet`（半包/粘包）
6. **Review**：更新 `docs/plan/v4-index.md` 的追溯矩阵/证据口径（至少把新增 offline e2e 套件作为 v4 门禁的一部分）。

## Risks

- 平台差异（Windows 文件锁/路径）导致 flake：所有 offline e2e 必须只用临时目录 + Okio FS；避免并发写同一 session。
- “坏行策略”会影响兼容性：必须先明确策略（skip vs fail-fast）再动实现，并用测试锁死。

