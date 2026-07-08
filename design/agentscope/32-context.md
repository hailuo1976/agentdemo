# 32 上下文模块（context）

> 包：`com.demo.agentscope.context`
> 主类：`ContextToolResultArchiver`
> 配套：`tool/ToolOutputArchive` + `tool/ToolResultSummarizer`

---

## 1. 设计目标

解决工具结果在上下文中的**信息衰减问题**——旧实现 `ToolResultSummarizer` 只要单次输出超阈值（3000 字符）就摘要，丢失首次细节、无回溯路径。本模块引入**时序驱动的归档 + 按需取回**：

1. **首次全量**：新工具结果首次入 context 时保留完整原始内容
2. **时序降级**：新工具结果进入时，把 context 里所有旧的 FULL 块就地改写为摘要
3. **可回溯**：原始输出持久化到 workspace，模型可随时通过 `get_full_tool_output` 取回
4. **差异化**：摘要复用 `ToolResultSummarizer` 的工具类型差异化策略
5. **带指引**：摘要块前缀告知 `tool_call_id` + 原始长度 + 取回方式

---

## 2. 核心不变量

任意时刻，`Agent.context` 中的 `ContentBlock.ToolResultBlock` 满足：

| 状态 | 数量 | 含义 |
|---|---|---|
| FULL | **至多 1 个** | 最新一次工具调用，保留完整原始内容 |
| SUMMARIZED | 0 或多个 | 历史工具调用，content 前缀带 `[此工具结果已摘要 ...]` |
| 无标记 | 不定 | 历史遗留数据，不误伤 |

每个有标记的块的 `toolCallId` 都能在 `ToolOutputArchive` 查到原始全量。

---

## 3. 状态标记位置

`ToolResultBlock` 字段全是 `final`（`toolCallId / content / isError`），无法加字段。
状态通过 **`Msg.metadata`** 携带：

| key | value |
|---|---|
| `toolCallId` | `"FULL"` 或 `"SUMMARIZED"` |

`Msg.metadata` 已是 `HashMap<String, Object>`（可变），`buildToolResultMsg` 不写 metadata，新增无冲突。

---

## 4. 类清单

| 类 | 角色 | 源文件 |
|---|---|---|
| `ContextToolResultArchiver` | 机制核心：compact + markAsFull | `context/ContextToolResultArchiver.java` |
| `ToolOutputArchive` | 持久化原始工具输出 | `tool/ToolOutputArchive.java` |
| `ToolResultSummarizer` | 差异化摘要（**复用，不改**） | `tool/ToolResultSummarizer.java` |
| `MicroCompactor` | 块级压缩（独立、不冲突） | `context/MicroCompactor.java` |

---

## 5. ContextToolResultArchiver API

```java
public class ContextToolResultArchiver {
    public static final String STATE_FULL = "FULL";
    public static final String STATE_SUMMARIZED = "SUMMARIZED";

    public ContextToolResultArchiver(ToolOutputArchive archive,
                                      ToolResultSummarizer summarizer);

    /** 归档实例访问（供 Agent.reply 写入原始输出）。 */
    public ToolOutputArchive archive();

    /** 新工具结果即将 add 到 context 前调用：扫描旧 FULL 块 → 改写为 SUMMARIZED。 */
    public void compactExistingToolResults(List<Msg> context);

    /** 新 tool result Msg 加入 context 后立即调用，标记为 FULL。 */
    public void markAsFull(Msg toolResultMsg, String toolCallId);
}
```

### 5.1 compact 算法

```
for each msg in context:
    for each block index where block is ToolResultBlock:
        toolCallId = block.toolCallId
        if msg.metadata.get(toolCallId) != "FULL":
            continue   # 已 SUMMARIZED 或无标记（保护历史数据）
        fullOutput = archive.getFullOutput(toolCallId)
        if fullOutput == null:
            continue   # archive 数据丢失，跳过保护
        toolName = archive.getMeta(toolCallId).toolName()
        summary = summarizer.summarize(toolName, fullOutput)
        newContent = "[此工具结果已摘要 | tool=... | tool_call_id=... | 原始 N 字符 | 如需完整内容请调用 get_full_tool_output 工具]\n" + summary
        msg.replaceBlock(index, new ToolResultBlock(toolCallId, newContent, block.isError))
        msg.metadata.put(toolCallId, "SUMMARIZED")
```

**安全保证**：
- 已 SUMMARIZED 的块不动（幂等，避免二次摘要）
- archive 数据丢失时不抛异常，保留原块（保护数据）
- 无 FULL 标记的历史数据不误伤

### 5.2 摘要前缀格式

```
[此工具结果已摘要 | tool=read_file | tool_call_id=abc123 | 原始 8916 字符 | 如需完整内容请调用 get_full_tool_output 工具]
{摘要正文，按工具类型差异化}
```

---

## 6. ToolOutputArchive

### 6.1 存储布局

```
workspace/cache/tool_outputs/
  ├─ {toolCallId}.txt          ← 原始输出
  ├─ {toolCallId2}.txt
  └─ index.json                ← 元数据索引
```

`index.json` 格式：
```json
[
  {
    "toolCallId": "abc123",
    "toolName": "read_file",
    "args": {"path": "a.txt"},
    "fullLength": 8916,
    "filePath": "workspace/cache/tool_outputs/abc123.txt"
  }
]
```

### 6.2 API

