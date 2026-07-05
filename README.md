# Agent Demo Applications

基于 AgentScope 2.0 框架和 Pi Mono 架构开发的两个智能体演示应用。

## AgentScope Demo v2.0 — 架构升级要点

根据 AgentScope 2.0 剖析报告，本 Demo 已升级到 2.0 架构，核心变化：

| 1.x | 2.0 | 说明 |
|-----|-----|------|
| ReActAgent/CoordinatorAgent | 统一 Agent 类 | 一个 Agent 类，reply()/replyStream() |
| pipeline 编排 | ❌ 删除 | 框架不再替 Agent 做编排决策 |
| 无 | Event 事件流 | 22 类型化事件，可重放还原 Msg |
| 无 | Middleware 洋葱模型 | 6 个 hook 点，中间件链 |
| 无 | Permission 三态权限 | allow/deny/ask + Rules/Mode/Built-in 三引擎 |
| 无 | Workspace 沙箱 | Local/Docker/E2B 统一 API |
| 无 | Agent Team | Leader 用 4 个内置工具动态管理 Worker |
| 无 | Credential 凭证 | 解耦 API Key，主备 failover |
| MCP 简单封装 | MCP 统一客户端 | 声明式 StdioMCPConfig/HttpMCPConfig |
| 简单上下文 | 结构化压缩 | 保留任务目标/当前状态/关键发现/下一步 |
| 阻塞式 LLM 调用 | **SSE 流式调用** | token 级实时回显，首字延迟从 10s+ 降至百毫秒级 |
| 工具硬编码加载 | **功能开关 + 运行期切换** | 股票工具默认关闭，按需开启 |

## 项目结构

```
agentdemo/
├── pom.xml                          # 父 POM
├── agentscope-demo/                 # AgentScope 2.0 Demo
│   └── src/main/java/com/demo/agentscope/
│       ├── AgentScopeDemoApplication.java   # 主入口
│       ├── agent/
│       │   ├── Agent.java                   # 统一 Agent 类(2.0核心)
│       │   └── AgentTeam.java               # Leader-Worker 团队
│       ├── event/
│       │   ├── EventType.java               # 20+ 事件类型枚举
│       │   ├── AgentEvent.java              # 类型化事件
│       │   └── EventStream.java             # 事件流(可重放)
│       ├── message/
│       │   ├── Msg.java                     # Pydantic 化消息
│       │   └── ContentBlock.java            # 6 种内容块
│       ├── middleware/
│       │   ├── Middleware.java              # 中间件接口(6 hook)
│       │   ├── MiddlewareChain.java         # 中间件链(洋葱模型)
│       │   ├── TracingMiddleware.java       # 链路追踪
│       │   ├── ContextCompressionMiddleware.java  # 上下文压缩
│       │   ├── ReplyBudgetControlMiddleware.java  # Token 预算控制
│       │   ├── PermissionMiddleware.java     # 权限中间件
│       │   ├── AgentContext.java            # Agent 上下文
│       │   └── BudgetExceededException.java # 预算超限异常
│       ├── permission/
│       │   ├── PermissionDecision.java      # ALLOW/DENY/ASK
│       │   ├── PermissionMode.java          # EXPLORE/DONT_ASK/BYPASS
│       │   ├── PermissionRule.java          # 权限规则
│       │   ├── PermissionEngine.java        # 三引擎权限引擎
│       │   ├── PermissionMiddleware.java     # 权限中间件
│       │   └── PermissionDeniedException.java
│       ├── workspace/
│       │   ├── Workspace.java              # 沙箱接口
│       │   ├── LocalWorkspace.java         # 本地工作空间
│       │   ├── DockerWorkspace.java        # Docker 沙箱(桩)
│       │   ├── E2BWorkspace.java           # E2B 云沙箱(桩)
│       │   └── WorkspaceManager.java       # 多租户工作空间管理
│       ├── credential/
│       │   ├── CredentialProvider.java     # 凭证接口
│       │   └── DefaultCredentialProvider.java  # 主备 failover
│       ├── mcp/
│       │   ├── MCPConfig.java              # 声明式配置(Stdio/Http)
│       │   ├── MCPClient.java             # 统一 MCP 客户端
│       │   └── McpServerConnection.java    # JSON-RPC 2.0 连接
│       ├── model/
│       │   ├── ChatModel.java             # 统一 LLM 客户端（阻塞 + SSE 流式）
│       │   └── StreamSink.java            # 流式事件实时下沉接口
│       └── ui/
│           ├── ConsoleUI.java             # 控制台 UI
│           └── AgentProgressTracker.java  # 流式进度跟踪（token 级渲染）
├── pimono-demo/                     # Pi Mono Demo (不变)
```

