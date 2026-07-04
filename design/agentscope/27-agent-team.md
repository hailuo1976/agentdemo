# 27 Agent 与团队模块（agent）

> 包：`com.demo.agentscope.agent`
> 主类：`Agent` + `AgentTeam`

---

## 1. Agent（单 Agent）

### 1.1 职责

提供完整的 ReAct 循环：
- `reply(userInput)` — 同步循环
- `replyStream(userInput)` — 流式（事件流）

### 1.2 关键字段

| 字段 | 说明 |
|---|---|
| `id` / `name` | UUID 与可读名 |
| `systemPrompt` | 系统提示词，**可动态追加**（团队工具注入） |
| `middlewareChain` | 中间件链 |
| `chatModel` / `mcpClient` / `credentialProvider` / `permissionEngine` / `workspaceManager` | 五件套引用 |
| `context: List<Msg>` | 对话历史 |
| `agentState: Map` | 状态 |
| `maxIterations = 50` | 循环兜底 |
| `progressTracker` | 进度跟踪器（每次 reply 重建以反映 verbosity） |

### 1.3 reply 主循环

详见 `01-数据流时序.md` § 2.2。核心结构：

```
onReplyStart
  for iteration in [0, 50):
    onModelCall → chatModel.chat → onModelCallEnd
    context.add(response)
    if !response.hasToolCalls(): break       ← 最终答案
    for toolCall in response.toolCalls:
      onToolCall → permissionEngine.check → mcpClient.executeTool → onToolResult
      context.add(toolResultMsg)
  onReplyEnd (含上下文压缩)
```

### 1.4 truncateToolResult

按工具分级截断工具结果：
| 工具 | 上限 |
|---|---|
| `agent_message` | 20000 字符 |
| `agent_create` | 500 |
| `agent_list` | 5000 |
| 默认 | 5000 |

防止团队模式下工作者回复撑爆 Leader 上下文。

### 1.5 appendToSystemPrompt

允许在 Agent 构造后追加系统提示词内容。用于：
- `AgentTeam.registerTeamTools()` 注入团队工具描述
- 让 LLM 感知自身具备团队管理能力

---

## 2. AgentTeam（Leader-Worker）

### 2.1 模型

```
┌─────────────────────────────────────┐
│ Leader（Agent + 5 团队工具）        │
│   ├─ agent_create                   │
│   ├─ agent_message                  │
│   ├─ agent_message_parallel         │
│   ├─ agent_list                     │
│   └─ team_dissolve                  │
└─────────────────────────────────────┘
            │（动态创建）
            ▼
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│ Worker A         │  │ Worker B         │  │ Worker C         │
│ （独立 MCPClient │  │ （独立 MCPClient │  │ （独立 MCPClient │
│   排除团队工具） │  │   排除团队工具） │  │   排除团队工具） │
└──────────────────┘  └──────────────────┘  └──────────────────┘
```

### 2.2 关键字段

| 字段 | 说明 |
|---|---|
| `teamId` | UUID |
| `leader: Agent` | 领导者 |
| `workers: LinkedHashMap<String, Agent>` | 工作者 |
| `active: volatile boolean` | 团队是否活跃 |
| `progressTracker: TeamProgressTracker` | 团队进度 |

### 2.3 核心方法

#### `createWorker(name, systemPrompt)`
```
1. new MCPClient + initialize()       ← 工作者独立 MCP
2. leader.mcpClient.copyToolsTo(workerMcpClient, exclude=5 团队工具)
3. new Agent(name, systemPrompt, ..., workerMcpClient, ...)
4. workers.put(name, worker)
5. progressTracker.onLeaderCreateWorker(name)
```

**关键隔离**：工作者拿不到团队工具，无法越权 agent_create / team_dissolve。

#### `sendMessageToWorker(name, message)`
```
1. progressTracker.onLeaderAssignTask / onAgentCommunication / onWorkerStart
2. worker.reply(message)    ← 递归进入 Agent.reply 完整循环
3. progressTracker.onWorkerComplete(success, duration)
4. return worker 的回复 Msg
```

#### `sendMessageToWorkersParallel(tasks: Map<name, message>)`
```
1. for each task: CompletableFuture.supplyAsync(() -> sendMessageToWorker(...))
2. CompletableFuture.allOf(...).join()
3. 收集 results
```

并行执行，所有工作者同时开始工作，显著减少总耗时。

#### `dissolve()`
```
1. for worker: worker.shutdown()
2. workers.clear()
3. active = false
```

### 2.4 registerTeamTools

注册 5 个团队工具到 Leader 的 MCPClient：
| 工具 | 参数 |
|---|---|
| `agent_create` | name, system_prompt |
| `agent_message` | worker_name, message |
| `agent_message_parallel` | tasks (Map) |
| `agent_list` | — |
| `team_dissolve` | — |

并把团队工具描述（自然语言）追加到 Leader 的系统提示词。

### 2.5 进度跟踪事件

| 事件 | 时机 |
|---|---|
| `onLeaderCreateWorker` | agent_create |
| `onLeaderAssignTask` | agent_message |
| `onAgentCommunication` | 同上 |
| `onWorkerStart` / `onWorkerComplete` | sendMessageToWorker 前后 |

REPL 中的 `team status` 命令展示这些状态。

---

## 3. 工具集隔离（关键不变量）

**Leader 拥有 17 工具**（4 文件 + 3 代码 + 5 团队 + 5 默认 mock 已移除 = 12 + 外部 MCP）；
**Worker 拥有 7 工具**（4 文件 + 3 代码 + 外部 MCP，排除 5 团队）。

通过 `MCPClient.copyToolsTo(workerMcpClient, excludeTools)` 实现。

---

## 4. 并发模型

- `sendMessageToWorkersParallel` 使用 `CompletableFuture.supplyAsync` → 默认 ForkJoinPool
- 共享 `progressTracker` 必须线程安全（实现中用并发集合）
- 共享 `workers` map 由 LinkedHashMap 保护（非并发，写时在外部同步）

> 已知风险：多工作者同时操作同一文件可能竞态；当前未加文件锁。

---

## 5. 与 pimono 的差异

| 维度 | pimono | agentscope |
|---|---|---|
| Agent 数量 | 单实例 | Leader + 动态 Worker |
| 工具集隔离 | 无 | copyToolsTo 排除团队工具 |
| 任务分发 | 无 | 同步 + 并行两种 |
| 进度跟踪 | 无 | TeamProgressTracker |

---

## 6. 测试

- `AgentTest`：单 Agent reply 循环、压缩、预算超限
- `AgentTeamTest`：createWorker、sendMessage、parallel、dissolve
- `TeamToolRegistrationTest`：5 工具正确注册到 Leader
- `WorkerIsolationTest`：工作者工具集正确排除团队工具
