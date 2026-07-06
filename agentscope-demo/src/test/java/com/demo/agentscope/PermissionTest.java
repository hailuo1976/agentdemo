package com.demo.agentscope;

import com.demo.agentscope.middleware.AgentContext;
import com.demo.agentscope.middleware.MiddlewareChain;
import com.demo.agentscope.message.ContentBlock;
import com.demo.agentscope.permission.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权限系统测试。
 */
@DisplayName("权限系统测试")
class PermissionTest {

    private PermissionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PermissionEngine();
    }

    // ==================== 规则引擎测试 ====================

    @Test
    @DisplayName("显式规则允许 get_weather → ALLOW")
    void explicitRuleAllowsGetWeather() {
        engine.addRule(new PermissionRule("get_weather", PermissionDecision.ALLOW, "天气查询安全"));

        PermissionDecision decision = engine.check("get_weather", Map.of("city", "Beijing"));
        assertTrue(decision.isAllowed());
    }

    @Test
    @DisplayName("显式规则拒绝 delete_file → DENY（携带 reason）")
    void explicitRuleDeniesDeleteFile() {
        engine.addRule(new PermissionRule("delete_file",
                PermissionDecision.deny("禁止删除文件"), "禁止删除文件"));

        PermissionDecision decision = engine.check("delete_file", Map.of("path", "/tmp/test.txt"));
        assertTrue(decision.isDenied());
        assertNotNull(decision.getReason());
    }

    @Test
    @DisplayName("显式规则优先于模式引擎")
    void explicitRuleTakesPrecedenceOverMode() {
        engine.setMode(PermissionMode.EXPLORE);
        // 即使在 EXPLORE 模式下，显式允许 write_file 的规则应该优先
        engine.addRule(new PermissionRule("write_file", PermissionDecision.ALLOW, "明确允许"));

        PermissionDecision decision = engine.check("write_file", Map.of("path", "test.txt"));
        assertTrue(decision.isAllowed());
    }

    @Test
    @DisplayName("bypassImmune 规则在 BYPASS 模式下依然生效")
    void bypassImmuneRuleSurvivesBypassMode() {
        engine.addRule(new PermissionRule("dangerous_tool",
                PermissionDecision.deny("不可绕过的安全规则"),
                "不可绕过的安全规则", true));
        engine.setMode(PermissionMode.BYPASS);

        PermissionDecision decision = engine.check("dangerous_tool", Map.of());
        assertTrue(decision.isDenied());
    }

    // ==================== 模式引擎测试 ====================

    @Test
    @DisplayName("EXPLORE 模式拒绝写入类工具（携带原因说明）")
    void exploreModeDeniesWriteTools() {
        engine.setMode(PermissionMode.EXPLORE);

        PermissionDecision d1 = engine.check("write_file", Map.of());
        assertTrue(d1.isDenied());
        assertNotNull(d1.getReason());

        assertTrue(engine.check("delete_record", Map.of()).isDenied());
        assertTrue(engine.check("execute_script", Map.of()).isDenied());
        assertTrue(engine.check("run_command", Map.of()).isDenied());
        assertTrue(engine.check("bash_script", Map.of()).isDenied());
    }

    @Test
    @DisplayName("EXPLORE 模式允许只读类工具")
    void exploreModeAllowsReadTools() {
        engine.setMode(PermissionMode.EXPLORE);

        assertTrue(engine.check("get_weather", Map.of()).isAllowed());
        assertTrue(engine.check("read_file", Map.of()).isAllowed());
        assertTrue(engine.check("search", Map.of()).isAllowed());
    }

    @Test
    @DisplayName("DONT_ASK 模式不拦截 ALLOW 决策")
    void dontAskModeAllowsAllowDecision() {
        engine.setMode(PermissionMode.DONT_ASK);

        PermissionDecision decision = engine.check("get_weather", Map.of("city", "Beijing"));
        assertTrue(decision.isAllowed());
    }

    @Test
    @DisplayName("BYPASS 模式允许所有（非 bypassImmune）工具")
    void bypassModeAllowsAllNonImmuneTools() {
        engine.setMode(PermissionMode.BYPASS);

        assertTrue(engine.check("write_file", Map.of()).isAllowed());
        assertTrue(engine.check("delete_record", Map.of()).isAllowed());
        assertTrue(engine.check("get_weather", Map.of()).isAllowed());
    }

    // ==================== 内置检查引擎测试 ====================

    @Test
    @DisplayName("危险 bash 命令（rm -rf）→ DENY（携带原因）")
    void dangerousCommandDeny() {
        PermissionDecision decision = engine.check("bash",
                Map.of("command", "rm -rf /"));
        assertTrue(decision.isDenied());
        assertNotNull(decision.getReason());
        assertTrue(decision.getReason().contains("rm -rf"));
    }

    @Test
    @DisplayName("危险路径（/etc/）→ ASK（携带原因）")
    void dangerousPathAsk() {
        PermissionDecision decision = engine.check("edit_file",
                Map.of("path", "/etc/passwd"));
        assertTrue(decision.isAsk());
        assertNotNull(decision.getReason());
        assertTrue(decision.getReason().contains("/etc/"));
    }

    @Test
    @DisplayName("安全参数 → ALLOW")
    void safeArgumentsAllow() {
        PermissionDecision decision = engine.check("get_weather",
                Map.of("city", "Beijing", "unit", "celsius"));
        assertTrue(decision.isAllowed());
    }

    @Test
    @DisplayName("禁用内置检查时安全规则不生效")
    void builtInCheckDisabledSkipsChecks() {
        engine.setBuiltInChecksEnabled(false);

        PermissionDecision decision = engine.check("bash",
                Map.of("command", "rm -rf /"));
        // DONT_ASK 模式下，无规则命中，模式引擎不拦截 → ALLOW
        assertTrue(decision.isAllowed());
    }

    @Test
    @DisplayName("空参数时内置检查返回 ALLOW")
    void builtInCheckEmptyArgsReturnsAllow() {
        PermissionDecision decision = engine.check("some_tool", Map.of());
        assertTrue(decision.isAllowed());
    }

    @Test
    @DisplayName("null 参数时内置检查返回 ALLOW")
    void builtInCheckNullArgsReturnsAllow() {
        PermissionDecision decision = engine.check("some_tool", null);
        assertTrue(decision.isAllowed());
    }

    // ==================== PermissionDecision 不可变类测试（T2 新增） ====================

    @Test
    @DisplayName("PermissionDecision.ALLOW 单例不带 reason")
    void allowSingletonHasNoReason() {
        assertTrue(PermissionDecision.ALLOW.isAllowed());
        assertFalse(PermissionDecision.ALLOW.isDenied());
        assertFalse(PermissionDecision.ALLOW.isAsk());
        assertNull(PermissionDecision.ALLOW.getReason());
    }

    @Test
    @DisplayName("PermissionDecision.deny(reason) 携带具体原因")
    void denyFactoryCarriesReason() {
        PermissionDecision d = PermissionDecision.deny("命中规则 X");
        assertTrue(d.isDenied());
        assertEquals("命中规则 X", d.getReason());
    }

    @Test
    @DisplayName("PermissionDecision.ask(reason) 携带具体原因")
    void askFactoryCarriesReason() {
        PermissionDecision d = PermissionDecision.ask("需人工确认");
        assertTrue(d.isAsk());
        assertEquals("需人工确认", d.getReason());
    }

    @Test
    @DisplayName("PermissionDecision equals/hashCode 基于类型与原因")
    void permissionDecisionEquals() {
        PermissionDecision d1 = PermissionDecision.deny("r1");
        PermissionDecision d2 = PermissionDecision.deny("r1");
        PermissionDecision d3 = PermissionDecision.deny("r2");
        assertEquals(d1, d2);
        assertNotEquals(d1, d3);
        assertEquals(d1.hashCode(), d2.hashCode());
    }

    // ==================== PermissionMiddleware 集成测试 ====================

    @Test
    @DisplayName("PermissionMiddleware 允许 ALLOW 决策的工具调用")
    void permissionMiddlewareAllowsAllowedTool() {
        engine.addRule(new PermissionRule("get_weather", PermissionDecision.ALLOW, "safe"));
        PermissionMiddleware pm = new PermissionMiddleware(engine);
        MiddlewareChain chain = new MiddlewareChain();
        chain.add(pm);

        AgentContext ctx = new AgentContext("agent-1", "sess-1", "user-1");
        ContentBlock.ToolCallBlock toolCall = new ContentBlock.ToolCallBlock("c1", "get_weather", "{\"city\":\"BJ\"}");

        assertDoesNotThrow(() -> chain.fireToolCall(ctx, toolCall));
    }

    @Test
    @DisplayName("PermissionMiddleware 拒绝 DENY 决策的工具调用（异常含具体原因）")
    void permissionMiddlewareDeniesDeniedTool() {
        engine.addRule(new PermissionRule("delete_file",
                PermissionDecision.deny("禁止删除文件"), "禁止删除文件"));
        PermissionMiddleware pm = new PermissionMiddleware(engine);
        MiddlewareChain chain = new MiddlewareChain();
        chain.add(pm);

        AgentContext ctx = new AgentContext("agent-1", "sess-1", "user-1");
        ContentBlock.ToolCallBlock toolCall = new ContentBlock.ToolCallBlock("c1", "delete_file", "{}");

        PermissionDeniedException ex = assertThrows(PermissionDeniedException.class,
                () -> chain.fireToolCall(ctx, toolCall));
        assertTrue(ex.getReason().contains("禁止删除文件"));
    }

    @Test
    @DisplayName("PermissionMiddleware ASK 决策抛出 PermissionDeniedException（原因透传）")
    void permissionMiddlewareAskDecisionThrows() {
        engine.setMode(PermissionMode.DONT_ASK);
        engine.setBuiltInChecksEnabled(true);

        PermissionMiddleware pm = new PermissionMiddleware(engine);
        MiddlewareChain chain = new MiddlewareChain();
        chain.add(pm);

        AgentContext ctx = new AgentContext("agent-1", "sess-1", "user-1");
        // 触发危险路径内置检查 → ASK → 中间件自动拒绝
        ContentBlock.ToolCallBlock toolCall = new ContentBlock.ToolCallBlock("c1", "read_file", "{\"path\":\"/etc/passwd\"}");

        PermissionDeniedException ex = assertThrows(PermissionDeniedException.class,
                () -> chain.fireToolCall(ctx, toolCall));
        assertTrue(ex.getReason().contains("/etc/"));
    }

    // ==================== PermissionDeniedException 测试 ====================

    @Test
    @DisplayName("PermissionDeniedException 携带工具名称和原因")
    void permissionDeniedExceptionProperties() {
        PermissionDeniedException ex = new PermissionDeniedException("delete_file", "安全策略禁止");
        assertEquals("delete_file", ex.getToolName());
        assertEquals("安全策略禁止", ex.getReason());
        assertTrue(ex.getMessage().contains("delete_file"));
    }

    // ==================== PermissionRule 测试 ====================

    @Test
    @DisplayName("PermissionRule 属性正确")
    void permissionRuleProperties() {
        PermissionRule rule = new PermissionRule("tool_a",
                PermissionDecision.deny("禁止"), "test reason", true);
        assertEquals("tool_a", rule.getToolName());
        assertTrue(rule.getAction().isDenied());
        // action 已携带 reason 时，rule 级 reason 被忽略（单一真相源）
        assertEquals("禁止", rule.getAction().getReason());
        assertTrue(rule.isBypassImmune());
    }

    @Test
    @DisplayName("PermissionRule 在 action 缺少 reason 时注入 rule 级 reason")
    void permissionRuleInjectsReasonWhenActionLacksOne() {
        // 裸 DENY（无 reason）+ rule 级 reason → 注入到 action
        PermissionRule rule = new PermissionRule("tool_a",
                PermissionDecision.deny(null), "注入原因", true);
        assertEquals("注入原因", rule.getAction().getReason());
    }

    @Test
    @DisplayName("PermissionRule 默认 bypassImmune 为 false")
    void permissionRuleDefaultBypassImmune() {
        PermissionRule rule = new PermissionRule("tool_b", PermissionDecision.ALLOW, "ok");
        assertFalse(rule.isBypassImmune());
    }

    // ==================== PermissionEngine 管理方法测试 ====================

    @Test
    @DisplayName("PermissionEngine removeRule 移除指定规则")
    void removeRule() {
        engine.addRule(new PermissionRule("temp_tool", PermissionDecision.ALLOW, "temp"));
        assertEquals(1, engine.getRules().size());

        engine.removeRule("temp_tool");
        assertTrue(engine.getRules().isEmpty());
    }

    @Test
    @DisplayName("PermissionEngine 默认模式为 DONT_ASK")
    void defaultModeIsDontAsk() {
        assertEquals(PermissionMode.DONT_ASK, engine.getMode());
    }

    @Test
    @DisplayName("PermissionEngine 默认启用内置检查")
    void builtInChecksEnabledByDefault() {
        assertTrue(engine.isBuiltInChecksEnabled());
    }
}
