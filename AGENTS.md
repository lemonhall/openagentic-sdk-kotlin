# Agent Notes: openagentic-sdk-kotlin

## Project Overview

`openagentic-sdk-kotlin` is a **pure Kotlin Agent SDK core** (runtime tool-loop, sessions/events, tools, skills, permissions/HITL, hooks) aimed primarily at **Android** apps, with a strong emphasis on **offline, deterministic test gates**.

## Quick Commands (PowerShell / Windows)

- Test (full): `.\gradlew.bat test`
- Test (package): `.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.*"`
- Test (single): `.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.tools.ReadToolTest.readSupportsOffsetAndLimitWithLineNumbers"`
- Run CLI (chat): `.\gradlew.bat run --args="chat"`

Notes:

- Default shell is **PowerShell 7.x**. Chain commands with `;` (not `&&`).
- `.env` is **gitignored**. Never commit secrets.

## Architecture Overview

**Core flow**

`OpenAgenticSdk.query(...)` → emits a `Flow<Event>` → persists events via `FileSessionStore` → tool loop executes registered `Tool`s with `PermissionGate` + `HookEngine` → produces `Result` (or `runtime.error` + `Result(stop_reason="error")`).

**Key modules**

- Runtime / loop: `src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/OpenAgenticSdk.kt`
- Runtime options/models: `src/main/kotlin/me/lemonhall/openagentic/sdk/runtime/RuntimeModels.kt`
- Events: `src/main/kotlin/me/lemonhall/openagentic/sdk/events/Events.kt`
- Sessions store (JSONL): `src/main/kotlin/me/lemonhall/openagentic/sdk/sessions/FileSessionStore.kt`
- Tool system: `src/main/kotlin/me/lemonhall/openagentic/sdk/tools/*`
- Providers: `src/main/kotlin/me/lemonhall/openagentic/sdk/providers/*`
- Permissions (HITL): `src/main/kotlin/me/lemonhall/openagentic/sdk/permissions/PermissionGate.kt`
- Hooks: `src/main/kotlin/me/lemonhall/openagentic/sdk/hooks/*`
- Compaction: `src/main/kotlin/me/lemonhall/openagentic/sdk/compaction/*`
- CLI entry: `src/main/kotlin/me/lemonhall/openagentic/sdk/cli/Main.kt`

**Persistence**

- Session files live under the session root (default: `~/.openagentic-sdk`), with:
  - `sessions/<session_id>/meta.json`
  - `sessions/<session_id>/events.jsonl`

## Runtime Config

- CLI reads repo root `.env` (gitignored):
  - `OPENAI_API_KEY`
  - `OPENAI_BASE_URL` (optional)
  - `MODEL` (optional)
- In restricted networks (e.g. CN), you may need:
  - `HTTP_PROXY=http://127.0.0.1:7897`
  - `HTTPS_PROXY=http://127.0.0.1:7897`

## Code Style & Conventions

- Language: Kotlin (Gradle Kotlin DSL), JVM toolchain 17 (see `build.gradle.kts`)
- Prefer small, focused changes with tests.
- Avoid introducing new dependencies unless necessary; keep the SDK core lean.
- Do not add large inline comments unless requested; keep code self-explanatory.

## Safety & Conventions (Do / Don’t)

- Do not hardcode or commit secrets.
  - Why: leaks API keys / tokens.
  - Do instead: use env vars or repo-local `.env` (already ignored).
  - Verify: `git status` shows no `.env` changes staged.

- Do not perform destructive filesystem operations without explicit confirmation.
  - Examples: `Remove-Item -Recurse -Force`, bulk deletions.
  - Verify: show the exact path(s) to be removed and get confirmation first.

- Do not weaken sandbox/path safety in tools.
  - Path traversal and symlink-escape defenses are intentional and gated by tests.
  - If changing tool path logic, add/adjust security tests in `src/test/kotlin/.../e2e`.

## Testing Strategy

**Full suite**

- `.\gradlew.bat test`

**Focus areas**

- Tools unit tests: `.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.tools.*"`
- Providers tests: `.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.providers.*"`
- Offline E2E gates: `.\gradlew.bat test --tests "me.lemonhall.openagentic.sdk.e2e.*"`

Rules:

- Add/update tests for any behavior change (especially around tools/permissions/sessions/provider errors).
- Keep gates deterministic: avoid real network calls in PR-level tests unless explicitly requested and env-gated.

## Documentation Policy

- If you change public behavior or contracts, update:
  - `docs/plan/*` (the relevant vN plan/closure doc)
  - `example/*` (human-facing usage examples) when it improves clarity

## Scope & Precedence

- Root `AGENTS.md` applies by default to the whole repo.
- A deeper `AGENTS.md` in a subdirectory overrides within its subtree.
- `AGENTS.override.md` in the same directory (if present) overrides `AGENTS.md`.
- User’s explicit chat instructions override everything.

## Local Preference (optional)

If you have `apn-pushtool` configured locally, send a short push notification when a task is completed (title: repo name; body: ≤10 chars summary). Do not include secrets in notifications.

