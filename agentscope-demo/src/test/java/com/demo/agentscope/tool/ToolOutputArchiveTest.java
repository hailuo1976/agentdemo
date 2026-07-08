package com.demo.agentscope.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolOutputArchive} 单元测试。
 * <p>
 * 覆盖核心契约：archive/getFullOutput 往返一致、listAll 完整、未知 ID 返回 null、
 * 重启模拟（新实例加载旧 index.json）。
 * </p>
 */
public class ToolOutputArchiveTest {

    @TempDir
    Path tempDir;

    @Test
    void archiveAndGetFullOutput_roundTripPreservesContent() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        archive.archive("call-1", "read_file", Map.of("path", "a.txt"), "原始内容 ABC");

        assertEquals("原始内容 ABC", archive.getFullOutput("call-1"));
        ToolOutputArchive.ArchivedMeta meta = archive.getMeta("call-1");
        assertNotNull(meta);
        assertEquals("read_file", meta.toolName());
        assertEquals(8, meta.fullLength());
        assertNotNull(meta.filePath());
    }

    @Test
    void getFullOutput_unknownIdReturnsNull() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        assertNull(archive.getFullOutput("non-existent"));
        assertNull(archive.getMeta("non-existent"));
    }

    @Test
    void listAll_returnsAllArchivedEntries() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        archive.archive("a", "read_file", Map.of(), "AAA");
        archive.archive("b", "execute_python", Map.of(), "BBB");

        Map<String, ToolOutputArchive.ArchivedMeta> all = archive.listAll();
        assertEquals(2, all.size());
        assertNotNull(all.get("a"));
        assertNotNull(all.get("b"));
    }

    @Test
    void reloadFromDisk_newInstanceReadsOldData() {
        ToolOutputArchive first = new ToolOutputArchive(tempDir);
        first.archive("call-restart", "read_file", Map.of("path", "x"), "持久化测试");

        // 用新实例指向同一目录，模拟重启
        ToolOutputArchive second = new ToolOutputArchive(tempDir);
        assertEquals("持久化测试", second.getFullOutput("call-restart"));
        ToolOutputArchive.ArchivedMeta meta = second.getMeta("call-restart");
        assertEquals("read_file", meta.toolName());
    }

    @Test
    void archive_nullOutputIsSkippedSilently() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        archive.archive("skipped", "read_file", Map.of(), null);

        assertNull(archive.getMeta("skipped"));
        assertTrue(archive.listAll().isEmpty());
    }

    @Test
    void archive_sameIdOverwritesPrevious() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        archive.archive("dup", "read_file", Map.of(), "第一版");
        archive.archive("dup", "read_file", Map.of(), "第二版");

        assertEquals("第二版", archive.getFullOutput("dup"));
    }

    @Test
    void clear_removesAllEntries() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        archive.archive("a", "read_file", Map.of(), "AAA");
        archive.archive("b", "execute_command", Map.of(), "BBB");

        archive.clear();

        assertTrue(archive.listAll().isEmpty());
        assertNull(archive.getFullOutput("a"));
    }

    @Test
    void argsSnapshot_isImmutable() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("k", "v");
        archive.archive("call-args", "read_file", args, "内容");

        // 修改原 Map 不应影响归档快照
        args.put("k", "modified");
        ToolOutputArchive.ArchivedMeta meta = archive.getMeta("call-args");
        assertEquals("v", meta.args().get("k"));
    }
}
