# 29 事件流模块（event）

> 包：`com.demo.agentscope.event`
> 主类：`EventStream` + `AgentEvent` + `EventType`

---

## 1. 职责

事件溯源（Event Sourcing）脊柱：把 Agent 运行过程中所有"发生了什么"按时间顺序记成不可变序列，供 UI 展示、日志审计、状态恢复（`replay()`）。

与中间件的区别：
- 中间件是**钩子**（做事的地方）
- 事件流是**账本**（记录做过的事）

---

## 2. EventType（20 种事件）

| 类别 | 事件 | 发射时机 |
|---|---|---|
| **回复生命周期** | `REPLY_START` | `Agent.reply()` 入口 |
| | `REPLY_END` | `Agent.reply()` 出口 |
| **模型调用** | `MODEL_CALL_START` | 调用 LLM 前 |
| | `MODEL_CALL_END` | LLM 返回，携带 prompt/completion tokens |
| **内容块** | `TEXT_BLOCK` | LLM 文本输出 |
| | `THINKING_BLOCK` | LLM 思考链（`reasoning_content` 字段） |
| **工具交互** | `TOOL_CALL` | 调用工具前 |
| | `TOOL_RESULT` | 工具返回后 |
| **用户/外部** | `REQUIRE_USER_CONFIRM` | 权限 ASK（当前未交互） |
| | `REQUIRE_EXTERNAL_EXECUTION` | 预留给外部执行 |
| **错误** | `ERROR` | 任意阶段异常 |
| **上下文** | `CONTEXT_COMPRESSED` | `ContextCompressionMiddleware` 触发后 |
| **权限** | `PERMISSION_CHECK` | `PermissionEngine.check` 返回 |
| | `PERMISSION_ASK` | 决策为 ASK |
| **工作空间** | `WORKSPACE_OPERATION` | 文件/目录操作 |
| **多智能体** | `AGENT_TEAM_CREATE` | `AgentTeam` 初始化 |
| | `AGENT_CREATE` | `agent_create` 工具执行 |
| | `AGENT_MESSAGE` | `agent_message` 工具执行 |
| | `TEAM_DISSOLVE` | `team_dissolve` 工具执行 |
| **预算** | `REPLY_BUDGET_EXCEEDED` | token 用量超过 REPLY_BUDGET |

---

## 3. AgentEvent 结构

```java
class AgentEvent {
    String  id;         // UUID
    EventType type;
    Instant timestamp;
    Map<String, Object> data;   // 类型特定载荷
    String  agentId;            // 发起 Agent
}
```

**不可变**：构造后字段不可改，`getData()` 返回不可变视图。

### 3.1 静态工厂方法（按事件类型）

| 方法 | 关键 data 字段 |
|---|---|
| `replyStart(agentId)` | — |
| `replyEnd(agentId)` | — |
| `modelCallStart(agentId, modelName)` | `modelName` |
| `modelCallEnd(agentId, modelName, prompt, completion)` | `modelName`, `promptTokens`, `completionTokens` |
| `textBlock(agentId, content)` | `content` |
| `thinkingBlock(agentId, content)` | `content` |
| `toolCall(agentId, toolName, args)` | `toolName`, `arguments` |
| `toolResult(agentId, toolName, result)` | `toolName`, `result` |
| `requireUserConfirm(agentId, message)` | `message` |
| `requireExternalExecution(agentId, command)` | `command` |
| `error(agentId, message)` | `message` |
| `contextCompressed(agentId, originalSize, newSize)` | `originalSize`, `newSize` |
| `permissionCheck(agentId, permission, granted)` | `permission`, `granted` |
| `permissionAsk(agentId, permission, reason)` | `permission`, `reason` |
| `workspaceOperation(agentId, operation, path)` | `operation`, `path` |
| `agentTeamCreate(agentId, teamId, purpose)` | `teamId`, `purpose` |
| `agentCreate(agentId, newAgentId, agentType)` | `newAgentId`, `agentType` |
| `agentMessage(agentId, targetAgentId, message)` | `targetAgentId`, `message` |
| `teamDissolve(agentId, teamId)` | `teamId` |
| `replyBudgetExceeded(agentId, budget, actual)` | `budget`, `actual` |

