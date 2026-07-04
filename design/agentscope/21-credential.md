# 21 凭证模块（credential）

> 包：`com.demo.agentscope.credential`
> 主类：`CredentialProvider`（接口） + `DefaultCredentialProvider`

---

## 1. 职责

为 `ChatModel` 提供 LLM API Key，支持**主备 failover**：
- 主 provider 失败时自动切到备用
- 多个 API key env var 候选（OPENAI / DASHSCOPE / ANTHROPIC / DEEPSEEK）

---

## 2. 接口

```java
public interface CredentialProvider {
    String getApiKey(String providerName);     // 按名取
    String getPrimaryProvider();               // 主 provider 名
    String getStandbyProvider();               // 备 provider 名（可空）
}
```

---

## 3. DefaultCredentialProvider 行为

### 3.1 主 provider 探测顺序
```
1. 若 env MODEL_PROVIDER 显式设置 → 用它
2. 否则自动探测：DASHSCOPE > OPENAI > ANTHROPIC > DEEPSEEK
   （按 env var 是否非空判断）
```

### 3.2 standby 选择
- 主之外的任意一个已配置 env var 即作为 standby
- 若仅有一个 key 配置，则 standby 为空

### 3.3 getApiKey(providerName)
按 provider 名映射到 env var：
| providerName | env var |
|---|---|
| `dashscope` | `DASHSCOPE_API_KEY` |
| `openai` | `OPENAI_API_KEY` |
| `anthropic` | `ANTHROPIC_API_KEY` |
| `deepseek` | `DEEPSEEK_API_KEY` |

返回 null 表示未配置。

---

## 4. failover 触发

当前实现：`ChatModel` 在调用 LLM 失败时，可读取 `getStandbyProvider()` 切换 endpoint。
- failover 仅在 HTTP 5xx / 网络异常时触发
- 401 / 403（认证错）不切换（备用 key 也未必有效）
- 切换后下次调用默认回到主 provider

---

## 5. 与 pimono 的差异

pimono 直接读单一 env var，无主备概念；启动时若 key 缺失即退出。

---

## 6. 测试

`DefaultCredentialProviderTest`：
- 主 provider 自动探测（多 env var 优先级）
- 显式 `MODEL_PROVIDER` 覆盖
- standby 为空场景
- getApiKey 对各 provider 名的映射
