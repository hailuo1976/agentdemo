package com.demo.agentscope.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 技能持久化层。
 * <p>
 * 混合存储布局（{@code {skillsRoot}}）：
 * </p>
 * <pre>
 * skillsRoot/
 * ├── index.json                     # 摘要索引 + schemaVersion
 * ├── manifests/{id}.json            # 完整 Skill JSON
 * ├── versions/{id}/v{n}.json        # 历史版本快照
 * ├── content/{id}/                  # 预留（附件目录，MVP 不使用）
 * └── access.log                     # JSONL 访问日志（追加写）
 * </pre>
 *
 * <h3>并发</h3>
 * 进程内 {@link ReentrantLock} 互斥所有写路径；多进程场景为未来工作。
 *
 * <h3>原子写</h3>
 * 参照 Claude Code {@code services/mcp/config.ts:writeMcpjsonFile} 与
 * {@code utils/plugins/zipCache.ts:atomicWriteToZipCache}：temp 文件 + {@code FileChannel.force(true)}
 * + {@code Files.move(ATOMIC_MOVE, REPLACE_EXISTING)} + 失败清理 temp。
 *
 * <h3>schema 版本</h3>
 * 参照 Claude Code {@code utils/statsCache.ts:14 STATS_CACHE_VERSION}：{@code index.json} 顶层 schemaVersion=1。
 * 缺失 → 视为 1 + 重建；相等 → 直接用；高于当前 → 日志 warn + 重建；低于当前 → 未来迁移。
 */
public class SkillStore {

    private static final Logger log = LoggerFactory.getLogger(SkillStore.class);

    /** 当前 index.json schema 版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final Path root;
    private final Path indexPath;
    private final Path manifestsDir;
    private final Path versionsDir;
    private final Path contentDir;
    private final Path accessLog;

    private final ObjectMapper mapper;
    /** 紧凑 mapper：access.log 必须一行一条 JSON（JSONL），不能用 INDENT_OUTPUT。 */
    private final ObjectMapper compactMapper;
    private final ReentrantLock writeLock = new ReentrantLock();

    /** 内存中的完整 Skill 缓存（id -> Skill）。启动时从 manifests 重建或从 index 加载。 */
    private final Map<String, Skill> cache = new ConcurrentHashMap<>();

    public SkillStore(Path skillsRoot) {
        this.root = Objects.requireNonNull(skillsRoot, "skillsRoot");
        this.indexPath = root.resolve("index.json");
        this.manifestsDir = root.resolve("manifests");
        this.versionsDir = root.resolve("versions");
        this.contentDir = root.resolve("content");
        this.accessLog = root.resolve("access.log");

        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

        this.compactMapper = new ObjectMapper();
        this.compactMapper.registerModule(new JavaTimeModule());
        this.compactMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        try {
            Files.createDirectories(root);
            Files.createDirectories(manifestsDir);
            Files.createDirectories(versionsDir);
            Files.createDirectories(contentDir);
        } catch (IOException e) {
            log.error("创建技能目录失败: {}", root, e);
        }
        loadFromDisk();
    }

    // ==================== 加载 / 重建 ====================

    /**
     * 启动加载：优先读 index.json，schemaVersion 异常或缺失则从 manifests 重建。
     */
    private void loadFromDisk() {
        writeLock.lock();
        try {
            IndexFile index = readIndexSafely();
            if (index != null && index.schemaVersion == CURRENT_SCHEMA_VERSION && index.skills != null) {
                // 用 index 做向导，逐个读 manifests/{id}.json
                boolean anyMissing = false;
                for (SkillSummary s : index.skills) {
                    Skill full = readManifest(s.id);
                    if (full != null) {
                        cache.put(full.getId(), full);
                    } else {
                        anyMissing = true;
                        log.warn("index.json 引用的 manifest 不存在或解析失败: id={}", s.id);
                    }
                }
                if (anyMissing) {
                    log.info("检测到 index 与 manifests 不一致，重建 index.json");
                    writeIndex();
                }
            } else {
                // 重建：扫描 manifests/ 全量加载
                log.info("index.json 缺失或版本不匹配，从 manifests 重建");
                rebuildFromManifests();
                writeIndex();
            }
            log.info("技能已加载: count={}", cache.size());
        } finally {
            writeLock.unlock();
        }
    }

