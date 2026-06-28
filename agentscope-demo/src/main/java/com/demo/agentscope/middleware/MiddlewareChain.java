package com.demo.agentscope.middleware;

import com.demo.agentscope.event.EventStream;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.permission.PermissionDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 中间件链。
 * <p>
 * 管理有序的中间件列表，并在每个hook点按顺序触发所有中间件。
 * 业务异常（BudgetExceededException、PermissionDeniedException）会穿透中间件链向上抛出。
 * </p>
 */
public class MiddlewareChain {

    private static final Logger log = LoggerFactory.getLogger(MiddlewareChain.class);

    private final List<Middleware> middlewares;

    public MiddlewareChain() {
        this.middlewares = new CopyOnWriteArrayList<>();
    }

    public void add(Middleware middleware) {
        if (middleware != null) {
            middlewares.add(middleware);
            log.debug("中间件已添加: {}", middleware.getClass().getSimpleName());
        }
    }

    public void remove(Middleware middleware) {
        if (middleware != null) {
            middlewares.remove(middleware);
            log.debug("中间件已移除: {}", middleware.getClass().getSimpleName());
        }
    }

    public int size() {
        return middlewares.size();
    }

    /**
     * 判断异常是否为业务异常，需要穿透中间件链向上抛出。
     */
    private boolean isBusinessException(Exception e) {
        return e instanceof BudgetExceededException || e instanceof PermissionDeniedException;
    }

    public void fireReplyStart(AgentContext ctx) {
        for (Middleware mw : middlewares) {
            try {
                mw.onReplyStart(ctx);
            } catch (Exception e) {
                if (isBusinessException(e)) throw (RuntimeException) e;
                log.warn("中间件 [{}] onReplyStart 执行异常", mw.getClass().getSimpleName(), e);
            }
        }
    }

    public void fireModelCall(AgentContext ctx, Msg request) {
        for (Middleware mw : middlewares) {
            try {
                mw.onModelCall(ctx, request);
            } catch (Exception e) {
                if (isBusinessException(e)) throw (RuntimeException) e;
                log.warn("中间件 [{}] onModelCall 执行异常", mw.getClass().getSimpleName(), e);
            }
        }
    }

    public void fireModelCallEnd(AgentContext ctx, Msg response) {
        for (Middleware mw : middlewares) {
            try {
                mw.onModelCallEnd(ctx, response);
            } catch (Exception e) {
                if (isBusinessException(e)) throw (RuntimeException) e;
                log.warn("中间件 [{}] onModelCallEnd 执行异常", mw.getClass().getSimpleName(), e);
            }
        }
    }

    public void fireToolCall(AgentContext ctx, ContentBlock.ToolCallBlock toolCall) {
        for (Middleware mw : middlewares) {
            try {
                mw.onToolCall(ctx, toolCall);
            } catch (Exception e) {
                if (isBusinessException(e)) throw (RuntimeException) e;
                log.warn("中间件 [{}] onToolCall 执行异常", mw.getClass().getSimpleName(), e);
            }
        }
    }

    public void fireToolResult(AgentContext ctx, ContentBlock.ToolResultBlock result) {
        for (Middleware mw : middlewares) {
            try {
                mw.onToolResult(ctx, result);
            } catch (Exception e) {
                if (isBusinessException(e)) throw (RuntimeException) e;
                log.warn("中间件 [{}] onToolResult 执行异常", mw.getClass().getSimpleName(), e);
            }
        }
    }

    public void fireReplyEnd(AgentContext ctx, EventStream stream) {
        for (Middleware mw : middlewares) {
            try {
                mw.onReplyEnd(ctx, stream);
            } catch (Exception e) {
                if (isBusinessException(e)) throw (RuntimeException) e;
                log.warn("中间件 [{}] onReplyEnd 执行异常", mw.getClass().getSimpleName(), e);
            }
        }
    }
}
