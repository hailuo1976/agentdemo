# 12 LlmClient：OpenAI 兼容客户端

> 源文件：`pimono-demo/src/main/java/com/demo/pimono/ai/LlmClient.java`（~210 行）

---

## 1. 职责

手写 OpenAI `/chat/completions` 协议封装：
- 构建请求 JSON（messages + tools + tool_choice）
- POST 到 `${MODEL_BASE_URL}/chat/completions`
- 解析响应，提取文本 + tool_calls
- 错误处理（HTTP 非 2xx → `LlmException`）

---

## 2. 设计取舍

**为什么不用 OpenAI Java SDK？**
- 教学透明：所有协议字段在 `LlmRequest` / `LlmResponse` 内嵌类里一目了然
- 零依赖膨胀：只用 OkHttp + Jackson，已经因为 MCP 需要
- 易迁移：换 Anthropic / DeepSeek 只要改 endpoint

---

## 3. 关键字段与超时

| 配置 | 值 |
|---|---|
| OkHttp `connectTimeout` | 30 s |
| OkHttp `readTimeout` | 120 s（LLM 推理慢） |
| HTTP 方法 | POST |
| Endpoint | `${MODEL_BASE_URL}/chat/completions` |
| Auth | `Bearer ${API_KEY}` |

---

## 4. 内嵌数据类

### 4.1 `LlmRequest`
对应 OpenAI 请求 body：
```
{
  "model": "...",
  "messages": [{role, content}, ...],
  "tools": [{type: "function", function: {name, description, parameters}}, ...],
  "tool_choice": "auto"
}
```

### 4.2 `LlmResponse`
对应响应 body 的子集：
```
{
  "choices": [{"message": {role, content, tool_calls}}],
  "usage": {prompt_tokens, completion_tokens, total_tokens}
}
```
- `hasToolCalls()`：判断是否进入工具循环

### 4.3 `ToolCallInfo`
```
{
  "id": "call_xxx",       ← tool_call_id（必须闭环）
  "name": "get_weather",
  "arguments": "{\"city\":\"...\"}"   // JSON 字符串
}
```

### 4.4 `ContextMessage`
上下文消息载体，role ∈ {`system`, `user`, `assistant`, `tool`}。

### 4.5 `ToolSchema`
工具定义：`{name, description, parameters(JSON Schema 字符串)}`。

### 4.6 `LlmException`
`RuntimeException` 子类，含 HTTP code 与原始 body。

---

## 5. 关键方法

| 方法 | 作用 |
|---|---|
| `chatCompletion(messages, tools)` | 同步调用，返回 `LlmResponse` |
| `buildRequestBody(...)` | 构造 JSON body |
| `parseResponse(json)` | Jackson 解析响应 |
| `handleError(Response)` | HTTP 非 2xx → `LlmException` |

---

## 6. 与 agentscope `ChatModel` 的差异

| 维度 | pimono `LlmClient` | agentscope `ChatModel` |
|---|---|---|
| 凭证来源 | 直接读 env var | `CredentialProvider`（主备 failover） |
| 多 provider | 单一 endpoint | provider 抽象 + 多后端 |
| 重试 | 无 | 无（一致） |
| max_tokens 默认 | 由 env | 4096 |
| temperature 默认 | 由 env | 0.7 |
| 流式 | 不支持 | 不支持（一致） |
| 思考块 | 不支持 | 支持 `ThinkingBlock` |

---

## 7. 错误模式

| 场景 | 行为 |
|---|---|
| API key 缺失 | 启动时 env 探测失败，`PiMonoDemoApplication` 报错退出 |
| 网络/超时 | OkHttp 抛 `SocketTimeoutException`，未捕获 → REPL 崩溃 |
| HTTP 4xx/5xx | `LlmException`，由 REPL 顶层捕获 |
| 响应 JSON 不合法 | Jackson 异常 → `LlmException` |
| 工具 schema 不合法 | `McpClientManager` 在注册时即报错 |

> pimono 的设计是"异常往上冒到 REPL"；agentscope 在 `Agent.reply` 内 try/catch。

---

## 8. 协议字段对照（OpenAI 官方）

| 本仓库字段 | OpenAI 字段 | 说明 |
|---|---|---|
| `LlmRequest.messages` | `messages` | 上下文数组 |
| `LlmRequest.tools` | `tools` | function 定义 |
| `ToolCallInfo.id` | `tool_calls[].id` | **关键闭环 id** |
| `LlmResponse.choices[0].message.tool_calls` | 同名 | 触发工具循环 |
| `usage` | `usage` | token 统计 |

---

## 9. 测试

`LlmClientTest` 使用 MockWebServer 验证：
- 正常文本响应解析
- 含 tool_calls 的响应解析
- HTTP 错误抛 `LlmException`
- 请求 body 包含正确的 messages 与 tools
