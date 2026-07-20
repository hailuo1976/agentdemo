package com.demo.agentscope.memory;

/**
 * 6 个记忆管理工具的 JSON Schema 文本块。
 * <p>
 * 仅供 {@link MemoryToolService} 注册工具时使用；参数 schema 采用 JSON Schema draft-07 子集，
 * 与项目其他 {@code registerCustomTool} 调用风格一致（OpenAI function calling 兼容）。
 * </p>
 */
final class MemoryToolSchemas {

    private MemoryToolSchemas() {}

    static final String STORE = """
            {
              "type": "object",
              "properties": {
                "summary": { "type": "string", "description": "记忆摘要（必填，一句话描述这条经验/偏好/发现）" },
                "key_findings": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "关键发现列表（可空）"
                },
                "entities": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "相关实体列表（人名/项目/概念等，便于后续检索）"
                },
                "task_context": { "type": "string", "description": "任务上下文（来源场景，可空）" },
                "importance": { "type": "number", "description": "重要性 0.0-1.0（默认 0.5，用户偏好/领域知识建议 0.8+）", "default": 0.5 },
                "scope": { "type": "string", "enum": ["short", "long"], "description": "记忆范围：short=会话级（默认），long=跨会话长期", "default": "short" }
              },
              "required": ["summary"]
            }
            """;

    static final String RECALL = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "检索关键词（必填，匹配 summary/entities/keyFindings/taskContext）" },
                "scope": { "type": "string", "enum": ["short", "long", "both"], "description": "检索范围（默认 both）", "default": "both" },
                "limit": { "type": "integer", "description": "最大返回数量", "default": 5 }
              },
              "required": ["query"]
            }
            """;

    static final String LIST = """
            {
              "type": "object",
              "properties": {
                "scope": { "type": "string", "enum": ["short", "long", "both"], "description": "列出范围（默认 both）", "default": "both" },
                "limit": { "type": "integer", "description": "最大返回数量", "default": 20 }
              }
            }
            """;

    static final String PROMOTE = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "短期记忆 ID（m_ 开头，必填）" }
              },
              "required": ["id"]
            }
            """;

    static final String DELETE = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "记忆 ID（必填）" },
                "scope": { "type": "string", "enum": ["short", "long"], "description": "删除范围（必填，short 或 long）" }
              },
              "required": ["id", "scope"]
            }
            """;

    static final String STATS = """
            {
              "type": "object",
              "properties": {}
            }
            """;
}