## 环境要求

- **JDK**: 17+
- **Maven**: 3.6+
- **API Key**: OpenAI / DashScope / Anthropic / DeepSeek 任一

## 快速开始

```bash
# 编译
mvn clean compile

# 测试
mvn test

# 打包
mvn package -DskipTests

# 运行 AgentScope 2.0 Demo
export OPENAI_API_KEY="your-key"
# 或: export DASHSCOPE_API_KEY="your-key"
java -jar agentscope-demo/target/agentscope-demo-1.0.0.jar

# 运行 Pi Mono Demo
java -jar pimono-demo/target/pimono-demo-1.0.0.jar
```

## 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `OPENAI_API_KEY` | OpenAI API Key | - |
| `DASHSCOPE_API_KEY` | 阿里云 DashScope Key | - |
| `ANTHROPIC_API_KEY` | Anthropic Key | - |
| `DEEPSEEK_API_KEY` | DeepSeek Key | - |
| `MODEL_PROVIDER` | 模型提供商 | 自动检测 |
| `MODEL_NAME` | 模型名 | gpt-4o-mini |
| `MODEL_BASE_URL` | API 基础 URL | 自动 |
| `MCP_SERVERS` | 外部 MCP 服务器配置 | - |
| `WORKSPACE_DIR` | 工作空间目录 | `workspace/` |
| `REPLY_BUDGET` | 回复 Token 预算上限 | - |
| `STOCK_TOOLS_ENABLED` | **股票分析工具启动期开关**（详见下方"功能开关"） | `false` |

## AgentScope 2.0 Demo 交互命令

| 命令 | 说明 |
|------|------|
| `exit` / `quit` | 退出应用 |
| `history` | 查看对话历史 |
| `status` | 查看 Agent 状态 |
| `clear` | 清除上下文 |
| `team create` | 创建 Agent Team |
| `team status` | 查看团队状态 |
| `team dissolve` | 解散团队 |
| `tools` | 列出可用 MCP 工具 |
| `events` | 切换事件显示模式 |
| `permission` | 查看权限规则 |
| `verbosity <level>` | 调整详细程度（`minimal`/`standard`/`verbose`/`debug`） |
| `/stock on` | **运行期开启**股票分析工具（注册 4 个工具 + 权限规则 + 系统提示词追加） |
| `/stock off` | **运行期关闭**股票分析工具（反注册工具；权限规则移除；系统提示词重启清零） |
| `help` | 显示帮助 |

## 2.0 架构核心 API

### Agent (统一智能体)
```java
Agent agent = new Agent(name, systemPrompt, chatModel, mcpClient,
                         middlewareChain, permissionEngine, workspaceManager);
Msg response = agent.reply("What's the weather in Beijing?");
EventStream stream = agent.replyStream("Calculate 2+3");
```

### Middleware (洋葱模型)
```java
MiddlewareChain chain = new MiddlewareChain();
chain.add(new TracingMiddleware());
chain.add(new ContextCompressionMiddleware(40, 10));
chain.add(new PermissionMiddleware(permissionEngine));
chain.add(new ReplyBudgetControlMiddleware(100000));
```

### Permission (三态权限)
```java
PermissionEngine engine = new PermissionEngine();
engine.addRule(new PermissionRule("get_weather", PermissionDecision.ALLOW, "safe"));
engine.addRule(new PermissionRule("bash", PermissionDecision.ASK, "needs approval"));
engine.setMode(PermissionMode.EXPLORE); // 只读模式
```

### Workspace (沙箱抽象)
```java
WorkspaceManager wm = new WorkspaceManager();
Workspace ws = wm.createWorkspace("local", "user1", "agent1", "session1");
String content = ws.readFile("data.txt");
ws.writeFile("output.txt", "result");
```

### AgentTeam (Leader-Worker)
```java
AgentTeam team = new AgentTeam(leaderAgent);
team.createWorker("researcher", "You are a research specialist");
Msg response = team.reply("Research quantum computing");
team.dissolve();
```

