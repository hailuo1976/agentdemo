# AgentScope Demo 智能体改进方案

## 1. 现状分析

### 1.1 当前架构
基于 `agent_improvement.md` 的分析，当前 Agent 系统的核心问题：

| 问题类型 | 具体表现 | 影响 |
|---------|---------|------|
| **上下文窗口限制** | 对话过长后早期内容被截断 | 丢失关键历史信息 |
| **工具结果膨胀** | 大量数据（如5000+字符的财务数据）占用空间 | 上下文被"淹没" |
| **无持久记忆** | 新对话从零开始 | 无法复用历史知识 |
| **中间结果不持久** | 无法存储计算中间结果 | 重复计算浪费资源 |
| **信息误导风险** | 原始数据缺乏标注（如除权标识） | 导致错误判断 |

### 1.2 已有能力
- ✅ 基础 ReAct 循环（Agent.reply）
- ✅ 工具调用机制（MCPClient）
- ✅ 权限控制（PermissionEngine）
- ✅ 文件操作（read_file/write_file）
- ✅ 代码执行（execute_python/execute_command）
- ✅ 团队模式（AgentTeam）
- ✅ 基础上下文压缩（ContextCompressionMiddleware）

## 2. 改进目标

### 2.1 核心目标
1. **扩展记忆能力**：支持短期、长期、结构化记忆
2. **优化上下文管理**：智能压缩、摘要、选择性加载
3. **增强工具能力**：结果摘要、中间结果存储
4. **提升协作效率**：团队间知识共享、任务分解优化

### 2.2 非目标
- 不改变现有 API 接口（保持向后兼容）
- 不引入重量级外部依赖（如独立数据库服务）
- 不破坏现有测试用例

## 3. 改进方案设计

### 3.1 记忆系统（Memory System）

#### 3.1.1 三层记忆架构

```
┌─────────────────────────────────────┐
│   工作记忆 (Working Memory)         │  ← 当前对话上下文
│   - 最近 N 轮对话                    │
│   - 当前任务状态                     │
│   - 临时计算结果                     │
└─────────────────────────────────────┘
              ↓ 压缩/摘要
┌─────────────────────────────────────┐
│   短期记忆 (Short-term Memory)      │  ← 会话级持久化
│   - 对话摘要（JSON）                 │
│   - 关键实体提取                     │
│   - 任务执行历史                     │
│   - 存储位置：workspace/memory/      │
└─────────────────────────────────────┘
              ↓ 重要信息提取
┌─────────────────────────────────────┐
│   长期记忆 (Long-term Memory)       │  ← 跨会话持久化
│   - 用户偏好                         │
│   - 领域知识库                       │
│   - 成功案例库                       │
│   - 存储位置：workspace/knowledge/   │
└─────────────────────────────────────┘
```

#### 3.1.2 实现方案

**新增类：**
- `MemoryManager` - 记忆管理器（统一接口）
- `WorkingMemory` - 工作记忆（内存）
- `ShortTermMemory` - 短期记忆（文件存储）
- `LongTermMemory` - 长期记忆（文件存储 + 索引）
- `MemoryEntry` - 记忆条目数据结构
- `MemorySearcher` - 记忆检索器（基于关键词 + 时间衰减）

**核心接口：**
```java
public interface MemoryManager {
    // 存储记忆
    void store(String key, Object value, MemoryType type);
    
    // 检索记忆
    List<MemoryEntry> recall(String query, MemoryType type, int limit);
    
    // 压缩记忆（将工作记忆转为短期记忆）
    void consolidate();
    
    // 提取重要信息（将短期记忆转为长期记忆）
    void extractImportant();
    
    // 清除过期记忆
    void cleanup(Duration retention);
}
```

**存储格式：**
```json
{
  "id": "mem_20260630_001",
  "type": "short_term",
  "timestamp": "2026-06-30T00:15:00Z",
  "content": {
    "summary": "分析了300502股票，发现除权导致价格跳变",
    "entities": ["300502", "除权", "价格跳变"],
    "task_context": "股票分析",
    "key_findings": ["6月11日为除权日", "前复权后价格连续"]
  },
  "importance": 0.85,
  "access_count": 3,
  "last_accessed": "2026-06-30T00:20:00Z"
}
```

**集成方式：**
- 在 `Agent.reply()` 开始时加载相关记忆
- 在 `Agent.reply()` 结束时保存新记忆
- 通过中间件 `MemoryMiddleware` 自动触发记忆压缩

#### 3.1.3 预期效果
- 跨会话保持用户偏好和历史知识
- 减少重复计算（复用历史分析结果）
- 提升长对话的连贯性

### 3.2 智能上下文管理

#### 3.2.1 动态上下文窗口

**当前问题：**
- 固定保留最近 N 条消息（简单但粗糙）
- 无法区分重要/不重要信息

