# 股票选择输入功能 PRD

> 版本：v1.0
> 日期：2026-07-03
> 适用项目：agentscope-demo
> 状态：草案（待评审）

---

## 0. 文档目的

本 PRD 定义在 agentscope-demo 中新增**股票选择输入功能**的产品形态、功能边界、数据模型与系统集成方式。目标是让用户通过自然语言即可完成「按行业挑龙头 → 多维筛选 → 数据更新 → 跨行业对比」的一站式股票研究流程，并由 Agent 自动调度底层工具完成数据采集、评分、缓存。

本文档面向：产品/设计评审、后端开发、Agent 提示词工程、测试。

---

## 1. 背景与目标

### 1.1 背景

A 股市场 5000+ 只股票，研究者/投资者在做行业对比或龙头筛选时面临：

| 痛点 | 现状 |
|---|---|
| 工具分散 | 同花顺、东方财富、Wind 各自为政，跨工具切换成本高 |
| 维度单一 | 多数工具只支持单维度（如市值）排序，无法复合筛选 |
| 龙头定义主观 | "龙头"无标准算法，依赖个人经验 |
| 数据更新滞后 | 不清楚本地缓存的股票数据是哪天的，容易误判 |
| 除权跳变风险 | 复权处理不当导致看似"暴跌"（参见 `agent_improvement_proposal.md` §1.1 信息误导风险） |

### 1.2 目标

构建一套基于 Agent 工具链的股票选择能力：

1. **自然语言驱动**：用户说"半导体龙头"，Agent 自动调用工具筛选
2. **复合评分选龙头**：多因子加权评分（市值 + 营收 + ROE + 品牌度），避免单一维度偏差
3. **多维交叉筛选**：支持 PE/PB/ROE/营收增速/市值区间任意组合
4. **数据更新透明**：每条数据带 `updated_at` 时间戳，支持按需/定时刷新
5. **跨行业并行对比**：复用 AgentTeam 模式，多行业并发拉取

### 1.3 非目标

- **不做**实时行情推送（akshare 的实时接口有延迟，不做 tick 级展示）
- **不做**交易/下单（纯研究工具）
- **不做**投资建议（评分仅作筛选辅助，不构成推荐）
- **不做**回测引擎（后续演进项）
- **不做**独立 GUI（终端 REPL 交互，与现有 `ConsoleUI` 一致）

---

## 2. 用户场景（User Stories）

### 2.1 场景 A：按行业选龙头

> **用户**："帮我挑出半导体行业的龙头股。"
>
> **Agent**：
> 1. 调用 `list_industries` 确认"半导体"属于申万二级"半导体Ⅱ"，对应一级"电子"
> 2. 调用 `select_industry_leaders(industry="半导体", top_n=10)`
> 3. 工具内部：拉取行业内全部股票 → 算龙头评分 → 取 Top10
> 4. 返回表格：代码 / 名称 / 评分 / 关键指标 / 数据更新时间
> 5. Agent 用自然语言解读："中芯国际、北方华创、韦尔股份位列前三……"

### 2.2 场景 B：多维复合筛选

> **用户**："找出 ROE 连续 3 年 > 15%、PE < 30、市值 > 500 亿的消费白马股。"
>
> **Agent**：
> 1. 调用 `select_industry_leaders(industry="食品饮料", filters={roe_min=15, pe_max=30, market_cap_min=500, roe_years=3})`
> 2. 工具返回满足全部条件的股票（可能为空 / 几只）
> 3. Agent 解释每只股票为何入选

### 2.3 场景 C：数据更新

> **用户**："刷新最近一周的行情数据。"
>
> **Agent**：
> 1. 调用 `update_stock_data(scope="market", data_type="quote", force=false)`
> 2. 工具检查每只股票 `updated_at`，仅刷新超过 TTL（默认 1h）的部分
> 3. 返回刷新摘要：共刷新 X 只、跳过 Y 只（未过期）、失败 Z 只
> 4. 用户可加 `force=true` 强制全量刷新

### 2.4 场景 D：跨行业对比（Team 模式）

