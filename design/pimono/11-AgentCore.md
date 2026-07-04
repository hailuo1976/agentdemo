# 11 AgentCore：ReAct 主循环

> 源文件：`pimono-demo/src/main/java/com/demo/pimono/agent/AgentCore.java`（~150 行）

---

## 1. 职责

把"用户输入"转成"最终文本答案"，方式是 ReAct 循环：
**推理（LLM）→ 行动（工具）→ 观察结果 → 再推理**，直到 LLM 不再要求工具调用。

---

## 2. 关键字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `MAX_TOOL_ROUNDS` | `int` 常量 = 50 | 循环兜底（防止 LLM 反复要工具） |
| `llmClient` | `LlmClient` | OpenAI 兼容客户端 |
| `contextManager` | `ContextManager` | 上下文 + 系统提示词 |
| `mcpClientManager` | `McpClientManager` | 工具调度 |

---

## 3. 核心方法：`chat(String userInput)`

伪代码（**与代码逻辑一致**）：

```java
public String chat(String userInput) {
    contextManager.addUserMsg(userInput);

    for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
        List<ContextMessage> messages = contextManager.buildMessages();
        List<ToolSchema> tools = mcpClientManager.listToolSchemas();

        LlmResponse response = llmClient.chatCompletion(messages, tools);

        if (!response.hasToolCalls()) {
            contextManager.addAssistantMsg(response.content);
            return response.content;            // ← 最终答案
        }

        contextManager.addAssistantMsg(response);   // 含 tool_calls
        for (ToolCallInfo tc : response.toolCalls) {
            String result = mcpClientManager.executeTool(tc.name, tc.arguments);
            contextManager.addToolResult(tc.id, result);
        }
    }
    return "已达到最大工具调用轮数，无法完成";
}
```

---

## 4. 循环退出条件

| 条件 | 结果 |
|---|---|
| LLM 响应不含 `tool_calls` | 正常返回文本 |
| `round >= MAX_TOOL_ROUNDS (50)` | 返回兜底字符串 |
| 异常 | 抛出（无 try/catch，由上层 REPL 处理） |

---

## 5. 与 agentscope `Agent.reply()` 的关键差异

| 维度 | pimono `AgentCore` | agentscope `Agent` |
|---|---|---|
| 钩子 | 无 | 6 个中间件钩子 |
| 事件流 | 无 | 20+ 类型化事件可重放 |
| 权限检查 | 无 | 双闸（中间件 + 引擎） |
| 工具结果截断 | 无 | 按工具分级 |
| 异常处理 | 不捕获 | try/catch 兜底返回错误消息 |
| 进度跟踪 | 无 | `AgentProgressTracker` |
| 系统提示词动态注入 | 固定 | `appendToSystemPrompt` 支持团队工具注入 |

---

## 6. 测试覆盖

`AgentCoreTest`（pimono-demo 测试）验证：
- 无工具场景下直接返回文本
- 单轮工具调用场景
- 多轮工具调用场景
- 达到 MAX_TOOL_ROUNDS 兜底

---

## 7. 修改建议（仅文档目的，不修改代码）

如需扩展，最自然的演进方向：
1. **try/catch 包裹**：避免一次工具异常终止整个对话
2. **工具结果截断**：防止外部长输出撑爆上下文
3. **事件钩子**：最小化加一个回调接口即可观察
4. **maxIterations 可配**：从 env 读

> 这些都是 agentscope-demo 已经做的事——pimono 故意不做，保留极简。
