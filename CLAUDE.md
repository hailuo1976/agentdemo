# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本仓库中工作时提供指引。

## 项目概览

本仓库包含两个**相互独立**的 Java 17 Agent 演示应用，用于对比两种实现哲学：

- **`pimono-demo/`** — 极简手写 Agent（约 700 行，8 个核心类）。面向教学/原型。ReAct 循环 + OpenAI 兼容客户端 + MCP stdio 客户端，不引入框架级抽象。
- **`agentscope-demo/`** — AgentScope 2.0「六件套」架构的完整 Java 复刻（约 2500 行，30+ 个类）。面向生产：事件流、中间件洋葱模型、三态权限引擎、工作空间沙箱、带 failover 的凭证提供者、Leader-Worker 智能体团队。

二者均为父 POM（`com.demo:agent-demos:1.0.0`）下的 Maven 子模块。**没有共享库模块** —— 通用概念（ReAct 循环、LLM 客户端、MCP）刻意保持重复，以便进行对照式教学。两个模块的并排对比见 `ARCHITECTURE.md`。

## 构建、运行、测试

JDK 17 与 Maven 已本地内置于 `.tools/`（已 gitignore）。`start*.sh` 脚本（同样已 gitignore —— 内含个人 API Key）负责设置 `JAVA_HOME`/`PATH` 并启动 shaded jar。

```bash
# 构建两个模块
mvn clean compile
mvn package -DskipTests        # 产出 agentscope-demo-1.0.0.jar / pimono-demo-1.0.0.jar（shaded）

# 运行测试（所有模块）
mvn test

# 运行单个测试类
mvn test -pl agentscope-demo -Dtest=PermissionTest
mvn test -pl pimono-demo -Dtest=AgentCoreTest

# 运行单个测试方法
mvn test -pl agentscope-demo -Dtest=PermissionTest#specificMethod
```

运行任一应用前必需的环境变量（由 `DefaultCredentialProvider` / `PiMonoDemoApplication` 读取）：

| 变量 | 用途 |
|---|---|
| `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` / `ANTHROPIC_API_KEY` / `DEEPSEEK_API_KEY` | 至少配置一个 |
| `MODEL_PROVIDER` | 省略时自动检测（agentscope 优先级：dashscope > openai） |
| `MODEL_NAME` | 如 `gpt-4o-mini`、`glm-5.1` |
| `MODEL_BASE_URL` | OpenAI 兼容的基础 URL |
| `MCP_SERVERS` | 可选，格式 `command:arg1,arg2` |
| `WORKSPACE_DIR` | 默认 `workspace/`（仅 agentscope） |
| `REPLY_BUDGET` | Token 预算上限（仅 agentscope） |

两个应用均为交互式 REPL —— 可通过 stdin 管道喂入提示词，或在终端中直接运行。

## 架构（总览）

### 共享的 Agent 基底（两个模块通用）

任何 Agent 都需要的五层，以及各模块的实现位置：

| 层 | pimono | agentscope |
|---|---|---|
| ReAct 循环 | `AgentCore.chat()` 的 `while (round < MAX_TOOL_ROUNDS)` | `Agent.reply()` —— 循环体拆分到各中间件 hook |
| LLM 客户端 | `ai/LlmClient`（OkHttp + Jackson，OpenAI `/chat/completions`） | `model/ChatModel`（多后端，阻塞 + SSE 流式） |
| MCP 客户端 | `mcp/McpServerConnection`（基于 stdio 的 JSON-RPC 2.0） | `mcp/McpServerConnection` + `MCPClient`（声明式 Stdio/Http 配置） |
| 上下文 | `context/ContextManager` FIFO 滑动窗口（50 条消息） | `middleware/ContextCompressionMiddleware` 结构化压缩 |
| 工具调用元数据 | `Context.ToolCallEntry` | `message/ContentBlock`（6 种块类型，含 ToolCall/ToolResult） |

请求与结果之间的 `tool_call_id` 关联在两个模块中都是强制要求 —— 绝不可丢。

### agentscope-demo 分层架构

入口：`com.demo.agentscope.AgentScopeDemoApplication`。其 `main()` 是规范的装配示例 —— 改动时请按此顺序：

1. **Credential**（`credential/`）—— `DefaultCredentialProvider` 通过环境变量解析 API Key，支持主备 failover。
2. **MCP**（`mcp/`）—— `MCPClient` 聚合内置工具 + 从 `MCP_SERVERS` 解析出的外部 stdio MCP 服务器，后续也会注册文件/代码执行工具。
3. **FilePermission + Workspace**（`filepermission/`、`workspace/`）—— 两层独立的权限：
   - `FilePermissionManager`（路径级，glob 模式，扩展名黑名单，10MB 上限，默认 DENY_ALL）通过 `SecureFileWorkspace` 包裹 `LocalWorkspace`。
   - `workspace/` 提供 `LocalWorkspace`（真实）以及 `DockerWorkspace` / `E2BWorkspace`（桩），并通过 `WorkspaceManager` 支持多租户隔离。