> **用户**："对比新能源、医药、半导体三个行业的龙头。"
>
> **Agent**（团队领导者）：
> 1. 创建 3 个 worker，每个负责一个行业
> 2. 并行调用 `sendMessageToWorkersParallel`
> 3. 每个 worker 内部调用 `select_industry_leaders` 拉取本行业 Top5
> 4. 领导者汇总三方结果，生成横向对比表（市值/PE/ROE/增速）

### 2.5 场景 E：单股详情

> **用户**："贵州茅台最近怎么样？"
>
> **Agent**：调用 `get_stock_detail(code="600519")`，返回完整指标 + 历史走势摘要 + 数据更新时间。

---

## 3. 功能需求

### 3.1 功能矩阵

| 模块 | 功能 | 优先级 | 备注 |
|---|---|---|---|
| **行业分类** | 申万一级（31 个） | P0 | 内置静态表 + 动态校验 |
| | 申万二级（134 个） | P0 | 用户可按二级筛选 |
| | 申万三级（386 个） | P1 | 精细筛选场景 |
| **龙头识别** | 复合评分算法 | P0 | §7 详述 |
| | 行业内归一化 | P0 | z-score，跨行业可比 |
| **多维筛选** | PE/PB 区间 | P0 | |
| | ROE 区间 + 连续年数 | P0 | |
| | 营收增速 / 净利润增速 | P1 | |
| | 市值区间 | P0 | |
| | 自定义指标 | P2 | 用户扩展 |
| **数据更新** | 按需刷新 | P0 | 显式触发 |
| | TTL 自动过期 | P0 | 默认 24h |
| | 启动预热 | P1 | 配置开关 |
| | 增量更新 | P1 | 仅刷新过期项 |
| **结果输出** | 终端表格 | P0 | 复用 `ConsoleUI` |
| | JSON 落盘 | P1 | workspace |
| | CSV 导出 | P2 | 外部分析 |
| **跨行业** | 并行拉取 | P1 | Team 模式 |
| | 对比表生成 | P1 | |

### 3.2 输入规范

用户输入支持以下自然语言形式（Agent 负责意图识别）：

| 意图 | 触发关键词示例 |
|---|---|
| 选龙头 | "龙头"、"龙头股"、"领先企业"、"头部公司" |
| 多维筛选 | "ROE 大于"、"PE 小于"、"市值超过"、"连续 N 年" |
| 数据更新 | "刷新"、"更新"、"最新数据"、"过期" |
| 单股查询 | 6 位股票代码、"贵州茅台"等公司名 |
| 跨行业 | "对比"、"VS"、"和...比较" |

### 3.3 输出规范

所有筛选类工具返回统一结构（详见 §4），由 Agent 渲染为：

```
┌─────────────────────────────────────────────────────────────┐
│ 半导体行业龙头 Top 5（数据更新：2026-07-02 15:30）              │
├──────────┬──────────┬──────┬──────┬───────┬───────┬─────────┤
│ 代码     │ 名称     │ 评分 │ 市值 │ PE    │ ROE   │ 营收增速 │
├──────────┼──────────┼──────┼──────┼───────┼───────┼─────────┤
│ 688981   │ 中芯国际 │ 92.3 │ 4500 │ 35.2  │ 18.5% │ 22.1%   │
│ 002371   │ 北方华创 │ 88.7 │ 2100 │ 48.6  │ 16.2% │ 35.4%   │
│ ...      │          │      │      │       │       │         │
└──────────┴──────────┴──────┴──────┴───────┴───────┴─────────┘
```

---

## 4. 数据模型

### 4.1 核心实体：StockEntity

