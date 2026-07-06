package com.demo.agentscope.team;

/**
 * receive() 返回的完整内容包。包含 artifact 元数据 + 原始字节。
 */
public class ArtifactContent {

    private final Artifact artifact;
    private final byte[] bytes;

    public ArtifactContent(Artifact artifact, byte[] bytes) {
        this.artifact = artifact;
        // 不在构造时 clone：唯一调用方 ArtifactManager.receive() 传入的是 Files.readAllBytes 的全新数组，
        // 立即被本对象接管；调用方不再持有该引用。getBytes() 仍返回 clone 保护内部状态。
        this.bytes = bytes != null ? bytes : new byte[0];
    }

    public Artifact getArtifact() { return artifact; }

    /** 返回内容字节的副本，调用方可安全修改。 */
    public byte[] getBytes() { return bytes.clone(); }}
