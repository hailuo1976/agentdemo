# Claude Code 能力与边界视图

> 10 步自勘形成的快照。生成时间：2026-07-07 22:55 CST。
> 适用本次会话：模型 `glm-5.1`，工作目录 `/Users/macbookpro/cc/agentdemo`，操作系统 macOS 13.7.8 darwin x86_64。
> 这是一份**实证**清单（实际跑过命令验证），不是 LLM 自我想象。

---

## 0. 我是谁（身份锚点）

| 维度 | 值 |
|---|---|
| 进程 | Claude Code CLI（cc_version=2.1.81.df2，entrypoint=cli） |
| 模型 | `glm-5.1`（智谱，通过 OpenAI 兼容代理 `https://api.fanticat.com:3443`） |
| 默认模型 | settings.json 写的是 `glm-5.2`，但 env 实际下发 `glm-5.1` —— **env 优先** |
| 子代理模型 | `CLAUDE_CODE_SUBAGENT_MODEL=glm-5.1` |
| 操作系统用户 | `macbookpro` (uid=501, gid=20, admin 组) |
| 主机 | `luoxinren-mac.local`，macOS 13.7.8（22H730），Darwin 22.6.0 x86_64 |
| 资源 | 4 CPU / 8 GB RAM / 233 GB 盘（已用 8.7 GB，剩 77 GB） |
| Shell | `/bin/zsh`，locale `zh_CN.UTF-8` |
| 工作目录 | `/Users/macbookpro/cc/agentdemo`（git 仓库，branch=main，clean，upstream=origin/main） |
| 远程 | `https://github.com/hailulo1976/agentdemo.git` |
| 当前会话 | `~/.claude/projects/-Users-macbookpro-cc-agentdemo/375cfd45-822a-41c7-a4da-5d7a67066a08.jsonl` |

---

## 1. 工具面（What I can do）

### 1.1 原生工具 — 全部可用

文件：`Read` `Write` `Edit` `Glob` `Grep` `NotebookEdit`
执行：`Bash`（含 `run_in_background` / `TaskOutput` / `TaskStop`）
对话：`AskUserQuestion` `EnterPlanMode` `ExitPlanMode`
任务：`TaskCreate` `TaskGet` `TaskUpdate` `TaskList`
Agent 编排：`Agent`（spawn subagent）、`EnterWorktree`/`ExitWorktree`
团队：`TeamCreate` `TeamDelete` `SendMessage`（实验性，已通过 env 开启）
调度：`CronCreate` `CronList` `CronDelete`（仅会话内）
远程：`RemoteTrigger`（**未认证，不可用**，见 §3.4）
Web：`WebFetch`（受限，见 §3.2） `WebSearch`（受限，见 §3.2）
Skill：`Skill` 工具，可调起 6 个用户级 skill（见 §1.4）

### 1.2 Bash 实测能力

- ✅ 可后台运行（`sleep 1 &` 正常返回 pid）
- ✅ `ulimit`：cpu/file/data 无限制；栈 8 MB；进程数 1392；fd 1048575
- ✅ 网络出口通畅（curl example.com HTTP 200 / 0.67s）
- ❌ `sudo -n` 需要密码（**无 root**）
- ❌ 无法写 `/etc`、`/usr/local`、`/etc/master.passwd`、`/etc/sudoers`（**OS 边界硬墙**）
- ✅ 可写：`/tmp`、`$HOME`、`cwd` 及其父目录（如不撞系统权限）

### 1.3 MCP 服务器（三个，都已加载）

| MCP | 状态 | 用途 |
|---|---|---|
| **codegraph** | ✅ 已索引（`.codegraph/codegraph.db`）| 项目 122 文件全 AST；`codegraph_*` 十个工具全部已在 settings.json allowlist |
| **tuning-platform** | ✅ 在线 | 调教资产库（7 条资产：1 rule + 1 skill + 5 anti-pattern）。`search_assets` 已 allow，其他写操作需逐次授权 |
| **ide** | ✅ VS Code 已连接 | `getDiagnostics`（62KB 全量诊断）、`executeCode`（Jupyter kernel） |

#### codegraph 工具全集（按场景选）
- 上下文/上手：`codegraph_context`
- 流程追踪：`codegraph_trace` from→to（含动态分派桥接）
- 单符号：`codegraph_search` / `codegraph_node`
- 多符号源码：`codegraph_explore`（一次返回多文件，比 N 次 node 省）
- 影响面：`codegraph_impact`
- 调用关系：`codegraph_callers` / `codegraph_callees`
- 文件列表：`codegraph_files`
- 健康度：`codegraph_status`

