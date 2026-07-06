package com.demo.agentscope.team;

/**
 * Artifact 相关异常的基类。所有具体异常继承自它，便于调用方按需 catch。
 */
public abstract class ArtifactException extends RuntimeException {

    private final String artifactId;

    protected ArtifactException(String message, String artifactId) {
        super(message);
        this.artifactId = artifactId;
    }

    protected ArtifactException(String message, String artifactId, Throwable cause) {
        super(message, cause);
        this.artifactId = artifactId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    /** artifact 不存在（manifest 缺失或文件丢失）。 */
    public static class NotFound extends ArtifactException {
        public NotFound(String artifactId) {
            super("Artifact 不存在: " + artifactId, artifactId);
        }
    }

    /** 调用者无权访问该 artifact。 */
    public static class AccessDenied extends ArtifactException {
        public AccessDenied(String artifactId, String requester) {
            super("无权访问 artifact " + artifactId + "，请求者: " + requester, artifactId);
        }
    }

    /** sha256 校验失败（文件损坏或被篡改）。 */
    public static class ChecksumMismatch extends ArtifactException {
        private final String expected;
        private final String actual;

        public ChecksumMismatch(String artifactId, String expected, String actual) {
            super("Checksum 校验失败 artifact=" + artifactId + " expected=" + expected + " actual=" + actual,
                    artifactId);
            this.expected = expected;
            this.actual = actual;
        }

        public String getExpected() { return expected; }
        public String getActual() { return actual; }
    }

    /** 参数非法（扩展名不在白名单、content 为空、recipients 为空等）。 */
    public static class Invalid extends ArtifactException {
        public Invalid(String message) {
            super(message, null);
        }
    }
}
