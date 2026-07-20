package com.demo.agentscope.session;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConversationStore} 单元测试。
 */
@DisplayName("命名对话存储测试")
class ConversationStoreTest {

    @TempDir
    Path tempDir;

    private ConversationStore store() {
        return new ConversationStore(tempDir);
    }

    // ==================== save / load 往返 ====================

    @Test
    @DisplayName("save 然后 load 完整往返消息列表")
    void saveThenLoadRoundTrips() {
        ConversationStore store = store();
        List<Msg> original = List.of(
                Msg.userText("你好"),
                Msg.assistantText("你好，有什么可以帮您？"));

        store.save("chat1", original, false);
        List<Msg> loaded = store.load("chat1");

        assertEquals(2, loaded.size());
        assertEquals("user", loaded.get(0).getRole());
        assertEquals("assistant", loaded.get(1).getRole());
        assertEquals("你好", textOf(loaded.get(0)));
        assertEquals("你好，有什么可以帮您？", textOf(loaded.get(1)));
    }

    @Test
    @DisplayName("save 保留所有 block 类型")
    void savePreservesAllBlockTypes() {
        ConversationStore store = store();
        Msg msg = new Msg(java.util.UUID.randomUUID().toString(), "assistant", List.of(
                new ContentBlock.TextBlock("text block"),
                new ContentBlock.ToolCallBlock("call-id-1", "search", "{\"q\":\"foo\"}"),
                new ContentBlock.ToolResultBlock("call-id-1", "result text", false),
                new ContentBlock.ThinkingBlock("thinking text"),
                new ContentBlock.HintBlock("hint text"),
                new ContentBlock.DataBlock("image/png", new byte[]{1, 2, 3})));

        store.save("blocks", List.of(msg), false);
        List<Msg> loaded = store.load("blocks");

        assertEquals(1, loaded.size());
        Msg restored = loaded.get(0);
        assertEquals(6, restored.getContent().size());

        // 验证每种类型正确还原
        assertTrue(restored.getContent().get(0) instanceof ContentBlock.TextBlock);
        assertEquals("text block", ((ContentBlock.TextBlock) restored.getContent().get(0)).getText());

        assertTrue(restored.getContent().get(1) instanceof ContentBlock.ToolCallBlock);
        ContentBlock.ToolCallBlock tc = (ContentBlock.ToolCallBlock) restored.getContent().get(1);
        assertEquals("call-id-1", tc.getId());
        assertEquals("search", tc.getName());
        assertEquals("{\"q\":\"foo\"}", tc.getArguments());

        assertTrue(restored.getContent().get(2) instanceof ContentBlock.ToolResultBlock);
        ContentBlock.ToolResultBlock tr = (ContentBlock.ToolResultBlock) restored.getContent().get(2);
        assertEquals("call-id-1", tr.getToolCallId());
        assertEquals("result text", tr.getContent());
        assertFalse(tr.isError());

        assertTrue(restored.getContent().get(3) instanceof ContentBlock.ThinkingBlock);
        assertEquals("thinking text", ((ContentBlock.ThinkingBlock) restored.getContent().get(3)).getText());

        assertTrue(restored.getContent().get(4) instanceof ContentBlock.HintBlock);
        assertEquals("hint text", ((ContentBlock.HintBlock) restored.getContent().get(4)).getText());

        assertTrue(restored.getContent().get(5) instanceof ContentBlock.DataBlock);
        ContentBlock.DataBlock db = (ContentBlock.DataBlock) restored.getContent().get(5);
        assertEquals("image/png", db.getMimeType());
        assertArrayEquals(new byte[]{1, 2, 3}, db.getData());
    }

    @Test
    @DisplayName("save 保留 metadata 和 usage")
    void savePreservesMetadataAndUsage() {
        ConversationStore store = store();
        Msg msg = new Msg(
                java.util.UUID.randomUUID().toString(),
                "assistant",
                List.of(new ContentBlock.TextBlock("payload")),
                new Msg.TokenUsage(123, 456),
                null,
                Map.of("compressed", true, "source", "test"));

        store.save("meta", List.of(msg), false);
        List<Msg> loaded = store.load("meta");

        assertEquals(1, loaded.size());
        Msg restored = loaded.get(0);
        assertNotNull(restored.getUsage());
        assertEquals(123, restored.getUsage().getPromptTokens());
        assertEquals(456, restored.getUsage().getCompletionTokens());
        assertEquals(true, restored.getMetadata().get("compressed"));
        assertEquals("test", restored.getMetadata().get("source"));
    }

    // ==================== 异常路径 ====================