```json
{
  "code": "600519",
  "name": "贵州茅台",
  "industry_l1": "食品饮料",
  "industry_l2": "白酒Ⅱ",
  "industry_l3": "白酒Ⅲ",
  "market_cap": 23000.5,
  "pe_ttm": 28.5,
  "pb": 9.2,
  "roe": 31.4,
  "roe_history_3y": [29.8, 30.2, 31.4],
  "revenue_growth": 18.2,
  "profit_growth": 20.1,
  "leader_score": 95.8,
  "score_components": {
    "market_cap_score": 100.0,
    "revenue_score": 92.3,
    "roe_score": 95.1,
    "brand_score": 96.0
  },
  "is_ex_right": false,
  "data_source": "akshare",
  "updated_at": "2026-07-03T15:30:00+08:00",
  "data_freshness": "quote_1h"
}
```

| 字段 | 类型 | 单位/格式 | 说明 |
|---|---|---|---|
| `code` | string | 6 位数字 | A 股代码 |
| `name` | string | 中文 | 股票名称 |
| `industry_l1/l2/l3` | string | | 申万行业分级 |
| `market_cap` | number | 亿元 | 总市值 |
| `pe_ttm` | number | 倍 | TTM 市盈率 |
| `pb` | number | 倍 | 市净率 |
| `roe` | number | % | 最近一期净资产收益率 |
| `roe_history_3y` | number[] | % | 近 3 年 ROE |
| `revenue_growth` | number | % | 同比营收增速 |
| `profit_growth` | number | % | 同比净利润增速 |
| `leader_score` | number | 0-100 | 龙头评分（行业内归一化） |
| `score_components` | object | | 各维度子分（可解释性） |
| `is_ex_right` | bool | | 是否处于除权除息期 |
| `data_source` | string | | 数据源标识 |
| `updated_at` | ISO 8601 | | 本次数据采集时间 |
| `data_freshness` | enum | | 新鲜度等级（见 §6.1） |

### 4.2 缓存条目：CachedStockEntry

落盘到 `workspace/cache/stocks/<code>.json` 的结构：

```json
{
  "code": "600519",
  "quote": {
    "price": 1830.5,
    "change_pct": 0.85,
    "volume": 12345678,
    "updated_at": "2026-07-03T15:30:00+08:00"
  },
  "fundamental": {
    "pe_ttm": 28.5,
    "pb": 9.2,
    "roe": 31.4,
    "updated_at": "2026-07-02T20:00:00+08:00"
  },
  "industry_rank": {
    "rank_in_l2": 1,
    "total_in_l2": 68,
    "updated_at": "2026-07-01T00:00:00+08:00"
  }
}
```

每个数据段独立 TTL，过期段单独刷新，避免全量重拉。

### 4.3 行业树：IndustryNode

```json
{
  "l1": [
    {
      "code": "801080",
      "name": "电子",
      "l2": [
        {
          "code": "801081",
          "name": "半导体Ⅱ",
          "l3": [
            {"code": "80108101", "name": "集成电路芯片设计"},
            {"code": "80108102", "name": "晶圆制造"},
            {"code": "80108103", "name": "封装测试"}
          ]
        }
      ]
    }
  ]
}
```

行业树为静态资源（每年初申万一/二次调整时手动更新），缓存于 `workspace/cache/industry_tree.json`。

---

## 5. 工具设计（MCP 工具）

复用 `MCPClient.registerCustomTool(name, description, parametersJson, executor)` 注册 4 个工具。每个工具的 executor 内部可调用 `execute_python` 拉取 akshare 数据。

### 5.1 `list_industries`

**描述**：列出申万行业分类树。

**参数 Schema**：
```json
{
  "type": "object",
  "properties": {
    "level": {
      "type": "string",
      "enum": ["l1", "l2", "l3", "all"],
      "default": "l1"
    },
    "parent": {
      "type": "string",
      "description": "父级行业名称（如 level=l2 时传入 parent='电子'）"
    }
  }
}
```

**返回**：JSON 字符串，行业节点列表。

**典型 Python 实现**（在 BuiltinToolExecutor 内调用 execute_python）：
```python
import akshare as ak
df = ak.stock_board_industry_summary_ths()
# 过滤 level/parent 后序列化
```

### 5.2 `select_industry_leaders`（核心）

**描述**：按行业 + 多维条件筛选龙头股，返回评分 Top N。

