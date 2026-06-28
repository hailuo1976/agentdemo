package com.demo.agentscope.permission;

import com.demo.agentscope.event.AgentEvent;
import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.middleware.AgentContext;
import com.demo.agentscope.middleware.Middleware;
import com.demo.agentscope.message.ContentBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 权限中间件。
 * <p>
 * 实现 Middleware 接口，在工具调用前通过 PermissionEngine 执行权限检查。
 * 根据检查结果执行不同策略：
 * <ul>
 *   <li>ALLOW - 放行，工具正常执行</li>
 *   <li>DENY - 抛出 PermissionDeniedException，中断工具执行</li>
 *   <li>ASK - 发射 PERMISSION_ASK 事件，本演示中自动拒绝
 *       （实际应用中应暂停等待人工确认）</li>
 * </ul>
 * </p>
 */
public class PermissionMiddleware implements Middleware {

    private static final Logger log = LoggerFactory.getLogger(PermissionMiddleware.class);

    /** JSON 解析器 */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 权限引擎 */
    private final PermissionEngine engine;

    public PermissionMiddleware(PermissionEngine engine) {
        this.engine = engine != null ? engine : new PermissionEngine();
    }

    @Override
    public void onToolCall(AgentContext ctx, ContentBlock.ToolCallBlock toolCall) {
        String toolName = toolCall.getName();

        // 从工具调用参数中解析参数映射
        Map<String, Object> args = parseArguments(toolCall.getArguments());

        // 执行权限检查
        PermissionDecision decision = engine.check(toolName, args);

        log.debug("权限中间件检查: toolName={}, decision={}", toolName, decision);

        switch (decision) {
            case ALLOW -> {
                // 允许执行，放行
                log.debug("权限中间件放行: toolName={}", toolName);
            }
            case DENY -> {
                // 拒绝执行，抛出异常
                String reason = "权限引擎判定拒绝";
                log.warn("权限中间件拒绝: toolName={}, reason={}", toolName, reason);
                throw new PermissionDeniedException(toolName, reason);
            }
            case ASK -> {
                // 需要人工确认
                String reason = "权限引擎需要人工确认";
                log.info("权限中间件询问: toolName={}, reason={}", toolName, reason);

                // 发射权限询问事件
                emitPermissionAskEvent(ctx, toolName, reason);

                // 演示环境下自动拒绝（实际应用中应暂停等待人工确认）
                log.warn("演示环境自动拒绝 ASK 决策: toolName={}", toolName);
                throw new PermissionDeniedException(toolName, "需要人工确认（演示环境自动拒绝）");
            }
        }
    }

    /**
     * 发射权限询问事件到事件流。
     *
     * @param ctx      智能体上下文
     * @param toolName 工具名称
     * @param reason   询问原因
     */
    private void emitPermissionAskEvent(AgentContext ctx, String toolName, String reason) {
        // 尝试从上下文中获取事件流
        EventStream stream = (EventStream) ctx.getAttribute("eventStream");
        if (stream != null) {
            AgentEvent event = AgentEvent.permissionAsk(ctx.getAgentId(), toolName, reason);
            stream.emit(event);
            log.debug("权限询问事件已发射: toolName={}", toolName);
        } else {
            log.debug("未找到事件流，跳过权限询问事件发射");
        }
    }

    /**
     * 解析工具调用参数字符串为参数映射。
     * <p>
     * 简易解析：如果参数为空则返回空映射。
     * 实际应用中可使用 Jackson 等库进行 JSON 解析。
     * </p>
     *
     * @param arguments 参数字符串
     * @return 参数映射
     */
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new HashMap<>();
        }

        String trimmed = arguments.trim();

        // 优先尝试 Jackson JSON 解析
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                return objectMapper.readValue(trimmed, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.debug("JSON 参数解析失败，尝试简易解析: {}", arguments);
            }
        }

        // 回退：简易 key=value 解析
        Map<String, Object> args = new HashMap<>();
        args.put("raw", arguments);
        return args;
    }

    /**
     * 获取内部权限引擎引用。
     *
     * @return 权限引擎
     */
    public PermissionEngine getEngine() {
        return engine;
    }
}
