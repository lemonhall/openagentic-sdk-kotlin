# v3: Tools 全量对齐

## PRD Trace

- PRD-0003 / REQ-0003-001

## Acceptance

- `.\gradlew.bat test` 全绿
- 工具输入/输出结构化且可序列化；安全边界（path traversal）可测试

## Steps（Strict）

1) TDD Red：按参考仓库 tests 为蓝本写 Kotlin tests（每个 tool 至少正常+异常）
2) TDD Green：逐个实现 tool（先实现最常用的 Read/Write/Edit/Glob/Grep，再 Skill/SlashCommand/TodoWrite/Task/WebFetch）
3) Refactor：统一错误类型与输出结构