```java
public class ToolOutputArchive {
    public ToolOutputArchive(Path archiveDir);
    public void archive(String toolCallId, String toolName,
                        Map<String,Object> args, String fullOutput);
    public String getFullOutput(String toolCallId);       // null if not found
    public ArchivedMeta getMeta(String toolCallId);       // null if not found
    public Map<String, ArchivedMeta> listAll();
    public void clear();
}

public record ArchivedMeta(String toolCallId, String toolName,
                           Map<String,Object> args,
                           int fullLength, Path filePath) {}
```

### 6.3 持久化与重启

- 构造时 `Files.createDirectories(archiveDir)`，读取已有 `index.json`
- 写入：`Files.writeString` 写内容文件 + readValue→writeValue 更新 index.json
- 重启模拟：新实例指向同一目录即可读旧数据（回归测试覆盖）

### 6.4 并发

单 REPL 线程内调用，无 synchronized。多工作者并行场景下，每个 worker 有独立 archive 目录（通过 workspaceManager 隔离）。

---

## 7. Agent.reply 整合点

`Agent.reply()` 和 `Agent.replyStream()` 在工具结果入 context 前后插入三步：

```java
String resultContent = toolResult.isSuccess() ? toolResult.getOutput() : toolResult.getError();

// ① 新结果进入前：compact 旧的
if (toolResultArchiver != null && toolResult.isSuccess()) {
    toolResultArchiver.compactExistingToolResults(context);
    toolResultArchiver.archive().archive(
            toolCall.getId(), toolCall.getName(), toolArgs, resultContent);
}

ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock(
        toolCall.getId(), resultContent, !toolResult.isSuccess());
Msg toolResultMsg = buildToolResultMsg(resultBlock);
context.add(toolResultMsg);

// ② 加入后立即标记 FULL，供下一轮 compact 识别
if (toolResultArchiver != null && toolResult.isSuccess()) {
    toolResultArchiver.markAsFull(toolResultMsg, toolCall.getId());
}
```

`toolResultArchiver` 可空（未注入时退化为原行为），通过 `setToolResultArchiver(...)` 注入。

---

## 8. 装配（AgentScopeDemoApplication）

在 `registerCacheTools` 之后：

```java
// 创建归档存储
ToolOutputArchive toolOutputArchive = new ToolOutputArchive(
        workspaceDir.resolve("cache/tool_outputs"));

// 注册取回工具
mcpClient.registerCustomTool(
    "get_full_tool_output",
    "提取某个工具结果的完整原始内容（上下文中只保留了摘要时使用）",
    schema,
    toolArgs -> {
        String id = (String) toolArgs.get("tool_call_id");
        String full = archive.getFullOutput(id);
        return full != null ? full
            : "[未找到 tool_call_id=" + id + " 对应的归档输出。可用的 ID：" + archive.listAll().keySet() + "]";
    });

// 创建机制核心 + 注入 Agent
ContextToolResultArchiver archiver = new ContextToolResultArchiver(
        toolOutputArchive, mcpClient.getToolResultSummarizer());
agent.setToolResultArchiver(archiver);
```

---

## 9. get_full_tool_output 工具

| 属性 | 值 |
|---|---|
| 名称 | `get_full_tool_output` |
| Schema | `{"properties":{"tool_call_id":{"type":"string"}},"required":["tool_call_id"]}` |
| 行为 | 从 archive 读 `toolCallId` 对应原始内容；未找到返回可用 ID 列表 |
| 注册时机 | `registerCacheTools` 之后 |

---

## 10. 与 MicroCompactor 的协调

两者**职责不同，不冲突**：

| 机制 | 触发 | 操作对象 | 操作 |
|---|---|---|---|
| `ContextToolResultArchiver` | 每次新工具结果入 context | 旧的 FULL 块 | 改写为带 `tool_call_id` 的摘要，原始存 archive |
| `MicroCompactor` | tool 调用次数 > 12 | 旧 `ToolCallBlock.arguments` + `ToolResultBlock.content` | 替换为 `[REDACTED]` stub |

**协调原则**：MicroCompactor 的 stub 是"最终态"。本机制一旦发现块的 metadata 已是 SUMMARIZED，就跳过（避免二次摘要）。

---

## 11. System Prompt 增强

`buildSystemPrompt()` 追加一段全局告知：

```
## 工具结果处理规则
- 上下文中除最新一条外，所有工具结果均已被自动摘要。
- 每个摘要块都标有 tool_call_id，原始全量已归档。
- 需要查看完整原始内容时，调用 get_full_tool_output(tool_call_id=...)。
```

让模型对上下文状态有准确认知，避免信息偏差。

---

## 12. 测试

### `ToolOutputArchiveTest`（8 个）
- archive + getFullOutput 往返一致
- listAll 完整
- 未知 toolCallId 返回 null
- 重启模拟：新实例指向同目录读旧数据
- null 输出跳过
- 同 toolCallId 覆盖
- clear 清空
- args 快照不可变

### `ContextToolResultArchiverTest`（7 个）
- 无 FULL 标记的块不误伤
- 双 FULL：compact 后首个变 SUMMARIZED + 前缀正确，第二个不动
- 已 SUMMARIZED 的不二次摘要（幂等）
- archive 数据缺失时跳过改写、不抛异常
- null/空 context 安全
- markAsFull 行为正确
- null 参数安全

### 验证命令
```bash
mvn test -pl agentscope-demo -Dtest='ToolOutputArchiveTest,ContextToolResultArchiverTest'
```
