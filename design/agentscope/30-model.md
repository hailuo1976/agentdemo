# 30 LLM 客户端模块（model）

> 包：`com.demo.agentscope.model`
> 主类：`ChatModel`

---

## 1. 职责

封装 OpenAI 兼容的 `/chat/completions` API：
- 把 `List<Msg>` + `List<ToolInfo>` 序列化为请求体
- 解析响应为 `Msg`（含 `TextBlock` / `ThinkingBlock` / `ToolCallBlock`）
- 通过 `CredentialProvider` 拿 API key / base URL / model name

**不做**：流式 SSE 解析、重试、缓存、路由（这些在当前 demo 版本中是 stub）。

---

## 2. 字段与构造

```java
class ChatModel {
    OkHttpClient httpClient;          // connectTimeout=30s, readTimeout=120s, writeTimeout=30s
    CredentialProvider credentialProvider;

    static int DEFAULT_MAX_TOKENS = 4096;
    static double DEFAULT_TEMPERATURE = 0.7;
}
```

构造只接受 `CredentialProvider`，OkHttp 客户端在内部固定超时。

---

## 3. 三个公开方法

### 3.1 `chat(providerName, messages, tools)` — 主入口

```
1. apiKey   = credentialProvider.getApiKey(providerName)
   baseUrl  = credentialProvider.getBaseUrl(providerName)
   modelName= credentialProvider.getModelName(providerName)
2. buildRequestBody(...)         ← 见 §4
3. POST baseUrl + "/chat/completions"
   Headers: Authorization: Bearer <apiKey>, Content-Type: application/json
4. 失败（!isSuccessful）→ throw IOException
5. parseResponse(body)          ← 见 §5
```

异常路径：捕获 `IOException` 后**返回**一条带错误文本的 assistant Msg（不抛），让上层 Agent 继续循环。

### 3.2 `chatWithToolCalls(...)`

直接转发给 `chat(...)`。存在是为了与未来"分流式工具调用"实现解耦——当前 demo 的 OpenAI 协议本身就支持单次响应里同时返回文本和 tool_calls，所以无需不同行为。

### 3.3 `chatStream(providerName, messages, tools, agentId)` — 伪流式

```
1. stream = new EventStream(agentId)
2. response = chat(...)
3. 遍历 response.content:
     TextBlock    → emit(textBlock)
     ThinkingBlock→ emit(thinkingBlock)
     ToolCallBlock→ emit(toolCall, args={"raw": arguments})
4. 累加 token: emit(modelCallEnd(prompt, completion))
5. return stream
```

**注意**：当前实现是"非阻塞语义、阻塞实现"——内部仍然 `chat()` 整体返回后才切事件，没有真正的 SSE 增量解析。这是演示版的简化。

---

## 4. 请求体构造（buildRequestBody）

### 4.1 顶层字段

| 字段 | 来源 |
|---|---|
| `model` | `credentialProvider.getModelName(providerName)` |
| `max_tokens` | 固定 `DEFAULT_MAX_TOKENS = 4096` |
| `temperature` | 固定 `DEFAULT_TEMPERATURE = 0.7` |
| `messages` | 遍历 `List<Msg>` 序列化 |
| `tools` | 遍历 `List<ToolInfo>` 序列化（无工具则省略） |

### 4.2 messages 序列化规则（按 role 分支）

**`role=tool`**（工具结果回写）：
```json
{"role":"tool", "content": "<result>", "tool_call_id": "<id>"}
```
仅取第一个 `ToolResultBlock`（OpenAI 协议要求一条 tool message 对应一次 tool_call）。

**`role=assistant` 且含 tool_calls**：
```json
{
  "role": "assistant",
  "content": "<文本或null>",
  "tool_calls": [
    {"id":"...", "type":"function",
     "function":{"name":"...", "arguments":"..."}}
  ]
}
```
- content 为空时显式 `null`（OpenAI 要求）
- 仅 TextBlock 计入 content；ThinkingBlock **不**回写（OpenAI 不接受）

**普通消息（user/system/纯文本 assistant）**：
```json
{"role":"<role>", "content": "<拼接文本>"}
```
ThinkingBlock 在此处被以 `[思考] <text>` 形式拼进 content（仅 system/user 回放时出现思考块的场景，实践中少见）。

### 4.3 tools 序列化

