package com.demo.agentscope.ui.markdown;

import com.demo.agentscope.mcp.MCPClient.BuiltinToolExecutor;
import com.demo.agentscope.ui.ConsoleUI;
import java.util.Map;

/**
 * {@code render_markdown} 内建工具执行器。
 * <p>
 * 把模型产出的 Markdown 文本渲染成带 ANSI 颜色的终端输出并直接 print 到 stdout。
 * 返回值只携带状态摘要（不携带渲染结果），避免触发 {@code ToolResultSummarizer}
 * 的 3000 字符摘要逻辑 —— 渲染结果已展示给用户，无需再回传给模型。
 * </p>
 */
public class MarkdownToolExecutor implements BuiltinToolExecutor {

    private static final String PARAMETERS_JSON = """
            {
              "type": "object",
              "properties": {
                "content": {
                  "type": "string",
                  "description": "要渲染的 Markdown 文本"
                },
                "theme": {
                  "type": "string",
                  "enum": ["dark", "light"],
                  "default": "dark",
                  "description": "配色主题（可选，默认 dark）"
                },
                "width": {
                  "type": "integer",
                  "minimum": 20,
                  "description": "目标列宽（可选，缺省取终端宽度）"
                }
              },
              "required": ["content"]
            }
            """;

    public static final String TOOL_NAME = "render_markdown";
    public static final String TOOL_DESCRIPTION = """
            把 Markdown 文本渲染成带颜色、对齐与代码高亮的终端输出，直接展示给用户。\
            适用于需要呈现结构化信息时：标题、表格、列表、代码块等。\
            工具会直接把渲染结果打印到用户终端；调用方无需再把同样内容作为文本回复重复一遍。\
            返回值仅为状态摘要（字节数与行数），不含渲染结果。""";

    public MarkdownToolExecutor() {
    }

    public static String parametersJson() {
        return PARAMETERS_JSON;
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        if (args == null || args.get("content") == null) {
            return errorJson("参数 content 必填");
        }
        String content = String.valueOf(args.get("content"));
        if (content.isBlank()) {
            return errorJson("参数 content 不能为空白");
        }
        String theme = args.get("theme") == null ? "dark" : String.valueOf(args.get("theme")).trim();
        int width = ConsoleUI.getTerminalWidth();
        Object widthArg = args.get("width");
        if (widthArg instanceof Number n) {
            int w = n.intValue();
            if (w >= 20) width = w;
        } else if (widthArg instanceof String s && !s.isBlank()) {
            try {
                int w = Integer.parseInt(s.trim());
                if (w >= 20) width = w;
            } catch (NumberFormatException ignored) {
                // 用终端宽度
            }
        }

        String rendered = MarkdownRenderer.render(content, theme, width);
        System.out.print(rendered);
        int bytes = rendered.length();
        int lines = rendered.isEmpty() ? 0 : rendered.split("\n", -1).length;
        return okJson(bytes, lines, theme, width);
    }

    private static String okJson(int bytes, int lines, String theme, int width) {
        return "{\"status\":\"ok\",\"bytes\":" + bytes
                + ",\"lines\":" + lines
                + ",\"theme\":\"" + escape(theme) + "\""
                + ",\"width\":" + width + "}";
    }

    private static String errorJson(String msg) {
        return "{\"status\":\"error\",\"error\":\"" + escape(msg) + "\"}";
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
