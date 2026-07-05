# 22 MCP / 工具模块（mcp）

> 包：`com.demo.agentscope.mcp`
> 主类：`MCPClient` + `McpServerConnection` + `MCPConfig`

---

## 1. 职责

聚合**所有**可调用工具的统一入口：
- 内置工具（builtin）：Java 本地实现
- 外部工具：通过 stdio 连接 MCP 服务器（JSON-RPC 2.0）

对外只暴露两个核心 API：
- `listTools()` → `List<ToolInfo>`
- `executeTool(name, args)` → `ToolResult`

---

## 2. 内置工具分类

### 2.1 文件工具（4 个）
通过 `registerFileTools(SecureFileWorkspace)` 注册：

| 工具 | 行为 |
|---|---|
| `read_file` | 读 path（必填） |
| `write_file` | 写 path（必填） |
| `edit_file` | 替换 old_text → new_text（path 必填） |
| `list_files` | 列目录（不传则 `.`） |

**全部受 `FilePermissionManager` 管控**——`SecureFileWorkspace` 内部检查，未授权抛 `FilePermissionDeniedException`，工具捕获后转 `ToolResult` 错误输出。

### 2.2 代码执行工具（3 个）
通过 `registerCodeExecutionTools(CodeExecutionManager)` 注册：

| 工具 | 行为 |
|---|---|
| `execute_python` | 写临时 .py 文件执行 |
| `execute_command` | Shell 命令 |
| `install_package` | pip install |

受 `CommandSafetyChecker` 拦截危险命令 + 30s 超时。

### 2.3 团队工具（5 个，仅 Leader）
通过 `AgentTeam.registerTeamTools()` 注入：

| 工具 | 行为 |
|---|---|
| `agent_create` | 创建工作者 |
| `agent_message` | 单工作者同步消息 |
| `agent_message_parallel` | 多工作者并行消息 |
| `agent_list` | 列出工作者 |
| `team_dissolve` | 解散团队 |

通过 `registerCustomTool(name, description, parametersJson, executor)` 公开接口注入。

---

## 3. 外部 MCP 集成

### 3.1 配置解析
`MCP_SERVERS` env var 格式：`command:arg1,arg2`，可多服务器。
解析为 `MCPConfig.StdioMCPConfig` 列表。

### 3.2 连接生命周期
```
MCPClient.initialize()
   ├─ registerBuiltinTools()
   ├─ connectToConfiguredServers()
   │    └─ for config:
   │         ├─ new McpServerConnection(name, command, args)
   │         ├─ connection.connect()                // JSON-RPC initialize
   │         ├─ tools = connection.discoverTools()   // tools/list
   │         └─ allToolInfos.addAll(tools)
   └─ initialized = true
```

### 3.3 MCP 协议
- 版本：`2024-11-05`
- 传输：stdio（stdin/stdout）
- 编码：JSON-RPC 2.0
- 关键方法：`initialize` / `tools/list` / `tools/call`

---

## 4. 关键数据结构

### 4.1 `ToolInfo`（record）
```
record ToolInfo(
    String name,
    String description,
    String server,            // "builtin" 或 MCP 服务器名
    String parametersJson     // JSON Schema 字符串
)
```

### 4.2 `ToolResult`
```
class ToolResult {
    boolean success;
    String  output;
    String  error;

    ContentBlock.ToolResultBlock toToolResultBlock(toolCallId)
}
```

### 4.3 `BuiltinToolExecutor`（函数式接口）
```java
@FunctionalInterface
interface BuiltinToolExecutor {
    String execute(Map<String, Object> args) throws Exception;
}
```

---

## 5. 关键方法

### 5.1 `registerCustomTool(name, description, parametersJson, executor)`
公开注册接口，团队工具通过此注入。

### 5.2 `registerFileTools(SecureFileWorkspace)` / `registerCodeExecutionTools(CodeExecutionManager)`
成组注册文件 / 代码工具。

### 5.3 `executeTool(name, args)`
执行顺序：
1. 查 `builtinTools` map → 找到即执行
2. 否则查 `allToolInfos` 找到所属 server → 通过 `McpServerConnection.callTool`
3. 都找不到 → `ToolResult(false, "未找到工具")`

### 5.4 `copyToolsTo(target, excludeTools)` —— **工作者隔离的关键**
- 用于 `AgentTeam.createWorker()`
- 把 Leader 的工具复制到 worker 的独立 MCPClient
- 排除 5 个团队工具，避免越权

---

## 6. SimpleExpressionEvaluator（内嵌静态类）

递归下降算术求值器，与 pimono 的实现几乎一致：
- 支持 `+ - * / ()`
- 安全过滤：正则 `[^0-9+\-*/.()\s]` 拦截非法字符

---

## 7. 与 pimono `McpClientManager` 的差异

| 维度 | pimono | agentscope |
|---|---|---|
| builtin 工具 | 5 个 mock | 12 个生产工具（4+3+5） |
| 注册接口 | 私有硬编码 | 公开 `registerCustomTool` |
| 文件工具 | 无 | 4 个 + 权限管控 |
| 代码执行 | 无 | 3 个 + 安全检查 + 超时 |
| 团队工具 | 无 | 5 个 |
| 工具集隔离 | 无 | `copyToolsTo` 支持工作者隔离 |
| ToolInfo | 内嵌 | record（含 parametersJson） |

---

## 8. 异常处理

| 场景 | 行为 |
|---|---|
| MCP 服务器连接失败 | log.error 但不中断主流程（其他工具仍可用） |
| 工具执行抛异常 | 捕获，返回 `ToolResult(false, error)` |
| 未找到工具 | 返回 `ToolResult(false, "未找到工具")` |
| 文件权限拒绝 | 捕获 `FilePermissionDeniedException`，包装为 RuntimeException 再被上层 ToolResult 捕获 |

---

## 9. 测试

- `MCPClientTest`：注册、执行、复制工具
- `McpServerConnectionTest`：stdio 握手与调用（mock 进程）
- 集成测试：外部 MCP 服务器（通过 testcontainers 或本地脚本）
