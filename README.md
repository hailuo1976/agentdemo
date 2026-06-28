# Agent Demo Applications

基于 AgentScope 2.0 框架和 Pi Mono 架构开发的两个智能体演示应用。

## AgentScope Demo v2.0 — 架构升级要点

根据 AgentScope 2.0 剖析报告，本 Demo 已升级到 2.0 架构，核心变化：

| 1.x | 2.0 | 说明 |
|-----|-----|------|
| ReActAgent/CoordinatorAgent | 统一 Agent 类 | 一个 Agent 类，reply()/replyStream() |
| pipeline 编排 | ❌ 删除 | 框架不再替 Agent 做编排决策 |
| 无 | Event 事件流 | 20+ 类型化事件，可重放还原 Msg |
| 无 | Middleware 洋葱模型 | 6 个 hook 点，中间件链 |
| 无 | Permission 三态权限 | allow/deny/ask + Rules/Mode/Built-in 三引擎 |
| 无 | Workspace 沙箱 | Local/Docker/E2B 统一 API |
| 无 | Agent Team | Leader 用 4 个内置工具动态管理 Worker |
| 无 | Credential 凭证 | 解耦 API Key，主备 failover |
| MCP 简单封装 | MCP 统一客户端 | 声明式 StdioMCPConfig/HttpMCPConfig |
| 简单上下文 | 结构化压缩 | 保留任务目标/当前状态/关键发现/下一步 |

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
│       │   └── ChatModel.java             # 统一 LLM 客户端
│       └── ui/
│           └── ConsoleUI.java             # 控制台 UI
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

## 测试报告

- AgentScope Demo: 127 个测试用例（Event/Message/Middleware/Permission/Workspace/MCP/Credential/Agent）
- Pi Mono Demo: 25 个测试用例
- 总计: **152 个测试用例，全部通过**

## 许可证

MIT License
