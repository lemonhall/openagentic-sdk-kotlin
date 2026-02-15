# openagentic-sdk-kotlin

A **pure Kotlin Agent SDK core** (runtime tool loop, sessions, events, tools, skills, permissions/HITL, hooks) aimed primarily at **Android** apps.

This repo focuses on:

- A stable, test-driven runtime/tool-loop core
- Offline E2E gates (deterministic, PR-friendly) for safety/robustness
- A minimal CLI for local experiments

## Quick Start

### Run tests (Windows PowerShell)

```powershell
.\gradlew.bat test
```

### Run CLI

The CLI reads from the repo root `.env`:

- `OPENAI_API_KEY`
- `OPENAI_BASE_URL` (optional)
- `MODEL` (optional)

```powershell
.\gradlew.bat run --args="chat"
```

## Examples

Human-oriented usage examples live in `example/README.md`.

## The OpenAgentic SDK family

This Kotlin repo is part of a small “SDK family” that shares the same overall philosophy (traceable sessions/events + safe tools + deterministic gates), but targets different runtimes and product surfaces.

### 1) `openagentic-sdk` (Python, the origin)

- Repo: `https://github.com/lemonhall/openagentic-sdk`
- The earliest project in the family, and the **most battle-tested test suite** (reported ~369 tests).
- The most complete tool set, plus CLI utilities.
- Includes a TUI dashboard for observing sessions/traces.

### 2) `openagentic-sdk-ts` (TypeScript, sandbox-first)

- Repo: `https://github.com/lemonhall/openagentic-sdk-ts`
- Derived from the Python lineage, with a strong focus on a **complete sandbox system**.
- Provides a **browser sandbox**; designed to be extremely safe in Linux and browser environments.

Side project built on top of the TS stack:

- `WebMCP_Sidecar`: `https://github.com/lemonhall/WebMCP_Sidecar`
- A full-featured **browser sidebar AI assistant** with WebMCP support.

### 3) `openagentic-sdk-gdscript` (Godot 4.6, the most complex)

- Repo: `https://github.com/lemonhall/openagentic-sdk-gdscript`
- A Godot 4.6 SDK that ships multiple demos, including a **VR Office** and a 2D demo.
- Contains a full-featured **IRC client SDK** for GDScript.
- Implements an IRC-based RPC protocol and supports a multi-machine architecture (OpenClaw-like).

### 4) `openagentic-sdk-kotlin` (this repo, Android-oriented)

- Repo: `https://github.com/lemonhall/openagentic-sdk-kotlin`
- A full Kotlin SDK architecture intended as a reusable core for Android apps.

### Planned siblings (to make ~6)

- Swift (Apple platforms)
- Rust (systems + embedded/CLI)

The exact feature distribution may evolve, but the goal is consistent: **safe agent runtimes with strong, traceable tests**.

