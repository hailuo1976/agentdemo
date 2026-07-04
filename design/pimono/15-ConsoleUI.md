# 15 ConsoleUI：JLine3 终端交互

> 源文件：`pimono-demo/src/main/java/com/demo/pimono/ui/ConsoleUI.java`

---

## 1. 职责

提供 ANSI 着色的 REPL：
- 读取用户输入（支持中文、退格、历史）
- 解析命令（exit/history/status/clear/tools/help）
- 把非命令文本送入 `AgentCore.chat(...)`
- 着色打印助手回复

---

## 2. 为什么用 JLine3

历史教训（仓库 commit 记录有体现）：
- 早期使用 `java.util.Scanner` 读输入，**中文退格会乱码**
- 改用 JLine3 后正确处理 UTF-8 + 终端转义

JLine3 提供：
- `LineReader`：行读取，原生支持 Unicode
- `History`：上下箭头浏览历史输入
- `AttributedString` + `AttributedStyle`：ANSI 着色
- 自动终端检测（支持重定向到管道）

---

## 3. 命令表

| 命令 | 行为 |
|---|---|
| `exit` / `quit` | 关闭资源并退出 JVM |
| `history` | 打印 `ContextManager.messages` |
| `status` | 打印 Agent 状态（轮次、sessionState） |
| `clear` | 清空 `ContextManager` |
| `tools` | 列出 `McpClientManager` 工具 |
| `help` | 显示命令列表 |
| 其他文本 | 作为用户输入送入 `AgentCore.chat(text)` |

---

## 4. 输出着色约定

| 角色 | 颜色 | 说明 |
|---|---|---|
| 用户提示符 `>` | Cyan | 输入邀请 |
| 用户输入回显 | 默认 | — |
| 助手回复 | Green | 最终答案 |
| 工具调用日志 | Yellow | 中间过程（可选显示） |
| 错误 | Red | 异常消息 |
| 系统消息 | Gray | 启动信息、帮助 |

---

## 5. 与 agentscope UI 的差异

| 维度 | pimono `ConsoleUI` | agentscope UI |
|---|---|---|
| REPL 命令 | 6 个 | 12+ 个（含 team/events/permission/verbosity） |
| 进度跟踪 | 无 | `AgentProgressTracker` + `TeamProgressTracker` |
| Verbosity 档位 | 无 | 3 档（quiet/normal/verbose） |
| 团队可视化 | 无 | 创建工作者 / 分配任务 / 完成情况实时展示 |
| ANSI 着色 | 基础 | 基础 + 团队层级缩进 |

---

## 6. 启动信息

启动时打印：
```
=== PiMono Demo ===
模型: ${MODEL_NAME}
Provider: ${MODEL_PROVIDER}
工具数: N（builtin + MCP）
输入 help 查看命令。
>
```

---

## 7. 退出流程

`exit` 命令触发：
1. 关闭 `McpClientManager`（断开所有 MCP 连接）
2. 关闭 `LineReader` / `Terminal`
3. `System.exit(0)`

---

## 8. 测试

UI 层不易单元测试，主要由 REPL 集成测试覆盖：
- 启动 → 输入文本 → 收到回复
- 各命令分支正确路由

---

## 9. 与上层解耦

`ConsoleUI` 只持有 `AgentCore` 与 `ContextManager` / `McpClientManager` 引用，不感知 LLM 细节或工具实现。换 LLM 后端只需替换 `AgentCore` 内部的 `LlmClient`。
