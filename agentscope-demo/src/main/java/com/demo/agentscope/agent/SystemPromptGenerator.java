package com.demo.agentscope.agent;

import com.demo.agentscope.config.AgentLimits;

/**
 * 系统提示词生成器函数式接口。
 * <p>
 * 由主应用装配时传入，支持 {@link Agent#regenerateSystemPrompt} 在运行期
 * （例如 REPL {@code /config set} 修改限制后）回调重建系统提示词。
 * </p>
 */
@FunctionalInterface
public interface SystemPromptGenerator {
    /**
     * 根据当前股票工具开关与运行时限制生成系统提示词。
     *
     * @param stockEnabled 当前是否启用股票分析工具
     * @param limits       当前运行时限制（不为 null）
     * @return 完整的系统提示词文本
     */
    String generate(boolean stockEnabled, AgentLimits limits);
}
