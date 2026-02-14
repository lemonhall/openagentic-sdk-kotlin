# v2: Sessions Resume（append-only + previous_response_id）

## Goal

支持从 `events.jsonl` 恢复 session，并继续对话（Responses 风格 previous_response_id 线程化）。

## PRD Trace

- PRD-0002 / REQ-0002-004

## Acceptance

- resume 后 provider 的 `previousResponseId` 从历史 `result.response_id` 推导正确
- append-only：events.jsonl 追加写入
- `.\gradlew.bat test` 全绿

