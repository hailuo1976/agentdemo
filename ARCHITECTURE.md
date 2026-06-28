# Agent Demo 项目架构与设计说明

> 生成日期：2026-06-28
> 范围：`agentdemo/` 下两个子模块 — `pimono-demo`（Pi Mono 极简实现）与 `agentscope-demo`（AgentScope 2.0 完整实现）
> 视角：**Agent 相关技术的通用性抽象** + **两个 Demo 的个性化差异**

---

## 一、项目定位

本仓库是一个 **Agent 技术验证型 Demo 项目**，包含两个独立可运行的 Java 17 应用，目标是对照展示"一个生产级 Agent 框架应该具备哪些核心能力"，以及"用最少的代码可以实现到什么程度"。

| 维度 | pimono-demo | agentscope-demo |
|---|---|---|
| 设计哲学 | **极简手写**：从零搭起 LLM + 工具循环 | **完整复刻**：对齐 AgentScope 2.0 的"六件套"抽象 |
| 代码量 | 8 个主类，约 700 行 | 30+ 个主类，约 2500 行 |
| 测试用例 | 25 | 127 |
| 适合阅读 | 入门 Agent 原理 | 进阶生产级架构 |
| 入口 | `com.demo.pimono.PiMonoDemoApplication` | `com.demo.agentscope.AgentScopeDemoApplication` |

---

## 二、Agent 技术的通用性（两个 Demo 共有的核心范式）

不论极简还是完整，一个能跑的 Agent 都跑不出以下 **五个通用层**。这是从两个 Demo 抽象出的"Agent 最大公约数"。

### 2.1 ReAct 推理-行动循环（Agent 的心脏）

两个 Demo 的核心循环都是同一个模式：

```
用户输入 → [上下文累积] → LLM 推理
                          ↓
                    ┌─────┴─────┐
                    │ 是否工具调用？│
                    └─────┬─────┘
                  是 ↓         否 ↓
            执行工具 → 结果回写   输出最终答案
                  ↓
            再回 LLM 推理（直到 max_rounds 或无工具调用）
```

- **pimono**：`AgentCore.chat()` 的 `while (round < MAX_TOOL_ROUNDS)` 循环（`MAX_TOOL_ROUNDS=10`），见 `AgentCore.java:60-104`。
- **agentscope**：`Agent.reply()` 内同样的循环结构，但循环体被拆分到中间件 hook 之间。

**通用性结论**：ReAct 循环是 Agent 的最低必要条件，任何 Agent 框架都必须实现它。

### 2.2 LLM 客户端抽象（统一 Chat Completions 协议）

两个 Demo 都把"调用大模型"封装成统一接口，屏蔽 OpenAI / DashScope / Anthropic 等厂商差异。

| 要素 | pimono `LlmClient` | agentscope `ChatModel` |
|---|---|---|
| 协议 | OpenAI 兼容 `/chat/completions` | 多 Backend（DashScope/Anthropic/OpenAI 等） |
| 工具协议 | OpenAI `tools` + `tool_calls` 字段 | 同上 + ContentBlock 表达 |
| 异常 | `LlmException` | 类似 |
| HTTP | OkHttp 4.12，30s/120s 超时 | 同 |

**通用性结论**：Agent 必须有"模型无关"的 LLM 抽象层，工具调用走 OpenAI Function Calling 已成事实标准。

### 2.3 工具协议（MCP / Function Calling）

两个 Demo 都实现了 **MCP（Model Context Protocol）** 客户端能力：

- **MCP 协议版本**：`2024-11-05`（JSON-RPC 2.0 over stdio）
- **生命周期**：`initialize` → `notifications/initialized` → `tools/list` → `tools/call`
- **工具描述结构**：`{name, description, inputSchema(JSON Schema)}`

`McpServerConnection`（pimono）和 `McpServerConnection`（agentscope）几乎一致，都是 `ProcessBuilder` 拉起子进程，按行读写 JSON-RPC。

**通用性结论**：MCP 是 Agent 与外部工具解耦的标准协议，工具发现 + 工具调用 + 工具结果回灌是固定三段式。

### 2.4 上下文管理（消息历史 + 截断）

两个 Demo 都维护一个有序的消息列表作为短期记忆，并提供截断策略避免上下文爆炸：