**改进方案：**
```
┌─────────────────────────────────────┐
│  系统提示词（始终保留）              │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│  压缩摘要（重要历史）                │  ← LLM 生成
│  - 任务目标                         │
│  - 关键发现                         │
│  - 待办事项                         │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│  相关记忆（按需加载）                │  ← 从记忆系统检索
│  - 与当前话题相关的历史对话          │
│  - 相关领域知识                     │
└─────────────────────────────────────┘
┌─────────────────────────────────────┐
│  最近 N 轮对话（完整保留）           │
└─────────────────────────────────────┘
```

**实现方案：**

**新增类：**
- `ContextManager` - 上下文管理器（替代简单 List）
- `ContextSelector` - 上下文选择器（决定保留哪些消息）
- `ContextSummarizer` - 上下文摘要器（调用 LLM 生成摘要）

**核心逻辑：**
```java
public class ContextManager {
    private final List<Msg> recentMessages;  // 最近消息
    private final MemoryManager memory;       // 记忆系统
    private final ContextSummarizer summarizer;
    
    public List<Msg> buildContext(String currentQuery) {
        List<Msg> context = new ArrayList<>();
        
        // 1. 添加系统提示词
        context.add(systemPrompt);
        
        // 2. 添加压缩摘要（如果有）
        if (hasCompressedSummary()) {
            context.add(getCompressedSummary());
        }
        
        // 3. 检索相关记忆
        List<MemoryEntry> relevantMemories = memory.recall(
            currentQuery, 
            MemoryType.SHORT_TERM, 
            5
        );
        for (MemoryEntry mem : relevantMemories) {
            context.add(formatMemoryAsMessage(mem));
        }
        
        // 4. 添加最近消息
        context.addAll(recentMessages);
        
        // 5. 如果总 token 数超限，触发压缩
        if (estimateTokens(context) > MAX_TOKENS) {
            context = compressContext(context);
        }
        
        return context;
    }
}
```

**压缩策略：**
1. **工具结果压缩**：大段数据自动摘要（如"返回了100条股票数据，包含价格、成交量等"）
2. **对话轮次压缩**：超过阈值时调用 LLM 生成摘要
3. **选择性保留**：优先保留包含"关键发现"、"错误修正"的消息

#### 3.2.2 工具结果摘要

**当前问题：**
- `execute_python` 返回的完整数据直接进入上下文
- 5000+ 字符的财务数据占用大量空间

**改进方案：**
```java
// 在 MCPClient 中添加工具结果后处理
public ToolResult executeTool(String toolName, Map<String, Object> args) {
    ToolResult result = executeToolInternal(toolName, args);
    
    // 对大结果进行摘要
    if (result.getOutput().length() > 2000) {
        String summary = summarizeToolResult(toolName, result.getOutput());
        result.setSummary(summary);
    }
    
    return result;
}

private String summarizeToolResult(String toolName, String output) {
    // 根据工具类型选择摘要策略
    if (toolName.equals("execute_python")) {
        return summarizePythonOutput(output);
    } else if (toolName.equals("read_file")) {
        return summarizeFileContent(output);
    }
    // ...
}
```

**摘要策略：**
- **数值数据**：提取统计信息（均值、最大、最小、趋势）
- **文本数据**：提取关键词 + 前 500 字符
- **结构化数据**：提取字段列表 + 样本数据

#### 3.2.3 预期效果
- 上下文利用率提升 3-5 倍
- 长对话保持关键信息不丢失
- 减少 LLM 调用成本（摘要比完整数据便宜）

### 3.3 中间结果管理

#### 3.3.1 计算结果缓存

**当前问题：**
- 每次对话重新计算所有数据
- 无法复用历史计算结果

**改进方案：**
```java
// 新增工具：save_intermediate / load_intermediate
public class IntermediateResultManager {
    private final Path cacheDir;
    
    public void save(String key, Object value, Duration ttl) {
        CacheEntry entry = new CacheEntry(key, value, ttl);
        Files.writeString(
            cacheDir.resolve(key + ".json"),
            objectMapper.writeValueAsString(entry)
        );
    }
    
    public Optional<CacheEntry> load(String key) {
        Path file = cacheDir.resolve(key + ".json");
        if (!Files.exists(file)) return Optional.empty();
        
        CacheEntry entry = objectMapper.readValue(
            Files.readString(file), 
            CacheEntry.class
        );
        
        // 检查是否过期
        if (entry.isExpired()) {
            Files.delete(file);
            return Optional.empty();
        }
        
        return Optional.of(entry);
    }
}
```

**新增工具：**
- `save_result` - 保存中间结果到文件
- `load_result` - 加载历史结果
- `list_results` - 列出所有可用结果