**参数 Schema**：
```json
{
  "type": "object",
  "required": ["industry"],
  "properties": {
    "industry": {
      "type": "string",
      "description": "行业名称（支持 l1/l2/l3 任意级别，如 '电子'、'半导体'）"
    },
    "level": {
      "type": "string",
      "enum": ["l1", "l2", "l3"],
      "default": "l2"
    },
    "top_n": {
      "type": "integer",
      "default": 10,
      "minimum": 1,
      "maximum": 50
    },
    "filters": {
      "type": "object",
      "properties": {
        "market_cap_min": {"type": "number", "description": "市值下限（亿）"},
        "market_cap_max": {"type": "number"},
        "pe_min": {"type": "number"},
        "pe_max": {"type": "number"},
        "pb_min": {"type": "number"},
        "pb_max": {"type": "number"},
        "roe_min": {"type": "number", "description": "ROE 下限（%）"},
        "roe_years": {"type": "integer", "default": 1, "description": "ROE 连续达标年数"},
        "revenue_growth_min": {"type": "number", "description": "营收增速下限（%）"},
        "exclude_st": {"type": "boolean", "default": true}
      }
    },
    "use_cache": {
      "type": "boolean",
      "default": true,
      "description": "是否使用缓存（false 则强制刷新）"
    }
  }
}
```

**返回**：
```json
{
  "industry": "半导体",
  "level": "l2",
  "total_universe": 187,
  "filtered_count": 142,
  "leaders": [
    {"code": "688981", "name": "中芯国际", "leader_score": 92.3, "...": "..."},
    {"code": "002371", "name": "北方华创", "leader_score": 88.7, "...": "..."}
  ],
  "data_updated_at": "2026-07-03T15:30:00+08:00",
  "cache_hit": true
}
```

**Executor 伪码**：
```
1. resolveIndustry(industry, level) → 取该行业全部 code 列表
2. for each code: loadCachedOrFetch(code) → StockEntity
3. applyFilters(entities, filters)
4. computeLeaderScores(entities)  ← §7
5. sort by score desc, take top_n
6. return JSON
```

### 5.3 `get_stock_detail`

**描述**：查询单只股票的完整指标。

**参数 Schema**：
```json
{
  "type": "object",
  "required": ["code"],
  "properties": {
    "code": {"type": "string", "pattern": "^\\d{6}$"},
    "include_history": {
      "type": "boolean",
      "default": false,
      "description": "是否包含近 30 日 K 线摘要"
    },
    "force_refresh": {"type": "boolean", "default": false}
  }
}
```

**返回**：完整 `StockEntity` + 可选历史段。

### 5.4 `update_stock_data`

**描述**：触发数据刷新。

**参数 Schema**：
```json
{
  "type": "object",
  "properties": {
    "scope": {
      "type": "string",
      "enum": ["single", "industry", "market"],
      "default": "industry"
    },
    "code": {"type": "string", "description": "scope=single 时必填"},
    "industry": {"type": "string", "description": "scope=industry 时指定行业"},
    "data_type": {
      "type": "string",
      "enum": ["quote", "fundamental", "industry_rank", "all"],
      "default": "all"
    },
    "force": {
      "type": "boolean",
      "default": false,
      "description": "true 跳过 TTL 检查强制刷新"
    }
  }
}
```

**返回**：
```json
{
  "scope": "industry",
  "target": "半导体",
  "total": 187,
  "refreshed": 142,
  "skipped": 40,
  "failed": 5,
  "failures": [
    {"code": "688XXX", "reason": "akshare timeout"}
  ],
  "duration_ms": 8230
}
```

---

## 6. 数据更新机制

### 6.1 三级新鲜度

| 等级 | 数据类型 | 默认 TTL | 数据源（akshare 接口） |
|---|---|---|---|
| `quote_1h` | 实时行情（价/量/涨跌） | 1 小时 | `stock_zh_a_spot_em` |
| `fundamental_24h` | 基本面（PE/PB/ROE/财务） | 24 小时 | `stock_a_indicator_lg`、`stock_financial_report` |
| `industry_rank_7d` | 行业排名、行业成分 | 7 天 | `stock_board_industry_*` |

