# 26 工具权限模块（permission）

> 包：`com.demo.agentscope.permission`
> 主类：`PermissionEngine` + `PermissionRule` + `PermissionDecision` + `PermissionMode`

---

## 1. 职责

**工具级**权限系统，对每次工具调用做三引擎三态决策：
- 三引擎：`Rules → Mode → Built-in`（任一非 ALLOW 即终止）
- 三态：`ALLOW` / `DENY` / `ASK`
- 三模式：`EXPLORE` / `DONT_ASK` / `BYPASS`

---

## 2. 三引擎流水线

```
check(name, args):
   1. checkRules(userRules)
      └─ for each rule:
           if namePattern 匹配 && argsMatcher 匹配:
               decision = rule.decision
               └─ bypassImmune 规则在 BYPASS 模式仍生效
   │
   2. checkMode(currentMode)
      └─ EXPLORE:  只读工具 ALLOW，写工具 DENY
      └─ DONT_ASK: ASK → DENY（应用默认）
      └─ BYPASS:   全 ALLOW（除 bypassImmune 规则）
   │
   3. checkBuiltIn(name, args)
      └─ WRITE_TOOL_PREFIXES（write/delete/remove/...）
      └─ DANGEROUS_COMMAND_PATTERNS（rm -rf / mkfs / ...）
      └─ DANGEROUS_PATH_PATTERNS（/etc/ /root/ C:\Windows\ ...）

任一返回非 ALLOW → 终止，返回该决策
```

---

## 3. 三态决策（PermissionDecision）

| 决策 | 含义 |
|---|---|
| `ALLOW` | 允许执行 |
| `DENY` | 拒绝，注入"权限拒绝"工具结果 |
| `ASK` | 转交上层询问用户（当前默认模式 DONT_ASK 下被降级为 DENY） |

---

## 4. 三模式（PermissionMode）

| 模式 | 行为 |
|---|---|
| `EXPLORE` | 只读，写工具一律 DENY |
| `DONT_ASK` | ASK 决策降级为 DENY（**应用默认**） |
| `BYPASS` | 全放行（bypassImmune 规则例外） |

切换方式：REPL `permission mode <mode>` 命令。

---

## 5. 默认 16 条规则

`AgentScopeDemoApplication.createPermissionEngine` 装配：

| 工具名模式 | 决策 | 备注 |
|---|---|---|
| `read_file` | ALLOW | 只读 |
| `list_files` | ALLOW | 只读 |
| `write_file` | ASK | 默认询问 |
| `edit_file` | ASK | 默认询问 |
| `execute_python` | ASK | 默认询问 |
| `execute_command` | ASK | 默认询问 |
| `install_package` | DENY | 默认拒绝 |
| `agent_create` | ALLOW | 团队操作允许 |
| `agent_message` | ALLOW | 团队操作允许 |
| `agent_message_parallel` | ALLOW | 团队操作允许 |
| `agent_list` | ALLOW | 只读 |
| `team_dissolve` | ALLOW | 团队管理 |
| 外部 MCP（`mcp_*`） | ASK | 谨慎 |
| ... | ... | 共 16 条 |

---

## 6. 关键常量

### 6.1 WRITE_TOOL_PREFIXES
```
{write, delete, remove, create, update, execute, run, bash, shell}
```
工具名以这些前缀开头的视为"写操作"，在 EXPLORE 模式下被拒。

### 6.2 DANGEROUS_COMMAND_PATTERNS
```
rm -rf, mkfs, dd if=, chmod 777, :(){ :|:& };:, nc -l, shutdown, reboot, ...
```

### 6.3 DANGEROUS_PATH_PATTERNS
```
/etc/, /root/, /var/log/, C:\Windows\, C:\Program Files\, ~/.ssh/, ...
```

---

## 7. bypassImmune 规则

部分规则即使在 BYPASS 模式下也强制生效：
- `install_package` 强制 DENY
- `execute_command` 涉及 `DANGEROUS_COMMAND_PATTERNS` 的强制 DENY
- `read_file` 涉及 `.env` / `secrets` 的强制 DENY

设计意图：即使用户主动 BYPASS，仍保留对致命操作的拦截。

---

## 8. PermissionMiddleware 桥接

`PermissionMiddleware` 把 `PermissionEngine` 接入中间件链：
- `onToolCall` 钩子检查决策
- ASK 决策在中间件层不走交互（当前实现：DONT_ASK 模式下 ASK → DENY）

**注意**：`Agent.reply()` 内部还有一道独立的 `permissionEngine.check()` 硬阻断。中间件是"软"检查（日志/事件），引擎是"硬"检查（DENY 直接注入错误结果）。

---

## 9. 与 FilePermissionManager 的关系

详见 `24-filepermission.md` § 6。两套独立系统协作：
- `PermissionEngine` 决定 `read_file` 工具是否被允许
- `FilePermissionManager` 决定具体 path 是否允许

---

## 10. 测试

`PermissionTest`（127 测试中的核心部分）：
- 三引擎各步骤独立测试
- 三态决策转换
- 三模式切换
- bypassImmune 在 BYPASS 模式仍生效
- EXPLORE 模式下写工具被拒
- DANGEROUS_*_PATTERNS 拦截

---

## 11. 设计取舍

| 选择 | 理由 |
|---|---|
| 三态而非二态 | 区分"明确允许"、"明确拒绝"、"需询问" |
| 三引擎串行 | 用户规则优先级最高，模式是全局策略，Built-in 是兜底 |
| bypassImmune | 防止用户 BYPASS 时误删生产数据 |
| ASK 当前不交互 | 简化实现，未来可接入审批流 |