    @Test
    @DisplayName("load 不存在的对话抛 IllegalArgumentException")
    void loadNonexistentThrows() {
        ConversationStore store = store();
        assertThrows(IllegalArgumentException.class, () -> store.load("nope"));
    }

    @Test
    @DisplayName("save 覆盖已存在但不传 force 抛异常")
    void saveOverwriteWithoutForceThrows() {
        ConversationStore store = store();
        store.save("dup", List.of(Msg.userText("a")), false);
        assertThrows(IllegalStateException.class,
                () -> store.save("dup", List.of(Msg.userText("b")), false));
    }

    @Test
    @DisplayName("save 带 force=true 覆盖已有对话")
    void saveWithForceOverwrites() {
        ConversationStore store = store();
        store.save("over", List.of(Msg.userText("old")), false);
        store.save("over", List.of(Msg.userText("new")), true);

        List<Msg> loaded = store.load("over");
        assertEquals(1, loaded.size());
        assertEquals("new", textOf(loaded.get(0)));
    }

    // ==================== 名称校验 ====================

    @Test
    @DisplayName("save 非法名称被拒绝（含斜杠、点点、空串）")
    void saveInvalidNameRejected() {
        ConversationStore store = store();
        assertThrows(IllegalArgumentException.class,
                () -> store.save("../etc", List.of(Msg.userText("x")), false));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("a/b", List.of(Msg.userText("x")), false));
        assertThrows(IllegalArgumentException.class,
                () -> store.save("", List.of(Msg.userText("x")), false));
        assertThrows(IllegalArgumentException.class,
                () -> store.save(null, List.of(Msg.userText("x")), false));
    }

    @Test
    @DisplayName("合法名称带下划线/连字符/数字可保存")
    void saveLegalNameWithUnderscoreHyphenDigit() {
        ConversationStore store = store();
        store.save("team_chat_v2-beta1", List.of(Msg.userText("ok")), false);
        assertTrue(store.list().contains("team_chat_v2-beta1"));
    }

    // ==================== list ====================

    @Test
    @DisplayName("list 返回不带 .json 后缀的文件名")
    void listReturnsNamesWithoutJsonSuffix() {
        ConversationStore store = store();
        store.save("alpha", List.of(Msg.userText("a")), false);
        store.save("beta", List.of(Msg.userText("b")), false);

        List<String> names = store.list();
        assertTrue(names.contains("alpha"));
        assertTrue(names.contains("beta"));
        for (String n : names) {
            assertFalse(n.endsWith(".json"));
        }
    }

    @Test
    @DisplayName("list 目录不存在时返回空列表")
    void listReturnsEmptyWhenDirMissing() {
        ConversationStore store = store();
        assertTrue(store.list().isEmpty());
    }

    @Test
    @DisplayName("list 排除隐藏文件（undo 快照）")
    void listExcludesHiddenFiles() {
        ConversationStore store = store();
        store.save("visible", List.of(Msg.userText("a")), false);
        store.snapshot(List.of(Msg.userText("snap")));

        List<String> names = store.list();
        assertTrue(names.contains("visible"));
        // undo 快照文件名以 . 开头，不应出现在 list 结果中
        assertFalse(names.stream().anyMatch(n -> n.startsWith(".")));
    }

    // ==================== snapshot / undo ====================

    @Test
    @DisplayName("snapshot 覆盖上一次快照")
    void snapshotOverwritesPrevious() {
        ConversationStore store = store();
        store.snapshot(List.of(Msg.userText("first")));
        store.snapshot(List.of(Msg.userText("second")));

        List<Msg> snap = store.loadSnapshot();
        assertNotNull(snap);
        assertEquals(1, snap.size());
        assertEquals("second", textOf(snap.get(0)));
    }

    @Test
    @DisplayName("loadSnapshot 无快照返回 null")
    void loadSnapshotReturnsNullWhenNone() {
        ConversationStore store = store();
        assertNull(store.loadSnapshot());
        assertFalse(store.snapshotExists());
    }

    @Test
    @DisplayName("snapshot 写入后 snapshotExists 为 true")
    void snapshotExistsAfterWrite() {
        ConversationStore store = store();
        store.snapshot(List.of(Msg.userText("x")));
        assertTrue(store.snapshotExists());
    }

    // ==================== 辅助 ====================

    private static String textOf(Msg m) {
        if (m.getContent() == null || m.getContent().isEmpty()) return "";
        ContentBlock b = m.getContent().get(0);
        return b instanceof ContentBlock.TextBlock t ? t.getText() : "";
    }
}
