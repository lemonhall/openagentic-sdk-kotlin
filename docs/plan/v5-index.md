# v5 Index: Offline E2E Robustness + Trace Contract（core checklist closure）

- 参考：`reference/openagentic-sdk-python/kotlin-sdk-core-e2e-test-checklist.md`
- PRD 锚点（本轮为测试/门禁基础设施，不新增业务需求）：
  - `docs/prd/PRD-0001-kotlin-core-agent-sdk.md`
  - `docs/prd/PRD-0002-kotlin-android-io-permissions-resume.md`
  - `docs/prd/PRD-0003-v3-tools-hooks-provider-parity.md`
  - `docs/prd/PRD-0004-v4-compaction-more-tools-real-cli.md`

## 愿景（v5）

把 checklist 里“强烈建议但未实现”的鲁棒性门禁补齐，形成 **PR 级可重复验证** 的 Offline E2E 扩展套件：

- 解析/落盘永不因“未知事件/坏数据/半包/取消”而失控
- trace 具备可比对的 contract（normalizer + strict mode）
- fuzz/chaos/并发 smoke 覆盖 Kotlin/Windows 高频坑

## 里程碑

### M14：Unknown Event + Trace Normalizer/Strict
- DoD（硬）：
  - `EventJson.loads()` 遇到未知 `type` **不崩溃**（保底事件可回放/可落盘）
  - 新增 trace normalizer：可忽略噪声字段（`ts`/`duration_ms` 等），但严格比较核心语义字段
  - 新增 strict gate：缺失关键字段/关键因果断裂时给出可解释失败（含 `session_id`/`seq`）
  - `.\gradlew.bat test` exit code = 0

### M15：Streaming Parser（半包/粘包/变形）
- DoD（硬）：
  - SSE/stream 解析逻辑可被离线单测覆盖（不依赖真实网络）
  - 覆盖：半包、粘包、空行/注释行、data 多行拼接、末尾无 completed 的失败路径
  - `.\gradlew.bat test` exit code = 0

### M16：Cancellation / No Partial JSONL
- DoD（硬）：
  - 在 provider call / tool run / 写 trace 的随机时刻触发取消（协程 cancel/timeout），不会产生半行 JSONL
  - 取消后再次 resume/append 仍可继续（seq 单调、事件可读）
  - `.\gradlew.bat test` exit code = 0

### M17：Fuzz（events.jsonl / tool args / schema evolution）
- DoD（硬）：
  - 固定 seed 的轻量 fuzz（≥ 200 case）纳入 PR 级测试，零 flake
  - events.jsonl fuzz：截断/非法 JSON/超大行/控制字符 → 行为可预测（skip/fail-fast 策略被测试锁定）
  - tool args fuzz：输入变形不 crash；要么规范化、要么结构化错误（可审计）
  - `.\gradlew.bat test` exit code = 0

### M18：Secrets + Concurrency Smoke
- DoD（硬）：
  - trace/events.jsonl 不包含敏感字段（token/api_key/authorization 等黑名单）
  - 并发 smoke：多 session 并发运行不串线（session_id 隔离、seq 单调、落盘互不覆盖）
  - `.\gradlew.bat test` exit code = 0

## 计划索引

- `docs/plan/v5-offline-e2e-robustness.md`

## 验证命令（门禁口径）

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.e2e.*"
```

## 差异列表（从 v4 延续）

- checklist 2.2：未知事件类型鲁棒解析（v4 仍会抛异常）
- checklist 2.2：normalizer/strict gate 缺失
- checklist 7.2：stream 半包/粘包离线覆盖不足
- checklist 7.2：取消/中断覆盖不足
- checklist 7.1：fuzz/property-based 缺失
- checklist 8：secrets 泄露断言与并发 smoke 缺失
