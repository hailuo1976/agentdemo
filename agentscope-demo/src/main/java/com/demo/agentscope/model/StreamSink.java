package com.demo.agentscope.model;

import com.demo.agentscope.event.AgentEvent;

/**
 * 流式 SSE 帧的实时下沉接口。
 * <p>
 * 由调用方（Agent.replyStream）提供实现，在 ChatModel 同步读取 SSE 流的过程中，
 * 每解析出一个 TEXT_DELTA / THINKING_DELTA / TOOL_CALL / MODEL_CALL_END 事件，
 * 就立即通过 {@link #onEvent(AgentEvent)} 上推。
 * <p>
 * 这样确保了 token 级实时回显 —— 控制台渲染和事件累积与 SSE 读取交替进行，
 * 而不是先全部读完再回放。
 */
@FunctionalInterface
public interface StreamSink {

    /**
     * 接收一个流式事件并立即处理（如打印到控制台、累积到缓冲区）。
     *
     * @param event 流式事件
     */
    void onEvent(AgentEvent event);
}
