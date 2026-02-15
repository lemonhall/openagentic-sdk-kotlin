# 示例（SDK 用法速查）

这些示例面向“人类读者”：不追求可直接一键运行，而是尽量用**最小可读代码块**展示 SDK 的核心能力（sessions/events/tools/permissions/hooks/compaction/retry）。

> 运行测试：`.\gradlew.bat test`  
> CLI 交互：`.\gradlew.bat run --args="chat"`

## 目录

1. `example/01-basic-query.md`：最小闭环（Responses provider + query）
2. `example/02-consume-events.md`：如何消费 `Flow<Event>`（delta/tool/result/error）
3. `example/03-sessions-resume.md`：落盘、拿到 `session_id`、resume
4. `example/04-tools-registry.md`：注册工具 + 工具 schema（OpenAI tool schemas）
5. `example/05-permissions-hitl.md`：PermissionGate（DEFAULT/PROMPT）与 userAnswerer
6. `example/06-allowed-tools.md`：`allowedTools` 白名单（强制门禁）
7. `example/07-hooks.md`：HookEngine（BeforeModelCall / PreToolUse 等）
8. `example/08-compaction.md`：Compaction（auto + prune + overflow 行为）
9. `example/09-retry-and-errors.md`：429 Retry-After / backoff 上限 / runtime.error
10. `example/10-task-runner.md`：Task tool（注入 taskRunner）

## 约定与提示

- `OpenAgenticSdk.query(...)` 返回 `kotlinx.coroutines.flow.Flow<Event>`：你可以边消费边落 UI，也可以 `toList()` 一次拿全。
- session 落盘：用 `FileSessionStore`，默认会写 `sessions/<session_id>/events.jsonl`。
- 对真实网络（OpenAI）示例：API key 仅从环境变量读取（不要把 key 写进代码或仓库）。

