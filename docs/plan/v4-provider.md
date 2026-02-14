# v4: Provider（Responses schema + stream）

## Checklist

- [ ] Responses tools schema 形状：`{type,name,description,parameters}`
- [ ] OpenAI Responses provider 支持 `store`（对话存储开关）
- [ ] SSE streaming：解析 `response.output_text.delta` + `response.completed`
- [ ] 单测：schema 形状转换 + SSE parser 基本用例（离线）

