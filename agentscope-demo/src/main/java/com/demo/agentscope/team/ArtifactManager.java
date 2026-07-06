package com.demo.agentscope.team;

import com.demo.agentscope.filepermission.PathSecurityUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Artifact 管理服务 —— 团队内文件形式消息传递的核心。
 * <p>
 * 负责：发送（写文件 + 写 manifest）、列出（按 sender/recipient ACL）、接收（读取 + sha256 校验 + 状态推进）、
 * 标记已读、元数据查询、完整性校验、团队解散时归档。
 * </p>
 * <p>
 * 存储布局（root 为 workspace 根）：
 * <ul>
 *   <li>{@code artifacts/{teamIdShort}/{artifactId}.{ext}} — 实际文件内容</li>
 *   <li>{@code artifacts/manifests/{artifactId}.json} — 元数据</li>
 * </ul>
 * </p>
 * <p>
 * 命名：{@code artifactId = "art_" + ts + "_" + uuid8}。存储文件名由 Manager 内部生成，
 * 拒绝接受 sender 指定的存储路径，规避路径穿越。
 * </p>
 */
public class ArtifactManager {

    private static final Logger log = LoggerFactory.getLogger(ArtifactManager.class);

    /** 扩展名 → mime 推断白名单。不在白名单的扩展名会被拒绝。 */
    private static final Map<String, String> EXT_MIME = new HashMap<>();

    static {
        EXT_MIME.put("txt", "text/plain");
        EXT_MIME.put("md", "text/markdown");
        EXT_MIME.put("json", "application/json");
        EXT_MIME.put("csv", "text/csv");
        EXT_MIME.put("py", "text/x-python");
        EXT_MIME.put("java", "text/x-java");
        EXT_MIME.put("js", "text/x-javascript");
        EXT_MIME.put("ts", "text/x-typescript");
        EXT_MIME.put("go", "text/x-go");
        EXT_MIME.put("sh", "text/x-shellscript");
        EXT_MIME.put("yaml", "application/yaml");
        EXT_MIME.put("yml", "application/yaml");
        EXT_MIME.put("xml", "application/xml");
        EXT_MIME.put("html", "text/html");
        EXT_MIME.put("png", "image/png");
        EXT_MIME.put("jpg", "image/jpeg");
        EXT_MIME.put("jpeg", "image/jpeg");
        EXT_MIME.put("gif", "image/gif");
        EXT_MIME.put("pdf", "application/pdf");
        EXT_MIME.put("log", "text/plain");
    }

    private final Path artifactsDir;       // {root}/artifacts/{teamIdShort}
    private final Path manifestDir;        // {root}/artifacts/manifests
    private final String teamId;
    private final String teamIdShort;
    private final ObjectMapper objectMapper;
    private final Map<String, Artifact> cache;

    /** 单文件大小上限（字节）；&lt;=0 表示不限制。可由上层通过 setter 维护。 */
    private long maxFileSizeBytes = 10L * 1024 * 1024;  // 默认 10MB

    public ArtifactManager(Path workspaceRoot, String teamId) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.teamIdShort = teamId.length() > 8 ? teamId.substring(0, 8) : teamId;
        this.artifactsDir = workspaceRoot.resolve("artifacts").resolve(teamIdShort);
        this.manifestDir = workspaceRoot.resolve("artifacts").resolve("manifests");
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.cache = new ConcurrentHashMap<>();

        try {
            Files.createDirectories(artifactsDir);
            Files.createDirectories(manifestDir);
        } catch (IOException e) {
            log.error("创建 artifact 目录失败: artifactsDir={}, manifestDir={}", artifactsDir, manifestDir, e);
        }
        loadAll();
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    // ==================== 发送 ====================

    /**
     * 发送一个 artifact：写入文件 + manifest，初始化所有 recipient 状态为 SENT。
     *
     * @param sender     发送者 agentName（来自 ThreadLocal）
     * @param recipients 接收者列表（worker 名或字面值 "leader"）
     * @param filename   原始文件名（含扩展），用于推断 ext 与 mime
     * @param mimeType   可选；为 null 时按扩展名推断
     * @param content    原始字节内容
     * @param description 描述
     * @param tags       标签
     * @return 创建的 Artifact
     * @throws ArtifactException.Invalid 参数非法（content 为空、扩展名不在白名单、超过大小上限）
     */
    public Artifact send(String sender, List<String> recipients,
                         String filename, String mimeType,
                         byte[] content, String description, List<String> tags) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(filename, "filename");
        if (recipients == null || recipients.isEmpty()) {
            throw new ArtifactException.Invalid("recipients 不能为空");
        }
        if (content == null || content.length == 0) {
            throw new ArtifactException.Invalid("content 不能为空");
        }
        if (maxFileSizeBytes > 0 && content.length > maxFileSizeBytes) {
            throw new ArtifactException.Invalid(
                    "文件大小 " + content.length + " 超过上限 " + maxFileSizeBytes);
        }

