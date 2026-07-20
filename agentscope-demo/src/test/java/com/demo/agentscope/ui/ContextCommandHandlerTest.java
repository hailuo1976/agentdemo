package com.demo.agentscope.ui;

import com.demo.agentscope.agent.Agent;
import com.demo.agentscope.credential.CredentialProvider;
import com.demo.agentscope.credential.DefaultCredentialProvider;
import com.demo.agentscope.mcp.MCPClient;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.message.Msg;
import com.demo.agentscope.model.ChatModel;
import com.demo.agentscope.permission.PermissionEngine;
import com.demo.agentscope.session.ConversationStore;
import com.demo.agentscope.workspace.WorkspaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContextCommandHandler} 单元测试。
 * <p>
 * 用真实 Agent（无需 LLM 调用——mutation 方法不走网络）+ {@code @TempDir} 隔离的
 * workspace，覆盖 8 个子命令的成功与失败路径。
 * </p>
 */
@DisplayName("/context 命令处理器测试")
class ContextCommandHandlerTest {

    @TempDir
    Path tempDir;

    private MCPClient mcpClient;
    private CredentialProvider credentialProvider;
    private PermissionEngine permissionEngine;
    private WorkspaceManager workspaceManager;
    private ChatModel chatModel;

    /** 捕获 System.out 以验证错误提示。 */
    private PrintStream originalOut;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void setUp() {
        mcpClient = new MCPClient();
        mcpClient.initialize();
        credentialProvider = new DefaultCredentialProvider();
        permissionEngine = new PermissionEngine();
        workspaceManager = new WorkspaceManager();
        chatModel = new ChatModel(credentialProvider);

        originalOut = System.out;
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        // 清理 static 字段，避免跨测试泄漏
        ContextCommandHandler.handle("/noop", createAgent(), null);
    }

    private Agent createAgent() {
        return new Agent(
                "TestAgent",
                "You are a helpful assistant.",
                chatModel,
                mcpClient,
                credentialProvider,
                permissionEngine,
                workspaceManager,
                "openai"
        );
    }

    private Agent createAgentWithContext(List<Msg> messages) {
        Agent agent = createAgent();
        agent.restoreContext(messages);
        return agent;
    }

    private String captured() {
        return capturedOut.toString();
    }

    private static String textOf(Msg m) {
        if (m.getContent() == null || m.getContent().isEmpty()) return "";
        ContentBlock b = m.getContent().get(0);
        return b instanceof ContentBlock.TextBlock t ? t.getText() : "";
    }

    // ==================== view ====================

    @Test
    @DisplayName("view 带索引参数：显示该消息详情")
    void viewWithIndexArgShowsDetail() {
        Agent agent = createAgentWithContext(List.of(
                Msg.userText("first"),
                Msg.assistantText("second")));
        boolean consumed = ContextCommandHandler.handle("/context view 1", agent, tempDir);

        assertTrue(consumed);
        // ConsoleUI.printContextDetail 会输出索引
        assertTrue(captured().contains("[1]"));
    }