### 6.2 缓存策略

**目录结构**：
```
workspace/
└── cache/
    ├── industry_tree.json              # 静态资源，年度更新
    └── stocks/
        ├── 600519.json                 # CachedStockEntry
        ├── 000001.json
        └── ...
```

**读写规则**：
- **读**：先查文件，未过期直接返回（`cache_hit=true`）；过期或缺失则拉取并落盘
- **写**：原子写入（先写 `.tmp` 再 rename，避免半截文件）
- **并发安全**：单 Agent 串行；Team 模式下不同 worker 处理不同行业，文件无冲突
- **过期判定**：`now - updated_at > TTL` 触发刷新

### 6.3 更新触发方式

| 触发方式 | 场景 |
|---|---|
| **用户显式** | 调用 `update_stock_data` |
| **Agent 自动** | `select_industry_leaders` 发现 30%+ 缓存过期时主动刷新 |
| **启动预热** | 配置 `stock.preheat=true` 时，Agent 启动并行拉取行业成分表（仅 `industry_rank`） |
| **后台定时** | （P2 演进项）独立线程每日收盘后批量更新 |

### 6.4 数据血缘与可观测性

每条返回数据携带：
- `data_source`：akshare / tushare / cache
- `updated_at`：采集时间
- `cache_hit`：是否命中缓存
- 工具执行事件通过 `EventStream` 发射 `WORKSPACE_OPERATION` 事件（写入文件时）和自定义 `STOCK_DATA_FETCHED` 事件（可在 `EventType` 扩展）。

---

## 7. 龙头评分算法

### 7.1 评分公式

```
leader_score = w_mc × market_cap_score
             + w_rev × revenue_score
             + w_roe × roe_score
             + w_brand × brand_score
```

**默认权重**：

| 维度 | 权重 | 说明 |
|---|---|---|
| 市值（market_cap） | 0.40 | 行业地位最直观体现 |
| 营收（revenue） | 0.25 | 规模优势 |
| ROE | 0.20 | 盈利质量 |
| 品牌度（brand） | 0.15 | 行业知名度（启发式） |

权重可通过 `workspace/config/scoring_weights.json` 覆盖。

### 7.2 行业内归一化

为避免跨行业不可比（如银行 PE 与科技股 PE 量级差异），所有评分在**同行业内做 z-score 归一化**：

```python
def normalize(values):
    mu = mean(values)
    sigma = std(values)
    return [(v - mu) / sigma for v in values]

# 转为 0-100 分
def to_score(z):
    return clip(50 + z * 16.67, 0, 100)  # ±3σ 映射到 [0, 100]
```

### 7.3 品牌度（brand_score）启发式

无直接数据源，用代理指标：
- 是否入选沪深 300 / 上证 50 成分（+20 / +30）
- 是否为该行业 l2 市值第一（+15）
- 机构持仓家数（akshare `stock_report_fund_hold`，归一化）
- 名称含"中国"/"中华"（+5，弱信号）

最终 brand_score 经上述加权后归一化到 0-100。

### 7.4 算法伪码

```
function computeLeaderScores(entities):
    # entities = 同行业内全部股票
    caps = [e.market_cap for e in entities]
    revs = [e.revenue for e in entities]
    roes = [e.roe for e in entities]
    brands = [heuristicBrand(e) for e in entities]

    cap_scores  = toScore(normalize(log(caps)))    # log 抑制长尾
    rev_scores  = toScore(normalize(log(revs)))
    roe_scores  = toScore(normalize(roes))
    brand_scores= toScore(normalize(brands))

    for i, e in enumerate(entities):
        e.leader_score = 0.40*cap_scores[i]
                       + 0.25*rev_scores[i]
                       + 0.20*roe_scores[i]
                       + 0.15*brand_scores[i]
        e.score_components = {cap, rev, roe, brand}
```

### 7.5 可解释性

每只股票的 `score_components` 暴露各维度子分，Agent 在解释时可以说："中芯国际评分 92.3，其中市值分 100（行业内第一）、ROE 分 85（中等偏上）……"。

