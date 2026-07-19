package com.demo.agentscope.skill;

/**
 * 技能存储层异常族（unchecked）。
 * <p>
 * 把存储层的失败映射成三类业务可识别的异常，避免上层用 {@code catch (Exception)}
 * 一把抓。所有子类均继承 {@link RuntimeException}，调用方按需选择捕获。
 * </p>
 */
public class SkillStoreException extends RuntimeException {

    public SkillStoreException(String message) {
        super(message);
    }

    public SkillStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 指定的 skillId / slug 不存在。 */
    public static class NotFound extends SkillStoreException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** 字段非法（如 name 为空、超过长度上限）。 */
    public static class Invalid extends SkillStoreException {
        public Invalid(String message) {
            super(message);
        }

        public Invalid(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** IO / 序列化 / 文件系统错误。 */
    public static class IO extends SkillStoreException {
        public IO(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