**使用示例：**
```
用户：分析300502的K线数据
Agent：
  1. 调用 execute_python 获取数据
  2. 调用 save_result 保存为 "300502_kline_20260630"
  3. 返回分析结果

用户：（第二天）继续分析300502
Agent：
  1. 调用 load_result 检查是否有历史数据
  2. 如果有，直接使用（节省 API 调用）
  3. 如果没有，重新获取
```

#### 3.3.2 预期效果
- 减少重复计算 50%+
- 支持跨会话任务续作
- 降低 API 调用成本

### 3.4 团队模式优化

#### 3.4.1 团队知识共享

**当前问题：**
- 工作者之间无法共享知识
- 领导者需要手动传递信息

**改进方案：**
```java
public class AgentTeam {
    private final SharedKnowledgeBase knowledgeBase;
    
    // 工作者完成任务后自动保存知识
    public Msg sendMessageToWorker(String workerName, String message) {
        Msg reply = worker.reply(message);
        
        // 提取关键发现并保存到共享知识库
        List<String> findings = extractKeyFindings(reply);
        knowledgeBase.add(workerName, findings);
        
        return reply;
    }
    
    // 领导者可以查询共享知识
    public String getTeamKnowledge(String query) {
        return knowledgeBase.search(query);
    }
}
```

**共享知识库结构：**
```json
{
  "team_id": "team_001",
  "knowledge": [
    {
      "contributor": "研究员A",
      "timestamp": "2026-06-30T00:15:00Z",
      "content": "300502在6月11日除权，前复权后价格连续",
      "tags": ["300502", "除权", "价格调整"],
      "confidence": 0.95
    }
  ]
}
```

#### 3.4.2 任务分解优化

**当前问题：**
- 领导者可能创建过多工作者（资源浪费）
- 任务分配不合理（如让一个工作者做所有事）

**改进方案：**
```java
// 在领导者系统提示词中增加任务分解指导
String leaderPrompt = """
你是团队领导者。在分配任务前，请先：
1. 分析任务复杂度（简单/中等/复杂）
2. 确定需要的工作者数量和角色
3. 明确每个工作者的具体职责和交付物

任务分解原则：
- 简单任务：直接自己处理，不创建工作者
- 中等任务：创建 1-2 个工作者
- 复杂任务：创建 3-5 个工作者，明确分工

示例：
用户：分析A股AI相关股票
错误做法：创建10个工作者，每个分析一只股票
正确做法：创建3个工作者（光模块、芯片、应用），每个负责一个细分领域
""";
```

#### 3.4.3 预期效果
- 团队资源利用率提升
- 减少冗余任务
- 提升协作效率

### 3.5 数据标注与元数据

#### 3.5.1 工具结果元数据

**当前问题：**
- 工具返回的原始数据缺乏上下文（如"除权"标识）
- 容易导致误读

**改进方案：**
```java
// 增强 ToolResult 结构
public class ToolResult {
    private final String output;
    private final Map<String, Object> metadata;  // 新增
    
    public ToolResult(String output, Map<String, Object> metadata) {
        this.output = output;
        this.metadata = metadata;
    }
}

// 在 execute_python 中自动添加元数据
public ToolResult executePython(String code) {
    String output = process.execute(code);
    
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("execution_time", process.getDuration());
    metadata.put("data_type", detectDataType(output));  // "stock_data", "financial_report", etc.
    metadata.put("warnings", detectWarnings(output));   // ["contains_dividend_adjustment"]
    
    return new ToolResult(output, metadata);
}
```

**元数据示例：**
```json
{
  "execution_time": 2.3,
  "data_type": "stock_kline",
  "warnings": [
    "contains_dividend_adjustment",
    "data_range: 2025-01-01 to 2026-06-30"
  ],
  "statistics": {
    "rows": 350,
    "columns": ["date", "open", "high", "low", "close", "volume"]
  }
}
```

#### 3.5.2 预期效果
- 减少数据误读
- 提升分析准确性
- 便于后续处理

## 4. 实现优先级

### 4.1 阶段一（P0 - 核心能力）
**目标：解决最紧迫的上下文管理问题**

| 功能 | 优先级 | 预计工作量 | 依赖 |
|-----|-------|-----------|------|
| 工具结果摘要 | P0 | 2天 | 无 |
| 基础记忆系统（短期） | P0 | 3天 | 无 |
| 中间结果缓存 | P0 | 2天 | 无 |

**交付物：**
- `ToolResultSummarizer` 类
- `ShortTermMemory` 类
- `IntermediateResultManager` 类
- 对应的单元测试
- 集成到 `Agent.reply()` 流程

### 4.2 阶段二（P1 - 增强能力）
**目标：提升长对话和跨会话能力**

