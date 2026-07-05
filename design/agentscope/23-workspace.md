# 23 工作空间模块（workspace）

> 包：`com.demo.agentscope.workspace`
> 主类：`WorkspaceManager` + `LocalWorkspace` + `DockerWorkspace`（stub） + `E2BWorkspace`（stub）

---

## 1. 职责

提供文件系统抽象，让 Agent 的文件操作不直接接触宿主 FS：
- `LocalWorkspace`：真实文件系统后端（默认）
- `DockerWorkspace`：Docker 沙箱后端（stub，待实现）
- `E2BWorkspace`：E2B 云沙箱后端（stub，待实现）
- `WorkspaceManager`：多租户隔离

---

## 2. 抽象接口（Workspace）

```
interface Workspace {
    String  readFile(path);
    void    writeFile(path, content);
    void    editFile(path, oldText, newText);
    List<String> listFiles(dir);
}
```

实现类各自决定真实存储位置。

---

## 3. LocalWorkspace

- 默认根目录：`${WORKSPACE_DIR:-workspace/}`
- 直接通过 `java.nio.file.Files` 操作
- 不做权限检查（由 `SecureFileWorkspace` 包装层做）

---

## 4. SecureFileWorkspace（桥接层）

> 包：`com.demo.agentscope.filepermission`

虽然不在 `workspace/` 包，但**事实上**是 LocalWorkspace 的代理：

```
MCPClient.registerFileTools(SecureFileWorkspace)
                       │
                       ▼
              SecureFileWorkspace
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
   FilePermissionManager      LocalWorkspace
       （7 步检查）            （真实 FS）
```

每次 read/write/edit/list：
1. 先经 `FilePermissionManager.check(path)` 或 `checkList(dir)`
2. 通过 → 委托 `LocalWorkspace`
3. 拒绝 → `FilePermissionDeniedException`

详见 `24-filepermission.md`。

---

## 5. WorkspaceManager

支持多租户：每个 Agent 会话可绑定独立 workspace 子目录。
- `getWorkspaceForAgent(agentId)` → 子目录
- 实现细节：在 `${WORKSPACE_DIR}/${agentId}/` 下隔离

---

## 6. 三态后端的演进意图

| 后端 | 状态 | 适用场景 |
|---|---|---|
| `LocalWorkspace` | 完整实现 | 单机教学、本地开发 |
| `DockerWorkspace` | stub | 容器级隔离、批量任务 |
| `E2BWorkspace` | stub | 云端沙箱、不可信代码执行 |

切后端只需替换 `SecureFileWorkspace` 包装的内部 workspace。

---

## 7. 与 pimono 的差异

pimono **无**工作空间抽象，工具直接访问宿主 FS（也没有文件工具）。

---

## 8. 默认 workspace 目录约定

- 根目录：`workspace/`（仓库根下，gitignored）
- 子目录：每个 agent / team 各自隔离
- 文件路径：调用 `read_file` / `write_file` / `edit_file` 时 `path` 必填，不再提供 `default.txt` 兜底

---

## 9. 测试

- `LocalWorkspaceTest`：基本读写、目录列表
- `SecureFileWorkspaceTest`：与 FilePermissionManager 集成（在 filepermission 测试套件）
- `WorkspaceManagerTest`：多租户隔离

---

## 10. 已知限制

- `DockerWorkspace` / `E2BWorkspace` 当前是 stub，调用会抛 `UnsupportedOperationException`
- `LocalWorkspace` 不处理并发写，需要上层调用方加锁
- 跨平台路径分隔符未统一（依赖 `java.nio.file.Path` 自动处理）