---

## 8. 技术架构

### 8.1 整体分层

```
┌────────────────────────────────────────────────────┐
│  用户输入（自然语言）                                │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│  Agent.reply() — ReAct 循环                          │
│  系统提示词中描述 4 个 stock 工具                      │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│  PermissionMiddleware → 4 条 ALLOW 规则              │
└────────────────────────────────────────────────────┘
                       ↓
┌────────────────────────────────────────────────────┐
│  MCPClient → BuiltinToolExecutor（stock 工具实现）    │
│  ┌──────────────────────────────────────────────┐  │
│  │  StockToolService（新增核心服务类）            │  │
│  │  - IndustryService       行业树解析            │  │
│  │  - StockDataService      数据采集 + 缓存       │  │
│  │  - LeaderScoringService  评分算法              │  │
│  │  - StockFilterService    多维筛选              │  │
│  └──────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────┘
                       ↓
┌──────────────────┬─────────────────────────────────┐
│  execute_python  │  LocalWorkspace（缓存读写）       │
│  (akshare 拉取)  │  workspace/cache/stocks/*.json   │
└──────────────────┴─────────────────────────────────┘
```

### 8.2 新增类清单

| 类名 | 职责 | 位置 |
|---|---|---|
| `StockToolService` | 工具入口，注册 4 个工具 | `com.demo.agentscope.stock` |
| `IndustryService` | 行业树加载与解析 | 同上 |
| `StockDataService` | akshare 调用 + 缓存读写 | 同上 |
| `LeaderScoringService` | 评分算法 | 同上 |
| `StockFilterService` | 多维筛选 | 同上 |
| `StockEntity` / `CachedStockEntry` | 数据模型 | 同上 |

### 8.3 数据源策略

| 数据源 | 优先级 | 优势 | 劣势 |
|---|---|---|---|
| **akshare** | 主 | 免费、无需 token、覆盖全 | 接口偶发不稳定、有限流 |
| **tushare** | 备 | 字段规范、稳定 | 需要 token、积分制 |
| **本地缓存** | 兜底 | 0 延迟 | 可能过期 |

降级链：akshare 失败 → 检查本地缓存（即使过期也返回，标注 `stale=true`）→ 仍无 → 报错。

### 8.4 并发控制

- **单 Agent 串行**：默认场景，工具内同步调用
- **Team 模式并行**：`sendMessageToWorkersParallel`（`agent/AgentTeam.java:200`）每个 worker 处理一个行业，互不干扰
- **限流**：akshare 调用间隔 ≥ 200ms（`Thread.sleep`），全市场刷新并发线程数 ≤ 5

---

## 9. 与现有系统集成点

### 9.1 工具注册

**文件**：`agentscope-demo/src/main/java/com/demo/agentscope/AgentScopeDemoApplication.java`

**改动**：在 `main()` 中 `registerCodeExecutionTools` 之后增加：
```java
// 注册股票选择工具
StockToolService stockService = new StockToolService(secureFileWorkspace, executionManager);
stockService.registerTools(mcpClient);
```

### 9.2 权限规则

**文件**：同上，`createPermissionEngine()` 方法（约 `AgentScopeDemoApplication.java:269`）

**改动**：新增 4 条规则：
```java
engine.addRule(new PermissionRule("list_industries", ALLOW, "行业分类查询"));
engine.addRule(new PermissionRule("select_industry_leaders", ALLOW, "龙头筛选"));
engine.addRule(new PermissionRule("get_stock_detail", ALLOW, "单股详情"));
engine.addRule(new PermissionRule("update_stock_data", ALLOW, "数据更新"));
```

### 9.3 系统提示词

**文件**：同上，`SYSTEM_PROMPT` 常量（约 `AgentScopeDemoApplication.java:58`）

**追加段落**：
```
## 股票研究能力
你可以调用以下工具进行 A 股股票研究：
- list_industries：查询申万行业分类
- select_industry_leaders：按行业筛选龙头股（支持多维过滤）
- get_stock_detail：查询个股详情
- update_stock_data：刷新股票数据

数据源为 akshare，默认带 24h 缓存。返回数据中包含 updated_at，请在解读时告知用户数据时效性。
```

