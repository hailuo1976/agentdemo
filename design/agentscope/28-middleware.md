# 28 中间件模块（middleware）

> 包：`com.demo.agentscope.middleware`
> 主接口：`Middleware` + `MiddlewareChain`
> 内置：`TracingMiddleware` / `ContextCompressionMiddleware` / `PermissionMiddleware` / `ReplyBudgetControlMiddleware`

---

## 1. 模型：洋葱式钩子链

```
请求 →  onReplyStart
        onModelCall
        [chatModel.chat]
        onModelCallEnd
        onToolCall
        [mcpClient.executeTool]
        onToolResult
        onReplyEnd    ← 出口
响应 ←
```

中间件按声明顺序串成"洋葱"，每层可在进入/退出两侧做事。

---

## 2. 6 个钩子

| 钩子 | 时机 | 典型用途 |
|---|---|---|
| `onReplyStart(ctx)` | reply 入口 | 初始化计数、预算重置 |
| `onModelCall(ctx, req)` | 调用 LLM 前 | 日志、限流 |
| `onModelCallEnd(ctx, resp)` | LLM 返回后 | token 统计、预算累计 |
| `onToolCall(ctx, tc)` | 调用工具前 | 权限检查、审计 |
| `onToolResult(ctx, block)` | 工具返回后 | 日志、截断 |
| `onReplyEnd(ctx, stream)` | reply 出口 | 上下文压缩、报告 |

---

## 3. AgentContext（钩子载体）

```
class AgentContext {
    String agentId;
    String sessionId;
    String channel;
    long   replyStartTime;
    Map<String, Object> attributes;   // 自由扩展
}
```

中间件通过 `attributes` 传递数据（例如 `Agent.reply` 设置 `contextMessages` 供 CompressionMiddleware 读取）。

---

## 4. 内置 4 个中间件

### 4.1 TracingMiddleware
- 6 钩子全打日志
- 级别 INFO（关键节点）/ DEBUG（细节）

### 4.2 ContextCompressionMiddleware
- 触发：`onReplyEnd`
- 阈值：`DEFAULT_THRESHOLD = 40` 条消息
- 保留：`DEFAULT_KEEP_RECENT = 10` 条
- 压缩格式（结构化）：
  ```
  task_goal: ...
  current_state: ...
  key_findings: ...
  next_steps: ...
  ```
- 读 `ctx.getAttribute("contextMessages")` 拿到真实 context

### 4.3 PermissionMiddleware
- 触发：`onToolCall`
- 调用 `PermissionEngine.check(name, args)`
- 与 `Agent.reply` 内部的 check 是**两道**检查（详见 `26-permission.md` § 8）

### 4.4 ReplyBudgetControlMiddleware
- 触发：`onModelCallEnd`（累计 token）
- 默认预算：500_000（env `REPLY_BUDGET` 覆盖）
- **2026-06 改动**：超限不再抛 `BudgetExceededException`，改为：
  - 发 `ReplyBudgetExceeded` 事件
  - log.warn 告警
  - 让 Agent.maxIterations 当兜底
- 原因：复杂多轮任务容易触发，硬中断让用户拿不到部分结果

---

## 5. MiddlewareChain

- `add(Middleware)` 按声明顺序
- `fire*` 方法按顺序调用每个中间件的对应钩子
- 一个中间件抛异常不影响其他（实现中 try/catch 包装）

---

## 6. 与 pimono 的差异

pimono **无**中间件抽象——所有横切逻辑只能写死在 `AgentCore.chat` 内。
agentscope 把可观察性、限流、权限、压缩都拆成可插拔层。

---

## 7. 扩展新中间件

```java
public class MyMiddleware implements Middleware {
    @Override
    public void onModelCallEnd(AgentContext ctx, Msg response) {
        // 自定义逻辑
    }
    // 其他钩子默认空实现
}

// main 中：
agent.getMiddlewareChain().add(new MyMiddleware());
```

---

## 8. 测试

- `MiddlewareChainTest`：钩子顺序
- `ContextCompressionTest`：阈值触发、压缩内容结构
- `ReplyBudgetControlTest`：累计、超限、env 覆盖
- `PermissionMiddlewareTest`：与 PermissionEngine 集成
