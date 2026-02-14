# v6 Index: Checklist Closure（命名对齐 + Provider 错误分类 + 安全边界）

参照：`reference/openagentic-sdk-python/kotlin-sdk-core-e2e-test-checklist.md`

## 愿景（v6）

在 v5 已完成的基础上，把 checklist 里剩余的“高频坑/强烈建议”补齐，并解决一个长期的可维护性问题：**测试用例命名与 checklist 的建议命名不一致**，导致追溯矩阵容易漂移、review 成本高。

v6 的交付目标：

- checklist 建议的 `offline_*` 用例名在 Kotlin 侧可直接找到（通过“重命名”或“同名薄包装”）
- provider 适配层的**错误分类/回退**有离线门禁（timeout / rate-limit / 非法 JSON / 字段缺失）
- runtime 的“未处理异常”不会只靠抛出堆栈，而是会在 trace 中留下**结构化 error 事件**（便于定位）
- tools 的输入边界与安全边界（路径穿越/控制字符/SSRF 默认拒绝等）被离线门禁锁死
- compaction / allowed_tools / permission 的跨回合不变量不回归

## 当前基线（v5 已完成）

- Unknown event：`EventJson.loads()` 遇到未知 `type` 不崩溃（保底 `UnknownEvent`）
- SSE parser：半包/粘包/多行 data/EOF/error 的离线覆盖（解析器已抽取）
- Cancellation：provider/tool/task runner 取消不吞并、不写半行 JSONL；cancel → resume seq 继续单调增长
- Fuzz：events.jsonl（截断/坏行/unknown type）+ tool args（seeded）
- Secrets：trace/events.jsonl 黑名单断言
- Concurrency：多 session 并发不串线

## 里程碑（v6）

### M19：Checklist 命名对齐 + 追溯矩阵硬化

- DoD（硬）：
  - checklist 的 “推荐用例名清单”（`offline_*`）在 Kotlin 侧可定位（同名函数存在）
  - `docs/plan/v6-offline-e2e-checklist-closure.md` 中的追溯矩阵完整（每条 gap 对应测试函数名 + 文件路径）
  - `.\gradlew.bat test` exit code = 0

### M20：Provider 错误分类（离线可测）

- DoD（硬）：
  - timeout / rate-limit / 非 2xx / 非法 JSON / 字段缺失 的处理策略被离线测试锁死
  - `FakeClock/DeterministicClock`（如引入）消除 backoff/timeout 的时间抖动
  - `.\gradlew.bat test` exit code = 0

### M21：Runtime 结构化 error event

- DoD（硬）：
  - provider/tool loop 的“未处理异常”会写入结构化 error 事件（含 session_id + seq + phase/call_id）
  - 失败可解释（不是只抛异常）

### M22：Tools 边界 + Security Invariants

- DoD（硬）：
  - tool args missing/wrong-type/unknown-props/string-instead-of-object：不 crash，返回结构化错误
  - path traversal / symlink escape / SSRF 默认拒绝 / 控制字符：行为可预测并可测

### M23：Compaction / AllowedTools / Permission 跨回合不变量

- DoD（硬）：
  - allowed_tools 在 compaction / resume 后仍强制执行
  - permission gate 的 scope precedence / schema parse error 默认 deny（如设计如此）有离线门禁

## 计划索引

- `docs/plan/v6-offline-e2e-checklist-closure.md`

## 验证命令（门禁口径）

```powershell
.\gradlew.bat test
.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.e2e.*"
```

