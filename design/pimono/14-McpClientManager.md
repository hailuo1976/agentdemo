# 14 McpClientManager：工具调度（builtin + MCP）

> 源文件：`pimono-demo/src/main/java/com/demo/pimono/mcp/McpClientManager.java`（~270 行）

---

## 1. 职责

统一调度两类工具：
1. **builtin mock 工具**（5 个）：本地 Java 实现
2. **外部 MCP 工具**：通过 stdio 连接 MCP 服务器，JSON-RPC 2.0

并对外暴露统一的 `executeTool(name, args) → String` 接口。

---

## 2. 5 个内置 mock 工具

| 工具 | 功能 | 实现 |
|---|---|---|
| `get_weather` | 返回 mock 天气 | 随机数据 |
| `calculate` | 算术求值 | 内嵌 `SimpleExpressionEvaluator`（递归下降） |
| `search` | mock 搜索 | 静态结果 |
| `get_time` | 当前时间 | `LocalDateTime.now()` |
| `translate` | mock 翻译 | 简单字符替换 |

每个工具注册时给出 JSON Schema，与外部 MCP 工具同构。

---

## 3. 外部 MCP 集成

### 3.1 配置格式
通过环境变量 `MCP_SERVERS`（格式：`command:arg1,arg2`）声明外部服务器。

### 3.2 连接流程
```
McpClientManager.initialize()
   ├─ registerBuiltinTools()
   └─ for config in parseMcpServers():
        ├─ new McpServerConnection(command, args)
        ├─ connection.connect()                  // JSON-RPC initialize 握手
        ├─ tools = connection.discoverTools()     // tools/list
        └─ 注册到本地工具表（含 parameters schema）
```

### 3.3 调用流程
```
executeTool(name, args):
   ├─ if name in builtinTools:
   │     return builtinTools[name].execute(args)
   ├─ else:
   │     serverName = findToolServer(name)
   │     return connections[serverName].callTool(name, args)
   └─ 未找到 → "工具未找到" 错误
```

---

## 4. SimpleExpressionEvaluator（内嵌）

递归下降解析器，支持：
- `+ - * /`
- 括号 `()`
- 正负号
- 浮点数

语法：
```
expression → term (('+' | '-') term)*
term       → factor (('*' | '/') factor)*
factor     → '(' expression ')' | number
```

安全：先用正则 `[^0-9+\-*/.()\s]` 过滤非法字符，再解析。

---

## 5. 工具接口约定

所有工具（builtin + MCP）必须遵循：
```
{
  name:        string,
  description: string,
  inputSchema: JSON Schema   // parameters 字段
}
```

返回：纯字符串（或字符串化的 JSON）。

**禁止**：
- 走独立的 "function calling" 旁路
- 不带 `tool_call_id` 的工具结果

---

## 6. 与 agentscope `MCPClient` 的差异

| 维度 | pimono `McpClientManager` | agentscope `MCPClient` |
|---|---|---|
| builtin 工具 | 5 个 mock | 4 文件 + 3 代码执行 + 5 团队 |
| 注册接口 | 内部硬编码 | 公开 `registerCustomTool` |
| 文件工具 | 无 | `registerFileTools(SecureFileWorkspace)` |
| 代码执行 | 无 | `registerCodeExecutionTools` |
| 工具复制 | 无 | `copyToolsTo(target, excludeTools)` 支持工作者隔离 |
| ToolInfo 字段 | name/desc/params | name/desc/server/paramsJson |
| 权限检查 | 无 | 上层 PermissionEngine + PermissionMiddleware |
| MCP 协议版本 | 2024-11-05 | 同 |

---

## 7. 关键类

### 7.1 `McpServerConnection`
- 持有 stdio `Process`
- JSON-RPC 2.0 over stdin/stdout
- 方法：`connect()` / `discoverTools()` / `callTool(name, args)` / `disconnect()`

### 7.2 `MCPConfig`
- 声明式配置：`command` + `args[]`
- 子类 `StdioMCPConfig` / `HttpMCPConfig`

---

## 8. 测试

`McpClientManagerTest` 验证：
- 5 个 builtin 工具可调用
- `SimpleExpressionEvaluator` 正确求值
- `executeTool` 未找到工具返回错误
- 工具列表 schema 完整

外部 MCP 服务器集成测试通过环境变量触发（可选）。

---

## 9. 演进路径（agentscope 已走完）

pimono 的工具管理走向生产化的方向（agentscope 已实现）：
1. **公开注册接口**：让外部组件注入工具（团队工具）
2. **工具集隔离**：为不同 Agent 复制工具子集（工作者隔离）
3. **权限闸**：在 executeTool 前检查
4. **审计**：每次工具调用发事件
5. **结果截断**：防止长输出撑爆上下文
