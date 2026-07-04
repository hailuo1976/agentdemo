package com.demo.agentscope.team;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 团队解散权限控制服务单元测试。
 */
public class TeamDissolutionPermissionServiceTest {

    private TeamDissolutionPermissionService permissionService;
    private List<String> designatedUsers;

    @BeforeEach
    void setUp() {
        // 设置指定的解散权限用户
        designatedUsers = Arrays.asList("admin_user", "external_manager");
        permissionService = new TeamDissolutionPermissionService(designatedUsers);
    }

    @Test
    void testDesignatedUserCanDissolve() {
        // 指定的外部用户可以解散团队
        String requesterId = "admin_user";
        String teamId = "team-001";
        Set<String> teamMemberIds = new HashSet<>(Arrays.asList("leader-1", "worker-1", "worker-2"));

        TeamDissolutionPermissionService.DissolutionPermissionResult result =
                permissionService.checkDissolutionPermission(requesterId, teamId, teamMemberIds);

        assertTrue(result.isAllowed(), "指定的外部用户应该被允许解散团队");
        assertEquals("权限验证通过", result.getReason());
    }

    @Test
    void testTeamLeaderCannotDissolve() {
        // 团队领导者不能解散自己所在的团队
        String requesterId = "leader-1";
        String teamId = "team-001";
        Set<String> teamMemberIds = new HashSet<>(Arrays.asList("leader-1", "worker-1", "worker-2"));

        TeamDissolutionPermissionService.DissolutionPermissionResult result =
                permissionService.checkDissolutionPermission(requesterId, teamId, teamMemberIds);

        assertFalse(result.isAllowed(), "团队成员不能解散自己所在的团队");
        assertTrue(result.getReason().contains("团队成员不能解散自己所在的团队"));
    }

    @Test
    void testTeamWorkerCannotDissolve() {
        // 工作者不能解散自己所在的团队
        String requesterId = "worker-1";
        String teamId = "team-001";
        Set<String> teamMemberIds = new HashSet<>(Arrays.asList("leader-1", "worker-1", "worker-2"));

        TeamDissolutionPermissionService.DissolutionPermissionResult result =
                permissionService.checkDissolutionPermission(requesterId, teamId, teamMemberIds);

        assertFalse(result.isAllowed(), "团队成员不能解散自己所在的团队");
        assertTrue(result.getReason().contains("团队成员不能解散自己所在的团队"));
    }

    @Test
    void testUnauthorizedExternalUserCannotDissolve() {
        // 未授权的外部用户不能解散团队
        String requesterId = "unauthorized_user";
        String teamId = "team-001";
        Set<String> teamMemberIds = new HashSet<>(Arrays.asList("leader-1", "worker-1", "worker-2"));

        TeamDissolutionPermissionService.DissolutionPermissionResult result =
                permissionService.checkDissolutionPermission(requesterId, teamId, teamMemberIds);

        assertFalse(result.isAllowed(), "未授权的外部用户不能解散团队");
        assertTrue(result.getReason().contains("不在指定的解散权限用户列表中"));
    }

    @Test
    void testAuditLogRecordsAllRequests() {
        // 测试审计日志记录所有请求
        String teamId = "team-001";
        Set<String> teamMemberIds = new HashSet<>(Arrays.asList("leader-1", "worker-1"));

        // 成功的请求
        permissionService.checkDissolutionPermission("admin_user", teamId, teamMemberIds);

        // 失败的请求 - 团队成员
        permissionService.checkDissolutionPermission("leader-1", teamId, teamMemberIds);

        // 失败的请求 - 未授权用户
        permissionService.checkDissolutionPermission("unauthorized", teamId, teamMemberIds);

        List<TeamDissolutionPermissionService.DissolutionAuditEntry> auditLog =
                permissionService.getAuditLog();

        assertEquals(3, auditLog.size(), "应该记录3条审计日志");

        // 验证第一条（成功）
        assertTrue(auditLog.get(0).isSuccess());
        assertEquals("admin_user", auditLog.get(0).getRequesterId());

        // 验证第二条（失败 - 团队成员）
        assertFalse(auditLog.get(1).isSuccess());
        assertEquals("leader-1", auditLog.get(1).getRequesterId());

        // 验证第三条（失败 - 未授权）
        assertFalse(auditLog.get(2).isSuccess());
        assertEquals("unauthorized", auditLog.get(2).getRequesterId());
    }

    @Test
    void testRecentAuditLog() {
        // 测试获取最近的审计日志
        String teamId = "team-001";
        Set<String> teamMemberIds = new HashSet<>(Arrays.asList("leader-1"));

        // 生成5条审计日志
        for (int i = 0; i < 5; i++) {
            permissionService.checkDissolutionPermission("admin_user", teamId, teamMemberIds);
        }

        List<TeamDissolutionPermissionService.DissolutionAuditEntry> recentLog =
                permissionService.getRecentAuditLog(3);

        assertEquals(3, recentLog.size(), "应该返回最近3条日志");
    }

    @Test
    void testAddDesignatedUser() {
        // 测试添加指定的解散权限用户
        String newAdmin = "new_admin";
        assertFalse(permissionService.getDesignatedUsers().contains(newAdmin));

        permissionService.addDesignatedUser(newAdmin);

        assertTrue(permissionService.getDesignatedUsers().contains(newAdmin),
                "新添加的用户应该在指定用户列表中");
    }

    @Test
    void testRemoveDesignatedUser() {
        // 测试移除指定的解散权限用户
        String adminToRemove = "admin_user";
        assertTrue(permissionService.getDesignatedUsers().contains(adminToRemove));

        permissionService.removeDesignatedUser(adminToRemove);

        assertFalse(permissionService.getDesignatedUsers().contains(adminToRemove),
                "被移除的用户不应该在指定用户列表中");
    }

    @Test
    void testEmptyDesignatedUsersList() {
        // 测试空的指定用户列表
        TeamDissolutionPermissionService emptyService =
                new TeamDissolutionPermissionService(null);

        String requesterId = "any_user";
        String teamId = "team-001";
        Set<String> teamMemberIds = new HashSet<>(Arrays.asList("leader-1"));

        TeamDissolutionPermissionService.DissolutionPermissionResult result =
                emptyService.checkDissolutionPermission(requesterId, teamId, teamMemberIds);

        assertFalse(result.isAllowed(), "没有指定用户时，所有请求都应该被拒绝");
    }

    @Test
    void testNonTeamMemberDesignatedUserCanDissolve() {
        // 测试非团队成员的指定用户可以解散
        String designatedNonMember = "external_manager";
        String teamId = "team-001";
        Set<String> teamMemberIds = new HashSet<>(Arrays.asList("leader-1", "worker-1"));

        // 确认指定用户不是团队成员
        assertFalse(teamMemberIds.contains(designatedNonMember));

        TeamDissolutionPermissionService.DissolutionPermissionResult result =
                permissionService.checkDissolutionPermission(designatedNonMember, teamId, teamMemberIds);

        assertTrue(result.isAllowed(), "非团队成员的指定用户应该可以解散团队");
    }

    @Test
    void testAuditLogEntryToString() {
        // 测试审计日志条目的 toString 方法
        String teamId = "team-001";
        Set<String> teamMemberIds = new HashSet<>(Arrays.asList("leader-1"));

        permissionService.checkDissolutionPermission("admin_user", teamId, teamMemberIds);

        TeamDissolutionPermissionService.DissolutionAuditEntry entry =
                permissionService.getAuditLog().get(0);

        String toString = entry.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("admin_user"));
        assertTrue(toString.contains("team-001"));
        assertTrue(toString.contains("success=true"));
    }
}
