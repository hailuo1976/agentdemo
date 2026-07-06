package com.demo.agentscope.team;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ArtifactManager 单元测试。覆盖：
 * <ul>
 *   <li>send + receive + checksum</li>
 *   <li>权限拒绝（非 recipient）</li>
 *   <li>checksum 篡改检测</li>
 *   <li>状态迁移 SENT → RECEIVED → READ</li>
 *   <li>list 过滤</li>
 *   <li>扩展名白名单</li>
 *   <li>持久化重启加载</li>
 * </ul>
 */
public class ArtifactManagerTest {

    @TempDir
    Path tempDir;

    private ArtifactManager newManager() {
        return new ArtifactManager(tempDir, "team-test-1234");
    }

    // ==================== send ====================

    @Test
    public void send_writesFileAndManifest() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("worker-1", List.of("leader"),
                "report.json", null, "{\"k\":1}".getBytes(),
                "test report", List.of("test"));

        assertNotNull(a.getArtifactId());
        assertTrue(a.getArtifactId().startsWith("art_"));
        assertEquals("json", a.getFormat());
        assertEquals("application/json", a.getMimeType());
        // 实际文件存在
        Path stored = tempDir.resolve("artifacts").resolve("team-tes")
                .resolve(a.getArtifactId() + ".json");
        assertTrue(Files.isReadable(stored));
        // manifest 存在
        Path manifest = tempDir.resolve("artifacts").resolve("manifests")
                .resolve(a.getArtifactId() + ".json");
        assertTrue(Files.isReadable(manifest));
    }

    @Test
    public void send_computesSha256Checksum() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "x.txt",
                null, "hello".getBytes(), null, null);
        assertTrue(a.getChecksum().startsWith("sha256:"));
        assertEquals(71, a.getChecksum().length()); // "sha256:" + 64 hex
    }

    @Test
    public void send_rejectsInvalidExtension() {
        ArtifactManager mgr = newManager();
        assertThrows(ArtifactException.Invalid.class, () ->
                mgr.send("w", List.of("leader"), "evil.exe",
                        null, new byte[]{1}, null, null));
    }

    @Test
    public void send_rejectsEmptyContent() {
        ArtifactManager mgr = newManager();
        assertThrows(ArtifactException.Invalid.class, () ->
                mgr.send("w", List.of("leader"), "x.txt",
                        null, new byte[0], null, null));
    }

    @Test
    public void send_rejectsOversizedContent() {
        ArtifactManager mgr = newManager();
        mgr.setMaxFileSizeBytes(10);
        assertThrows(ArtifactException.Invalid.class, () ->
                mgr.send("w", List.of("leader"), "x.txt",
                        null, new byte[100], null, null));
    }

    @Test
    public void send_rejectsEmptyRecipients() {
        ArtifactManager mgr = newManager();
        assertThrows(ArtifactException.Invalid.class, () ->
                mgr.send("w", List.of(), "x.txt",
                        null, new byte[]{1}, null, null));
    }

    // ==================== receive ====================

    @Test
    public void receive_returnsContentAndVerifiesChecksum() {
        ArtifactManager mgr = newManager();
        byte[] payload = "{\"a\":1}".getBytes();
        Artifact a = mgr.send("worker-1", List.of("leader"),
                "r.json", null, payload, null, null);
        ArtifactContent c = mgr.receive("leader", a.getArtifactId());
        assertArrayEquals(payload, c.getBytes());
    }

    @Test
    public void receive_promotesSentToReceived() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        mgr.receive("leader", a.getArtifactId());
        Artifact meta = mgr.getMetadata("leader", a.getArtifactId());
        assertEquals(Artifact.STATUS_RECEIVED, meta.getRecipientStatus().get("leader"));
    }

    @Test
    public void receive_isIdempotent() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        mgr.receive("leader", a.getArtifactId());
        mgr.receive("leader", a.getArtifactId());  // 第二次不报错
        Artifact meta = mgr.getMetadata("leader", a.getArtifactId());
        assertEquals(Artifact.STATUS_RECEIVED, meta.getRecipientStatus().get("leader"));
    }

    @Test
    public void receive_rejectsNonRecipient() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        assertThrows(ArtifactException.AccessDenied.class, () ->
                mgr.receive("bystander", a.getArtifactId()));
    }

    @Test
    public void receive_detectsChecksumMismatch() throws Exception {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "hello".getBytes(), null, null);
        // 篡改存储文件
        Path stored = tempDir.resolve("artifacts").resolve("team-tes")
                .resolve(a.getArtifactId() + ".txt");
        Files.writeString(stored, "tampered");
        assertThrows(ArtifactException.ChecksumMismatch.class, () ->
                mgr.receive("leader", a.getArtifactId()));
    }

    @Test
    public void receive_rejectsMissingFile() throws Exception {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "hello".getBytes(), null, null);
        Path stored = tempDir.resolve("artifacts").resolve("team-tes")
                .resolve(a.getArtifactId() + ".txt");
        Files.delete(stored);
        assertThrows(ArtifactException.NotFound.class, () ->
                mgr.receive("leader", a.getArtifactId()));
    }

    @Test
    public void receive_notFoundForUnknownId() {
        ArtifactManager mgr = newManager();
        assertThrows(ArtifactException.NotFound.class, () ->
                mgr.receive("leader", "art_unknown"));
    }

    // ==================== markRead ====================

    @Test
    public void markRead_transitionsToRead() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        mgr.markRead("leader", a.getArtifactId());
        Artifact meta = mgr.getMetadata("leader", a.getArtifactId());
        assertEquals(Artifact.STATUS_READ, meta.getRecipientStatus().get("leader"));
    }

    @Test
    public void markRead_rejectsNonRecipient() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        assertThrows(ArtifactException.AccessDenied.class, () ->
                mgr.markRead("bystander", a.getArtifactId()));
    }

    @Test
    public void markRead_afterReceiveStaysRead() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        mgr.receive("leader", a.getArtifactId());
        mgr.markRead("leader", a.getArtifactId());
        Artifact meta = mgr.getMetadata("leader", a.getArtifactId());
        assertEquals(Artifact.STATUS_READ, meta.getRecipientStatus().get("leader"));
    }

    // ==================== list ====================

    @Test
    public void list_filtersByStatus() {
        ArtifactManager mgr = newManager();
        Artifact a1 = mgr.send("w1", List.of("leader"), "a.txt",
                null, "x".getBytes(), null, null);
        mgr.send("w2", List.of("leader"), "b.txt",
                null, "x".getBytes(), null, null);
        mgr.receive("leader", a1.getArtifactId());

        List<ArtifactSummary> received = mgr.list("leader",
                new ArtifactManager.ArtifactFilter().status(Artifact.STATUS_RECEIVED));
        assertEquals(1, received.size());
        assertEquals(a1.getArtifactId(), received.get(0).getArtifactId());
    }

    @Test
    public void list_filtersByTag() {
        ArtifactManager mgr = newManager();
        mgr.send("w1", List.of("leader"), "a.txt",
                null, "x".getBytes(), null, List.of("alpha"));
        mgr.send("w2", List.of("leader"), "b.txt",
                null, "x".getBytes(), null, List.of("beta"));

        List<ArtifactSummary> out = mgr.list("leader",
                new ArtifactManager.ArtifactFilter().tag("beta"));
        assertEquals(1, out.size());
        assertEquals("b.txt", out.get(0).getFilename());
    }

    @Test
    public void list_filtersBySender() {
        ArtifactManager mgr = newManager();
        mgr.send("alice", List.of("leader"), "a.txt",
                null, "x".getBytes(), null, null);
        mgr.send("bob", List.of("leader"), "b.txt",
                null, "x".getBytes(), null, null);

        List<ArtifactSummary> out = mgr.list("leader",
                new ArtifactManager.ArtifactFilter().sender("alice"));
        assertEquals(1, out.size());
    }

    @Test
    public void list_excludesNonParticipant() {
        ArtifactManager mgr = newManager();
        mgr.send("alice", List.of("leader"), "a.txt",
                null, "x".getBytes(), null, null);
        // bystander 既不是 sender 也不是 recipient
        List<ArtifactSummary> out = mgr.list("bystander", null);
        assertTrue(out.isEmpty());
    }

    @Test
    public void list_includesSenderAndRecipientPerspective() {
        ArtifactManager mgr = newManager();
        mgr.send("alice", List.of("leader"), "a.txt",
                null, "x".getBytes(), null, null);
        // alice 自己能看到（作为 sender）
        assertEquals(1, mgr.list("alice", null).size());
        // leader 能看到（作为 recipient）
        assertEquals(1, mgr.list("leader", null).size());
    }

    // ==================== 元数据 ====================

    @Test
    public void getMetadata_doesNotChangeStatus() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        mgr.getMetadata("leader", a.getArtifactId());
        Artifact meta2 = mgr.getMetadata("leader", a.getArtifactId());
        // 仍是 SENT
        assertEquals(Artifact.STATUS_SENT, meta2.getRecipientStatus().get("leader"));
    }

    // ==================== 完整性校验 ====================

    @Test
    public void verifyIntegrity_returnsTrueForIntact() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        assertTrue(mgr.verifyIntegrity(a.getArtifactId()));
    }

    @Test
    public void verifyIntegrity_returnsFalseForCorrupt() throws Exception {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                null, "hello".getBytes(), null, null);
        Path stored = tempDir.resolve("artifacts").resolve("team-tes")
                .resolve(a.getArtifactId() + ".txt");
        Files.writeString(stored, "different");
        assertFalse(mgr.verifyIntegrity(a.getArtifactId()));
    }

    // ==================== 持久化重启 ====================

    @Test
    public void reload_loadsFromManifests() {
        ArtifactManager mgr1 = newManager();
        Artifact a = mgr1.send("alice", List.of("leader"),
                "r.json", null, "{\"k\":1}".getBytes(),
                "report", List.of("alpha"));
        mgr1.receive("leader", a.getArtifactId());

        // 重建一个新的 Manager（模拟重启）
        ArtifactManager mgr2 = new ArtifactManager(tempDir, "team-test-1234");
        List<ArtifactSummary> list = mgr2.list("leader", null);
        assertEquals(1, list.size());
        assertEquals(a.getArtifactId(), list.get(0).getArtifactId());
        // 状态从 manifest 恢复
        Artifact meta = mgr2.getMetadata("leader", a.getArtifactId());
        assertEquals(Artifact.STATUS_RECEIVED, meta.getRecipientStatus().get("leader"));
    }

    // ==================== 归档 ====================

    @Test
    public void archiveAll_stampsDissolvedAt() {
        ArtifactManager mgr = newManager();
        mgr.send("w", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        mgr.send("w", List.of("leader"), "r2.txt",
                null, "x".getBytes(), null, null);
        Instant before = Instant.now();
        mgr.archiveAll(null);

        ArtifactManager mgr2 = new ArtifactManager(tempDir, "team-test-1234");
        List<ArtifactSummary> list = mgr2.list("leader", null);
        for (ArtifactSummary s : list) {
            // 通过 getMetadata 取完整 Artifact
            Artifact meta = mgr2.getMetadata("leader", s.getArtifactId());
            assertNotNull(meta.getDissolvedAt());
            assertTrue(meta.getDissolvedAt().isAfter(before.minusSeconds(1)));
        }
    }

    // ==================== 边界 ====================

    @Test
    public void senderCanReadOwnArtifact() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("alice", List.of("leader"), "r.txt",
                null, "x".getBytes(), null, null);
        ArtifactContent c = mgr.receive("alice", a.getArtifactId());
        assertArrayEquals("x".getBytes(), c.getBytes());
    }

    @Test
    public void multipleRecipientsHaveIndependentStatus() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("alice", List.of("leader", "bob"),
                "r.txt", null, "x".getBytes(), null, null);
        // leader 接收
        mgr.receive("leader", a.getArtifactId());
        // bob 不动
        Artifact meta = mgr.getMetadata("leader", a.getArtifactId());
        assertEquals(Artifact.STATUS_RECEIVED, meta.getRecipientStatus().get("leader"));
        assertEquals(Artifact.STATUS_SENT, meta.getRecipientStatus().get("bob"));
    }

    @Test
    public void mimeTypeInferredFromExtWhenAbsent() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.csv",
                null, "a,b\n1,2".getBytes(), null, null);
        assertEquals("text/csv", a.getMimeType());
    }

    @Test
    public void mimeTypeOverriddenWhenProvided() {
        ArtifactManager mgr = newManager();
        Artifact a = mgr.send("w", List.of("leader"), "r.txt",
                "application/custom", "x".getBytes(), null, null);
        assertEquals("application/custom", a.getMimeType());
    }
}
