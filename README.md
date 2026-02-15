# openagentic-sdk-kotlin

目标：对齐 Python 参考项目 `openagentic-sdk` 的核心模块（sessions / events / tools / skills / runtime tool loop），作为未来 Android 应用可复用的纯 Kotlin Agent SDK 基础。

- 参考仓库已 clone 到 `reference/openagentic-sdk-python/`（并在本仓库 `.gitignore` 忽略）
- 规划与口径：`docs/prd/PRD-0001-kotlin-core-agent-sdk.md`、`docs/plan/v1-index.md`

运行测试（Windows PowerShell）：

```powershell
.\gradlew.bat test
```

CLI（从项目根目录的 `.env` 读取 `OPENAI_API_KEY` / `OPENAI_BASE_URL` / `MODEL`）：

```powershell
.\gradlew.bat run --args="chat"
```

示例（给人类看的 SDK 用法）：`example/README.md`
