# 06：allowedTools 白名单（强制门禁）

目标：用 `allowedTools` 强制限制“模型可用工具集合”，即使权限门放行也不允许越界。

```kotlin
OpenAgenticOptions(
  // ...
  allowedTools = setOf("Read", "Grep", "Glob", "List"),
)
```

行为：

- 当 provider 产出 `tool.use(name="Bash")` 这类不在白名单内的调用时，SDK 会产生 `tool.result(is_error=true, error_type="ToolNotAllowed")`，并继续 loop（直到 maxSteps 或模型结束）。
- 这层门禁是“面向模型能力”的；PermissionGate 是“面向用户授权”的。建议两层同时用：白名单缩小攻击面，PermissionGate 做 HITL。