- **pimono**：`ContextManager.MAX_CONTEXT_MESSAGES = 50`，FIFO 滑动窗口（`trimContext()` 删除最早消息）
- **agentscope**：滑动窗口 + `ContextCompressionMiddleware`（结构化压缩，保留"任务目标/当前状态/关键发现/下一步"）

**通用性结论**：上下文窗口有限 → 必须有"加消息 + 截断/压缩"机制。

### 2.5 工具调用元数据闭环

工具调用的全过程需要四元组 `tool_call_id / tool_name / arguments / result` 在上下文中完整记录，否则 LLM 无法把"我刚才调了什么"和"工具返回了什么"对应起来。两个 Demo 都在上下文里保留了这四元组（见 `Context.ToolCallEntry` 和 `Msg` 中的 `ToolCallBlock` + `ToolResultBlock`）。

**通用性结论**：`tool_call_id` 是 Agent 上下文里最容易被忽略、但绝对不能省的关联键。

---

## 三、pimono-demo 个性化设计（极简实现）

### 3.1 包结构与职责

```
com.demo.pimono
├── PiMonoDemoApplication   ← main 入口，环境变量解析 + REPL
├── agent
│   ├── AgentCore           ← ReAct 循环主体（核心 152 行）
│   ├── ToolDefinition      ← 工具元数据(name/desc/schema/server)
│   └── ToolResult          ← 工具结果(success/error 双态)
├── ai
│   └── LlmClient           ← OkHttp + Jackson 手写 OpenAI 兼容客户端
├── context
│   ├── Context             ← Message 列表 + toolCalls 关联
│   └── ContextManager      ← 滑动窗口 + sessionState
├── mcp
│   ├── McpClientManager    ← builtin 工具 + 外部 MCP 连接管理
│   └── McpServerConnection ← JSON-RPC 2.0 over stdio
└── ui
    └── ConsoleUI           ← ANSI 彩色控制台输出
```

### 3.2 个性化亮点（"少即是多"）

1. **零外部 Agent 框架依赖**：`pom.xml` 只引 Jackson + OkHttp，全部逻辑手写，便于教学。
2. **内置 Mock 工具集**：`McpClientManager.registerBuiltinTools()` 注册了 `get_weather / calculate / search / get_time / translate` 五个内置工具，无需外部 MCP Server 即可演示完整 ReAct 链路。
3. **手写表达式求值器**：`SimpleExpressionEvaluator` 用递归下降解析四则运算（`parseTerm/parseFactor`），不依赖任何表达式引擎 —— 演示"工具内部实现可以任意复杂"。
4. **单上下文实例**：`ContextManager` 只维护**一个** `currentContext`，没有多会话概念；sessionState 用 `ConcurrentHashMap` 承载临时 KV。
5. **三态系统提示词**：默认 prompt 在构造器里写死（`ContextManager.java:18`），可以通过 `setSystemPrompt` 替换。

### 3.3 个性化短板（刻意的取舍）

- 无权限系统（任何工具都能直接执行）
- 无中间件 / 事件流（不可观测、不可插拔）
- 无工作空间沙箱（直接在主机进程内执行）
- 无流式输出（一次性返回完整响应）
- 上下文压缩仅做 FIFO 截断，不做语义压缩

---

## 四、agentscope-demo 个性化设计（AgentScope 2.0 完整实现）

### 4.1 包结构（"六件套" + 三大子系统）

```
com.demo.agentscope
├── agent
│   ├── Agent               ← 统一 Agent 类（reply / replyStream）
│   └── AgentTeam           ← Leader-Worker 动态团队
├── message
│   ├── Msg                 ← Pydantic 化消息（对标 2.0 Msg）
│   └── ContentBlock        ← 6 种内容块（Text/ToolCall/ToolResult/Thinking/Hint/Data）
├── event
│   ├── EventType           ← 20+ 类型化事件枚举
│   ├── AgentEvent          ← 事件载体
│   └── EventStream         ← 可重放事件流
├── middleware               ← 洋葱模型（6 hook）
│   ├── Middleware(接口)
│   ├── MiddlewareChain
│   ├── TracingMiddleware
│   ├── ContextCompressionMiddleware
│   ├── ReplyBudgetControlMiddleware
│   └── AgentContext
├── permission               ← 三态权限三引擎
│   ├── PermissionDecision  ← ALLOW / DENY / ASK
│   ├── PermissionMode      ← EXPLORE / DONT_ASK / BYPASS
│   ├── PermissionRule
│   ├── PermissionEngine    ← Rules → Mode → Built-in 串联
│   └── PermissionMiddleware
├── workspace                ← 沙箱抽象（Local/Docker/E2B 三后端）
│   ├── Workspace(接口)
│   ├── LocalWorkspace
│   ├── DockerWorkspace(桩)
│   ├── E2BWorkspace(桩)
│   └── WorkspaceManager    ← 多租户隔离
├── credential               ← 凭证解耦 + 主备 failover
│   ├── CredentialProvider(接口)
│   └── DefaultCredentialProvider
├── mcp                      ← 声明式 MCP 配置
│   ├── MCPConfig            ← StdioMCPConfig / HttpMCPConfig
│   ├── MCPClient            ← 统一客户端
│   └── McpServerConnection
├── model
│   └── ChatModel            ← 多 Backend 统一 LLM 客户端
└── ui
    └── ConsoleUI
```

