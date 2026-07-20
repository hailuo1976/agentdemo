package com.demo.agentscope.message;

import com.demo.agentscope.session.SessionEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * {@link ContentBlock} 与 {@link SessionEntry.BlockDto} 之间的双向编解码器。
 * <p>
 * 抽自原 {@code SessionLogger.toBlockDto} 和 {@code SessionRecovery.fromBlockDto}，
 * 供 SessionLogger / SessionRecovery / ConversationStore 三处共用，保证 6 种块类型
 * 的序列化口径一致。
 * </p>
 * <p>
 * 兜底语义：未识别的块类型 forward 时落成 {@code TextBlock.TYPE + block.toString()}，
 * reverse 时返回 {@code null}（由调用方决定跳过或报错）。
 * </p>
 */
public final class ContentBlockCodec {

    private static final Logger log = LoggerFactory.getLogger(ContentBlockCodec.class);

    private ContentBlockCodec() {}

    /**
     * 批量 {@link ContentBlock} → {@link SessionEntry.BlockDto}。
     */
    public static List<SessionEntry.BlockDto> toBlockDtos(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return new ArrayList<>();
        }
        List<SessionEntry.BlockDto> result = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            result.add(toBlockDto(block));
        }
        return result;
    }

    /**
     * 单个 {@link ContentBlock} → {@link SessionEntry.BlockDto}。
     * 未识别类型落为 text + toString 兜底（保持向前兼容）。
     */
    public static SessionEntry.BlockDto toBlockDto(ContentBlock block) {
        if (block instanceof ContentBlock.TextBlock t) {
            return new SessionEntry.BlockDto(
                    ContentBlock.TextBlock.TYPE, t.getText(),
                    null, null, null, null, null, null, null);
        }
        if (block instanceof ContentBlock.ToolCallBlock tc) {
            return new SessionEntry.BlockDto(
                    ContentBlock.ToolCallBlock.TYPE, null,
                    tc.getId(), tc.getName(), tc.getArguments(),
                    null, null, null, null);
        }
        if (block instanceof ContentBlock.ToolResultBlock tr) {
            return new SessionEntry.BlockDto(
                    ContentBlock.ToolResultBlock.TYPE, null,
                    tr.getToolCallId(), null, null,
                    tr.getContent(), tr.isError(), null, null);
        }
        if (block instanceof ContentBlock.ThinkingBlock tb) {
            return new SessionEntry.BlockDto(
                    ContentBlock.ThinkingBlock.TYPE, tb.getText(),
                    null, null, null, null, null, null, null);
        }
        if (block instanceof ContentBlock.HintBlock hb) {
            return new SessionEntry.BlockDto(
                    ContentBlock.HintBlock.TYPE, hb.getText(),
                    null, null, null, null, null, null, null);
        }
        if (block instanceof ContentBlock.DataBlock db) {
            String b64 = db.getData() != null
                    ? Base64.getEncoder().encodeToString(db.getData()) : null;
            return new SessionEntry.BlockDto(
                    ContentBlock.DataBlock.TYPE, null,
                    null, null, null, null, null, db.getMimeType(), b64);
        }
        // 兜底：未识别的块类型，记类型 + toString
        return new SessionEntry.BlockDto(
                block.getType(), block.toString(),
                null, null, null, null, null, null, null);
    }

    /**
     * 批量 {@link SessionEntry.BlockDto} → {@link ContentBlock}；null 元素自动跳过。
     */
    public static List<ContentBlock> fromBlockDtos(List<SessionEntry.BlockDto> blocks) {
        List<ContentBlock> result = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) {
            return result;
        }
        for (SessionEntry.BlockDto b : blocks) {
            ContentBlock block = fromBlockDto(b);
            if (block != null) {
                result.add(block);
            }
        }
        return result;
    }

    /**
     * 单个 {@link SessionEntry.BlockDto} → {@link ContentBlock}，按 {@code type} 判别。
     * 未识别类型返回 null（并打 warn 日志）。
     */
    public static ContentBlock fromBlockDto(SessionEntry.BlockDto b) {
        if (b == null || b.type() == null) {
            return null;
        }
        return switch (b.type()) {
            case ContentBlock.TextBlock.TYPE -> new ContentBlock.TextBlock(
                    b.text() != null ? b.text() : "");
            case ContentBlock.ToolCallBlock.TYPE -> new ContentBlock.ToolCallBlock(
                    b.toolCallId() != null ? b.toolCallId() : UUID.randomUUID().toString(),
                    b.toolName() != null ? b.toolName() : "unknown",
                    b.arguments() != null ? b.arguments() : "{}");
            case ContentBlock.ToolResultBlock.TYPE -> new ContentBlock.ToolResultBlock(
                    b.toolCallId() != null ? b.toolCallId() : UUID.randomUUID().toString(),
                    b.content() != null ? b.content() : "",
                    b.isError() != null && b.isError());
            case ContentBlock.ThinkingBlock.TYPE -> new ContentBlock.ThinkingBlock(
                    b.text() != null ? b.text() : "");
            case ContentBlock.HintBlock.TYPE -> new ContentBlock.HintBlock(
                    b.text() != null ? b.text() : "");
            case ContentBlock.DataBlock.TYPE -> {
                String mt = b.mimeType() != null ? b.mimeType() : "application/octet-stream";
                byte[] data = b.dataBase64() != null
                        ? Base64.getDecoder().decode(b.dataBase64()) : new byte[0];
                yield new ContentBlock.DataBlock(mt, data);
            }
            default -> {
                log.warn("未识别的 BlockDto type: {}", b.type());
                yield null;
            }
        };
    }
}
