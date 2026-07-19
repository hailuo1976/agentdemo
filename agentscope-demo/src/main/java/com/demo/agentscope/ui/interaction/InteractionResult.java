package com.demo.agentscope.ui.interaction;

import java.util.List;

/**
 * 交互式工具执行结果。
 * <p>
 * 表示一次 {@code ask_user} 调用的结果，由 {@link UserInteractionToolExecutor} 构造。
 * 通过 {@link #toJson()} 序列化为 JSON 字符串作为 ToolResult 返回给模型。
 * </p>
 *
 * @param status       状态：{@code ok} / {@code cancelled} / {@code other} / {@code error}
 * @param mode         触发时的模式：choice / multi / fill / confirm
 * @param value        规范化后的值（选项原文、填空内容、yes/no）
 * @param raw          用户原始输入（Other 场景下可能与 value 不同）
 * @param indices      选中项的索引列表（choice/multi 场景；其它模式为空）
 * @param errorMessage 错误场景下的说明（其它场景为 null）
 */
public record InteractionResult(
        String status,
        String mode,
        String value,
        String raw,
        List<Integer> indices,
        String errorMessage
) {

    public static InteractionResult ok(String mode, String value, String raw, List<Integer> indices) {
        return new InteractionResult("ok", mode, value, raw, indices, null);
    }

    public static InteractionResult other(String mode, String rawInput) {
        return new InteractionResult("other", mode, rawInput, rawInput, List.of(), null);
    }

    public static InteractionResult cancelled(String mode, String reason) {
        return new InteractionResult("cancelled", mode, null, reason, List.of(), null);
    }

    public static InteractionResult error(String message) {
        return new InteractionResult("error", null, null, null, List.of(), message);
    }

    /** 序列化为 JSON 字符串（不带外层换行）。 */
    public String toJson() {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        appendKv(sb, "status", status, false);
        if (mode != null) appendKv(sb, "mode", mode, true);
        if (value != null) appendKv(sb, "value", value, true);
        if (raw != null) appendKv(sb, "raw", raw, true);
        if (indices != null && !indices.isEmpty()) {
            sb.append(",\"indices\":[");
            for (int i = 0; i < indices.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(indices.get(i));
            }
            sb.append(']');
        }
        if (errorMessage != null) appendKv(sb, "error", errorMessage, true);
        sb.append('}');
        return sb.toString();
    }

    private static void appendKv(StringBuilder sb, String k, String v, boolean withComma) {
        if (withComma) sb.append(',');
        sb.append('"').append(k).append("\":\"").append(escape(v)).append('"');
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
