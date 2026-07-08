# AgentScope Demo 操作使用手册

> 面向深度玩家的完整使用指南。覆盖启动装配、REPL 命令、运行时调参、权限模型、团队协作、归档共享、上下文管理、LLM 多提供商、深度玩法与故障排查。
>
> 适用版本：agentscope-demo 1.0.0（Java 17，~2700 行，32+ 主类，142 测试）。
> 设计文档与架构对照见 `design/agentscope/` 目录；本文聚焦**怎么用**。

---

## 目录

1. [快速开始](#1-快速开始)
2. [环境变量完整参考](#2-环境变量完整参考)
3. [REPL 命令手册](#3-repl-命令手册)
4. [运行时调参：/config](#4-运行时调参config)
5. [股票分析工具：/stock](#5-股票分析工具stock)
6. [权限系统：双闸 + 三模式 + 三态](#6-权限系统双闸--三模式--三态)
7. [团队协作：Leader-Worker 模型](#7-团队协作leader-worker-模型)
8. [归档共享：4 个 artifact 工具](#8-归档共享4-个-artifact-工具)
9. [输出详细度：四档 verbosity](#9-输出详细度四档-verbosity)
10. [上下文管理：压缩 + 时序归档](#10-上下文管理压缩--时序归档)
11. [LLM 多提供商与 failover](#11-llm-多提供商与-failover)
12. [深度玩家玩法](#12-深度玩家玩法)
13. [故障排查 FAQ](#13-故障排查-faq)
14. [内部机制速查表](#14-内部机制速查表)

---

## 1. 快速开始

### 1.1 前置条件

- JDK 17（仓库自带在 `.tools/jdk-17.0.12.jdk/Contents/Home`，已 gitignore）
- Maven 3.9.x（仓库自带在 `.tools/apache-maven-3.9.6`）
- 至少一个 LLM API Key（见 [§2](#2-环境变量完整参考)）

### 1.2 构建

```bash
# 在仓库根目录
/Users/macbookpro/cc/agentdemo/.tools/apache-maven-3.9.6/bin/mvn -pl agentscope-demo package -DskipTests
# 产物：agentscope-demo/target/agentscope-demo-1.0.0.jar（shaded uber-jar）
```

> ⚠️ **踩坑警告**：`java -jar` 加载的是 jar 内的 class，**改完源码必须重新 `mvn package`**，否则跑的是旧 jar。开发期可用 `mvn exec:java` 直跑 main class 跳过打包。

### 1.3 启动

最小启动（dashscope/qwen）：

```bash
export DASHSCOPE_API_KEY=sk-xxx
export MODEL_NAME=qwen-plus
java -jar agentscope-demo/target/agentscope-demo-1.0.0.jar
```

本地启动脚本（含个人 Key，已 gitignore）：

```bash
source startas.sh   # 设置 JAVA_HOME / PATH / API Key / MODEL_BASE_URL
```

成功启动会看到 AgentScope 2.0 横幅和 `You ▶` 提示符。

### 1.4 第一次对话

```text
You ▶ 你好，自我介绍一下
You ▶ 读 README.md 给我看前 20 行
You ▶ help
```

### 1.5 REPL 心智模型

```
用户输入
   │
   ├─ 命令分支（exit/history/status/clear/team/tools/events/permission/verbosity/help//config//stock）
   │     └─ 直接执行，不进 LLM
   │
   └─ 其他文本
         ├─ 团队活跃 → team.reply(text) （非流式）
         └─ 否则     → agent.replyStream(text) （token 级实时回显）
```

**关键事实**：
- 团队模式（`team create` 后）**关闭流式**，走 `team.reply()` 同步等待
- 单 Agent 模式默认流式，token 级实时回显到终端
- `events` 开关只在单 Agent 流式路径下生效

---

## 2. 环境变量完整参考

### 2.1 凭证（至少一个）

| 变量 | 提供商 | 默认模型 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 阿里通义千问 | `qwen-plus` |
| `OPENAI_API_KEY` | OpenAI | `gpt-4o-mini` |
| `ANTHROPIC_API_KEY` | Anthropic Claude | `claude-sonnet-4-20250514` |
| `DEEPSEEK_API_KEY` | DeepSeek | `deepseek-chat` |

**优先级**：`MODEL_PROVIDER` 显式指定 > 自动检测（agentscope 内部顺序：dashscope > openai）。
**Failover**：主提供商失败（401/网络）自动切到备用提供商。

### 2.2 模型与端点

| 变量 | 作用 | 示例 |
|---|---|---|
| `MODEL_PROVIDER` | 强制指定主提供商 | `dashscope`/`openai`/`anthropic`/`deepseek` |
| `MODEL_NAME` | 全局模型名覆盖 | `gpt-4o-mini`、`glm-5.1` |
| `MODEL_BASE_URL` | 全局 OpenAI 兼容端点 | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| `{PROVIDER}_BASE_URL` | 单提供商端点 | `OPENAI_BASE_URL=...` |
| `{PROVIDER}_MODEL` | 单提供商模型 | `DASHSCOPE_MODEL=qwen-max` |

私有兼容端点示例（本仓库实测配置）：

```bash
export MODEL_BASE_URL=https://api.fanticat.com:3443/v1
export MODEL_NAME=glm-5.1
```

### 2.3 运行时限制（21 个，全部可在 `/config` 运行时覆盖）

| 变量 | 默认 | 含义 |
|---|---|---|
| `MAX_ITERATIONS` | 50 | ReAct 循环最大轮数（硬上限） |
| `REPLY_BUDGET` | 500000 | 单次回复 token 预算（软上限，超限仅告警） |
| `MAX_OUTPUT_TOKENS` | 8192 | 单次 LLM 调用的 `max_tokens` |
| `ITERATION_WARN_REMAINING` | 5 | 剩余多少轮开始 WARN 告警 |
| `TOKEN_BUDGET_WARN_PERCENT` | 80 | token 预算告警阈值（%） |
| `MAX_CONTEXT_TOKENS` | 40000 | 上下文 token 软窗口 |
| `MAX_RECENT_MESSAGES` | 10 | 压缩保留的最近消息条数 |
| `SHORT_TERM_MEMORY_LIMIT` | 20 | 短期记忆上限 |
| `LONG_TERM_MEMORY_LIMIT` | 50 | 长期记忆上限 |
| `MICRO_COMPACTOR_KEEP_RECENT` | 4 | 微压缩保留最近工具调用数 |
| `MICRO_COMPACTOR_TRIGGER_TOOL_COUNT` | 12 | 工具调用次数超过此值触发微压缩 |
| `TOOL_RESULT_SUMMARY_THRESHOLD` | 3000 | 工具结果超此长度触发摘要（字符） |
| `TOOL_RESULT_SUMMARY_MAX_LENGTH` | 500 | 摘要后最大字符数 |
| `COMMAND_TIMEOUT_SECONDS` | 30 | execute_command 单次执行超时 |
| `WORKSPACE_TIMEOUT_SECONDS` | 60 | workspace 单次操作超时 |
| `MAX_FILE_SIZE_BYTES` | 0（=沿用内置 10MB） | 文件读写大小上限 |
| `LLM_READ_TIMEOUT_SECONDS` | 300 | LLM 流式读取超时（OkHttp readTimeout） |
| `LLM_CONNECT_TIMEOUT_SECONDS` | 10 | LLM 连接建立超时 |
| `LLM_WRITE_TIMEOUT_SECONDS` | 10 | LLM 请求发送超时 |
| `LLM_MAX_RETRIES` | 2 | SocketTimeoutException 自动重试次数 |

### 2.4 功能开关与路径

| 变量 | 默认 | 作用 |
|---|---|---|
| `MCP_SERVERS` | 空 | 外部 MCP stdio 服务器，格式 `command:arg1,arg2` |
| `WORKSPACE_DIR` | `workspace/` | Agent 运行时工作目录 |
| `STOCK_TOOLS_ENABLED` | 未设=关 | 启动时即开启股票工具（等价 `/stock on`） |

### 2.5 调参三档覆盖关系

```
代码默认（AgentLimits.java 硬编码）
   │
   ├─ 环境变量（启动时读取，覆盖默认）
   │
   └─ /config set key=value（运行期，覆盖一切）
```

`/config set` 即时同步到：Agent.maxIterations、ContextManager、ReplyBudgetControlMiddleware、CodeExecutionManager、ChatModel（超时）、SecureFileWorkspace（文件大小）。

---

## 3. REPL 命令手册

### 3.1 命令一览

| 命令 | 别名/参数 | 行为 |
|---|---|---|
| `exit` | `quit` | 退出应用 |
| `history` | — | 打印 Leader 当前上下文（系统提示词 + 消息序列） |
| `status` | — | 显示 Agent 状态：当前 verbosity、团队状态、工具数 |
| `clear` | — | 清空 Leader 上下文（worker 上下文不动） |
| `team create` | — | 显式创建团队（含 Leader） |
| `team status` | — | 团队状态：worker 列表、活跃标志、进度跟踪 |
| `team dissolve` | — | 解散团队，关闭所有 worker |
| `tools` | — | 列出 Leader 当前可见的所有 MCP 工具 |
| `events` | — | 切换事件流展示（开/关），仅单 Agent 流式路径生效 |
| `permission` | — | 显示当前权限模式 + 已加载规则 |
| `verbosity` | `quiet`/`normal`/`verbose`/`debug` | 切换输出详细度（详见 [§9](#9-输出详细度四档-verbosity)） |
| `/config` | — | 查看所有运行时限制（21 项） |
| `/config set` | `key=value` | 运行期修改限制，即时生效 |
| `/stock` | `on`/`off` | 开启/关闭股票分析工具（4 个工具） |
| `help` | — | 打印命令列表 |
| 其他文本 | — | 送入 `agent.replyStream(text)` 或 `team.reply(text)` |

### 3.2 命令分支细节

- **大小写不敏感**：`Exit`、`EXIT` 都识别。`/config`、`/stock` 用 `.toLowerCase().startsWith(...` 判定。
- **`/config set` 语法**：必须是 `/config set key=value` 三段式（按空白切 3 段），value 不需要引号。多参数用 `/config set maxIterations=30 maxOutputTokens=4096`？**不行** —— 当前实现一次只接受一对 `key=value`。
- **`team create` 之后**：单 Agent 流式路径关闭，所有对话走 `team.reply()`。若想回到流式单 Agent，先 `team dissolve`。
- **`events` 模式**：开启时单 Agent 路径会额外打印 EventStream 重放视图（按事件类型分行），并显示响应耗时。

### 3.3 管道喂入（非交互）

```bash
echo "用 Python 写一个斐波那契" | java -jar agentscope-demo-1.0.0.jar
```

适合自动化测试或 CI。但无法用 `/config`、`/stock` 这类交互命令。

---

## 4. 运行时调参：/config

### 4.1 查看当前值

```text
You ▶ /config
```

输出 21 项当前值 + 用法提示。

### 4.2 修改单项

```text
You ▶ /config set maxIterations=30
You ▶ /config set replyBudgetTokens=200000
You ▶ /config set toolResultSummaryThreshold=2000
You ▶ /config set llmReadTimeoutSeconds=120
You ▶ /config set commandTimeoutSeconds=10
```

成功显示 `已更新: key=value`，失败显示错误（如拼写错、值非数字）。

### 4.3 应用范围（`applyLimitsToRuntime`）

| 修改的字段 | 同步到的对象 |
|---|---|
| `maxIterations` | `Agent.maxIterations` |
| `replyBudgetTokens`、`tokenBudgetWarnPercent` | `ReplyBudgetControlMiddleware` |
| `maxRecentMessages`、`microCompactor*`、`toolResultSummary*`、`maxContextTokens` | `ContextManager` |
| `commandTimeoutSeconds`、`workspaceTimeoutSeconds` | `CodeExecutionManager` |
| `llmReadTimeoutSeconds`、`llmConnectTimeoutSeconds`、`llmWriteTimeoutSeconds`、`llmMaxRetries` | `ChatModel`（下次请求生效） |
| `maxFileSizeBytes` | `SecureFileWorkspace` |

> **注意**：`maxOutputTokens` 修改后**下一次** LLM 调用生效（每次调用都把当前值作为 `max_tokens` 传入）。

### 4.4 典型场景调参建议

| 场景 | 推荐 |
|---|---|
| 长任务（深度 ReAct） | `maxIterations=80`，`replyBudgetTokens=1000000` |
| 省钱（轻量任务） | `maxIterations=20`，`maxOutputTokens=2048` |
| 上下文易爆（长文件读取） | `toolResultSummaryThreshold=1500`，`maxContextTokens=30000` |
| LLM 频繁超时 | `llmReadTimeoutSeconds=600`，`llmMaxRetries=3` |
| 长跑命令 | `commandTimeoutSeconds=120` |
| 调试压缩行为 | `MICRO_COMPACTOR_TRIGGER_TOOL_COUNT=4`，开 debug verbosity |

---

## 5. 股票分析工具：/stock

可选的领域工具集（4 个），通过 `/stock` 命令运行期挂载/卸载。

### 5.1 开启

```text
You ▶ /stock on
✅ 股票分析工具已开启（4 个工具）
```

挂载动作：
1. 初始化数据源
2. 注册 4 个工具到 leader 的 MCPClient
3. 添加 4 条默认权限规则到 PermissionEngine
4. 追加股票领域知识到系统提示词

### 5.2 关闭

```text
You ▶ /stock off
✅ 股票分析工具已关闭
ℹ️ 提示: 系统提示词中的股票说明需重启后清除
```

> **限制**：关闭时只移除工具与权限规则，**系统提示词中追加的股票说明文字需要重启才能完全清除**。

### 5.3 工具清单

| 工具 | 作用 |
|---|---|
| `list_industries` | 列出可选行业分类 |
| `select_industry_leaders` | 选出指定行业的龙头股 |
| `get_stock_detail` | 获取单只股票的详细数据 |
| `update_stock_data` | 刷新本地缓存数据 |

### 5.4 与权限系统交互

`/stock on` 自动给 4 个工具加 `ALLOW` 规则；`/stock off` 自动 `removeRule`。若你手动加过相关规则，关闭后这些规则也会被移除。

---

## 6. 权限系统：双闸 + 三模式 + 三态

仓库有**两套独立**权限系统，通过 `SecureFileWorkspace` 桥接。

### 6.1 双闸总览

```
工具调用（execute_python / read_file / agent_create / ...）
   │
   ▼
┌──────────────────────────────────────────┐
│ 闸 1：工具级 PermissionEngine             │
│   Rules → Mode → Built-in（任一非 ALLOW 终止）│
└──────────────────────────────────────────┘
   │
   ▼ （通过后执行工具）
┌──────────────────────────────────────────┐
│ 闸 2：文件级 FilePermissionManager        │
│   仅作用于文件类工具（read/write/edit/list） │
│   7 步检查：路径安全 → 黑名单 → 白名单 → 大小 → 默认│
└──────────────────────────────────────────┘
```

### 6.2 工具级 PermissionEngine

**三模式**：

| 模式 | 行为 | 适用 |
|---|---|---|
| `EXPLORE` | 只允许只读工具；写/执行类一律 DENY | 探索/审计 |
| `DONT_ASK`（默认） | ASK 自动转 DENY（不弹用户确认） | 自动化/CI |
| `BYPASS` | 全部 ALLOW，但仍尊重 `bypassImmune` 规则 | 信任环境深度调试 |

**三态**：`ALLOW`（通过）/ `DENY`（拒绝，注入错误结果）/ `ASK`（建议确认，DONT_ASK 模式下变 DENY）

**判定链**（任一非 ALLOW 立即终止）：

```
1. checkRules    用户规则（PermissionRule 列表）
                  - bypassImmune=true 的规则在 BYPASS 模式仍生效
2. checkMode     模式规则（EXPLORE 只读白名单）
3. checkBuiltIn  内置规则：
                  - WRITE_TOOL_PREFIXES（write/delete/remove/...）
                  - DANGEROUS_COMMAND_PATTERNS（rm -rf, mkfs, dd, fork bomb, chmod 777...）
                  - DANGEROUS_PATH_PATTERNS（/etc/, /root/, /var/log/, C:\Windows\...）
```

**REPL 查看**：

```text
You ▶ permission
```

输出当前模式 + 所有已加载规则（含来源：用户/env/内置）。

### 6.3 文件级 FilePermissionManager

7 步检查（任一失败 → `FilePermissionDeniedException` → 工具捕获转 `ToolResult` 错误）：

| 步骤 | 内容 |
|---|---|
| 1. 路径安全 | 防 `../` 穿越 |
| 2. 黑名单路径 | `.env`、`secrets`、`.git/`、`*.key` |
| 3. 黑名单扩展 | `.exe`、`.sh`、`.bat` |
| 4. 白名单扩展 | 通过配置允许的扩展 |
| 5. 白名单路径 | 必须在 workspace 子树内 |
| 6. 文件大小 | ≤ 10MB（`list_files` 跳过） |
| 7. 默认策略 | `DENY_ALL` 兜底 |

### 6.4 运行期切换权限模式

当前 REPL **未提供直接切模式的命令**。切换途径：
- 环境变量 `PERMISSION_MODE`（启动时）
- 修改 `AgentScopeDemoApplication` 装配代码，调用 `permissionEngine.setMode(...)`
- 写一个 PermissionRule（`PERMISSION_RULE_<n>` 环境变量），按规则粒度放行/拒绝

### 6.5 规则配置（环境变量）

`PERMISSION_RULE_<n>` 格式：`<toolNamePattern>=<ALLOW|DENY|ASK>[;bypassImmune=true][;reason=...]`

示例：

```bash
# 放行所有 read_file
export PERMISSION_RULE_1="read_file=ALLOW"
# 拒绝 execute_command（即使 BYPASS 模式也拒绝）
export PERMISSION_RULE_2="execute_command=DENY;bypassImmune=true;reason=生产环境禁命令"
```

---

## 7. 团队协作：Leader-Worker 模型

### 7.1 模型概览

```
Leader（Agent + 5 团队工具 + 4 归档工具）
   ├─ agent_create
   ├─ agent_message
   ├─ agent_message_parallel
   ├─ agent_list
   ├─ team_dissolve
   ├─ share_file          ← 归档工具，详见 §8
   ├─ list_artifacts
   ├─ get_artifact
   └─ mark_artifact_read
        │
        ▼ （动态创建）
   ┌──────────┐  ┌──────────┐
   │ Worker A │  │ Worker B │  ...
   │ 独立 MCPClient（排除 5 团队工具）
   └──────────┘  └──────────┘
```

**关键隔离**：worker 通过 `copyToolsTo(workerMcpClient, exclude=5 团队工具)` 拿到工具集，**不能** agent_create/team_dissolve 越权操作团队。但**能**用 4 个 artifact 工具（共享归档是双方都需要的）。

### 7.2 创建团队

```text
You ▶ team create
```

显式创建。但**无需**显式创建——只要 Leader 第一次调用 `agent_create` 工具，团队会自动激活。

### 7.3 让 Leader 自动调度

通常用户**不直接**操作 worker。流程是：

```text
You ▶ 帮我分析仓库的代码质量，用 3 个专家并行：前端/后端/测试
```

Leader 会自动：
1. 调用 `agent_create` 创建 3 个 worker（前端专家、后端专家、测试专家）
2. 调用 `agent_message_parallel` 并行派发任务
3. 等所有 worker 完成（`CompletableFuture.allOf().join()`）
4. 汇总结果返回用户

### 7.4 手动操作 worker（高级）

如果你想让 Leader 显式调度，直接告诉它：

```text
You ▶ 用 agent_create 创建一个叫"数据专家"的工作者，系统提示词是"你是数据分析专家，只回答数据相关问题"
```

LLM 会调 `agent_create(name="数据专家", system_prompt="...")`。然后用：

```text
You ▶ 让数据专家分析 workspace/report.csv 的前 10 行
```

Leader 会调 `agent_message(worker_name="数据专家", message="...")`，worker 进入完整 ReAct 循环（可继续调文件/代码工具）。

### 7.5 5 个团队工具参数

| 工具 | 参数 | 返回 |
|---|---|---|
| `agent_create` | `name`, `system_prompt` | "工作者已创建，ID: xxx" |
| `agent_message` | `worker_name`, `message` | worker 的回复文本（截断 20000 字符） |
| `agent_message_parallel` | `tasks: Map<name, message>` | 各 worker 回复汇总 |
| `agent_list` | — | worker 列表（截断 5000 字符） |
| `team_dissolve` | — | "团队已解散，关闭 N 个工作者" |

### 7.6 并行分发的并发模型

- 实现：`CompletableFuture.supplyAsync` → 默认 ForkJoinPool
- 所有 worker 同时开始，`allOf().join()` 等齐
- **已知风险**：多 worker 同时改同一文件**无文件锁**，可能竞态。深度玩家应避免让 worker 共享写入路径，或让 Leader 串行化任务。

### 7.7 状态查看

```text
You ▶ team status
```

输出：teamId、活跃标志、worker 列表（name + id）、进度跟踪事件（最近一次的 onLeaderCreateWorker / onLeaderAssignTask / onWorkerComplete）。

### 7.8 解散

```text
You ▶ team dissolve
```

关闭所有 worker，清空 workers map，置 active=false。Leader 自身不关闭，可以继续单 Agent 对话（流式路径重新激活）。

---

## 8. 归档共享：4 个 artifact 工具

worker 之间/worker 与 Leader 之间的**结构化产出共享**机制。解决 `agent_message` 文本截断、二进制无法传的问题。

### 8.1 工具清单

| 工具 | 作用 |
|---|---|
| `share_file` | 发送文件，落盘 + sha256 校验，状态置 SENT |
| `list_artifacts` | 列出当前调用者可见的 artifact（作 sender 或 recipient） |
| `get_artifact` | 读取内容并校验，自动推进状态 RECEIVED |
| `mark_artifact_read` | 标记已读，状态置 READ |

### 8.2 share_file 参数

```json
{
  "filename": "report.md",        // 必填，扩展名必须在白名单
  "recipients": ["leader"],       // 必填，"leader" 表示领导者
  "content": "...",               // 必填，UTF-8 文本或 base64
  "encoding": "utf8",             // utf8 | base64，默认 utf8
  "mimeType": "text/markdown",    // 可选，省略按扩展推断
  "description": "周报",          // 可选 ≤200 字
  "tags": ["report", "weekly"]    // 可选
}
```

**扩展名白名单**：`txt/md/json/csv/py/java/js/ts/go/sh/yaml/yml/xml/html/png/jpg/jpeg/gif/pdf/log`

返回：`artifactId`、文件名、字节数、sha256、接收者、状态。

### 8.3 list_artifacts 参数（全部可选）

```json
{
  "status": "SENT|RECEIVED|READ",
  "tag": "report",
  "sender": "data-expert",
  "limit": 20
}
```

返回 Markdown 表格（ID / 文件名 / 发送者 / 大小 / 状态 / 创建时间）。

### 8.4 get_artifact 参数

```json
{
  "artifactId": "abc-123",
  "encoding": "utf8"   // 二进制建议 base64
}
```

返回 `[artifact: xxx from yyy, N bytes, sha256 verified]` 前缀 + 内容。状态自动推进到 RECEIVED。

### 8.5 状态机

```
share_file 创建 → SENT
                   │
                   ▼
   recipient 调 get_artifact → RECEIVED
                                │
                                ▼
                  mark_artifact_read → READ
```

### 8.6 调用者识别

工具内部用 `currentAgentName` ThreadLocal 判定调用者：
- Leader 直接调：ThreadLocal 未设置 → 默认 `leader.getName()`
- Worker 调：`sendMessageToWorker` 在调度前已 set workerName

所以 worker 之间的 artifact 流转**不需要**显式声明身份，工具自动识别。

### 8.7 持久化

artifact 落盘到 `workspace/artifacts/`（包括内容 + 元数据），跨 reply 持久。`team dissolve` **不**清空 artifacts。

### 8.8 典型工作流（深度玩家）

```text
You ▶ 让 3 个专家并行分析：前端、后端、测试，各自产出一份 markdown 报告，最后由 Leader 合并

Leader 自动：
1. agent_create × 3（前端/后端/测试专家）
2. agent_message_parallel 同时派发任务
3. 每个 worker 完成后调 share_file 发回报告（避免 agent_message 截断）
4. Leader 调 list_artifacts 看清单
5. Leader 调 get_artifact 读取每份报告
6. Leader 调 mark_artifact_read 标记已处理
7. Leader 合并三份报告，通过文本回复用户
```

**关键**：让模型**优先用 share_file**传长内容/二进制，不要塞进 `agent_message`（会被截断到 20000 字符）。系统提示词已声明此规范。

---

## 9. 输出详细度：四档 verbosity

| 档位 | 命令 | 行为 |
|---|---|---|
| `quiet`（MINIMAL） | `verbosity quiet` | 仅显示最终结果，无过程输出 |
| `normal`（STANDARD） | `verbosity normal` | 显示规划过程和关键里程碑（默认） |
| `verbose`（VERBOSE） | `verbosity verbose` | 显示所有执行细节和工具调用 |
| `debug`（DEBUG） | `verbosity debug` | 显示完整内部状态和调试信息 |

切换：

```text
You ▶ verbosity verbose
```

影响 `AgentProgressTracker` 的输出粒度（每次 reply 内部重建 tracker 以反映当前 verbosity）。

---

## 10. 上下文管理：压缩 + 时序归档

### 10.1 两套正交机制

| 机制 | 触发 | 操作对象 | 操作 |
|---|---|---|---|
| **消息级压缩**（`ContextManager.compressContext`） | 上下文 token 数 > `maxContextTokens` 或消息条数 > 阈值 | 整条 Msg | 丢弃旧消息，保留 `maxRecentMessages` 条 + 钉住最近 user 消息 + 注入压缩告知 user 消息 |
| **微压缩**（`MicroCompactor`） | 工具调用次数 > `microCompactorTriggerToolCount`(12) | ToolCallBlock.arguments + ToolResultBlock.content | 替换为 `[REDACTED]` stub |
| **工具结果时序归档**（`ContextToolResultArchiver`） | 每次新工具结果入 context | 旧的 FULL ToolResultBlock | 改写为带 `tool_call_id` 的差异化摘要，原始全量落盘到 `workspace/cache/tool_outputs/` |

三者**正交，互不干扰**。

### 10.2 工具结果时序归档（核心不变量）

任意时刻 context 中的 ToolResultBlock 满足：

- **至多 1 个** `FULL` 状态（最新一次工具调用）
- **零或多个** `SUMMARIZED` 状态（历史调用）
- 每个块的 `toolCallId` 都能在 `workspace/cache/tool_outputs/{toolCallId}.txt` 查到原始全量

**触发流程**（每次新工具结果入 context 前后）：

```
① compactExistingToolResults(context)
   └─ 扫描所有 FULL 块 → archive.getFullOutput(id) → summarizer.summarize → 改写为 SUMMARIZED + 前缀
② archive.archive(toolCallId, toolName, args, fullOutput)
   └─ 写 workspace/cache/tool_outputs/{id}.txt + 更新 index.json
③ context.add(toolResultMsg)
④ markAsFull(toolResultMsg, toolCallId)
   └─ Msg.metadata.put(toolCallId, "FULL")
```

**摘要块前缀格式**：

```
[此工具结果已摘要 | tool_call_id=abc123 | 原始 8916 字符 | 如需完整内容请调用 get_full_tool_output 工具]
{差异化摘要正文}
```

### 10.3 取回原始全量：get_full_tool_output 工具

```text
（模型自动调用，无需用户操作）
get_full_tool_output(tool_call_id="abc123")
→ 返回 workspace/cache/tool_outputs/abc123.txt 的完整内容
```

未找到时返回提示信息 + 可用 ID 列表。

### 10.4 工具结果入 context 时的分级截断

| 工具 | 上限（字符） |
|---|---|
| `agent_message` | 20000 |
| `agent_create` | 500 |
| `agent_list` | 5000 |
| 其他默认 | 5000 |

**重要顺序**：截断**先于**归档。`ContextToolResultArchiver` 看到的是截断后的内容。

### 10.5 压缩告知消息

当消息级压缩触发，`buildContext` 会在 system 之后插入一条 user 角色告知：

> N 条较早消息已被压缩丢弃，如需具体细节请询问

让模型感知上下文裁剪事实，避免信息偏差（这是踩坑后的修复，详见 `MEMORY.md`）。

### 10.6 深度玩家调参

```text
You ▶ /config set maxContextTokens=30000          # 更激进压缩
You ▶ /config set maxRecentMessages=15            # 保留更多近期
You ▶ /config set microCompactorTriggerToolCount=6 # 更早触发 stub
You ▶ /config set toolResultSummaryThreshold=1500 # 更早摘要
```

### 10.7 检查归档产物

```bash
ls workspace/cache/tool_outputs/
# {toolCallId}.txt  index.json

cat workspace/cache/tool_outputs/index.json | jq .
# 列出所有归档的元数据
```

跨重启复用：新实例指向同一 `workspace/` 即可读旧归档。

---

## 11. LLM 多提供商与 failover

### 11.1 支持的 4 个提供商

| 提供商 | env var | 默认模型 | 默认端点 |
|---|---|---|---|
| dashscope | `DASHSCOPE_API_KEY` | `qwen-plus` | `https://dashscope.aliyuncs.com/compatible-mode/v1` |
| openai | `OPENAI_API_KEY` | `gpt-4o-mini` | `https://api.openai.com/v1` |
| anthropic | `ANTHROPIC_API_KEY` | `claude-sonnet-4-20250514` | Anthropic 兼容端点 |
| deepseek | `DEEPSEEK_API_KEY` | `deepseek-chat` | `https://api.deepseek.com/v1` |

全部走 **OpenAI 兼容 `/chat/completions`** 协议。

### 11.2 主备 failover

`DefaultCredentialProvider` 同时持有 primary 和 fallback：
- 主提供商调用失败（401、网络异常、5xx）→ 自动切 fallback
- fallback 通常是第二个配置了 Key 的提供商
- 优先级：`MODEL_PROVIDER` 显式 > dashscope > openai > ...

配置示例（dashscope 主，openai 备）：

```bash
export DASHSCOPE_API_KEY=sk-xxx
export OPENAI_API_KEY=sk-yyy
# MODEL_PROVIDER 不设，自动选 dashscope 为主、openai 为备
```

### 11.3 全局 vs 单提供商覆盖

- `MODEL_BASE_URL` / `MODEL_NAME`：全局，覆盖所有提供商
- `{PROVIDER}_BASE_URL` / `{PROVIDER}_MODEL`：单提供商，仅在该提供商激活时生效

私有兼容网关（如代理多个模型）：

```bash
export MODEL_BASE_URL=https://api.fanticat.com:3443/v1
export MODEL_NAME=glm-5.1
export DASHSCOPE_API_KEY=anything   # 任一 Key 即可启动
```

### 11.4 流式协议

- 请求带 `stream:true` + `stream_options.include_usage:true`
- OkHttp 同步 `execute()` 拿 InputStream，`BufferedReader` 逐行解析 `data:` 帧
- 工具调用按 `tool_calls[].index` 增量累积，流结束才聚合 `TOOL_CALL` 事件
- 思考链（thinking block）实时输出，DIM 样式

### 11.5 超时与重试

```text
You ▶ /config set llmReadTimeoutSeconds=600
You ▶ /config set llmMaxRetries=3
```

`SocketTimeoutException` 会自动重试 `llmMaxRetries` 次（已修复的常见踩坑，见 `MEMORY.md`）。

---

## 12. 深度玩家玩法

### 12.1 玩法 1：极简模式（教学/演示）

```bash
# EXPLORE 模式 + quiet verbosity
export PERMISSION_MODE=EXPLORE
java -jar agentscope-demo-1.0.0.jar
# REPL 内：
# verbosity quiet
```

适合给新人看 agent 怎么用，最少噪音。

### 12.2 玩法 2：调试权限系统

```bash
# BYPASS 模式 + debug verbosity
export PERMISSION_MODE=BYPASS
java -jar agentscope-demo-1.0.0.jar
# REPL 内：
# verbosity debug
# permission    # 看规则
# /config set maxIterations=100   # 允许更长链路
```

观察中间件洋葱、权限判定、事件流。

### 12.3 玩法 3：团队并行分析（最高级）

```text
You ▶ 我想做一份仓库代码质量报告，分 4 个 worker 并行：架构/测试/日志/安全。每个 worker 产 markdown 报告通过 share_file 发回，你（Leader）汇总成一份总报告写回 workspace/final-report.md

预期：
- Leader 自动创建 4 个 worker
- agent_message_parallel 并行
- 每个 worker 完成后 share_file
- Leader list_artifacts → get_artifact × 4 → write_file 汇总
```

观察 `team status` 看进度，看 `workspace/artifacts/` 看产出。

### 12.4 玩法 4：上下文压力测试

```text
You ▶ /config set toolResultSummaryThreshold=800
You ▶ /config set microCompactorTriggerToolCount=4
You ▶ /config set maxContextTokens=15000
You ▶ verbosity debug

# 然后连续让模型读多个大文件：
You ▶ 读 src/main/java/com/demo/agentscope/agent/Agent.java 全文
You ▶ 读 src/main/java/com/demo/agentscope/mcp/MCPClient.java 全文
You ▶ 读 src/main/java/com/demo/agentscope/AgentScopeDemoApplication.java 全文
You ▶ 现在告诉我第一个文件中 replyStream 方法的实现细节
```

模型会自动调 `get_full_tool_output` 取回被摘要的原始内容。

### 12.5 玩法 5：外部 MCP 服务器挂载

```bash
# 挂一个 filesystem MCP server
export MCP_SERVERS="npx:-y,@modelcontextprotocol/server-filesystem,/tmp"
java -jar agentscope-demo-1.0.0.jar
# REPL 内：
# tools    # 应该看到外部工具
```

`MCP_SERVERS` 格式：`command:arg1,arg2`（多服务器用分号分隔）。

### 12.6 玩法 6：流式事件流观察

```text
You ▶ events      # 开启事件展示
You ▶ 用 Python 算 100 以内的质数
```

看到完整的 `ReplyStart → ModelCall → [ThinkingBlock] → TextBlock → ToolCall → ToolResult → ... → ReplyEnd` 序列。

### 12.7 玩法 7：自定义权限规则

通过环境变量加规则：

```bash
# 放行所有读，拒绝所有执行
export PERMISSION_RULE_1="read_file=ALLOW;bypassImmune=true"
export PERMISSION_RULE_2="list_files=ALLOW"
export PERMISSION_RULE_3="execute_command=DENY;bypassImmune=true;reason=只读模式"
export PERMISSION_RULE_4="execute_python=DENY;bypassImmune=true"
```

启动后 `permission` 命令查看。

---

## 13. 故障排查 FAQ

### 13.1 改了源码不生效

**症状**：commit 完成、源码已正确，但运行行为没变。

**原因**：`java -jar` 加载的是 jar 内 class。源码改了但 jar 没重新生成。

**修复**：

```bash
mvn -pl agentscope-demo package -DskipTests
# 或在 startas.sh 的 java -jar 前加一行 mvn package
# 开发期：mvn exec:java -pl agentscope-demo
```

### 13.2 HTTP 400 `messages 参数非法`

**症状**：压缩触发后或长对话后，`chatStream` 返回 400，错误码 1214 之类。

**根因 1**：tool_call 与 tool_result 没双向配对（压缩时单向 orphan check 漏掉）。
**根因 2**：messages 序列里**没有 user 角色消息**（被压缩挤出窗口）。

**修复**：两者都已在 `ContextManager.compressContext` 修复，但如果你改了压缩逻辑要重新踩坑。回归测试：`ContextManagerCompressionTest`（3 个用例）。

### 13.3 SocketTimeoutException

**症状**：LLM 调用超时，回复中断。

**修复**：

```text
You ▶ /config set llmReadTimeoutSeconds=600
You ▶ /config set llmMaxRetries=3
```

`llmMaxRetries` 会自动重试（已内置）。

### 13.4 文件读写被拒绝

**症状**：`FilePermissionDeniedException`。

**排查**：
1. 文件路径是否在 `workspace/` 子树内？（白名单路径）
2. 扩展名是否在白名单？（`txt/md/json/csv/py/java/js/ts/go/sh/yaml/yml/xml/html/png/jpg/jpeg/gif/pdf/log`）
3. 文件是否 > 10MB？
4. 是否命中黑名单路径（`.env`、`secrets`、`.git/`）？

**绕过**：切到 BYPASS 模式（但 `bypassImmune` 规则仍生效）。**注意**：文件级权限（FilePermissionManager）**不**受 PermissionEngine 模式控制，是独立的 7 步检查。

### 13.5 工具调用被权限拒绝

**症状**：工具返回"权限拒绝"错误。

**排查**：

```text
You ▶ permission    # 看当前模式 + 规则
```

- 模式是 EXPLORE？切 DONT_ASK 或 BYPASS
- 有用户规则 DENY？用 `PERMISSION_RULE_<n>` 覆盖或去掉对应环境变量
- 命中内置危险模式？（`rm -rf`、`chmod 777` 等）—— 改命令

### 13.6 worker 回复被截断

**症状**：`agent_message` 返回不完整。

**根因**：`agent_message` 结果截断到 20000 字符。

**修复**：让 worker 用 `share_file` 发回长内容，Leader 用 `get_artifact` 读取。

### 13.7 团队模式下没有流式输出

**现象**：`team create` 后输入文本，没有 token 级实时回显，要等很久才一次性出。

**原因**：团队模式走 `team.reply()` 同步路径，**故意关闭流式**（多智能体协作暂不流式）。

**修复**：想流式就 `team dissolve` 回到单 Agent 模式。

### 13.8 `/config set` 拼写错误

**现象**：`设置失败: ...` 报错。

**排查**：用 `/config`（无参数）查看所有可用 key 与正确拼写。注意是 camelCase（`maxIterations` 不是 `max_iterations`）。

### 13.9 中文退格乱码

**现象**：终端输入中文后按退格，屏幕回显与实际缓冲不一致。

**修复**：应用已用 JLine3 LineReader 接管终端 raw mode 解决此问题。如果你绕过 LineReader 直接读 stdin，会踩坑。保持用 `ConsoleUI.promptUser()`。

---

## 14. 内部机制速查表

### 14.1 装配顺序（main 10 步，必须自底向上）

```
1. determinePrimaryProvider(dashscope > openai)
2. new ChatModel(...)             ← LLM 客户端
3. new MCPClient() + initialize()
4. SecureFileWorkspace + registerFileTools(ws)
5. CodeExecutionManager + registerCodeExecutionTools(em)
6. PermissionEngine (Rules → Mode → Built-in)
7. WorkspaceManager
8. ToolOutputArchive + ContextToolResultArchiver
   └─ registerCustomTool("get_full_tool_output", ...)
   └─ MCPClient.getToolResultSummarizer() 复用
9. new Agent(leader, ...)
   ├─ middlewareChain 挂载：Tracing / Compression / Permission / Budget
   └─ agent.setToolResultArchiver(archiver)
10. AgentTeam + registerTeamTools + registerArtifactTools
   └─ runREPL
```

### 14.2 中间件洋葱（6 钩子）

```
onReplyStart(ctx)
   ↓
onModelCall(ctx, requestMsg)
   ↓
[chatModel.chat]
   ↓
onModelCallEnd(ctx, response)   ← Budget 在此累计 token
   ↓
onToolCall(ctx, toolCall)       ← PermissionMiddleware 软检查
   ↓
[mcpClient.executeTool]
   ↓
onToolResult(ctx, resultBlock)
   ↓
onReplyEnd(ctx, eventStream)    ← ContextCompressionMiddleware 在此触发
```

4 个内置中间件（按声明顺序）：
1. `TracingMiddleware`（日志）
2. `ContextCompressionMiddleware`（阈值 `maxContextTokens` / 保留 `maxRecentMessages`）
3. `PermissionMiddleware`（工具调用前置检查，与 `PermissionEngine` 双闸）
4. `ReplyBudgetControlMiddleware`（默认 500K，超限仅告警不中断）

### 14.3 事件流类型（20+）

一次成功 reply：

```
ReplyStart
  └─ ModelCall
       ├─ ThinkingBlock     (若 LLM 返回思考块)
       ├─ TextBlock         (流式/最终文本)
       ├─ ToolCall
       │    └─ ToolResult
       └─ ... (循环)
  └─ ReplyEnd
```

异常分支：`Error`、`ReplyBudgetExceeded`（超预算但**不中断**）。

### 14.4 工具集分类

| 类别 | 数量 | 工具 |
|---|---|---|
| 文件 | 4 | read_file / write_file / edit_file / list_files |
| 代码执行 | 3 | execute_python / execute_command / install_package |
| 上下文归档 | 1 | get_full_tool_output |
| 团队（仅 Leader） | 5 | agent_create / agent_message / agent_message_parallel / agent_list / team_dissolve |
| Artifact（Leader + Worker） | 4 | share_file / list_artifacts / get_artifact / mark_artifact_read |
| 股票（可选） | 4 | list_industries / select_industry_leaders / get_stock_detail / update_stock_data |
| 外部 MCP | N | 通过 `MCP_SERVERS` 配置 |

**Leader 总数**：4 + 3 + 1 + 5 + 4 = 17（未计外部 MCP 与股票）。
**Worker 总数**：4 + 3 + 1 + 4 = 12（排除 5 团队工具）。

### 14.5 关键文件路径速查

| 路径 | 性质 | gitignore |
|---|---|---|
| `workspace/` | Agent 运行时 scratch | 是 |
| `workspace/cache/tool_outputs/` | 工具结果原始归档（`{toolCallId}.txt` + `index.json`） | 是 |
| `workspace/artifacts/` | 团队 artifact 共享存储 | 是 |
| `workspace/*.md` 等 | Agent 生成的产物 | 是 |
| `.tools/` | 内置 JDK + Maven | 是 |
| `.codegraph/` | CodeGraph 索引 | 是 |
| `startas.sh` / `startpm.sh` | 含 API Key 的启动脚本 | 是（`start*.sh`） |
| `agentscope-demo-*.jar` | shaded 产物 | 默认在 `target/` |

### 14.6 关键类速查

| 类 | 路径 | 职责 |
|---|---|---|
| `AgentScopeDemoApplication` | 根包 | main + REPL + 装配 |
| `Agent` | `agent/` | ReAct 循环（reply/replyStream） |
| `AgentTeam` | `agent/` | Leader-Worker 团队 + 9 工具注册 |
| `MCPClient` | `mcp/` | 工具聚合 + executeTool |
| `PermissionEngine` | `permission/` | 工具级权限（Rules → Mode → Built-in） |
| `FilePermissionManager` | `filepermission/` | 文件级权限（7 步） |
| `SecureFileWorkspace` | `workspace/` | 桥接 LocalWorkspace 与 FilePermissionManager |
| `ContextManager` | `context/` | 消息级压缩 + 配对校验 |
| `ContextToolResultArchiver` | `context/` | 工具结果时序归档（FULL/SUMMARIZED） |
| `ToolOutputArchive` | `tool/` | 原始输出落盘 |
| `ToolResultSummarizer` | `tool/` | 差异化摘要 |
| `ChatModel` | `model/` | OpenAI 兼容 LLM 客户端（阻塞 + SSE 流式） |
| `DefaultCredentialProvider` | `credential/` | 4 提供商主备 failover |
| `AgentLimits` | `config/` | 21+ 可调参数（env + /config set） |
| `ConsoleUI` | `ui/` | JLine3 ANSI REPL |
| `VerbosityLevel` | `ui/` | 四档详细度枚举 |
| `EventStream` | `event/` | 20+ 类型化可重放事件 |

---

## 附录：相关文档

| 文档 | 作用 |
|---|---|
| `ARCHITECTURE.md`（仓库根） | 两模块并排对比，最重要的入门 |
| `design/00-总览.md` | design 总览 |
| `design/01-数据流时序.md` | 关键流程时序图 |
| `design/02-模块组件功能索引.md` | 全量组件索引 |
| `design/03-横切关注点.md` | 跨模块约定 |
| `design/agentscope/20-架构.md` | agentscope 六件套架构 |
| `design/agentscope/27-agent-team.md` | Agent + 团队 |
| `design/agentscope/32-context.md` | 工具结果归档机制 |
| `AgentScope2.0剖析_20260626.md` | 上游 Python 框架剖析 |
| `MEMORY.md`（auto-memory） | 项目踩坑与工作流约定 |

---

**维护提示**：本文档与代码同步演进。新增命令/工具/参数时，优先更新 §3（命令）、§14（速查表）。新增环境变量时更新 §2.3。
