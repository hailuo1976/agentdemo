package com.demo.agentscope.team;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队解散权限控制服务。
 * <p>
 * 实现团队解散的权限校验机制：
 * <ul>
 *   <li>团队成员（包括领导者）不能解散自己所在的团队</li>
 *   <li>只有指定的外部用户（非团队成员）才能发起解散请求</li>
 *   <li>记录所有解散请求的审计日志</li>
 * </ul>
 * </p>
 */
public class TeamDissolutionPermissionService {

    private static final Logger log = LoggerFactory.getLogger(TeamDissolutionPermissionService.class);

    /** 指定的解散权限用户集合（非团队成员，但有解散权限） */
    private final Set<String> designatedDissolutionUsers;

    /** 审计日志：记录所有解散请求 */
    private final List<DissolutionAuditEntry> auditLog;

    /**
     * 构造函数。
     *
     * @param designatedUsers 指定的解散权限用户列表
     */
    public TeamDissolutionPermissionService(List<String> designatedUsers) {
        this.designatedDissolutionUsers = ConcurrentHashMap.newKeySet();
        if (designatedUsers != null) {
            this.designatedDissolutionUsers.addAll(designatedUsers);
        }
        this.auditLog = Collections.synchronizedList(new ArrayList<>());
        log.info("团队解散权限服务已初始化，指定解散用户: {}", designatedDissolutionUsers);
    }

    /**
     * 验证解散请求的权限。
     *
     * @param requesterId 请求者ID
     * @param teamId 团队ID
     * @param teamMemberIds 团队成员ID集合（包括领导者和工作者）
     * @return 权限验证结果
     */
    public DissolutionPermissionResult checkDissolutionPermission(
            String requesterId,
            String teamId,
            Set<String> teamMemberIds) {

        Instant timestamp = Instant.now();

        // 检查1: 请求者是否为团队成员
        if (teamMemberIds.contains(requesterId)) {
            String reason = "团队成员不能解散自己所在的团队";
            log.warn("解散请求被拒绝: requesterId={}, teamId={}, 原因={}", requesterId, teamId, reason);
            auditLog.add(new DissolutionAuditEntry(timestamp, requesterId, teamId, false, reason));
            return DissolutionPermissionResult.deny(reason);
        }

        // 检查2: 请求者是否为指定的解散权限用户
        if (!designatedDissolutionUsers.contains(requesterId)) {
            String reason = "请求者不在指定的解散权限用户列表中";
            log.warn("解散请求被拒绝: requesterId={}, teamId={}, 原因={}", requesterId, teamId, reason);
            auditLog.add(new DissolutionAuditEntry(timestamp, requesterId, teamId, false, reason));
            return DissolutionPermissionResult.deny(reason);
        }

        // 权限验证通过
        log.info("解散请求权限验证通过: requesterId={}, teamId={}", requesterId, teamId);
        auditLog.add(new DissolutionAuditEntry(timestamp, requesterId, teamId, true, "权限验证通过"));
        return DissolutionPermissionResult.allow();
    }

    /**
     * 添加指定的解散权限用户。
     *
     * @param userId 用户ID
     */
    public void addDesignatedUser(String userId) {
        designatedDissolutionUsers.add(userId);
        log.info("已添加指定解散用户: {}", userId);
    }

    /**
     * 移除指定的解散权限用户。
     *
     * @param userId 用户ID
     */
    public void removeDesignatedUser(String userId) {
        designatedDissolutionUsers.remove(userId);
        log.info("已移除指定解散用户: {}", userId);
    }

    /**
     * 获取所有指定的解散权限用户。
     *
     * @return 用户ID集合
     */
    public Set<String> getDesignatedUsers() {
        return Collections.unmodifiableSet(designatedDissolutionUsers);
    }

    /**
     * 获取审计日志。
     *
     * @return 审计日志列表
     */
    public List<DissolutionAuditEntry> getAuditLog() {
        return Collections.unmodifiableList(auditLog);
    }

    /**
     * 获取审计日志（最近N条）。
     *
     * @param limit 最大返回条数
     * @return 审计日志列表
     */
    public List<DissolutionAuditEntry> getRecentAuditLog(int limit) {
        int size = auditLog.size();
        int fromIndex = Math.max(0, size - limit);
        return Collections.unmodifiableList(auditLog.subList(fromIndex, size));
    }

    /**
     * 权限验证结果。
     */
    public static class DissolutionPermissionResult {
        private final boolean allowed;
        private final String reason;

        private DissolutionPermissionResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static DissolutionPermissionResult allow() {
            return new DissolutionPermissionResult(true, "权限验证通过");
        }

        public static DissolutionPermissionResult deny(String reason) {
            return new DissolutionPermissionResult(false, reason);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * 审计日志条目。
     */
    public static class DissolutionAuditEntry {
        private final Instant timestamp;
        private final String requesterId;
        private final String teamId;
        private final boolean success;
        private final String reason;

        public DissolutionAuditEntry(Instant timestamp, String requesterId, String teamId,
                                     boolean success, String reason) {
            this.timestamp = timestamp;
            this.requesterId = requesterId;
            this.teamId = teamId;
            this.success = success;
            this.reason = reason;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public String getRequesterId() {
            return requesterId;
        }

        public String getTeamId() {
            return teamId;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return String.format("[%s] requester=%s, team=%s, success=%s, reason=%s",
                    timestamp, requesterId, teamId, success, reason);
        }
    }
}
