package com.demo.agentscope.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具输出归档器。
 * <p>
 * 把工具调用的原始全量输出按 {@code toolCallId} 持久化到 workspace 下的
 * {@code cache/tool_outputs/} 目录，供后续按需提取（例如 {@code get_full_tool_output}
 * 内置工具，或外部诊断）。
 * </p>
 *
 * <h3>存储布局</h3>
 * <ul>
 *   <li>{@code {archiveDir}/{toolCallId}.txt} — 原始输出文本</li>
 *   <li>{@code {archiveDir}/index.json}       — 元数据索引（ArchivedMeta 列表）</li>
 * </ul>
 *
 * <h3>并发</h3>
 * 当前由 REPL 单线程调用，未加 synchronized。如有并发需求可后续补。
 */
public class ToolOutputArchive {

    private static final Logger log = LoggerFactory.getLogger(ToolOutputArchive.class);

    /** 元数据索引文件名。 */
    private static final String INDEX_FILE = "index.json";

    private final Path archiveDir;
    private final ObjectMapper objectMapper;

    /** 内存中的元数据索引（启动时从 index.json 加载，写盘时同步更新）。 */
    private final Map<String, ArchivedMeta> index = new LinkedHashMap<>();

    /**
     * 构造归档器并加载已有索引。
     *
     * @param archiveDir 归档目录（通常为 {@code workspace/cache/tool_outputs}）
     */
    public ToolOutputArchive(Path archiveDir) {
        this.archiveDir = Objects.requireNonNull(archiveDir, "archiveDir 不能为 null");
        this.objectMapper = new ObjectMapper();
        try {
            Files.createDirectories(archiveDir);
        } catch (IOException e) {
            log.error("创建工具输出归档目录失败: {}", archiveDir, e);
        }
        loadIndex();
    }

    // ==================== 核心 API ====================

    /**
     * 归档一条工具输出。
     *
     * @param toolCallId 工具调用 ID（唯一键）
     * @param toolName   工具名称（用于后续差异化摘要）
     * @param args       工具入参（用于诊断）
     * @param fullOutput 原始全量输出文本
     */
    public void archive(String toolCallId, String toolName,
                        Map<String, Object> args, String fullOutput) {
        Objects.requireNonNull(toolCallId, "toolCallId 不能为 null");
        if (fullOutput == null) {
            log.debug("跳过 null 输出的归档: toolCallId={}", toolCallId);
            return;
        }

        Path outputFile = archiveDir.resolve(safeFileName(toolCallId) + ".txt");
        try {
            Files.writeString(outputFile, fullOutput);
        } catch (IOException e) {
            log.error("写入工具输出归档失败: toolCallId={}, file={}", toolCallId, outputFile, e);
            return;
        }

        ArchivedMeta meta = new ArchivedMeta(
                toolCallId,
                toolName != null ? toolName : "unknown",
                args != null ? Collections.unmodifiableMap(new LinkedHashMap<>(args)) : Collections.emptyMap(),
                fullOutput.length(),
                outputFile);
        index.put(toolCallId, meta);
        saveIndex();
        log.debug("已归档工具输出: toolCallId={}, toolName={}, length={}",
                toolCallId, toolName, fullOutput.length());
    }

    /**
     * 取回某条工具结果的原始全量输出。
     *
     * @param toolCallId 工具调用 ID
     * @return 原始文本；未归档或读失败时返回 null
     */
    public String getFullOutput(String toolCallId) {
        ArchivedMeta meta = index.get(toolCallId);
        if (meta == null) {
            return null;
        }
        try {
            return Files.readString(meta.filePath());
        } catch (IOException e) {
            log.warn("读取归档输出失败: toolCallId={}, file={}", toolCallId, meta.filePath(), e);
            return null;
        }
    }

    /**
     * 取回元数据。
     *
     * @param toolCallId 工具调用 ID
     * @return 元数据；不存在返回 null
     */
    public ArchivedMeta getMeta(String toolCallId) {
        return index.get(toolCallId);
    }

    /**
     * 列出当前所有归档元数据（不可变视图）。
     */
    public Map<String, ArchivedMeta> listAll() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(index));
    }

    /**
     * 清空所有归档（主要用于测试）。
     */
    public void clear() {
        for (ArchivedMeta meta : index.values()) {
            try {
                Files.deleteIfExists(meta.filePath());
            } catch (IOException e) {
                log.debug("删除归档文件失败: {}", meta.filePath(), e);
            }
        }
        index.clear();
        saveIndex();
    }

    // ==================== 内部 ====================

    /** 加载磁盘上的 index.json 到内存。 */
    private void loadIndex() {
        Path indexFile = archiveDir.resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) {
            return;
        }
        try {
            List<ArchivedMeta> list = objectMapper.readValue(
                    indexFile.toFile(),
                    new TypeReference<List<ArchivedMeta>>() {});
            index.clear();
            for (ArchivedMeta m : list) {
                index.put(m.toolCallId(), m);
            }
            log.debug("已加载工具输出归档索引: {} 条", index.size());
        } catch (IOException e) {
            log.warn("加载归档索引失败，将使用空索引: {}", indexFile, e);
        }
    }

    /** 把内存索引写回 index.json。 */
    private void saveIndex() {
        Path indexFile = archiveDir.resolve(INDEX_FILE);
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(indexFile.toFile(), List.copyOf(index.values()));
        } catch (IOException e) {
            log.warn("写回归档索引失败: {}", indexFile, e);
        }
    }

    /**
     * 把 toolCallId 转成安全的文件名片段。
     * <p>
     * 当前 toolCallId 由 UUID 或类似形态构成，本身已安全；这里额外过滤一遍
     * 路径分隔符与可疑字符，防御未来格式变化。
     * </p>
     */
    private static String safeFileName(String toolCallId) {
        return toolCallId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 归档元数据记录。
     *
     * @param toolCallId 工具调用 ID
     * @param toolName   工具名称
     * @param args       工具入参快照
     * @param fullLength 原始输出字符数
     * @param filePath   原始输出文件路径
     */
    public record ArchivedMeta(String toolCallId, String toolName,
                               Map<String, Object> args, int fullLength,
                               Path filePath) {
    }
}
