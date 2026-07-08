package com.demo.agentscope.context;

import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.tool.ToolOutputArchive;
import com.demo.agentscope.tool.ToolResultSummarizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContextToolResultArchiver} 单元测试。
 * <p>
 * 验证核心契约：
 * <ul>
 *   <li>场景 1：context 只有未标记 FULL 的 tool result → compact 不误伤（保护历史数据）</li>
 *   <li>场景 2：context 有 2 个 FULL → compact 后第 1 个变 SUMMARIZED + 前缀正确，第 2 个不动</li>
 *   <li>场景 3：context 有 1 个 SUMMARIZED + 1 个 FULL → compact 不二次摘要已 SUMMARIZED 的</li>
 *   <li>archive 为空时（数据丢失模拟）跳过改写，不抛异常</li>
 * </ul>
 * </p>
 */
public class ContextToolResultArchiverTest {

    @TempDir
    Path tempDir;

    private static final String TOOL_NAME = "read_file";

    @Test
    void contextWithoutFullBlocks_compactIsNoOp() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        ContextToolResultArchiver archiver = new ContextToolResultArchiver(archive, summarizer);

        // 准备：一条 user + 一条未标记 FULL 的 tool result（历史数据/未经 markAsFull）
        List<Msg> context = new ArrayList<>();
        context.add(userMsg("读文件 A"));
        ContentBlock.ToolResultBlock block =
                new ContentBlock.ToolResultBlock("call-1", "内容AAA", false);
        Msg toolMsg = toolResultMsg(block);
        context.add(toolMsg);

        archiver.compactExistingToolResults(context);