```json
{
  "type": "function",
  "function": {
    "name": "...",
    "description": "...",
    "parameters": { ...JSON Schema... }
  }
}
```

- `parametersJson` 为空时用 `{type:object, properties:{}, required:[]}` 兜底
- schema 解析失败时 `log.warn` 后回退到空 schema（**不**抛异常，避免单个工具拖垮整个请求）

---

## 5. 响应解析（parseResponse）

```
1. 读 choices[0].message
2. content 字段非空 → TextBlock
3. reasoning_content 字段非空 → ThinkingBlock
   ↑ OpenAI 标准无此字段；DeepSeek / 智谱 GLM 等国内模型支持
4. tool_calls 数组 → 逐项生成 ToolCallBlock(id, name, arguments)
   - id 缺失时用 "call_" + UUID 兜底
   - arguments 缺失时用 "{}" 兜底
5. usage.prompt_tokens / completion_tokens → TokenUsage
6. 解析异常 → 把原始响应体作为 TextBlock 返回（不抛）
```

返回：`Msg(id=UUID, role="assistant", content=blocks, usage, timestamp=now, metadata=null)`。

---

## 6. 协议字段映射速查

| Msg 侧 | OpenAI JSON |
|---|---|
| `Msg.role` | `choices[0].message.role` |
| `TextBlock.text` | `choices[0].message.content` |
| `ThinkingBlock.text` | `choices[0].message.reasoning_content` |
| `ToolCallBlock.id` | `choices[0].message.tool_calls[i].id` |
| `ToolCallBlock.name` | `choices[0].message.tool_calls[i].function.name` |
| `ToolCallBlock.arguments` | `choices[0].message.tool_calls[i].function.arguments`（字符串形式） |
| `ToolResultBlock.content` | （请求侧）`messages[i].content` |
| `ToolResultBlock.toolCallId` | （请求侧）`messages[i].tool_call_id` |
| `Msg.usage` | `usage.{prompt_tokens, completion_tokens}` |

---

## 7. 错误处理策略

| 场景 | 行为 |
|---|---|
| `apiKey` / `baseUrl` 缺失 | 抛 `IllegalStateException`（不可恢复，启动期就该挂） |
| HTTP 非 2xx | 抛 `IOException`（被外层 catch 后转错误 Msg） |
| 响应 JSON 解析失败 | 把原始 body 包成 TextBlock 返回 |
| 工具 schema 解析失败 | 用空 schema 兜底 + `log.warn` |
| `chat()` 网络 IOException | 返回带错误文本的 assistant Msg |

整体策略：**协议层错误不抛到 Agent 循环**，让 Agent 能继续上下文（下次迭代可能恢复）。

---

## 8. 与 pimono LlmClient 的对比

| 维度 | pimono LlmClient | agentscope ChatModel |
|---|---|---|
| 凭证 | 直接读 env | `CredentialProvider`（多 provider 切换） |
| 消息格式 | 自定义 `ContextMessage` | `Msg` + `ContentBlock`（6 种块） |
| 思考链 | 不支持 | 解析 `reasoning_content` |
| 流式 | 不支持 | `chatStream`（伪流式） |
| 工具结果 | `Context.ToolCallEntry` | `ContentBlock.ToolResultBlock` |
| 超时 | 30s call / 120s read | 同 |
| 重试 | 无 | 无 |
| 内部数据类 | 6 个 inner class | 复用 message/Msg + mcp/ToolInfo |

---

## 9. 已知限制

- **无重试**：网络抖动直接失败（依赖 Agent 下一轮重试）
- **无流式**：`chatStream` 是包装层，未实现 SSE
- **max_tokens / temperature 硬编码**：未暴露给 Agent 配置
- **无 thinking budget 控制**：`reasoning_content` 是被动解析，不能要求模型思考
- **无批处理**：每条 reply 内的多次 LLM 调用串行
- **请求体不打印**：`log.debug` 仅记录 provider/model/url，body 因为含完整 system prompt 和上下文未打日志（避免泄露）

---

## 10. 测试

- `ChatModelTest`：
  - 请求体构造（messages 各 role、tools 序列化、空 schema 兜底）
  - 响应解析（文本/思考/工具调用组合）
  - 错误路径（apiKey 缺失、HTTP 失败、JSON 异常）
  - mock `OkHttpClient` + stub `CredentialProvider`