---

## 新增特性：流式调用 + 股票工具功能开关（v2.0+）

### 特性一：SSE 流式调用改造

**痛点：** 原先 `ChatModel.chat()` 走 OpenAI `/chat/completions` 阻塞接口，模型推理完才整段返回，REPL 首字延迟 10–30 秒。

**改造方案：** 真 SSE 流式拉取，token 级实时回显。

#### 关键组件

| 组件 | 角色 | 文件 |
|---|---|---|
| `StreamSink` | 流式事件回调接口（`@FunctionalInterface`），SSE 读取期间每解析出一个事件就上推 | `model/StreamSink.java` |
| `ChatModel.chatStream()` | 同步阻塞读 SSE 流，通过 `sink.onEvent()` 推送 `TEXT_DELTA`/`THINKING_DELTA`/`TOOL_CALL`/`MODEL_CALL_END` | `model/ChatModel.java` |
| `EventType.TEXT_DELTA` / `THINKING_DELTA` | 新增的增量事件类型（与原 `TEXT_BLOCK`/`THINKING_BLOCK` 区分） | `event/EventType.java` |
| `AgentEvent.textDelta()` / `thinkingDelta()` | 增量事件工厂 | `event/AgentEvent.java` |
| `AgentProgressTracker.onTextDelta()` | 控制台 token 级渲染（`System.out.print` 不换行 + flush） | `ui/AgentProgressTracker.java` |
| `Agent.StreamSinkSink` | 内部 sink 实现：同时转发到外层 `EventStream`、调用进度跟踪器、累积到缓冲区构建最终 `Msg` | `agent/Agent.java` |

#### 工作流程

```
用户输入 ──▶ Agent.replyStream()
              │
              ├─ 构造 StreamSinkSink(eventStream, progressTracker)
              ├─ ChatModel.chatStream(..., sink) ── HTTP SSE 连接 ──▶ LLM
              │       │
              │       ├─ 读 "data: {...}" 行
              │       ├─ 解析 delta.content    ──▶ sink.onEvent(TEXT_DELTA)
              │       ├─ 解析 delta.reasoning  ──▶ sink.onEvent(THINKING_DELTA)
              │       ├─ 解析 delta.tool_calls ──▶ 累积到 ToolCallAccumulator
              │       └─ 流结束聚合 tool_call ──▶ sink.onEvent(TOOL_CALL)
              │                                     sink.onEvent(MODEL_CALL_END)
              │
              ├─ StreamSinkSink.onEvent:
              │     ├─ progressTracker.onTextDelta()  ──▶ 终端实时打印
              │     ├─ outer.emit()                   ──▶ 外层 EventStream
              │     └─ 累积到 textBuf / toolCalls
              │
              ├─ sink.buildResponse() ──▶ 构建最终 assistant Msg
              ├─ context.add(response)
              └─ 若有 tool_calls ──▶ 执行工具循环（下一轮重新流式调用）
```

#### 关键技术点

- **工具调用分片累积：** OpenAI 流式协议中 `tool_calls[].arguments` 是分片送达的，按 `index` 维护 `ToolCallAccumulator`，流结束才聚合完整的 JSON arguments 字符串。
- **EventStream 性能：** 流式路径单线程内 emit + 读，`ArrayList` 替代 `CopyOnWriteArrayList`，避免每 token O(N) 复制导致的 O(N²) 总开销。
- **DIM 样式作用域：** 思考链（reasoning_content）按 `\u001B[2m` 进入暗色、`\u001B[0m` 退出。状态由 `thinkingStreamStarted` 标志位管理，避免每个 delta 各自闭合造成渲染错乱。
- **usage 兜底：** 末帧带 `stream_options.include_usage=true` 时回 token 用量；未带时由 `Msg.TokenUsage(0, 0)` 兜底。
- **非流式回退：** `ChatModel.chat()` 与 `Agent.reply()` 完整保留，单测、批处理或低延迟容忍场景仍可走原路径。

#### 验证方式

```bash
mvn -pl agentscope-demo clean compile
# 启动 REPL，问"你好"，应看到逐字输出
# 问"列出当前目录文件" —— 验证 list_files 工具调用流式路径正常
mvn -pl agentscope-demo test   # 既有 276 个测试全绿（除预存在的 CredentialTest 环境相关失败）
```