4. **Code execution**（`execution/`）—— `CodeExecutionManager` 运行 python/shell，`CommandSafetyChecker` 拦截危险命令并设 30 秒超时。
5. **Permission**（`permission/`）—— `PermissionEngine` 按 **Rules → Mode → Built-in** 链式判定（任一非 ALLOW 即终止）。三态决策：`ALLOW`/`DENY`/`ASK`。模式：`EXPLORE`（只读）、`DONT_ASK`（ASK→DENY，应用默认）、`BYPASS`（跳过）。`PermissionMiddleware` 把它接入中间件链。
6. **Agent**（`agent/Agent.java`）—— 统一的 `reply()` / `replyStream()`。`AgentTeam` 为 leader 提供四个团队管理工具（`agent_create`、`agent_message`、`agent_list`、`team_dissolve`），动态拉起 worker 会话而非静态 DAG。
7. **Middleware 洋葱模型**（`middleware/`）—— 6 个 hook 点（`onReplyStart`、`onModelCall`、`onModelCallEnd`、`onToolCall`、`onToolResult`、`onReplyEnd`）。内置：`TracingMiddleware`、`ContextCompressionMiddleware`、`PermissionMiddleware`、`ReplyBudgetControlMiddleware`（抛出 `BudgetExceededException`）。
8. **Events**（`event/`）—— `EventStream` 是可观测性主轴；`reply()` 发射类型化的、可重放的序列（`ReplyStart → ModelCall → TextBlock → ToolCall → ToolResult → ... → ReplyEnd`）。

### pimono-demo 布局

入口：`com.demo.pimono.PiMonoDemoApplication`。整个框架能装进一屏心智模型 —— `AgentCore` 驱动循环，`LlmClient` 讲 OpenAI 协议，`ContextManager` 持有当前上下文，`McpClientManager` 内置五个 mock 工具（`get_weather`/`calculate`/`search`/`get_time`/`translate`）外加外部 MCP。无中间件、无事件、无权限系统、无沙箱 —— 这正是刻意设计的对照样本。

## 约定

- **日志**：SLF4J + Logback（`org.slf4j.Logger`）。面向用户的输出走 `ui/ConsoleUI`（通过 JLine3 终端的 ANSI 颜色）。保持日志/应用输出分离。
- **中文优先**：系统提示词、日志消息、UI 字符串以简体中文为主。新增内容时与所在文件的语言保持一致。
- **工具协议**：所有工具调用（内置或 MCP）遵循 `{name, description, inputSchema(JSON Schema)}`，并返回以 `tool_call_id` 为键的结果。内置工具与 MCP 发现的工具注册方式相同 —— 没有 separate 的「function calling」路径。
- **不使用 Spring**：纯 `public static void main`，手工装配。通过 `maven-shade-plugin` 打成 shaded uber-jar。
- **编译器怪癖**：父 POM 传入 `--add-exports java.base/sun.nio.ch=ALL-UNNAMED` —— 改动编译配置时请保留。
- **JUnit 5 + Mockito 5** 用于测试；测试位于各模块的 `src/test/java`，与主包结构镜像。

## 上手时优先阅读的关键文件

- `ARCHITECTURE.md` —— 最重要的文档；两模块并排对比 + 「五层通用抽象」。
- `AgentScope2.0剖析_20260626.md` —— `agentscope-demo` 所复刻的上游 Python 框架分析。
- `agentscope-demo/src/main/java/com/demo/agentscope/AgentScopeDemoApplication.java` —— 全栈装配参考。
- `pimono-demo/src/main/java/com/demo/pimono/agent/AgentCore.java` —— 150 行的 Agent 内核；先读它，便于理解 `agentscope-demo` 在哪些维度做扩展。

## 仓库备注

- `startas.sh` / `startpm.sh` 是本地启动脚本，内含个人 API Key —— 已 gitignore（`start*.sh`），不要提交，也不要假设它们在其他机器上存在。
- 仓库根的 `workspace/` 是 Agent 的运行时工作目录（已 gitignore）。其中生成的报告属于产物，不是源码。
- `.tools/`（内置 JDK + Maven）和 `.codegraph/`（CodeGraph 索引）均已 gitignore —— 不要 stage。
- `project-documentation.html` 是生成产物，也已 gitignore。
