package com.demo.agentscope.agent;

/**
 * 共享的系统提示词片段。
 */
public final class SystemPrompts {

    private SystemPrompts() {
    }

    /**
     * 可用工具列表及调用规范（leader 与 worker 共享）。
     * <p>
     * 单独抽取的原因：Leader LLM 通过 {@code agent_create} 自由生成的 worker
     * system_prompt 往往只描述角色和任务，漏掉具体的工具调用约束，导致 worker
     * 调用 {@code write_file} 时漏传 path。把这段规范集中到一处，避免双源维护漂移。
     * </p>
     */
    public static final String TOOL_CALL_NORMS = """
            ## 可用工具及调用规范

            调用工具时必须严格按 schema 传参，所有标 required 的参数不得省略、不得传空字符串、不得传 "null"：

            - read_file(path)：读取文件，path 必填（相对工作空间根目录）
            - write_file(path, content)：写入文件，path 和 content 均必填，必须明确指定文件名（不得用 default.txt 等兜底）
            - edit_file(path, old_text, new_text)：编辑文件，三个参数均必填
            - list_files(dir)：列目录，dir 可省略（默认工作空间根目录）
            - execute_python(code)：执行 Python 代码，code 必填（支持多行）
            - execute_command(command)：执行 Shell 命令，command 必填
            - install_package(package)：pip 安装包，package 必填
            """;
}