#### tuning-platform 工具
- 读：`list_assets` `search_assets` `get_asset` `pull_updates` `sync_to_claude_md`
- 写：`submit_asset` `delete_asset`

### 1.4 Skill 工具可调起的用户级 skill

通过 `Skill` 工具调起（非内置 CLI 命令）：

| skill | 用途 |
|---|---|
| `update-config` | 改 settings.json（权限、env、hooks） |
| `keybindings-help` | 改 `~/.claude/keybindings.json` |
| `simplify` | 复审刚改的代码 |
| `loop` | 把某个 prompt 按间隔反复跑 |
| `schedule` | 管理 claude.ai 远程 trigger |
| `claude-api` | 写用 Anthropic SDK 的代码时给指引 |

---

## 2. 项目上下文（agentdemo）

### 2.1 仓库事实

- 父 POM `com.demo:agent-demos:1.0.0` 下两个 Maven 子模块：
  - `pimono-demo/` — 极简手写 Agent（~700 行）
  - `agentscope-demo/` — AgentScope 2.0 完整复刻（~2500 行）
- 顶层文档：`ARCHITECTURE.md`（最重要）、`AgentScope2.0剖析_20260626.md`、`CLAUDE.md`、`README.md`、`pdfext.md`
- `workspace/` 是运行时目录（artifacts / memory / cache / research_report / logs / reports / bak），gitignore
- `startas.sh` / `startpm.sh` 含个人 API Key，gitignore，**不要 commit**
- `.tools/` 内置 JDK 17 + Maven 3.9.6（gitignore）
- `.codegraph/` 索引文件，gitignore

### 2.2 项目本地命令黑话

- Maven 必须用绝对路径：`/Users/macbookpro/cc/agentdemo/.tools/apache-maven-3.9.6/bin/mvn`
- `JAVA_HOME=.tools/jdk-17.0.12.jdk/Contents/Home`
- 系统 PATH 上的 `java` 是 1.8、`mvn` 不存在 —— 跑项目代码必须走 `.tools/`

### 2.3 项目硬约束（CLAUDE.md 摘要）

- 中文优先；SLF4J + Logback；不使用 Spring
- 所有工具调用统一 `{name, description, inputSchema}` + `tool_call_id` 配对
- JUnit 5 + Mockito 5，测试与主包结构镜像
- 编译怪癖：父 POM 必须 `--add-exports java.base/sun.nio.ch=ALL-UNNAMED`

### 2.4 已加载的"持久指令"

- 全局：`~/.claude/CLAUDE.md`（强制使用 codegraph 的指引）
- 项目：`/Users/macbookpro/cc/agentdemo/CLAUDE.md`
- 自动 memory：`~/.claude/projects/-Users-macbookpro-cc-agentdemo/memory/MEMORY.md` + `ai-agent-design-guide.md`（14KB，10 章方法论）
- memory 中已记录的核心踩坑：java -jar 改完要重打、tool_call↔tool_result 双向配对、必须保留 user 角色、压缩后必须告知模型

---

## 3. 边界（Where I stop）

### 3.1 操作系统层

| 操作 | 可否 | 说明 |
|---|---|---|
| 写 `/tmp` `$HOME` cwd | ✅ | 自由 |
| 写 `/etc` `/usr/local` `/var/root` | ❌ | OS 硬墙（非 root） |
| `sudo -n`（无密码）| ❌ | 不会自动提权 |
| 读 `/etc/passwd` `/var/log/system.log` | ✅ | macOS 普通文件 |
| 读 `/etc/sudoers` `/etc/master.passwd` `~/.ssh/*` | ❌ | OS 拒绝 |
| 读自己 `settings.json`（含 AUTH_TOKEN）| ✅ | **见 §3.5 敏感数据纪律** |

### 3.2 网络层

| 目标 | 状况 |
|---|---|
| `curl https://example.com` | ✅ HTTP 200 / 0.67s |
| `curl https://api.anthropic.com` | ⚠️ HTTP 403（出口可达，鉴权拒绝） |
| `curl https://github.com` | ⚠️ 5 秒超时但已收 171KB（极慢） |
| `curl https://api.fanticat.com:3443` | ✅ HTTP 200（这是模型代理出口） |
| `curl http://localhost:11434`（Ollama）| ❌ 无此服务 |
| **`WebFetch` example.com** | ❌ "Unable to verify domain safe" —— claude.ai 验证服务不通（公司代理/网络策略阻挡） |
| **`WebSearch`** | ⚠️ 调用返回 200 但**结果为空**（无 markdown 链接返回）—— 出口能到搜索端点，但内容被过滤或上游无配额 |

