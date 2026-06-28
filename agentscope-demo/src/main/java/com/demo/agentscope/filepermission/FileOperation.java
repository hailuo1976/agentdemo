package com.demo.agentscope.filepermission;

/**
 * 文件操作类型枚举。
 */
public enum FileOperation {
    READ("读取"),
    WRITE("写入"),
    EDIT("编辑"),
    DELETE("删除"),
    LIST("列目录"),
    EXECUTE("执行命令");

    private final String description;

    FileOperation(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
