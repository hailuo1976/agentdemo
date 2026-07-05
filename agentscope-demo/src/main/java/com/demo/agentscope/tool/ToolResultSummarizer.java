package com.demo.agentscope.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具结果摘要器。
 * <p>
 * 对工具返回的大结果进行智能摘要，减少上下文占用。
 * 根据工具类型和数据特征选择不同的摘要策略。
 * </p>
 */
public class ToolResultSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultSummarizer.class);

    /** 触发摘要的字符数阈值 */
    private static final int SUMMARY_THRESHOLD = 3000;

    /** 摘要最大长度 */
    private static final int MAX_SUMMARY_LENGTH = 500;

    /** 数值模式（用于检测数值数据） */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    /** CSV/表格模式 */
    private static final Pattern TABLE_PATTERN = Pattern.compile("(?:^|\\n)(?:[^,\\n]+,){2,}[^,\\n]+(?:\\n|$)");

    /**
     * 对工具结果进行摘要（如果超过阈值）。
     *
     * @param toolName 工具名称
     * @param output   原始输出
     * @return 摘要后的结果（如果未超过阈值则返回原始结果）
     */
    public String summarize(String toolName, String output) {
        if (output == null || output.length() <= SUMMARY_THRESHOLD) {
            return output;
        }

        log.debug("工具 [{}] 结果超过阈值 {} 字符，开始摘要（原始长度: {}）",
                toolName, SUMMARY_THRESHOLD, output.length());

        String summary = switch (toolName) {
            case "execute_python" -> summarizePythonOutput(output);
            case "read_file" -> summarizeFileContent(output);
            case "execute_command" -> summarizeCommandOutput(output);
            default -> summarizeGenericOutput(output);
        };

        double compressionRate = (1.0 - (double) summary.length() / output.length()) * 100;
        log.debug("工具 [{}] 摘要完成，摘要长度: {}，压缩率: {}%",
                toolName, summary.length(), String.format("%.1f", compressionRate));

        return summary;
    }

    /**
     * 摘要 Python 执行输出。
     */
    private String summarizePythonOutput(String output) {
        StringBuilder summary = new StringBuilder();
        summary.append("[Python 执行结果摘要]\n");

        String dataType = detectDataType(output);
        summary.append("数据类型: ").append(dataType).append("\n");

        // 根据数据类型提取统计信息（避免重复正则扫描）
        if ("数值数据".equals(dataType)) {
            summary.append(extractNumericStats(output));
        } else if ("表格数据".equals(dataType)) {
            summary.append(extractTableInfo(output));
        } else if ("JSON 数据".equals(dataType)) {
            summary.append(extractJsonInfo(output));
        } else {
            summary.append("内容预览:\n");
            summary.append(truncate(output, MAX_SUMMARY_LENGTH));
        }

        List<String> warnings = detectWarnings(output);
        if (!warnings.isEmpty()) {
            summary.append("\n警告: ").append(String.join(", ", warnings));
        }

        return summary.toString();
    }

    /**
     * 摘要文件内容。
     */
    private String summarizeFileContent(String output) {
        StringBuilder summary = new StringBuilder();
        summary.append("[文件内容摘要]\n");

        // 检测文件类型
        String fileType = detectFileType(output);
        summary.append("文件类型: ").append(fileType).append("\n");

        // 统计信息
        int lines = output.split("\n").length;
        int chars = output.length();
        summary.append(String.format("统计: %d 行, %d 字符\n", lines, chars));

        // 内容预览
        summary.append("内容预览:\n");
        summary.append(truncate(output, MAX_SUMMARY_LENGTH));

        return summary.toString();
    }

    /**
     * 摘要命令输出。
     */
    private String summarizeCommandOutput(String output) {
        StringBuilder summary = new StringBuilder();
        summary.append("[命令执行结果摘要]\n");

        String lowerOutput = output.toLowerCase();
        if (lowerOutput.contains("error") || lowerOutput.contains("exception")) {
            summary.append("状态: 包含错误信息\n");
        }

        summary.append("内容:\n");
        summary.append(truncate(output, MAX_SUMMARY_LENGTH));

        return summary.toString();
    }

    /**
     * 摘要通用输出。
     */
    private String summarizeGenericOutput(String output) {
        StringBuilder summary = new StringBuilder();
        summary.append("[结果摘要]\n");
        summary.append(String.format("原始长度: %d 字符\n", output.length()));
        summary.append("内容:\n");
        summary.append(truncate(output, MAX_SUMMARY_LENGTH));
        return summary.toString();
    }

    /**
     * 检测数据类型。
     */
    private String detectDataType(String output) {
        if (isJsonData(output)) {
            return "JSON 数据";
        } else if (isTableData(output)) {
            return "表格数据";
        } else if (isNumericData(output)) {
            return "数值数据";
        } else {
            return "文本数据";
        }
    }

    /**
     * 检测是否为 JSON 数据。
     */
    private boolean isJsonData(String output) {
        String trimmed = output.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    /**
     * 检测是否为表格数据。
     */
    private boolean isTableData(String output) {
        return TABLE_PATTERN.matcher(output).find();
    }

    /**
     * 检测是否为数值数据。
     */
    private boolean isNumericData(String output) {
        Matcher matcher = NUMBER_PATTERN.matcher(output);
        int count = 0;
        while (matcher.find() && count < 10) {
            count++;
        }
        return count >= 5; // 至少有 5 个数值
    }

    /**
     * 提取数值统计信息。
     */
    private String extractNumericStats(String output) {
        List<Double> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(output);

        while (matcher.find()) {
            try {
                numbers.add(Double.parseDouble(matcher.group()));
            } catch (NumberFormatException e) {
                // 忽略解析失败的数值
            }
        }

        if (numbers.isEmpty()) {
            return "";
        }

        double min = numbers.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = numbers.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double avg = numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        return String.format("数值统计: 共 %d 个数值, 最小=%.2f, 最大=%.2f, 平均=%.2f\n",
                numbers.size(), min, max, avg);
    }

    /**
     * 提取表格信息。
     */
    private String extractTableInfo(String output) {
        String[] lines = output.split("\n");
        int dataLines = 0;
        int columns = 0;

        for (String line : lines) {
            if (line.contains(",") || line.contains("\t")) {
                dataLines++;
                String[] parts = line.split("[,\\t]");
                columns = Math.max(columns, parts.length);
            }
        }

        return String.format("表格统计: %d 行数据, %d 列\n", dataLines, columns);
    }

    /**
     * 提取 JSON 信息。
     */
    private String extractJsonInfo(String output) {
        String trimmed = output.trim();

        if (trimmed.startsWith("[")) {
            // JSON 数组
            int itemCount = countJsonArrayItems(trimmed);
            return String.format("JSON 数组: %d 个元素\n", itemCount);
        } else if (trimmed.startsWith("{")) {
            // JSON 对象
            int fieldCount = countJsonObjectFields(trimmed);
            return String.format("JSON 对象: %d 个字段\n", fieldCount);
        }

        return "";
    }

    /**
     * 统计 JSON 数组元素数量（简化版）。
     */
    private int countJsonArrayItems(String jsonArray) {
        // 简单统计逗号数量 + 1
        long commaCount = jsonArray.chars().filter(ch -> ch == ',').count();
        return (int) (commaCount + 1);
    }

    /**
     * 统计 JSON 对象字段数量（简化版）。
     */
    private int countJsonObjectFields(String jsonObject) {
        // 简单统计冒号数量
        long colonCount = jsonObject.chars().filter(ch -> ch == ':').count();
        return (int) colonCount;
    }

    /**
     * 检测警告信息。
     */
    private List<String> detectWarnings(String output) {
        List<String> warnings = new ArrayList<>();

        String lowerOutput = output.toLowerCase();
        if (lowerOutput.contains("除权") || lowerOutput.contains("dividend")) {
            warnings.add("包含除权信息");
        }
        if (lowerOutput.contains("停牌") || lowerOutput.contains("suspend")) {
            warnings.add("包含停牌信息");
        }
        if (lowerOutput.contains("warning") || lowerOutput.contains("警告")) {
            warnings.add("包含警告信息");
        }

        return warnings;
    }

    /**
     * 检测文件类型。
     */
    private String detectFileType(String output) {
        if (output.contains("{") && output.contains("}")) {
            return "JSON/代码文件";
        } else if (output.contains(",") && output.contains("\n")) {
            return "CSV/表格文件";
        } else if (output.contains("#") || output.contains("//")) {
            return "代码文件";
        } else {
            return "文本文件";
        }
    }

    /**
     * 截断文本到指定长度。
     */
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n... (已截断，共 " + text.length() + " 字符)";
    }
}
