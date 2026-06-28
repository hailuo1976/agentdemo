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
        assertEquals(PermissionDecision.ALLOW, decision);
    }

    @Test
    @DisplayName("显式规则拒绝 delete_file → DENY")
    void explicitRuleDeniesDeleteFile() {
        engine.addRule(new PermissionRule("delete_file", PermissionDecision.DENY, "禁止删除文件"));

        PermissionDecision decision = engine.check("delete_file", Map.of("path", "/tmp/test.txt"));
        assertEquals(PermissionDecision.DENY, decision);
    }

    @Test
    @DisplayName("显式规则优先于模式引擎")
    void explicitRuleTakesPrecedenceOverMode() {
        engine.setMode(PermissionMode.EXPLORE);
        // 即使在 EXPLORE 模式下，显式允许 write_file 的规则应该优先
        engine.addRule(new PermissionRule("write_file", PermissionDecision.ALLOW, "明确允许"));

        PermissionDecision decision = engine.check("write_file", Map.of("path", "test.txt"));
        assertEquals(PermissionDecision.ALLOW, decision);
    }

    @Test
    @DisplayName("bypassImmune 规则在 BYPASS 模式下依然生效")
    void bypassImmuneRuleSurvivesBypassMode() {
        engine.addRule(new PermissionRule("dangerous_tool", PermissionDecision.DENY, "不可绕过的安全规则", true));
        engine.setMode(PermissionMode.BYPASS);

        PermissionDecision decision = engine.check("dangerous_tool", Map.of());
        assertEquals(PermissionDecision.DENY, decision);
    }

    // ==================== 模式引擎测试 ====================

    @Test
    @DisplayName("EXPLORE 模式拒绝写入类工具")
    void exploreModeDeniesWriteTools() {
        engine.setMode(PermissionMode.EXPLORE);

        assertEquals(PermissionDecision.DENY, engine.check("write_file", Map.of()));
        assertEquals(PermissionDecision.DENY, engine.check("delete_record", Map.of()));
        assertEquals(PermissionDecision.DENY, engine.check("execute_script", Map.of()));
        assertEquals(PermissionDecision.DENY, engine.check("run_command", Map.of()));
        assertEquals(PermissionDecision.DENY, engine.check("bash_script", Map.of()));
    }

    @Test
    @DisplayName("EXPLORE 模式允许只读类工具")
    void exploreModeAllowsReadTools() {
        engine.setMode(PermissionMode.EXPLORE);

        assertEquals(PermissionDecision.ALLOW, engine.check("get_weather", Map.of()));
        assertEquals(PermissionDecision.ALLOW, engine.check("read_file", Map.of()));
        assertEquals(PermissionDecision.ALLOW, engine.check("search", Map.of()));
    }

    @Test
    @DisplayName("DONT_ASK 模式不拦截 ALLOW 决策")
    void dontAskModeAllowsAllowDecision() {
        engine.setMode(PermissionMode.DONT_ASK);

        PermissionDecision decision = engine.check("get_weather", Map.of("city", "Beijing"));
        assertEquals(PermissionDecision.ALLOW, decision);
    }

    @Test
    @DisplayName("BYPASS 模式允许所有（非 bypassImmune）工具")
    void bypassModeAllowsAllNonImmuneTools() {
        engine.setMode(PermissionMode.BYPASS);

        assertEquals(PermissionDecision.ALLOW, engine.check("write_file", Map.of()));
        assertEquals(PermissionDecision.ALLOW, engine.check("delete_record", Map.of()));
        assertEquals(PermissionDecision.ALLOW, engine.check("get_weather", Map.of()));
    }

    // ==================== 内置检查引擎测试 ====================

    @Test
    @DisplayName("危险 bash 命令（rm -rf）→ DENY")
    void dangerousCommandDeny() {
        PermissionDecision decision = engine.check("bash",
                Map.of("command", "rm -rf /"));
        assertEquals(PermissionDecision.DENY, decision);
    }

    @Test
    @DisplayName("危险路径（/etc/）→ ASK")
    void dangerousPathAsk() {
        PermissionDecision decision = engine.check("edit_file",
                Map.of("path", "/etc/passwd"));
        assertEquals(PermissionDecision.ASK, decision);
    }

    @Test
    @DisplayName("安全参数 → ALLOW")
    void safeArgumentsAllow() {
        PermissionDecision decision = engine.check("get_weather",
                Map.of("city", "Beijing", "unit", "celsius"));
        assertEquals(PermissionDecision.ALLOW, decision);
    }

    @Test
    @DisplayName("禁用内置检查时安全规则不生效")
    void builtInCheckDisabledSkipsChecks() {
        engine.setBuiltInChecksEnabled(false);

        PermissionDecision decision = engine.check("bash",
                Map.of("command", "rm -rf /"));
        // DONT_ASK 模式下，无规则命中，模式引擎不拦截 → ALLOW
        assertEquals(PermissionDecision.ALLOW, decision);
    }

    @Test
    @DisplayName("空参数时内置检查返回 ALLOW")
    void builtInCheckEmptyArgsReturnsAllow() {
        PermissionDecision decision = engine.check("some_tool", Map.of());
        assertEquals(PermissionDecision.ALLOW, decision);
    }

    @Test
    @DisplayName("null 参数时内置检查返回 ALLOW")
    void builtInCheckNullArgsReturnsAllow() {
        PermissionDecision decision = engine.check("some_tool", null);
        assertEquals(PermissionDecision.ALLOW, decision);
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
    @DisplayName("PermissionMiddleware 拒绝 DENY 决策的工具调用")
    void permissionMiddlewareDeniesDeniedTool() {
        engine.addRule(new PermissionRule("delete_file", PermissionDecision.DENY, "forbidden"));
        PermissionMiddleware pm = new PermissionMiddleware(engine);
        MiddlewareChain chain = new MiddlewareChain();
        chain.add(pm);

        AgentContext ctx = new AgentContext("agent-1", "sess-1", "user-1");
        ContentBlock.ToolCallBlock toolCall = new ContentBlock.ToolCallBlock("c1", "delete_file", "{}");

        assertThrows(PermissionDeniedException.class, () -> chain.fireToolCall(ctx, toolCall));
    }

    @Test
    @DisplayName("PermissionMiddleware ASK 决策抛出 PermissionDeniedException")
    void permissionMiddlewareAskDecisionThrows() {
        // 使用 EXPLORE 模式下不匹配的只读工具 + 危险路径触发 ASK
        engine.setMode(PermissionMode.DONT_ASK);
        engine.setBuiltInChecksEnabled(true);

        PermissionMiddleware pm = new PermissionMiddleware(engine);
        MiddlewareChain chain = new MiddlewareChain();
        chain.add(pm);

        AgentContext ctx = new AgentContext("agent-1", "sess-1", "user-1");
        // 触发危险路径内置检查 → ASK → 中间件自动拒绝
        ContentBlock.ToolCallBlock toolCall = new ContentBlock.ToolCallBlock("c1", "read_file", "{\"path\":\"/etc/passwd\"}");

        assertThrows(PermissionDeniedException.class, () -> chain.fireToolCall(ctx, toolCall));
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
        PermissionRule rule = new PermissionRule("tool_a", PermissionDecision.DENY, "test reason", true);
        assertEquals("tool_a", rule.getToolName());
        assertEquals(PermissionDecision.DENY, rule.getAction());
        assertEquals("test reason", rule.getReason());
        assertTrue(rule.isBypassImmune());
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