| 功能 | 优先级 | 预计工作量 | 依赖 |
|-----|-------|-----------|------|
| 智能上下文管理 | P1 | 3天 | 阶段一 |
| 长期记忆系统 | P1 | 3天 | 阶段一 |
| 团队知识共享 | P1 | 2天 | 阶段一 |

**交付物：**
- `ContextManager` 类（替代简单 List）
- `LongTermMemory` 类
- `SharedKnowledgeBase` 类
- 对应的单元测试

### 4.3 阶段三（P2 - 优化体验）
**目标：提升易用性和效率**

| 功能 | 优先级 | 预计工作量 | 依赖 |
|-----|-------|-----------|------|
| 数据标注与元数据 | P2 | 2天 | 阶段一 |
| 任务分解优化 | P2 | 1天 | 阶段二 |
| 记忆检索优化 | P2 | 2天 | 阶段二 |

**交付物：**
- 元数据增强
- 优化的系统提示词
- 记忆检索性能优化

## 5. 技术细节

### 5.1 文件存储结构
```
workspace/
├── memory/
│   ├── short_term/
│   │   ├── session_20260630_001.json
│   │   └── session_20260630_002.json
│   └── long_term/
│       ├── user_preferences.json
│       └── domain_knowledge.json
├── cache/
│   ├── intermediate/
│   │   ├── 300502_kline_20260630.json
│   │   └── financial_analysis_20260630.json
│   └── tool_results/
│       └── ...
└── knowledge/
    └── team/
        └── team_001_knowledge.json
```

### 5.2 配置项
```yaml
# application.yml
agent:
  memory:
    enabled: true
    short_term_retention: 7d
    long_term_retention: 90d
    max_entries: 1000
  
  context:
    max_tokens: 4000
    max_recent_messages: 10
    enable_summarization: true
  
  cache:
    enabled: true
    intermediate_ttl: 24h
    max_cache_size: 100MB
```

### 5.3 性能考虑
- **记忆检索**：使用内存索引 + 文件存储，检索延迟 < 100ms
- **上下文压缩**：异步执行，不阻塞主流程
- **缓存清理**：后台线程定期清理过期数据

## 6. 测试计划

### 6.1 单元测试
- `MemoryManagerTest` - 记忆存储/检索/压缩
- `ContextManagerTest` - 上下文构建/压缩
- `IntermediateResultManagerTest` - 缓存保存/加载/过期

### 6.2 集成测试
- 长对话测试（50+ 轮）
- 跨会话记忆测试
- 团队协作测试

### 6.3 性能测试
- 记忆检索延迟
- 上下文压缩耗时
- 缓存命中率

## 7. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|-----|------|---------|
| 记忆系统引入额外延迟 | 用户体验下降 | 异步加载、缓存热点记忆 |
| 文件存储性能瓶颈 | 检索变慢 | 使用内存索引、定期归档 |
| 记忆质量不可控 | 引入噪声 | 重要性评分、定期清理 |
| 向后兼容性问题 | 破坏现有功能 | 渐进式迁移、保留旧接口 |

## 8. 成功指标

### 8.1 功能指标
- [ ] 支持跨会话记忆（新对话能引用历史信息）
- [ ] 长对话保持关键信息（50+ 轮不丢失核心发现）
- [ ] 工具结果自动摘要（减少 50%+ 上下文占用）
- [ ] 中间结果缓存（复用率 > 30%）

### 8.2 性能指标
- [ ] 记忆检索延迟 < 100ms
- [ ] 上下文压缩耗时 < 500ms
- [ ] 缓存命中率 > 30%

### 8.3 体验指标
- [ ] 用户满意度提升（减少重复提问）
- [ ] 分析准确性提升（减少误读）
- [ ] 资源消耗降低（减少 API 调用）

## 9. 后续演进

### 9.1 向量数据库集成（P3）
- 引入轻量级向量数据库（如 ChromaDB）
- 支持语义检索（不仅仅是关键词匹配）
- 提升记忆检索准确性

### 9.2 多模态记忆（P3）
- 支持图片、图表记忆
- 支持音频记忆
- 统一多模态检索

### 9.3 自适应学习（P4）
- 根据用户反馈调整记忆重要性
- 自动优化上下文压缩策略
- 个性化记忆管理

## 10. 总结

本方案通过引入**记忆系统**、**智能上下文管理**、**中间结果缓存**三大核心能力，系统性解决当前 Agent 的上下文限制问题。

**核心价值：**
1. 扩展记忆能力 - 从"金鱼记忆"到"大象不忘"
2. 优化资源利用 - 减少重复计算，降低成本
3. 提升用户体验 - 更连贯、更智能的对话

**实施路径：**
- 阶段一（P0）：7天，解决最紧迫问题
- 阶段二（P1）：8天，增强核心能力
- 阶段三（P2）：5天，优化体验细节

**总预计工作量：20天**
