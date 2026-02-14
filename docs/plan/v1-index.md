# v1 Index: Kotlin 核心模块对齐（PRD-0001）

## 愿景（Vision）

- PRD：`docs/prd/PRD-0001-kotlin-core-agent-sdk.md`

## 里程碑（Milestones）

### M1：核心模块最小闭环（REQ-0001-001 ~ 008）

- **DoD（硬）**
  - `.\gradlew.bat test` 退出码为 0（Windows）
  - 关键测试覆盖：
    - events roundtrip
    - sessions/events.jsonl 读写
    - streaming delta 不落盘
    - skill parse + index（含 global/project 覆盖）
    - runtime tool loop（fake provider + Read tool）
    - ToolNotAllowed（白名单拒绝）
  - **反作弊条款**：测试必须真实读写临时目录下的 `events.jsonl`，并校验文件内容/事件类型；禁止只写空实现让测试变成无意义断言。
- **验证命令**
  - `.\gradlew.bat test`
- **状态**：done
  - **最新证据**：`2026-02-14` 在 Windows PowerShell 通过 `.\gradlew.bat test`

## 计划索引（Plans）

- `docs/plan/v1-core-parity.md`

## 追溯矩阵（Req → Plan → Tests → Evidence）

| Req ID | v1 Plan | Tests（Kotlin） | 证据 |
|---|---|---|---|
| REQ-0001-001 | v1-core-parity §Events | `EventSerializationTest` | `.\gradlew.bat test` |
| REQ-0001-002 | v1-core-parity §Sessions | `FileSessionStoreTest` | `.\gradlew.bat test` |
| REQ-0001-003 | v1-core-parity §Streaming | `EventsJsonlExcludesDeltasTest` | `.\gradlew.bat test` |
| REQ-0001-004 | v1-core-parity §Skills | `SkillParserTest` | `.\gradlew.bat test` |
| REQ-0001-005 | v1-core-parity §Skills | `SkillIndexTest` | `.\gradlew.bat test` |
| REQ-0001-006 | v1-core-parity §Tools | `ToolRegistryTest` | `.\gradlew.bat test` |
| REQ-0001-007 | v1-core-parity §Runtime | `RuntimeToolLoopTest` | `.\gradlew.bat test` |
| REQ-0001-008 | v1-core-parity §Runtime | `ToolNotAllowedTest` | `.\gradlew.bat test` |

## ECN 索引

- （v1 暂无）

## 差异列表（愿景 vs 现实）

- v1 仅交付“最小闭环”的核心：events/sessions/tools/skills + 最小 runtime tool loop；以下仍未覆盖（进入 v2 讨论/计划）：
  - Hooks（HookEngine、Pre/PostToolUse 等）
  - 人类交互与权限门（交互式 approve/prompt/callback；v1 仅提供 allowedTools 白名单拒绝）
  - Sessions 高级时间线控制（checkpoint / set_head / undo / redo / fork / diff / resume 重建策略的完整对齐）
  - 内置 tools 覆盖面（Write/Edit/AskUserQuestion/TodoWrite/SlashCommand 等）
  - Provider 协议多形态（legacy messages vs responses input 的完整兼容、tool schemas）