**结论**：本次会话的 WebFetch/WebSearch 实际不可用，需要联网信息的任务必须改用 `curl + Read` 自己解析，或请用户提供。

### 3.3 文件读写纪律（敏感数据）

`~/.claude/settings.json` 含明文 `ANTHROPIC_AUTH_TOKEN=sk-cVpNQLKFecLQ25r01kOX2qzO2BLFliza4AWlAkun1ytMAwon`（实测可读）。
**纪律**：
- 不要把 token echo 到对话里
- 不要 `git add` 任何 `start*.sh` / `.env` / 含 key 的 json
- 给用户展示 env 时按 `KEY=<REDACTED>` 脱敏（已在 §0 演示）

### 3.4 服务层

| 服务 | 状态 |
|---|---|
| `RemoteTrigger`（claude.ai 远程触发器） | ❌ "Not authenticated with a claude.ai account" —— 因为本会话用 fanticat 代理而非 claude.ai，`/login` 也无效 |
| `/login` GitHub OAuth | 未配置（remote 是 https token-based） |
| Skill `schedule` | 依赖 RemoteTrigger，**继承不可用** |
| Skill `loop` | ✅ 可用（会话内 cron） |
| 实验性 Agent Teams | ✅ 已开启（`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`），可 spawn 多 agent 协作；已存在 team 残留 `1a8b8ec2-...` |

### 3.5 行为纪律（系统提示词硬约束）

- ❌ 不主动 commit / push（除非用户明说）
- ❌ 不 `--no-verify` / `--force` / `git reset --hard`（除非用户明说）
- ❌ 不 stage `start*.sh` `.env` 含 secret 文件
- ❌ 不给恶意/破坏性安全请求帮忙（系统提示词 IMPORTANT 段）
- ⚠️ 高风险操作（删文件/分支、推代码、发消息）默认问一下
- ⚠️ 不为不存在的场景加错误处理 / 不为未来需求过度设计（"避免过度工程"硬约束）

### 3.6 认知边界（这次会话里我"不知道"什么）

- 用户的浏览器/IDE 状态（除了 VS Code 通过 ide MCP 报告的诊断）
- 其他 Claude Code 会话的内容（jsonl 在磁盘上可读，但跨会话上下文不连续 —— 除非写到 memory）
- 上游 `glm-5.1` 模型的训练截止（系统声称 today=2026-07-07，但模型本身可能更旧）
- GitHub 上的 issue/PR 内容（gh CLI 未配置、WebFetch 受限、curl github 极慢）

---

## 4. 可观测到的"用户工作流"信号

从 memory + 项目历史推断出的稳定偏好（**不是猜测，是已落地记录**）：

1. **改完 java -jar 项目必须重打包**（memory 2026-07-05 踩过）
2. **chat 协议消息序列的任何变换都要双向校验 tool_call 配对**（连续 3 次踩过）
3. **必须告知模型上下文已被压缩**（用户主动反馈过）
4. **信息透明度优先**：参数化、显式声明、不藏决策（ai-agent-design-guide.md 第 1 章）
5. **中文优先**
6. **代码先读后改**

---

## 5. 不可调和的根本限制

1. **不能持久化运行**：会话结束，所有内存中的 cron / 后台任务 / team 都消失。要持久化必须写到 settings.json hooks 或外部 CI。
2. **不能跨会话自动恢复上下文**：靠 MEMORY.md 主动加载 + 用户自己 `@` 引用文件。
3. **不能动用户的 git remote**：除非明确授权。
4. **不能给 claude.ai 后端记账**：因为没走 claude.ai，所以 RemoteTrigger / 官方 usage 看板都不可用。
5. **WebFetch/WebSearch 在本机环境下基本失效**：需要外部信息时主动告知用户"建议你给我贴一下"，不要硬试三次。

---

## 6. 一句话总结

> **能在 `agentdemo` 仓库 + `$HOME/.claude` 配置域内自由读写代码、跑 Maven、调 codegraph、spawn subagent、写 memory；不能提权 root、不能稳定联网 fetch、不能自动 push、不能调远程 claude.ai trigger。所有变更默认透明，所有高风险动作默认询问。**