    private IndexFile readIndexSafely() {
        if (!Files.exists(indexPath)) return null;
        try {
            String json = Files.readString(indexPath);
            IndexFile idx = mapper.readValue(json, IndexFile.class);
            if (idx.schemaVersion > CURRENT_SCHEMA_VERSION) {
                log.warn("index.json schemaVersion={} 高于当前 {}，按重建处理",
                        idx.schemaVersion, CURRENT_SCHEMA_VERSION);
                return null;
            }
            return idx;
        } catch (IOException e) {
            log.warn("读取 index.json 失败，将重建: {}", indexPath, e);
            return null;
        }
    }

    private void rebuildFromManifests() {
        cache.clear();
        if (!Files.isDirectory(manifestsDir)) return;
        try (Stream<Path> stream = Files.list(manifestsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                  .forEach(p -> {
                      Skill s = readManifestFile(p);
                      if (s != null && s.getId() != null) {
                          cache.put(s.getId(), s);
                      }
                  });
        } catch (IOException e) {
            log.error("枚举 manifests 失败: {}", manifestsDir, e);
        }
    }

    private Skill readManifest(String id) {
        Path file = manifestsDir.resolve(safeFileName(id) + ".json");
        return readManifestFile(file);
    }

    private Skill readManifestFile(Path file) {
        try {
            String json = Files.readString(file);
            return mapper.readValue(json, Skill.class);
        } catch (IOException e) {
            log.warn("读取 manifest 失败: {}", file, e);
            return null;
        }
    }

    /** 重新加载磁盘状态（主要供测试与管理员动作使用）。 */
    public void reloadFromDisk() {
        loadFromDisk();
    }

    // ==================== CRUD ====================

    /**
     * 创建新技能。
     * <ul>
     *   <li>生成 id 与 slug（若未提供）</li>
     *   <li>初始化 createdAt/updatedAt/version=1/status=DRAFT</li>
     *   <li>写 manifest + 更新 index</li>
     * </ul>
     */
    public Skill create(Skill input) {
        Objects.requireNonNull(input, "input");
        if (input.getName() == null || input.getName().isBlank()) {
            throw new SkillStoreException.Invalid("name 不能为空");
        }
        if (input.getName().length() > 80) {
            throw new SkillStoreException.Invalid("name 超过 80 字符上限");
        }
        if (input.getDescription() == null || input.getDescription().isBlank()) {
            throw new SkillStoreException.Invalid("description 不能为空");
        }

        writeLock.lock();
        try {
            Instant now = Instant.now();
            String id = "sk_" + System.currentTimeMillis() + "_"
                    + UUID.randomUUID().toString().substring(0, 8);
            input.setId(id);
            if (input.getSlug() == null || input.getSlug().isBlank()) {
                input.setSlug(slugify(input.getName()));
            }
            input.setStatus(input.getStatus() == null ? SkillStatus.DRAFT : input.getStatus());
            input.setVersion(1);
            input.setCreatedAt(now);
            input.setUpdatedAt(now);
            input.setUseCount(0);

            writeManifest(input);
            cache.put(id, input);
            writeIndex();
            log.info("技能已创建: id={} slug={} name={}", id, input.getSlug(), input.getName());
            return input;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 按 id 加载完整 Skill；找不到抛 {@link SkillStoreException.NotFound}。
     */
    public Skill load(String id) {
        Objects.requireNonNull(id, "id");
        Skill s = cache.get(id);
        if (s == null) {
            throw new SkillStoreException.NotFound("技能不存在: " + id);
        }
        return s;
    }

    /** 是否存在。 */
    public boolean exists(String id) {
        return id != null && cache.containsKey(id);
    }

    /**
     * 按 slug 查找；找不到抛 NotFound。
     */
    public Skill findBySlug(String slug) {
        Objects.requireNonNull(slug, "slug");
        for (Skill s : cache.values()) {
            if (slug.equals(s.getSlug())) return s;
        }
        throw new SkillStoreException.NotFound("slug 不存在: " + slug);
    }

    /**
     * 更新技能字段（name/description/tags/steps/cases/successCases/resources/visibility/author）。
     * <p>version 自增；旧版本归档到 {@code versions/{id}/v{n}.json}。</p>
     */
    public Skill update(String id, Skill patch) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patch, "patch");
        writeLock.lock();
        try {
            Skill current = load(id);
            int oldVersion = current.getVersion();
            int newVersion = oldVersion + 1;

            // 先归档旧版本
            archiveVersion(current);

            // 套用 patch（非 null 字段覆盖）
            if (patch.getName() != null) {
                if (patch.getName().isBlank() || patch.getName().length() > 80) {
                    throw new SkillStoreException.Invalid("name 非法");
                }
                current.setName(patch.getName());
            }
            if (patch.getDescription() != null) current.setDescription(patch.getDescription());
            if (patch.getTags() != null) current.setTags(new ArrayList<>(patch.getTags()));
            if (patch.getSteps() != null) current.setSteps(new ArrayList<>(patch.getSteps()));
            if (patch.getCases() != null) current.setCases(new ArrayList<>(patch.getCases()));
            if (patch.getSuccessCases() != null) current.setSuccessCases(new ArrayList<>(patch.getSuccessCases()));
            if (patch.getResources() != null) current.setResources(new ArrayList<>(patch.getResources()));
            if (patch.getVisibility() != null) current.setVisibility(patch.getVisibility());
            if (patch.getAuthor() != null) current.setAuthor(patch.getAuthor());
            if (patch.getSlug() != null && !patch.getSlug().isBlank()) current.setSlug(patch.getSlug());

            current.setVersion(newVersion);
            current.setUpdatedAt(Instant.now());

            writeManifest(current);
            writeIndex();
            log.info("技能已更新: id={} version={} -> {}", id, oldVersion, newVersion);
            return current;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 软删除：status → DEPRECATED。物理删除见 {@link #purge(String)}。
     */
    public Skill deprecate(String id) {
        return transitionStatus(id, SkillStatus.DEPRECATED);
    }

    /**
     * 发布：status → PUBLISHED，写入 publishedAt。
     */
    public Skill publish(String id) {
        writeLock.lock();
        try {
            Skill s = load(id);
            if (s.getStatus() == SkillStatus.PUBLISHED) return s;
            s.setStatus(SkillStatus.PUBLISHED);
            s.setPublishedAt(Instant.now());
            s.setUpdatedAt(Instant.now());
            writeManifest(s);
            writeIndex();
            log.info("技能已发布: id={}", id);
            return s;
        } finally {
            writeLock.unlock();
        }
    }

    /** 复位为草稿。 */
    public Skill unpublish(String id) {
        return transitionStatus(id, SkillStatus.DRAFT);
    }

    private Skill transitionStatus(String id, SkillStatus target) {
        writeLock.lock();
        try {
            Skill s = load(id);
            s.setStatus(target);
            s.setUpdatedAt(Instant.now());
            writeManifest(s);
            writeIndex();
            return s;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 物理删除：移除 manifest、versions、index 条目。不可逆。
     */
    public void purge(String id) {
        Objects.requireNonNull(id, "id");
        writeLock.lock();
        try {
            if (!cache.containsKey(id)) {
                throw new SkillStoreException.NotFound("技能不存在: " + id);
            }
            cache.remove(id);
            try {
                Files.deleteIfExists(manifestsDir.resolve(safeFileName(id) + ".json"));
            } catch (IOException e) {
                log.warn("删除 manifest 失败: {}", id, e);
            }
            Path versionDir = versionsDir.resolve(safeFileName(id));
            if (Files.isDirectory(versionDir)) {
                try (Stream<Path> stream = Files.walk(versionDir)) {
                    stream.sorted(Comparator.reverseOrder())
                          .forEach(p -> {
                              try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                          });
                } catch (IOException e) {
                    log.warn("清理 versions 目录失败: {}", versionDir, e);
                }
            }
            writeIndex();
            log.info("技能已物理删除: id={}", id);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 记录一次访问：useCount + 1，lastUsedAt 更新；写 manifest + index。
     * <p>不抛 NotFound —— 若缺失则静默忽略（调用方应在 load 之前校验）。</p>
     */
    public Skill incrementUse(String id) {
        writeLock.lock();
        try {
            Skill s = cache.get(id);
            if (s == null) return null;
            s.setUseCount(s.getUseCount() + 1);
            s.setLastUsedAt(Instant.now());
            writeManifest(s);
            writeIndex();
            return s;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 列出全部技能（不可变视图）。
     */
    public List<Skill> listAll() {
        return List.copyOf(cache.values());
    }

    /**
     * 列出技能摘要（用于 index.json 与 list 工具）。摘要字段：id/slug/name/tags/status/version/updatedAt/useCount。
     */
    public List<SkillSummary> listSummaries() {
        List<SkillSummary> out = new ArrayList<>(cache.size());
        for (Skill s : cache.values()) {
            out.add(new SkillSummary(
                    s.getId(),
                    s.getSlug(),
                    s.getName(),
                    s.getTags() != null ? List.copyOf(s.getTags()) : List.of(),
                    s.getStatus() == null ? SkillStatus.DRAFT : s.getStatus(),
                    s.getVersion(),
                    s.getUpdatedAt(),
                    s.getUseCount()));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 列出某技能的全部历史版本文件（按版本号升序）。
     */
    public List<VersionEntry> listVersions(String id) {
        Objects.requireNonNull(id, "id");
        Path dir = versionsDir.resolve(safeFileName(id));
        if (!Files.isDirectory(dir)) return List.of();
        List<VersionEntry> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.endsWith(".json")) return;
                String stem = name.substring(0, name.length() - ".json".length());
                if (!stem.startsWith("v")) return;
                try {
                    int v = Integer.parseInt(stem.substring(1));
                    Skill snapshot = readManifestFile(p);
                    out.add(new VersionEntry(v, p, snapshot == null ? null : snapshot.getUpdatedAt()));
                } catch (NumberFormatException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("枚举版本失败: {}", dir, e);
        }
        out.sort(Comparator.comparingInt(VersionEntry::version));
        return out;
    }

    /**
     * 读历史版本快照。
     */
    public Skill readVersion(String id, int version) {
        Objects.requireNonNull(id, "id");
        Path file = versionsDir.resolve(safeFileName(id)).resolve("v" + version + ".json");
        if (!Files.exists(file)) {
            throw new SkillStoreException.NotFound("版本不存在: " + id + "/v" + version);
        }
        return readManifestFile(file);
    }

    // ==================== 访问日志 ====================

    /**
     * 追加一条访问日志（JSONL）。失败仅记日志，不抛异常（避免污染主流程）。
     */
    public void appendAccess(SkillAccessEntry entry) {
        Objects.requireNonNull(entry, "entry");
        writeLock.lock();
        try {
            String line;
            try {
                line = compactMapper.writeValueAsString(entry) + System.lineSeparator();
            } catch (IOException e) {
                log.warn("序列化 access log 失败: {}", entry, e);
                return;
            }
            try {
                Files.writeString(accessLog, line,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("写入 access.log 失败: {}", accessLog, e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 读取 access.log 全量，逐行解析为 {@link SkillAccessEntry}。坏行跳过并记 warn。
     */
    public List<SkillAccessEntry> readAccessLog() {
        if (!Files.exists(accessLog)) return List.of();
        List<SkillAccessEntry> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(accessLog, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line == null || line.isBlank()) return;
                try {
                    out.add(mapper.readValue(line, SkillAccessEntry.class));
                } catch (IOException e) {
                    log.warn("跳过非法 access.log 行: {}", line);
                }
            });
        } catch (IOException e) {
            log.warn("读取 access.log 失败: {}", accessLog, e);
        }
        return out;
    }

    // ==================== 原子写盘 ====================

    /**
     * 原子写 manifest：temp 文件 → force → rename。
     */
    private void writeManifest(Skill skill) {
        Path target = manifestsDir.resolve(safeFileName(skill.getId()) + ".json");
        writeJsonAtomic(target, skill);
    }

    /**
     * 归档当前 Skill 到 {@code versions/{id}/v{n}.json}。
     */
    private void archiveVersion(Skill skill) {
        int v = skill.getVersion();
        Path dir = versionsDir.resolve(safeFileName(skill.getId()));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new SkillStoreException.IO("创建版本目录失败: " + dir, e);
        }
        Path target = dir.resolve("v" + v + ".json");
        writeJsonAtomic(target, skill);
    }

    /**
     * 原子写 JSON 文件：写 temp → fsync → rename → 失败清理 temp。
     * <p>移植自 Claude Code {@code atomicWriteToZipCache}。</p>
     */
    private void writeJsonAtomic(Path target, Object value) {
        Path temp = target.resolveSibling("." + target.getFileName() + ".tmp");
        try {
            String json = mapper.writeValueAsString(value);
            Files.writeString(temp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            // fsync 文件内容
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            throw new SkillStoreException.IO("原子写失败: " + target, e);
        }
    }

    /**
     * 写 index.json（包含 schemaVersion + 摘要列表）。
     */
    private void writeIndex() {
        IndexFile idx = new IndexFile(CURRENT_SCHEMA_VERSION, listSummaries());
        writeJsonAtomic(indexPath, idx);
    }

    // ==================== 工具 ====================

    /** 把任意字符串转成 slug（小写、连字符、ASCII 安全）。 */
    public static String slugify(String name) {
        if (name == null) return "skill";
        String s = name.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                sb.append(c);
            } else if (c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ' || c == '.' || c == '/' || c == '\\' || c == '\t') {
                sb.append('-');
            } else {
                // CJK / 其他 → 转义为十六进制，保证 slug 是 ASCII 安全的文件名片段
                sb.append('_');
            }
        }
        String out = sb.toString();
        while (out.contains("--")) out = out.replace("--", "-");
        if (out.startsWith("-")) out = out.substring(1);
        if (out.endsWith("-")) out = out.substring(0, out.length() - 1);
        if (out.isEmpty()) out = "skill";
        // 限长 60，避免文件名过长
        if (out.length() > 60) out = out.substring(0, 60);
        return out;
    }

    /** 防御性文件名消毒（id 已是 sk_ts_uuid 形态，但兜底）。 */
    private static String safeFileName(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public Path getRoot() { return root; }

    public Path getIndexPath() { return indexPath; }

    public Path getManifestsDir() { return manifestsDir; }

    public Path getVersionsDir() { return versionsDir; }

    public Path getAccessLog() { return accessLog; }

    // ==================== index.json 数据结构 ====================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"schemaVersion", "skills"})
    public static class IndexFile {
        @JsonProperty("schemaVersion")
        public int schemaVersion;
        @JsonProperty("skills")
        public List<SkillSummary> skills;

        public IndexFile() {}

        public IndexFile(int schemaVersion, List<SkillSummary> skills) {
            this.schemaVersion = schemaVersion;
            this.skills = skills;
        }
    }

    /**
     * 技能摘要（index.json / skill_list 输出共用）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "slug", "name", "tags", "status", "version", "updatedAt", "useCount"})
    public static class SkillSummary {
        @JsonProperty("id") public final String id;
        @JsonProperty("slug") public final String slug;
        @JsonProperty("name") public final String name;
        @JsonProperty("tags") public final List<String> tags;
        @JsonProperty("status") public final SkillStatus status;
        @JsonProperty("version") public final int version;
        @JsonProperty("updatedAt") public final Instant updatedAt;
        @JsonProperty("useCount") public final int useCount;

        @com.fasterxml.jackson.annotation.JsonCreator
        public SkillSummary(
                @JsonProperty("id") String id,
                @JsonProperty("slug") String slug,
                @JsonProperty("name") String name,
                @JsonProperty("tags") List<String> tags,
                @JsonProperty("status") SkillStatus status,
                @JsonProperty("version") int version,
                @JsonProperty("updatedAt") Instant updatedAt,
                @JsonProperty("useCount") int useCount) {
            this.id = id;
            this.slug = slug;
            this.name = name;
            this.tags = tags;
            this.status = status;
            this.version = version;
            this.updatedAt = updatedAt;
            this.useCount = useCount;
        }
    }

    /**
     * 版本目录条目。
     */
    public record VersionEntry(int version, Path file, Instant updatedAt) {}
}
