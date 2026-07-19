package com.demo.agentscope.skill;

/**
 * 12 个技能管理工具的 JSON Schema 文本块。
 * <p>
 * 仅供 {@link SkillToolService} 注册工具时使用；参数 schema 采用 JSON Schema draft-07 子集，
 * 与项目其他 {@code registerCustomTool} 调用风格一致（OpenAI function calling 兼容）。
 * </p>
 * <p>
 * 全部以 {@code """} 文本块形式提供，注册时原样传给 {@code MCPClient.registerCustomTool}。
 * </p>
 */
final class SkillToolSchemas {

    private SkillToolSchemas() {}

    static final String CREATE = """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string", "description": "技能名称（1-80 字符，必填）" },
                "description": { "type": "string", "description": "技能描述（必填，多行文本）" },
                "tags": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "标签列表（可空，会自动去重 trim）"
                },
                "steps": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "实施步骤（有序）"
                },
                "cases": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "应用场景"
                },
                "successCases": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "成功案例"
                },
                "resources": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "相关资源链接/引用"
                }
              },
              "required": ["name", "description"]
            }
            """;

    static final String GET = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "技能 ID（sk_ 开头）" }
              },
              "required": ["id"]
            }
            """;

    static final String LIST = """
            {
              "type": "object",
              "properties": {
                "tag": { "type": "string", "description": "按标签过滤（可空）" },
                "status": { "type": "string", "enum": ["DRAFT", "PUBLISHED", "DEPRECATED"], "description": "按状态过滤（可空）" },
                "limit": { "type": "integer", "description": "最大返回数量（<=0 表示不限）", "default": 50 }
              }
            }
            """;

    static final String UPDATE = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "技能 ID" },
                "name": { "type": "string" },
                "description": { "type": "string" },
                "tags": { "type": "array", "items": { "type": "string" } },
                "steps": { "type": "array", "items": { "type": "string" } },
                "cases": { "type": "array", "items": { "type": "string" } },
                "successCases": { "type": "array", "items": { "type": "string" } },
                "resources": { "type": "array", "items": { "type": "string" } }
              },
              "required": ["id"]
            }
            """;

    static final String DELETE = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "技能 ID" },
                "confirm": { "type": "boolean", "description": "必须为 true 才执行软删除", "default": false }
              },
              "required": ["id", "confirm"]
            }
            """;

    static final String SEARCH = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "查询字符串（必填）" },
                "tag": { "type": "string", "description": "按标签预过滤（可空）" },
                "limit": { "type": "integer", "description": "最大返回数量", "default": 10 }
              },
              "required": ["query"]
            }
            """;

    static final String PUBLISH = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "技能 ID（DRAFT → PUBLISHED）" }
              },
              "required": ["id"]
            }
            """;

    static final String DEPRECATE = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "技能 ID（任意状态 → DEPRECATED）" }
              },
              "required": ["id"]
            }
            """;

    static final String HISTORY = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "技能 ID" }
              },
              "required": ["id"]
            }
            """;

    static final String EXPORT = """
            {
              "type": "object",
              "properties": {
                "target_dir": { "type": "string", "description": "目标目录（相对路径会基于 workspace，不存在则创建）" },
                "tag": { "type": "string", "description": "按标签过滤（可空）" },
                "ids": { "type": "array", "items": { "type": "string" }, "description": "ID 白名单（可空）" }
              },
              "required": ["target_dir"]
            }
            """;

    static final String IMPORT = """
            {
              "type": "object",
              "properties": {
                "source_dir": { "type": "string", "description": "源目录（扫描 .md 文件）" }
              },
              "required": ["source_dir"]
            }
            """;

    static final String STATS = """
            {
              "type": "object",
              "properties": {
                "period": { "type": "string", "enum": ["7d", "30d", "all"], "description": "统计周期", "default": "all" }
              }
            }
            """;
}