### 4.2 个性化亮点（"工程化生产级"）

#### (1) 事件流（EventStream）— 可观测的核心
- 一次 `reply()` 产出一条**类型化事件流**：`ReplyStart → ModelCall → TextBlock → ToolCall → ToolResult → ... → ReplyEnd`
- 事件流**可重放**：累加事件即可还原最终的 assistant `Msg`
- 对标 AG-UI / A2A 协议的 streaming-first 设计

#### (2) 中间件洋葱模型 — 可插拔的横切关注点
6 个 hook 点串起整个回复生命周期：
```
onReplyStart → onModelCall → [LLM] → onModelCallEnd
                                   ↕
                          onToolCall → [Tool] → onToolResult
onReplyEnd ← ← ← ← ← ← ← ← ← ← ←
```
内置中间件：
- `TracingMiddleware`（OpenTelemetry 链路追踪）
- `ContextCompressionMiddleware`（结构化压缩）
- `ReplyBudgetControlMiddleware`（token 预算控制，超限抛 `BudgetExceededException`）
- `PermissionMiddleware`（接入权限引擎）

#### (3) 权限系统 — 金融级高危操作管控
**三态决策**：`ALLOW / DENY / ASK`（ASK 时可一键"采纳建议规则"未来自动放行）
**三引擎串联**（任一非 ALLOW 即终止）：
1. **Rules**：显式规则匹配（如 `bash → ASK`）
2. **Mode**：全局模式（`EXPLORE` 只读 / `DONT_ASK` 静默拒绝 / `BYPASS` 跳过）
3. **Built-in checks**：危险模式检测（`rm -rf`、`/etc/` 路径、`chmod 777` 等）

#### (4) Workspace 沙箱 — 执行环境抽象
- 三后端同一套 API：`LocalWorkspace / DockerWorkspace / E2BWorkspace`
- `WorkspaceManager` 做 agent 级隔离：`workspace_id` 绑定 `user_id / agent_id / session_id`
- 多租户友好

#### (5) AgentTeam — Leader-Worker 动态团队
不是 LangGraph 的显式 DAG，也不是 AutoGen 的对话编排，而是 **Leader Agent 用 4 个内置工具**（TeamCreate / AgentCreate / 消息交换 / TeamDissolve）**动态拉起 Worker session**，每个 Worker 各自独立 workspace 和事件流。

#### (6) Credential 解耦
API Key 不再硬编码在 Agent 里，由 `CredentialProvider` 统一管理，支持主备 failover（primary 失败自动切 standby）。

### 4.3 个性化短板（与上游一致）

- RAG / 长期记忆未实现（对齐 2.0.2 上游"RAG will return"状态）
- Docker / E2B Workspace 是桩实现
- 分布式 / Actor 模型未实现（对齐 2.0 上游"分布式还在补"）

---

## 五、关键对比表