    @Test
    @DisplayName("view 索引越界：打印错误")
    void viewIndexOutOfBoundsPrintsError() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("only")));
        ContextCommandHandler.handle("/context view 9", agent, tempDir);

        assertTrue(captured().contains("索引越界"));
    }

    @Test
    @DisplayName("view 空上下文：打印空状态")
    void viewEmptyContextPrintsEmpty() {
        Agent agent = createAgent();
        ContextCommandHandler.handle("/context view", agent, tempDir);

        assertTrue(captured().contains("上下文为空"));
    }

    // ==================== edit ====================

    @Test
    @DisplayName("edit user 消息：替换第一个 TextBlock")
    void editUserMessageReplacesFirstTextBlock() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("原始")));
        boolean consumed = ContextCommandHandler.handle(
                "/context edit 0 替换后", agent, tempDir);

        assertTrue(consumed);
        assertEquals(1, agent.getContext().size());
        assertEquals("替换后", textOf(agent.getContext().get(0)));
        assertTrue(captured().contains("✓"));
    }

    @Test
    @DisplayName("edit tool 消息：拒绝并提示")
    void editToolMessageRejectedWithClearError() {
        Msg toolMsg = new Msg(java.util.UUID.randomUUID().toString(), "tool",
                List.of(new ContentBlock.ToolResultBlock("call-1", "result", false)));
        Agent agent = createAgentWithContext(List.of(toolMsg));

        ContextCommandHandler.handle("/context edit 0 x", agent, tempDir);

        assertTrue(captured().contains("仅可编辑 user/assistant"));
    }

    @Test
    @DisplayName("edit 索引越界：打印错误")
    void editIndexOutOfBoundsPrintsError() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("x")));

        ContextCommandHandler.handle("/context edit 9 nope", agent, tempDir);

        assertTrue(captured().contains("索引越界"));
    }

    @Test
    @DisplayName("edit 带 \\n 字面量：转成真换行")
    void editWithNewlineEscapeIsConverted() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("x")));

        ContextCommandHandler.handle("/context edit 0 line1\\nline2", agent, tempDir);

        assertEquals("line1\nline2", textOf(agent.getContext().get(0)));
    }

    @Test
    @DisplayName("edit 保留 id/role/timestamp：仅替换文本块")
    void editPreservesIdRoleTimestamp() {
        Msg original = Msg.userText("orig");
        Agent agent = createAgentWithContext(List.of(original));

        ContextCommandHandler.handle("/context edit 0 new", agent, tempDir);

        Msg replaced = agent.getContext().get(0);
        assertEquals(original.getId(), replaced.getId());
        assertEquals("user", replaced.getRole());
        assertEquals(original.getTimestamp(), replaced.getTimestamp());
    }

    @Test
    @DisplayName("edit 无文本参数：提示用法")
    void editWithoutTextPrintsUsage() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("x")));

        ContextCommandHandler.handle("/context edit 0", agent, tempDir);

        assertTrue(captured().contains("用法"));
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete 合法索引：删除并前移")
    void deleteValidIndexRemovesAndShifts() {
        Agent agent = createAgentWithContext(List.of(
                Msg.userText("a"), Msg.userText("b"), Msg.userText("c")));

        ContextCommandHandler.handle("/context delete 1", agent, tempDir);

        assertEquals(2, agent.getContext().size());
        assertEquals("a", textOf(agent.getContext().get(0)));
        assertEquals("c", textOf(agent.getContext().get(1)));
    }

    @Test
    @DisplayName("delete 索引越界：打印错误")
    void deleteIndexOutOfBoundsPrintsError() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("x")));

        ContextCommandHandler.handle("/context delete 9", agent, tempDir);

        assertTrue(captured().contains("索引越界"));
        assertEquals(1, agent.getContext().size());
    }

    // ==================== trim ====================

    @Test
    @DisplayName("trim 默认 keep=10：保留最近 10 条")
    void trimDefaultKeep10KeepsLast10() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            msgs.add(Msg.userText("m" + i));
        }
        Agent agent = createAgentWithContext(msgs);

        ContextCommandHandler.handle("/context trim", agent, tempDir);

        // fallback：15 - 10 = 5 丢弃；全 user 无需钉位；加 1 条告知消息
        // 结果 = 10 + 1 = 11
        assertEquals(11, agent.getContext().size());
        // 第一条是告知消息
        assertTrue(textOf(agent.getContext().get(0)).contains("上下文已手动裁剪"));
    }

    @Test
    @DisplayName("trim keep=2：保留尾部 2 条")
    void trimKeep2() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            msgs.add(Msg.userText("m" + i));
        }
        Agent agent = createAgentWithContext(msgs);

        ContextCommandHandler.handle("/context trim keep=2", agent, tempDir);

        // 5 - 2 = 3 丢弃；告知消息 1；总 3 条
        assertEquals(3, agent.getContext().size());
        assertTrue(textOf(agent.getContext().get(0)).contains("上下文已手动裁剪"));
        assertEquals("m3", textOf(agent.getContext().get(1)));
        assertEquals("m4", textOf(agent.getContext().get(2)));
    }

    @Test
    @DisplayName("trim 短上下文：无需裁剪")
    void trimShortContextNoChange() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("a"), Msg.userText("b")));

        ContextCommandHandler.handle("/context trim keep=5", agent, tempDir);

        assertEquals(2, agent.getContext().size());
    }

    @Test
    @DisplayName("trim 非法参数：打印错误")
    void trimIllegalArgPrintsError() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("a")));

        ContextCommandHandler.handle("/context trim abc", agent, tempDir);

        assertTrue(captured().contains("非法"));
        assertEquals(1, agent.getContext().size());
    }

    // ==================== system ====================

    @Test
    @DisplayName("system 显示完整系统提示词（含字符统计）")
    void systemShowsFullPrompt() {
        Agent agent = createAgent(); // 默认 "You are a helpful assistant."

        ContextCommandHandler.handle("/context system", agent, tempDir);

        String out = captured();
        assertTrue(out.contains("You are a helpful assistant."));
        assertTrue(out.contains("字符")); // 字符统计前缀
    }

    @Test
    @DisplayName("system short：超长提示词截断到 500 字符并显示省略数")
    void systemShortTruncatesLongPrompt() {
        // 构造一个 >500 字符的 system prompt，尾部带唯一标记以便断言截断
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("abcdefghij"); // 500 字符
        sb.append("TAILMARKER_唯一末尾"); // 唯一后缀
        String longPrompt = sb.toString();
        Agent agent = new Agent(
                "TestAgent", longPrompt, chatModel, mcpClient,
                credentialProvider, permissionEngine, workspaceManager, "openai");

        ContextCommandHandler.handle("/context system short", agent, tempDir);

        String out = captured();
        assertTrue(out.contains("前 500 字符"));
        assertTrue(out.contains("字符省略"));
        // 截断后应不含尾部唯一标记
        assertFalse(out.contains("TAILMARKER"));
    }

    @Test
    @DisplayName("system short：短提示词仍完整显示（不截断）")
    void systemShortKeepsShortPromptIntact() {
        Agent agent = createAgent(); // "You are a helpful assistant." 仅 26 字符

        ContextCommandHandler.handle("/context system short", agent, tempDir);

        // 短 prompt 不触发截断，走完整显示路径
        assertTrue(captured().contains("You are a helpful assistant."));
        assertFalse(captured().contains("前 500 字符"));
    }

    @Test
    @DisplayName("system 未设置系统提示词：打印空状态")
    void systemEmptyPromptShowsNotSet() {
        Agent agent = new Agent(
                "TestAgent", "", chatModel, mcpClient,
                credentialProvider, permissionEngine, workspaceManager, "openai");

        ContextCommandHandler.handle("/context system", agent, tempDir);

        assertTrue(captured().contains("未设置"));
    }

    // ==================== save / load ====================

    @Test
    @DisplayName("save 合法名称：写入文件")
    void saveLegalNameWritesFile() {
        Agent agent = createAgentWithContext(List.of(
                Msg.userText("hello"), Msg.assistantText("world")));

        ContextCommandHandler.handle("/context save chat1", agent, tempDir);

        assertTrue(captured().contains("✓"));
        Path file = tempDir.resolve("conversations/chat1.json");
        assertTrue(java.nio.file.Files.exists(file));
    }

    @Test
    @DisplayName("save 非法名称：拒绝（含斜杠、点点、空）")
    void saveInvalidNameRejected() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("x")));

        ContextCommandHandler.handle("/context save ../etc", agent, tempDir);
        assertTrue(captured().contains("非法") || captured().contains("失败"));

        ContextCommandHandler.handle("/context save a/b", agent, tempDir);
        assertTrue(captured().contains("非法") || captured().contains("失败"));
    }

    @Test
    @DisplayName("save 已存在不传 --force：拒绝")
    void saveExistingWithoutForceRejected() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("first")));
        ContextCommandHandler.handle("/context save dup", agent, tempDir);
        capturedOut.reset();

        ContextCommandHandler.handle("/context save dup", agent, tempDir);
        assertTrue(captured().contains("已存在") || captured().contains("失败"));
    }

    @Test
    @DisplayName("save 带 --force：覆盖")
    void saveWithForceOverwrites() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("old")));
        ContextCommandHandler.handle("/context save over", agent, tempDir);

        agent.restoreContext(List.of(Msg.userText("new")));
        ContextCommandHandler.handle("/context save over --force", agent, tempDir);

        // 加载验证
        agent.restoreContext(List.of());
        ContextCommandHandler.handle("/context load over", agent, tempDir);
        assertEquals("new", textOf(agent.getContext().get(0)));
    }

    @Test
    @DisplayName("load 加载后替换当前上下文")
    void loadReplacesCurrentContext() {
        Agent agent = createAgentWithContext(List.of(
                Msg.userText("first"), Msg.assistantText("reply")));
        ContextCommandHandler.handle("/context save snapshot", agent, tempDir);

        agent.restoreContext(List.of(Msg.userText("transient")));
        assertEquals(1, agent.getContext().size());

        ContextCommandHandler.handle("/context load snapshot", agent, tempDir);

        assertEquals(2, agent.getContext().size());
        assertEquals("first", textOf(agent.getContext().get(0)));
    }

    @Test
    @DisplayName("load 不存在对话：打印错误")
    void loadNonexistentPrintsError() {
        Agent agent = createAgent();

        ContextCommandHandler.handle("/context load nope", agent, tempDir);

        assertTrue(captured().contains("失败") || captured().contains("不存在"));
    }

    // ==================== undo ====================

    @Test
    @DisplayName("undo 无快照：打印警告")
    void undoWithoutSnapshotPrintsWarning() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("x")));

        ContextCommandHandler.handle("/context undo", agent, tempDir);

        assertTrue(captured().contains("无可恢复") || captured().contains("空"));
    }

    @Test
    @DisplayName("undo 在 edit 后：恢复 edit 前状态")
    void undoAfterEditRestoresPreviousState() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("原始")));
        ContextCommandHandler.handle("/context edit 0 改后", agent, tempDir);
        assertEquals("改后", textOf(agent.getContext().get(0)));

        ContextCommandHandler.handle("/context undo", agent, tempDir);

        assertEquals(1, agent.getContext().size());
        assertEquals("原始", textOf(agent.getContext().get(0)));
    }

    @Test
    @DisplayName("undo 在 delete 后：恢复被删消息")
    void undoAfterDeleteRestoresDeleted() {
        Agent agent = createAgentWithContext(List.of(
                Msg.userText("a"), Msg.userText("b")));
        ContextCommandHandler.handle("/context delete 0", agent, tempDir);
        assertEquals(1, agent.getContext().size());

        ContextCommandHandler.handle("/context undo", agent, tempDir);

        assertEquals(2, agent.getContext().size());
        assertEquals("a", textOf(agent.getContext().get(0)));
    }

    @Test
    @DisplayName("undo 在 trim 后：恢复被裁剪消息")
    void undoAfterTrimRestoresTrimmed() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 5; i++) msgs.add(Msg.userText("m" + i));
        Agent agent = createAgentWithContext(msgs);

        ContextCommandHandler.handle("/context trim keep=1", agent, tempDir);
        // trim 后：告知消息 + m4 = 2 条
        assertEquals(2, agent.getContext().size());

        ContextCommandHandler.handle("/context undo", agent, tempDir);

        assertEquals(5, agent.getContext().size());
    }

    // ==================== 快照写入验证 ====================

    @Test
    @DisplayName("edit 前写入快照：snapshot 文件存在")
    void snapshotWrittenBeforeEdit() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("x")));
        ConversationStore store = new ConversationStore(tempDir);
        assertFalse(store.snapshotExists());

        ContextCommandHandler.handle("/context edit 0 y", agent, tempDir);

        assertTrue(store.snapshotExists());
    }

    @Test
    @DisplayName("delete 前写入快照")
    void snapshotWrittenBeforeDelete() {
        Agent agent = createAgentWithContext(List.of(
                Msg.userText("a"), Msg.userText("b")));

        ContextCommandHandler.handle("/context delete 0", agent, tempDir);

        assertTrue(new ConversationStore(tempDir).snapshotExists());
    }

    @Test
    @DisplayName("trim 前写入快照")
    void snapshotWrittenBeforeTrim() {
        Agent agent = createAgentWithContext(List.of(
                Msg.userText("a"), Msg.userText("b"), Msg.userText("c")));

        ContextCommandHandler.handle("/context trim keep=1", agent, tempDir);

        assertTrue(new ConversationStore(tempDir).snapshotExists());
    }

    // ==================== 非 /context 输入 ====================

    @Test
    @DisplayName("非 /context 输入：返回 false 不消费")
    void nonContextInputReturnsFalse() {
        Agent agent = createAgent();

        boolean consumed = ContextCommandHandler.handle("hello world", agent, tempDir);
        assertFalse(consumed);

        consumed = ContextCommandHandler.handle("/stock", agent, tempDir);
        assertFalse(consumed);

        consumed = ContextCommandHandler.handle(null, agent, tempDir);
        assertFalse(consumed);
    }

    @Test
    @DisplayName("未知子命令：打印用法")
    void unknownSubcommandPrintsUsage() {
        Agent agent = createAgent();

        ContextCommandHandler.handle("/context bogus", agent, tempDir);

        assertTrue(captured().contains("未知子命令") || captured().contains("可用子命令"));
    }

    @Test
    @DisplayName("裸 /context（无子命令）：等同 view")
    void bareContextEqualsView() {
        Agent agent = createAgentWithContext(List.of(Msg.userText("hi")));

        ContextCommandHandler.handle("/context", agent, tempDir);

        // ConsoleUI.printContextView 会输出 [0] 索引
        assertTrue(captured().contains("[0]"));
    }
}