---

### 特性二：股票分析工具功能开关

**痛点：** 股票分析工具（`list_industries` / `select_industry_leaders` / `get_stock_detail` / `update_stock_data`）在非股票场景下会无条件加载，把行业术语、数据 schema 注入系统提示词和工具列表，污染模型对其他任务（写代码、问答、文档生成等）的推理。

**方案：** 默认关闭，启动期环境变量 + 运行期 REPL 命令双入口开关。

#### 控制入口

| 入口 | 用法 | 时机 | 影响 |
|---|---|---|---|
| 环境变量 | `STOCK_TOOLS_ENABLED=true` | 启动期 | 一次性：注册工具 + 权限规则 + 系统提示词追加 |
| REPL 命令 | `/stock on` / `/stock off` | 运行期 | 动态：调 `MCPClient.registerCustomTool()` / `unregisterTool()` |

#### 默认行为（`STOCK_TOOLS_ENABLED=false`）

- ❌ 不注册 4 个股票工具到 `MCPClient`
- ❌ 不添加 4 条 `PermissionRule`（`list_industries` / `select_industry_leaders` / `get_stock_detail` / `update_stock_data`）
- ❌ 系统提示词不包含股票工具描述
- ✅ 启动日志：`股票分析工具已禁用 (STOCK_TOOLS_ENABLED=false，可通过 /stock on 或环境变量启用)`

#### `/stock on` 行为

调用 `enableStockTools()`（`AgentScopeDemoApplication.java:711`）：

1. 构造 `IndustryService` / `AkShareDataSource` / `TuShareDataSource` / `StockDataService` / `StockToolService`
2. `stockToolService.registerTools(mcpClient)` 注册 4 个工具
3. `addStockPermissionRules(permissionEngine)` 添加 4 条 ALLOW 规则
4. `agent.appendToSystemPrompt(STOCK_PROMPT_ADDENDUM)` 追加工具描述到系统提示词

#### `/stock off` 行为

```java
for (String tool : STOCK_TOOL_NAMES) {
    mcpClient.unregisterTool(tool);   // 反注册工具
    permissionEngine.removeRule(tool); // 移除权限规则
}
```

> **注：** 系统提示词**不动态缩减**（只追加不删，重启清零）。`/stock off` 输出会提示用户重启获得纯净 prompt。

#### 关键 API

- `MCPClient.unregisterTool(String name)` — 对称于 `registerCustomTool()`，移除 builtin 工具并刷新工具列表缓存
- `MCPClient.rebuildToolInfos()` — 重新计算 `allToolInfos` 缓存
- `PermissionEngine.removeRule(String toolName)` — 按工具名移除规则

#### 验证方式

```bash
# 1. 默认关闭
unset STOCK_TOOLS_ENABLED; ./startas.sh
# 启动日志显示"已禁用"，问"查询 600519"模型应不知道该工具存在

# 2. 运行期开启
/stock on    # 显示已开启
tools        # 列表里有 4 个股票工具
# 问"查询 600519" 能正常调 get_stock_detail

# 3. 运行期关闭
/stock off   # 显示已关闭
# 后续问"查询 600519"模型要么用通用知识答，要么提示该功能未启用

# 4. 启动期开启
STOCK_TOOLS_ENABLED=true ./startas.sh  # 日志显示"已启用 (4 个工具)"
```

#### 设计权衡

- **只做股票工具开关，不泛化到其他 builtin 工具：** read_file/execute_python 等通用工具被广泛依赖，开关化收益小、风险高。
- **`/stock off` 不删已注入的系统提示词：** 实现简单，避免字符串拼接到一半要回滚的复杂性。重启即清零。
- **权限规则集中化：** 启动期 `createPermissionEngine(stockEnabled)` 和运行期 `enableStockTools()` 都走同一个 `addStockPermissionRules()` 辅助方法，避免规则散落多处。

---

## 测试报告

- AgentScope Demo: 276 个测试用例（Event/Message/Middleware/Permission/Workspace/MCP/Credential/Agent/Stock）
- Pi Mono Demo: 25 个测试用例
- 已知预存在失败：`CredentialTest.defaultProviderGetModelName`（环境变量相关，与本次改动无关）

## 许可证

MIT License