带类型转换的取值：`event.getData("promptTokens", Integer.class)`。

---

## 4. EventStream

### 4.1 字段

```java
class EventStream {
    String agentId;
    List<AgentEvent> events;   // CopyOnWriteArrayList（并发安全）
}
```

`CopyOnWriteArrayList`：写少（按 reply 计）、读多（UI/日志频繁遍历）、需要线程安全（团队并行场景下 worker 也会向共享流写）。

### 4.2 核心 API

| 方法 | 行为 |
|---|---|
| `emit(event)` | 追加事件（null 安全） |
| `getEvents()` | 不可变视图 |
| `getEventsByType(type)` | 按类型筛选 |
| `size()` / `isEmpty()` | 状态查询 |
| `clear()` | 清空（很少用，慎用） |
| `toText()` | 拼接所有 TEXT_BLOCK 内容 |
| `replay()` | **重建为 Msg**（事件溯源核心） |

### 4.3 replay 算法

```
遍历 events，按 type 转换：
  TEXT_BLOCK      → ContentBlock.TextBlock
  THINKING_BLOCK  → ContentBlock.ThinkingBlock
  TOOL_CALL       → ContentBlock.ToolCallBlock(callId="call_"+idx, ...)
  TOOL_RESULT     → ContentBlock.ToolResultBlock(callId="call_"+(idx-1), ...)
  MODEL_CALL_END  → 累加 promptTokens / completionTokens
  ERROR           → 包成 TextBlock("[ERROR] ...")
  其他            → 跳过（不影响消息重建）

汇总为 Msg(id=UUID, role="assistant", content=blocks, usage=合计tokens, ...)
metadata 带：agentId / eventCount / firstEventTimestamp / lastEventTimestamp
```

**注意**：当前 `replay()` 用 `call_<index>` 重建 `tool_call_id`，与真实 `chatModel.chat` 返回的 ID 不一致，主要用于演示和审计场景的状态重建，**不**用于回写 `context`。

---

## 5. 与 Agent / Middleware 的接线

```
Agent.reply():
  stream = new EventStream(agentId)
  stream.emit(replyStart)

  for iter:
    stream.emit(modelCallStart)
    response = chatModel.chat(...)
    stream.emit(modelCallEnd(prompt, completion))
    for block in response.content:
      if text:    stream.emit(textBlock)
      if think:   stream.emit(thinkingBlock)
      if toolCall:
        stream.emit(toolCall)
        ... executeTool ...
        stream.emit(toolResult)

  stream.emit(replyEnd)
  return stream.replay() or stream
```

中间件也通过 `EventStream` 发射事件（例如 `ContextCompressionMiddleware` 发 `CONTEXT_COMPRESSED`、`ReplyBudgetControlMiddleware` 发 `REPLY_BUDGET_EXCEEDED`）。

---

## 6. UI 消费（ConsoleUI）

`ConsoleUI.printEventStream(stream)`：
- 按事件类型分配 emoji 图标（`REPLY_START=🚀`、`TOOL_CALL=🔧`、`AGENT_TEAM_CREATE=👥`、…）
- 显示 `[HH:mm:ss] 简要描述`
- 简要描述由 `eventSummary(event)` 生成（按类型取关键 data 字段）

由 REPL `events` 命令切换是否展示。

---

## 7. 并发与生命周期

- **线程安全**：`CopyOnWriteArrayList` 支持多线程并发 emit；`getEvents()` 返回不可变快照
- **生命周期**：通常一个 `Agent.reply()` 一个 `EventStream`；`Agent` 字段不持有长期流
- **回放 ≠ 重放**：`replay()` 把事件折叠回单条 `Msg`，不会重新触发工具执行或模型调用

---

## 8. 与 pimono 的差异

pimono **无**事件流抽象——所有可观察性只能通过 SLF4J 日志。agentscope 的 EventStream 提供：
- 结构化（typed event + data map）
- 可回放（replay → Msg）
- UI 友好（图标/摘要/筛选）
- 与中间件、团队、权限、预算、压缩深度集成

---

## 9. 测试

- `EventStreamTest`：emit 顺序、并发 emit、replay 重建、按类型筛选、toText 拼接
- `AgentEventTest`：各静态工厂方法、getData 类型转换、不可变性
