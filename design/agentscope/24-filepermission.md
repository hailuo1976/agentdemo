# 24 文件权限模块（filepermission）

> 包：`com.demo.agentscope.filepermission`
> 主类：`FilePermissionManager` + `FilePermissionConfig` + `SecureFileWorkspace` + `PathPattern`

---

## 1. 职责

**独立的文件级权限系统**，与工具级 `PermissionEngine` 并列。
对每个文件操作（read/write/edit/list）做 7 步检查，默认 `DENY_ALL` 兜底。

---

## 2. 7 步检查流水线

```
check(path):
   1. 路径安全检查          ← 防 ../ 穿越、绝对路径滥用
   2. 黑名单路径            ← .env / secrets / .git 一律拒绝
   3. 黑名单扩展            ← .exe / .sh / .bat
   4. 白名单扩展            ← 显式 allow 的扩展
   5. 白名单路径            ← allowGlob 匹配的路径
   6. 文件大小检查          ← ≤ 10 MB（list 跳过）
   7. 默认策略              ← ALLOW_ALL / DENY_ALL
   ↑
   任一失败立即 DENY
```

`checkList(dir)` 额外有容器匹配增强：白名单 `allowed/**` 允许列 `allowed/` 自身。

---

## 3. FilePermissionConfig（构造器模式）

`AgentScopeDemoApplication.createSecureFileWorkspace` 的默认配置：

```java
FilePermissionConfig.builder()
    .allowReadWrite("**")              // 允许读写工作空间内任意文件
    .denyPath(".env")                  // 拒绝敏感配置
    .denyPath("secrets")
    .denyPath(".git")
    .denyExtension("exe")              // 拒绝可执行文件
    .denyExtension("sh")
    .denyExtension("bat")
    .maxFileSize(10 * 1024 * 1024)     // 10 MB
    .defaultPolicy(DENY_ALL)           // 默认拒绝（兜底）
    .build();
```

---

## 4. PathPattern（glob 匹配）

支持：
- `*` 单层通配
- `**` 跨层通配
- 字面路径

例：
- `allowed/**` 匹配 `allowed/a.txt`、`allowed/sub/b.txt`
- `*.txt` 匹配任何 `.txt`（不跨层）

---

## 5. SecureFileWorkspace 桥接

构造：
```
new SecureFileWorkspace(LocalWorkspace, FilePermissionManager)
```

每次操作：
```
readFile(path):
   try:
       manager.check(path)
       return workspace.readFile(path)
   catch FilePermissionDeniedException:
       throw   // 上层 MCP 工具捕获转 ToolResult
```

---

## 6. 与工具级 PermissionEngine 的关系

**两套独立系统，互不替代**：

| 维度 | FilePermissionManager | PermissionEngine |
|---|---|---|
| 对象 | 文件路径 | 工具调用（任意工具名） |
| 检查时机 | SecureFileWorkspace 调用时 | Agent.reply 内的工具调用前 |
| 决策粒度 | 路径级 + 扩展级 + 大小 | 工具名 + 参数模式 |
| 状态 | 二态（ALLOW/DENY） | 三态（ALLOW/DENY/ASK） |
| 默认 | DENY_ALL | DONT_ASK（ASK→DENY） |

**协作**：
1. `PermissionEngine` 决定 `read_file` 工具是否被允许
2. 若允许，工具执行进入 `SecureFileWorkspace`
3. `FilePermissionManager` 决定具体 path 是否允许

---

## 7. 异常

`FilePermissionDeniedException extends RuntimeException`，含：
- 拒绝原因
- 触发的步骤（1-7）
- 具体路径

MCP 文件工具捕获后包装为 `ToolResult(false, "文件读取被拒绝: ...")`。

---

## 8. 测试

`PermissionTest` + `FilePermissionManagerTest`：
- 各步骤触发场景
- glob 匹配
- 容器匹配增强
- 大小限制边界
- 默认策略兜底

---

## 9. 已知边界情况

- 软链接：当前实现不解析符号链接，可能绕过黑名单（生产场景需 `Files.readSymbolicLink` 解析）
- 路径规范化：`Paths.normalize()` 处理 `../` 但不绝对化；首次校验需配合 `toAbsolutePath()`
- 大小检查：list_files 不检查大小（无法预知）；write_file 在写入后校验
- 并发写：FilePermissionManager 不加锁，多 Agent 同时写同一文件可能竞态