| 能力维度 | pimono-demo | agentscope-demo | 通用性 |
|---|---|---|---|
| ReAct 循环 | ✅ while 循环 | ✅ + 中间件包装 | ★★★ 必备 |
| LLM 抽象 | ✅ OpenAI 兼容 | ✅ 多 Backend | ★★★ 必备 |
| MCP 客户端 | ✅ stdio JSON-RPC | ✅ + Http 配置 | ★★★ 必备 |
| 上下文管理 | ✅ FIFO 截断 | ✅ + 结构化压缩 | ★★★ 必备 |
| 工具元数据闭环 | ✅ tool_call_id | ✅ + ContentBlock | ★★★ 必备 |
| 事件流 | ❌ | ✅ 20+ 类型化事件 | ★★ 进阶 |
| 中间件洋葱 | ❌ | ✅ 6 hook | ★★ 进阶 |
| 权限系统 | ❌ | ✅ 三态三引擎 | ★★ 进阶（金融必备） |
| 沙箱 | ❌ | ✅ 三后端抽象 | ★★ 进阶 |
| Agent 团队 | ❌ | ✅ Leader-Worker | ★ 高级 |
| 凭证 failover | ❌ | ✅ 主备切换 | ★ 高级 |
| 流式输出 | ❌ | ✅ EventStream | ★★ 进阶 |
| 多租户 | ❌ | ✅ WorkspaceManager | ★ 高级 |

---

## 六、设计权衡与启示

### 6.1 极简派（pimono）的哲学
- **适合**：教学、原型验证、单一场景工具型 Agent
- **代价**：无法观测、无法管控、无法扩展
- **关键代码**：`AgentCore.java` 152 行读完即理解 Agent 本质

### 6.2 工程派（agentscope）的哲学
- **适合**：生产环境、多租户、金融等强合规场景
- **代价**：复杂度高、学习曲线陡
- **关键判断**：上游 AgentScope 2.0 的核心立场是 "**为单 Agent 的可靠性/可控性而生**"，刻意削弱多智能体编排（删 pipeline、删 DAG），只保留 Leader-Worker 软编排。

### 6.3 一个 Agent 框架的"必要 vs 可选"清单（项目验证结论）

| 类别 | 必备 | 可选（看场景） |
|---|---|---|
| 推理循环 | ReAct 循环 | Tree of Thoughts / Reflection |
| 模型接入 | OpenAI 兼容协议 | 多 Backend / 自托管模型 |
| 工具协议 | MCP / Function Calling | 自定义工具描述 |
| 上下文 | FIFO 截断 | 结构化压缩 / RAG 长期记忆 |
| 可观测 | 日志 | 事件流 / OpenTelemetry |
| 横切 | 无 | 中间件洋葱 |
| 安全 | 无 | 三态权限 |
| 执行环境 | 进程内 | 沙箱 / 容器 / 云 |
| 协作 | 单 Agent | Leader-Worker / DAG / 对话型 |

### 6.4 与上游对齐度

本仓库 `agentscope-demo` 是 AgentScope 2.0 Python 上游的 **Java 复刻教学版**，对照 `AgentScope2.0剖析_20260626.md` 中提到的"六件套"（Message / Event / Agent / Middleware / Workspace / Toolkit+Permission+Skill+Model+Credential+App）已覆盖前五件，`App`（FastAPI 服务化）未复刻（Java 侧可用 Spring Boot 等价替代，留作后续工作）。

---

## 七、运行与验证

```bash
# 编译全部
mvn clean compile

# 运行极简版
export OPENAI_API_KEY="sk-..."
java -jar pimono-demo/target/pimono-demo-1.0.0.jar

# 运行完整版
java -jar agentscope-demo/target/agentscope-demo-1.0.0.jar
```

测试覆盖：pimono 25 用例 / agentscope 127 用例，全部通过。

---

## 附：核心源码索引

| 关键概念 | pimono 文件:行 | agentscope 文件 |
|---|---|---|
| Agent 主循环 | `pimono/agent/AgentCore.java:60` | `agentscope/agent/Agent.java` |
| LLM 调用 | `pimono/ai/LlmClient.java:34` | `agentscope/model/ChatModel.java` |
| MCP 连接 | `pimono/mcp/McpServerConnection.java:37` | `agentscope/mcp/McpServerConnection.java` |
| 上下文 | `pimono/context/Context.java:6` | `agentscope/message/Msg.java` |
| 工具执行 | `pimono/mcp/McpClientManager.java:99` | `agentscope/agent/Agent.java` |
| 权限引擎 | — | `agentscope/permission/PermissionEngine.java:21` |
| 中间件 | — | `agentscope/middleware/Middleware.java:22` |
| 事件流 | — | `agentscope/event/EventStream.java` |
| 工作空间 | — | `agentscope/workspace/Workspace.java` |