### 9.4 Team 模式注入

**文件**：`agentscope-demo/src/main/java/com/demo/agentscope/agent/AgentTeam.java`

**改动**：在 `registerTeamTools()` 中，领导者提示词追加 stock 工具描述（参考 `getTeamToolsDescription()` 模式，约 `AgentTeam.java:389`）。

### 9.5 缓存目录约定

复用 `LocalWorkspace.baseDir`（`workspace/LocalWorkspace.java`），新增子目录：
- `workspace/cache/stocks/` — 个股缓存
- `workspace/cache/industry_tree.json` — 行业树
- `workspace/config/scoring_weights.json` — 评分权重配置

`LocalWorkspace.writeFile`（`workspace/LocalWorkspace.java:69`）已支持自动创建父目录，无需改动。

### 9.6 集成最小改动汇总

| 文件 | 改动量 |
|---|---|
| `AgentScopeDemoApplication.java` | +15 行（注册 + 权限 + 提示词） |
| `AgentTeam.java` | +5 行（领导者提示词） |
| 新增 `stock/` 包 | ~600 行（4 个服务类 + 数据模型） |
| 新增 `stock/` 测试 | ~400 行 |

**不改动**：`MCPClient`、`LocalWorkspace`、`PermissionEngine`、`Agent`、`Middleware` 链。

---

## 10. 非功能需求

| 维度 | 指标 |
|---|---|
| **延迟** | 单行业筛选（缓存命中）< 200ms；冷启动 < 3s |
| **吞吐** | 全市场刷新并发 ≤ 5 线程；akshare 间隔 ≥ 200ms |
| **存储** | 单只股票缓存 < 5KB；全市场（5500 只）< 30MB |
| **可用性** | akshare 失败时返回过期缓存并标注 `stale=true`，不抛错 |
| **可观测性** | 工具调用经 `EventStream` 记录；缓存命中率可通过 `cache_hit` 字段统计 |
| **可配置** | TTL、权重、并发数、数据源优先级均可通过 `workspace/config/` 覆盖 |

---

## 11. 实现优先级

### 11.1 阶段一（P0，5 天）—— 核心能力

| 任务 | 工时 |
|---|---|
| `IndustryService` + 行业树静态资源 | 1 天 |
| `StockDataService` 基础（akshare 集成 + 文件缓存） | 1.5 天 |
| `LeaderScoringService`（z-score + 权重） | 1 天 |
| `select_industry_leaders` 工具 + 测试 | 1 天 |
| `list_industries`、`get_stock_detail` 工具 | 0.5 天 |

**交付**：能用自然语言完成场景 A（按行业选龙头）。

### 11.2 阶段二（P1，3 天）—— 增强能力

| 任务 | 工时 |
|---|---|
| 多维 filters 完整支持 | 1 天 |
| `update_stock_data` 工具（含 TTL 检查） | 1 天 |
| 权限规则 + 系统提示词 + 集成测试 | 1 天 |

**交付**：场景 B、C 可用。

### 11.3 阶段三（P2，3 天）—— 体验优化

| 任务 | 工时 |
|---|---|
| Team 模式跨行业对比 | 1 天 |
| JSON/CSV 导出 | 0.5 天 |
| 评分权重可配置 + 品牌度启发式调优 | 1 天 |
| 文档与示例 | 0.5 天 |

**交付**：场景 D 可用，全功能完备。

---

## 12. 验收标准

### 12.1 功能验收

| 用例 | 预期 |
|---|---|
| 输入"半导体龙头" | 返回 Top10，每只含 leader_score、updated_at |
| 输入"ROE>15% PE<30 的消费股" | 返回满足条件的股票（可能 ≤ 10） |
| 24h 内重复同一查询 | `cache_hit=true`，返回数据 `updated_at` 不变 |
| 输入"刷新半导体数据" | `update_stock_data` 返回 refreshed 计数 > 0 |
| `force=true` 全量刷新 | 全部 refreshed，无 skipped |
| Team 模式 3 行业对比 | 并行耗时 < 单行业 × 1.5 |
| 查询 ST 股票（默认 exclude_st=true） | 不出现在结果中 |

