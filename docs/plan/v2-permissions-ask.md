# v2: Permissions + AskUserQuestion（Human-in-the-loop）

## Goal

核心 runtime 支持在 tool use 前“问人类/等人类”，但不涉及任何 UI。

## PRD Trace

- PRD-0002 / REQ-0002-002、REQ-0002-003

## Acceptance

- `PermissionGate` 为 `suspend` API
- `AskUserQuestion` tool：产出 `user.question` + tool.result（answer 为 Json）
- `.\gradlew.bat test` 全绿