        String ext = PathSecurityUtil.getExtension(filename);
        if (!EXT_MIME.containsKey(ext)) {
            throw new ArtifactException.Invalid("扩展名不在白名单: " + ext);
        }
        String inferredMime = mimeType != null ? mimeType : EXT_MIME.get(ext);

        String artifactId = "art_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().substring(0, 8);
        String storedFilename = artifactId + "." + ext;
        Path storedPath = artifactsDir.resolve(storedFilename);
        String relativeStored = artifactsDir.getParent().getFileName() + "/"
                + teamIdShort + "/" + storedFilename; // artifacts/{teamIdShort}/{file}

        String checksum = sha256(content);

        try {
            Files.write(storedPath, content);
        } catch (IOException e) {
            throw new ArtifactException.Invalid("写入文件失败: " + storedPath + ": " + e.getMessage());
        }

        Map<String, String> recipientStatus = new HashMap<>();
        for (String r : recipients) {
            recipientStatus.put(r, Artifact.STATUS_SENT);
        }

        Artifact artifact = new Artifact(
                artifactId, teamId, sender, new ArrayList<>(recipients),
                filename, relativeStored, ext, inferredMime,
                content.length, checksum, description, tags, null,
                recipientStatus, Instant.now(), null);

        cache.put(artifactId, artifact);
        persistManifest(artifact);
        log.info("Artifact 已发送: id={} filename={} sender={} recipients={} size={} checksum={}",
                artifactId, filename, sender, recipients, content.length, checksum);
        return artifact;
    }

    // ==================== 列出 ====================

    /**
     * 列出调用者可见的 artifact（sender 或 recipient）。
     *
     * @param requester 调用者
     * @param filter    可选过滤器（status / tag / sender / limit）
     * @return 摘要列表
     */
    public List<ArtifactSummary> list(String requester, ArtifactFilter filter) {
        List<ArtifactSummary> out = new ArrayList<>();
        for (Artifact a : cache.values()) {
            if (!a.isAccessibleBy(requester)) continue;
            String statusForRequester = statusFor(a, requester);
            if (filter != null) {
                if (filter.status != null && !filter.status.equals(statusForRequester)) continue;
                if (filter.tag != null && (a.getTags() == null || !a.getTags().contains(filter.tag))) continue;
                if (filter.sender != null && !filter.sender.equals(a.getSender())) continue;
            }
            out.add(new ArtifactSummary(
                    a.getArtifactId(), a.getSender(), a.getFilename(), a.getFormat(),
                    a.getSizeBytes(), a.getChecksum(), a.getDescription(),
                    a.getTags(), statusForRequester, a.getCreatedAt()));
        }
        out.sort(Comparator.comparing(ArtifactSummary::getCreatedAt).reversed());
        int limit = (filter != null && filter.limit > 0) ? filter.limit : 20;
        if (out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    // ==================== 接收 ====================

    /**
     * 读取 artifact 内容 + 校验 sha256 + 推进状态。
     *
     * @throws ArtifactException.NotFound 不存在
     * @throws ArtifactException.AccessDenied 调用者无权访问
     * @throws ArtifactException.ChecksumMismatch sha256 不匹配
     */
    public ArtifactContent receive(String requester, String artifactId) {
        Artifact a = requireAccessible(artifactId, requester);
        Path file = resolveStoredPath(a);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | OutOfMemoryError e) {
            throw new ArtifactException.NotFound(artifactId);
        }
        String actual = sha256(bytes);
        if (!actual.equals(a.getChecksum())) {
            throw new ArtifactException.ChecksumMismatch(artifactId, a.getChecksum(), actual);
        }
        promoteStatus(a, requester, Artifact.STATUS_RECEIVED);
        log.info("Artifact 已接收: id={} requester={} size={}", artifactId, requester, bytes.length);
        return new ArtifactContent(a, bytes);
    }

    // ==================== 标记已读 ====================

    public boolean markRead(String requester, String artifactId) {
        Artifact a = requireAccessible(artifactId, requester);
        promoteStatus(a, requester, Artifact.STATUS_READ);
        log.info("Artifact 标记已读: id={} requester={}", artifactId, requester);
        return true;
    }

    // ==================== 元数据查询 ====================

    public Artifact getMetadata(String requester, String artifactId) {
        return requireAccessible(artifactId, requester);
    }

    /** 主动校验文件 sha256 与 manifest 是否一致（不要求 requester 视角）。 */
    public boolean verifyIntegrity(String artifactId) {
        Artifact a = cache.get(artifactId);
        if (a == null) return false;
        Path file = resolveStoredPath(a);
        try {
            byte[] bytes = Files.readAllBytes(file);
            return sha256(bytes).equals(a.getChecksum());
        } catch (IOException e) {
            return false;
        }
    }

    // ==================== 归档 ====================

    public void archiveAll(Instant dissolvedAt) {
        Instant ts = dissolvedAt != null ? dissolvedAt : Instant.now();
        for (Artifact a : cache.values()) {
            if (a.getDissolvedAt() == null) {
                a.setDissolvedAt(ts);
                persistManifest(a);
            }
        }
        log.info("Artifact 已归档: teamId={} count={}", teamId, cache.size());
    }

    // ==================== 内部辅助 ====================

    private Artifact requireAccessible(String artifactId, String requester) {
        Artifact a = cache.get(artifactId);
        if (a == null) {
            throw new ArtifactException.NotFound(artifactId);
        }
        if (!a.isAccessibleBy(requester)) {
            throw new ArtifactException.AccessDenied(artifactId, requester);
        }
        return a;
    }

    private String statusFor(Artifact a, String requester) {
        if (requester.equals(a.getSender())) {
            return Artifact.STATUS_SENT;  // 发送者视角固定 SENT（已发出）
        }
        Map<String, String> rs = a.getRecipientStatus();
        String s = rs != null ? rs.get(requester) : null;
        return s != null ? s : Artifact.STATUS_SENT;
    }

    /**
     * 状态推进：SENT → RECEIVED → READ，单调向前。
     */
    private void promoteStatus(Artifact a, String requester, String target) {
        if (requester.equals(a.getSender())) return;  // sender 不推进
        Map<String, String> rs = a.getRecipientStatus();
        if (rs == null) {
            rs = new HashMap<>();
            a.setRecipientStatus(rs);
        }
        String current = rs.getOrDefault(requester, Artifact.STATUS_SENT);
        int rankCurrent = rank(current);
        int rankTarget = rank(target);
        if (rankTarget > rankCurrent) {
            rs.put(requester, target);
            persistManifest(a);
        }
    }

    private static int rank(String status) {
        return switch (status) {
            case Artifact.STATUS_SENT -> 0;
            case Artifact.STATUS_RECEIVED -> 1;
            case Artifact.STATUS_READ -> 2;
            default -> 0;
        };
    }

    private Path resolveStoredPath(Artifact a) {
        // storedPath 形如 "artifacts/{teamIdShort}/{file}"，相对 workspace root。
        // 但此处我们直接根据 artifactsDir 推算：取文件名即可。
        String stored = a.getStoredPath();
        String filename = stored.substring(stored.lastIndexOf('/') + 1);
        return artifactsDir.resolve(filename);
    }

    private void persistManifest(Artifact artifact) {
        Path file = manifestDir.resolve(artifact.getArtifactId() + ".json");
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(artifact);
            Files.writeString(file, json);
        } catch (IOException e) {
            log.error("持久化 manifest 失败: {}", artifact.getArtifactId(), e);
        }
    }

    private void loadAll() {
        if (!Files.isDirectory(manifestDir)) return;
        try (Stream<Path> stream = Files.list(manifestDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                  .forEach(this::loadManifestFile);
        } catch (IOException e) {
            log.error("枚举 manifest 失败: {}", manifestDir, e);
        }
        log.info("Artifact 已加载: teamId={} count={}", teamId, cache.size());
    }

    private void loadManifestFile(Path path) {
        try {
            String json = Files.readString(path);
            Artifact a = objectMapper.readValue(json, Artifact.class);
            cache.put(a.getArtifactId(), a);
        } catch (IOException e) {
            log.warn("加载 manifest 失败: {}", path, e);
        }
    }

    // ==================== 静态工具 ====================

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** list 过滤器。 */
    public static class ArtifactFilter {
        public String status;
        public String tag;
        public String sender;
        public int limit = 20;

        public ArtifactFilter status(String s) { this.status = s; return this; }
        public ArtifactFilter tag(String t) { this.tag = t; return this; }
        public ArtifactFilter sender(String s) { this.sender = s; return this; }
        public ArtifactFilter limit(int n) { this.limit = n; return this; }
    }
}
