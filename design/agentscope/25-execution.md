# 25 代码执行模块（execution）

> 包：`com.demo.agentscope.execution`
> 主类：`CodeExecutionManager` + `CommandSafetyChecker`

---

## 1. 职责

让 Agent 能执行 Python / Shell / pip 安装，是 Agent 的"行动力"核心。
所有执行都经过：
- 安全检查（拦截危险命令）
- 超时限制（默认 30s）
- 工作目录绑定（指定 workspace）
- 网络访问允许（不同于纯沙箱）

---

## 2. 三类工具

| 工具 | 执行方式 |
|---|---|
| `execute_python` | 把代码写到临时 `.py` 文件，调用 `python <tmpfile>` |
| `execute_command` | 直接 `sh -c "<command>"`（Windows: `cmd /c`） |
| `install_package` | `pip install <package>` |

---

## 3. CommandSafetyChecker

危险命令拦截（正则 + 关键字匹配）：

| 模式 | 示例 |
|---|---|
| 递归删除 | `rm -rf /`、`rm -rf ~` |
| 文件系统破坏 | `mkfs`、`dd if=/dev/zero of=...` |
| 权限滥用 | `chmod 777`、`chown root` |
| 危险路径写入 | `/etc/`、`/root/`、`C:\Windows\` |
| Fork 炸弹 | `:(){ :|:& };:` |
| 网络后门 | `nc -l` 监听 |
| 关机重启 | `shutdown`、`reboot` |

命中即拒绝执行，返回 `ToolResult(false)`。

---

## 4. ExecutionResult

```
class ExecutionResult {
    String stdout;
    String stderr;
    int    exitCode;
    String toString();   // 格式化输出
}
```

`exitCode != 0` 不算失败（仍返回 success=true，把 stderr 交给 LLM 判断）。

---

## 5. 超时与中断

- 默认超时：30s
- 实现：`Process.waitFor(timeout)` + 超时后 `destroyForcibly`
- 超时 → `ToolResult(false, "执行超时(30s)")`

---

## 6. Python 执行细节

为什么不直接 `python -c "<code>"`？
- 命令行长度限制
- 多行字符串引号转义复杂
- 错误信息行号不准

实现：
```
1. Files.createTempFile("agent_exec_", ".py")
2. 写入 code
3. python <tmpfile>
4. 捕获 stdout/stderr/exitCode
5. 删除临时文件
```

---

## 7. 工作目录

通过构造器传入 `workDirectory`：
- 通常是 `workspace/<agentId>/`
- `ProcessBuilder.directory(workDirectory)`
- 所有相对路径都基于此

---

## 8. 与 pimono 的差异

pimono **无**代码执行能力，工具仅限 mock。

---

## 9. 安全模型

| 层 | 控制 |
|---|---|
| 工具级 | `PermissionEngine` 决定是否允许 `execute_*` 工具调用 |
| 命令级 | `CommandSafetyChecker` 拦截危险模式 |
| 进程级 | 30s 超时 + 强制销毁 |
| 文件级 | 工作目录绑定，理论上不影响外部（但 Local 后端无强制） |

> 注意：Local 后端上 `execute_command` 仍可访问任意可写路径；生产场景应切 `DockerWorkspace` / `E2BWorkspace`。

---

## 10. 测试

- `CodeExecutionManagerTest`：Python hello world、命令成功失败、超时
- `CommandSafetyCheckerTest`：各危险模式拦截、合法命令放行