        // 无 FULL 标记的块不应被误伤（保护历史数据）
        assertNull(toolMsg.getMetadata("call-1"),
                "未标记 FULL 的块不应被 compact 触碰");
        assertEquals("内容AAA", block.getContent(),
                "未标记 FULL 的块内容不应改变");
    }

    @Test
    void twoFullBlocks_compactRewritesFirstToSummarized() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        ContextToolResultArchiver archiver = new ContextToolResultArchiver(archive, summarizer);

        List<Msg> context = new ArrayList<>();
        context.add(userMsg("读 A 和 B"));

        // 第 1 次工具结果
        Msg msg1 = archiveToolResult(
                archiver, archive, "call-1", "这是文件 A 的完整内容，很长很长很长很长很长。",
                context);
        archiver.markAsFull(msg1, "call-1");

        // 模拟 Agent.reply：新工具结果入 context 前先 compact
        archiver.compactExistingToolResults(context);

        // 第 2 次工具结果归档并加入
        archive.archive("call-2", TOOL_NAME, Map.of(), "文件 B 内容");
        ContentBlock.ToolResultBlock block2 =
                new ContentBlock.ToolResultBlock("call-2", "文件 B 内容", false);
        Msg msg2 = toolResultMsg(block2);
        context.add(msg2);
        archiver.markAsFull(msg2, "call-2");

        // 断言
        assertEquals(ContextToolResultArchiver.STATE_SUMMARIZED, msg1.getMetadata("call-1"));
        ContentBlock.ToolResultBlock b1 = (ContentBlock.ToolResultBlock) msg1.getContent().get(0);
        assertTrue(b1.getContent().contains("[此工具结果已摘要"),
                "第一个块应被改写为摘要前缀");
        assertTrue(b1.getContent().contains("tool_call_id=call-1"),
                "摘要前缀应包含 tool_call_id");
        assertTrue(b1.getContent().contains("get_full_tool_output"),
                "摘要前缀应告知如何取回完整内容");

        assertEquals(ContextToolResultArchiver.STATE_FULL, msg2.getMetadata("call-2"));
        ContentBlock.ToolResultBlock b2 = (ContentBlock.ToolResultBlock) msg2.getContent().get(0);
        assertEquals("文件 B 内容", b2.getContent(),
                "第二个块应保持完整原始内容");
    }

    @Test
    void alreadySummarized_notSummarizedAgain() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        ContextToolResultArchiver archiver = new ContextToolResultArchiver(archive, summarizer);

        List<Msg> context = new ArrayList<>();
        context.add(userMsg("读 A 和 B"));

        // 第 1 个块已预先标记为 SUMMARIZED（模拟 MicroCompactor 已处理过）
        ContentBlock.ToolResultBlock block1 =
                new ContentBlock.ToolResultBlock("call-1", "已经被摘要的内容", false);
        Msg msg1 = toolResultMsg(block1);
        msg1.setMetadata("call-1", ContextToolResultArchiver.STATE_SUMMARIZED);
        context.add(msg1);

        // 第 2 个块是 FULL
        archive.archive("call-2", TOOL_NAME, Map.of(), "文件 B 内容");
        ContentBlock.ToolResultBlock block2 =
                new ContentBlock.ToolResultBlock("call-2", "文件 B 内容", false);
        Msg msg2 = toolResultMsg(block2);
        msg2.setMetadata("call-2", ContextToolResultArchiver.STATE_FULL);
        context.add(msg2);

        archiver.compactExistingToolResults(context);

        // 已 SUMMARIZED 的不动
        ContentBlock.ToolResultBlock b1 = (ContentBlock.ToolResultBlock) msg1.getContent().get(0);
        assertEquals("已经被摘要的内容", b1.getContent(),
                "已 SUMMARY 状态的块内容不应被二次改写");
        assertEquals(ContextToolResultArchiver.STATE_SUMMARIZED, msg1.getMetadata("call-1"));
    }

    @Test
    void archiveDataMissing_skipsRewriteNoException() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        ContextToolResultArchiver archiver = new ContextToolResultArchiver(archive, summarizer);

        List<Msg> context = new ArrayList<>();
        context.add(userMsg("读 A"));

        // 构造一个 FULL 状态的块，但不往 archive 里写数据（模拟归档丢失）
        ContentBlock.ToolResultBlock block =
                new ContentBlock.ToolResultBlock("missing-id", "原始内容", false);
        Msg msg = toolResultMsg(block);
        msg.setMetadata("missing-id", ContextToolResultArchiver.STATE_FULL);
        context.add(msg);

        // 不应抛异常
        archiver.compactExistingToolResults(context);

        // 块内容未被改动（保护数据）
        ContentBlock.ToolResultBlock b = (ContentBlock.ToolResultBlock) msg.getContent().get(0);
        assertEquals("原始内容", b.getContent(),
                "archive 数据缺失时应保留原块内容");
    }

    @Test
    void nullAndEmptyContext_safe() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        ContextToolResultArchiver archiver = new ContextToolResultArchiver(archive, summarizer);

        assertDoesNotThrow(() -> archiver.compactExistingToolResults(null));
        assertDoesNotThrow(() -> archiver.compactExistingToolResults(new ArrayList<>()));
        assertDoesNotThrow(() -> archiver.compactExistingToolResults(List.of()));
    }

    @Test
    void markAsFull_setsMetadataState() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        ContextToolResultArchiver archiver = new ContextToolResultArchiver(archive, summarizer);

        Msg msg = toolResultMsg(new ContentBlock.ToolResultBlock("c-1", "x", false));
        archiver.markAsFull(msg, "c-1");

        assertEquals(ContextToolResultArchiver.STATE_FULL, msg.getMetadata("c-1"));
    }

    @Test
    void markAsFull_nullArgsAreSafe() {
        ToolOutputArchive archive = new ToolOutputArchive(tempDir);
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        ContextToolResultArchiver archiver = new ContextToolResultArchiver(archive, summarizer);

        assertDoesNotThrow(() -> archiver.markAsFull(null, "c-1"));
        Msg msg = toolResultMsg(new ContentBlock.ToolResultBlock("c-1", "x", false));
        assertDoesNotThrow(() -> archiver.markAsFull(msg, null));
    }

    // ==================== 辅助方法 ====================

    private static Msg userMsg(String text) {
        return new Msg(UUID.randomUUID().toString(), "user",
                List.of(new ContentBlock.TextBlock(text)));
    }

    private static Msg toolResultMsg(ContentBlock.ToolResultBlock block) {
        return new Msg(UUID.randomUUID().toString(), "tool", List.of(block));
    }

    /**
     * 模拟 Agent.reply 的工具结果入 context 流程：归档 + 构造块 + 加 context。
     *
     * @return 新加入 context 的 tool result 消息
     */
    private static Msg archiveToolResult(ContextToolResultArchiver archiver,
                                          ToolOutputArchive archive,
                                          String toolCallId,
                                          String output,
                                          List<Msg> context) {
        archive.archive(toolCallId, TOOL_NAME, Map.of(), output);
        ContentBlock.ToolResultBlock block =
                new ContentBlock.ToolResultBlock(toolCallId, output, false);
        Msg msg = toolResultMsg(block);
        context.add(msg);
        return msg;
    }
}
