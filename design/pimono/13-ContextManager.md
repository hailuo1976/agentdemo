# 13 ContextManager：FIFO 滑窗上下文

> 源文件：`pimono-demo/src/main/java/com/demo/pimono/context/ContextManager.java`（~70 行）

---

## 1. 职责

维护 Agent 的对话上下文，包含：
- 系统提示词（可选，固定）
- 历史消息（user / assistant / tool）
- 工具调用 id 映射（`ToolCallEntry`）
- 会话状态（`sessionState` Map）

策略：**FIFO 滑窗**，超出 `MAX_CONTEXT_MESSAGES = 50` 即丢最早。

---

## 2. 设计取舍

**为什么 FIFO 而非压缩？**
- 实现简单（一个 ArrayList + size 检查）
- 行为可预测（不会因 LLM 摘要失真）
- 教学场景下，50 条足够演示多轮工具调用

**代价**：
- 早期上下文丢失可能导致 LLM 失忆
- 不像 agentscope 的结构化压缩（task_goal/current_state/...）

---

## 3. 关键字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `MAX_CONTEXT_MESSAGES` | `int` 常量 = 50 | 滑窗上限 |
| `systemPrompt` | `String` | 系统提示词，prepend 到 messages |
| `messages` | `List<ContextMessage>` | 历史消息 |
| `toolCallMap` | `Map<String, ToolCallEntry>` | id → 工具调用记录 |
| `sessionState` | `ConcurrentHashMap<String, Object>` | 会话状态 |

---

## 4. 核心方法

### 4.1 `buildMessages()`
返回发送给 LLM 的完整 messages：
```
[system] + messages[0..N-1]
```
每次调用重建列表（不缓存），保证最新。

### 4.2 `addUserMsg(text)`
追加 `{role: "user", content: text}`，触发 `trimContext()`。

### 4.3 `addAssistantMsg(response)`
追加 `{role: "assistant", content, tool_calls}`，把每个 toolCall 注册到 `toolCallMap`。

### 4.4 `addToolResult(toolCallId, result)`
追加 `{role: "tool", tool_call_id: <id>, content: result}`。

> **关键**：必须用同一个 `toolCallId` 关联 assistant 的 tool_call 与 tool 角色的 result，否则下一轮 LLM 协议错误。

### 4.5 `trimContext()`
```java
while (messages.size() > MAX_CONTEXT_MESSAGES) {
    messages.remove(0);
}
```
**注意**：系统提示词不在 `messages` 列表里，不会被裁剪。

---

## 5. ToolCallEntry

记录一次工具调用的元数据，便于审计与调试：
```
{
  id: "call_xxx",
  name: "get_weather",
  arguments: "{\"city\":\"北京\"}",
  timestamp: Instant.now()
}
```

---

## 6. 与 agentscope 的差异

| 维度 | pimono `ContextManager` | agentscope `Agent.context` + 中间件 |
|---|---|---|
| 存储 | `List<ContextMessage>` + Map | `List<Msg>`（含 ContentBlock） |
| 上限策略 | FIFO 50 条硬截断 | 阈值 40 → 结构化压缩保留 10 |
| 压缩内容 | 无 | task_goal / current_state / key_findings / next_steps |
| 系统提示词 | 固定 | 可动态追加（团队工具注入） |
| 会话状态 | `sessionState` | `agentState` Map |
| 块类型 | role + text 字段 | 6 种 ContentBlock（Text/Tool/Thinking/...） |

---

## 7. 测试

`ContextManagerTest` 验证：
- FIFO 触发后最早消息被丢弃
- `toolCallMap` 正确登记
- 系统提示词不被裁剪
- `buildMessages` 顺序正确（system → user → assistant → tool）

---

## 8. 已知限制

- 无 token 计数，纯按消息条数裁剪 → 一条超长 tool result 可能让上下文 token 爆炸
- 无持久化，进程退出即丢失
- 单 Agent 单上下文，不支持多会话隔离
- 裁剪时若丢弃了某 `tool_call` 但保留了对应的 `tool result`，会触发协议错误（实际未观察到，因为 FIFO 同时丢）