### 12.2 性能验收

- 缓存命中查询 P95 < 200ms
- 冷启动单行业查询 P95 < 3s
- 全市场刷新（5500 只）< 60s

### 12.3 健壮性验收

- akshare 接口超时 → 返回过期缓存，`stale=true`
- 无效股票代码 → 友好错误信息
- 行业名称拼写近似（"半导体" vs "半导体Ⅱ"）→ 模糊匹配

---

## 13. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| **akshare 接口限流/字段变更** | 数据拉取失败 | 重试 3 次 + 字段容错映射 + 降级到缓存 |
| **龙头评分主观性** | 用户质疑排名 | 权重可配置 + `score_components` 可解释 + 支持用户覆盖 |
| **除权除息导致价格跳变** | 误判为暴跌 | `is_ex_right` 元数据标注（呼应 `agent_improvement_proposal.md` §3.5 数据标注） |
| **行业分类调整**（申万年度修订） | 行业树过期 | 行业树带 `version` 与 `updated_at`，启动时检查 |
| **缓存与磁盘膨胀** | workspace 无限增长 | LRU 清理（保留近 90 天访问的）+ 大小阈值告警 |
| **Team 并发文件冲突** | 缓存写坏 | 不同 worker 处理不同行业，自然分片；写文件用原子 rename |
| **数据源合规性** | akshare 数据版权 | 仅作研究展示，不二次分发；导出 CSV 时附加数据源声明 |

---

## 14. 后续演进

| 演进项 | 优先级 | 说明 |
|---|---|---|
| 港股/美股扩展 | P3 | 复用工具接口，新增数据源适配层 |
| 北向资金动向 | P3 | 作为龙头评分的辅助因子 |
| 舆情因子接入 | P3 | 新闻/公告情感分析作为 brand_score 补充 |
| 回测验证 | P4 | 验证龙头评分的历史表现 |
| 实时行情订阅 | P4 | WebSocket 接入（当前受限于 akshare） |
| 向量化相似股票 | P4 | 找"与茅台相似的股票"（呼应 `agent_improvement_proposal.md` §9.1 向量数据库） |

---

## 15. 附录

### 15.1 术语表

| 术语 | 含义 |
|---|---|
| 申万行业分类 | 申银万国证券发布的 A 股标准行业分类，三级结构 |
| 龙头股 | 行业内具有领先地位的股票，本 PRD 用复合评分定义 |
| ROE | 净资产收益率（Return on Equity） |
| TTM | 滚动十二个月（Trailing Twelve Months） |
| z-score | 标准分，`(x - μ) / σ`，衡量偏离均值的程度 |
| TTL | Time To Live，缓存生存期 |

### 15.2 参考资料

- 项目内：`design/agent_improvement_proposal.md`（数据标注需求呼应）
- 项目内：`agentscope-demo/src/main/java/com/demo/agentscope/mcp/MCPClient.java`（工具注册接口）
- 项目内：`agentscope-demo/src/main/java/com/demo/agentscope/workspace/LocalWorkspace.java`（缓存基础设施）
- 外部：[akshare 文档](https://akshare.akfamily.xyz/)
- 外部：[申万行业分类标准](http://www.swsresearch.com/)

### 15.3 评审检查清单

- [ ] 功能边界清晰（非目标已明确）
- [ ] 数据模型字段完整、无歧义
- [ ] 4 个工具的 Schema 可被 LLM 正确调用
- [ ] 评分算法可解释、权重可配置
- [ ] 缓存策略明确（TTL 分级、原子写入、降级）
- [ ] 与现有系统集成改动最小化（≤ 20 行改动到现有文件）
- [ ] 无新引入重量级依赖（仅 akshare Python 库，通过 execute_python 复用）
- [ ] 验收用例可执行、可验证
