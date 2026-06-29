# 混合分级 PDF 解析 Agent 工具开发说明文档

> **版本**：v2.0
> **状态**：正式发布稿
> **受众**：Java 后端工程师、架构师、Agent 工具集成方
> **维护方**：Agent 工具组
> **最后更新**：2026-06-29

---

## 目录

1. [工具概述](#1-工具概述)
2. [核心功能说明](#2-核心功能说明)
3. [技术架构设计](#3-技术架构设计)
4. [环境依赖要求](#4-环境依赖要求)
5. [配置说明](#5-配置说明)
6. [详细开发步骤](#6-详细开发步骤)
7. [API 接口规范](#7-api-接口规范)
8. [使用示例](#8-使用示例)
9. [错误处理机制](#9-错误处理机制)
10. [测试方法](#10-测试方法)
11. [验收标准](#11-验收标准)
12. [附录](#12-附录)

---

## 1. 工具概述

### 1.1 工具定位

`pdf-parser-agent` 是面向 Agent 工作流的**混合分级 PDF 解析工具**。它提供一份 PDF 的结构化 Markdown 输出，核心技术路线为"**先分类、后路由、再融合**"：

- **分类**：基于规则分类器将每页归为 `EASY`（纯文本）或 `HARD`（图表/多栏/公式）
- **路由**：EASY 页走 PDFBox 本地提取（低成本），HARD 页走 VLM 视觉模型（高精度）
- **融合**：跨页排序、段落拼接、表格缝合，输出完整 Markdown

通过分级路由，**将计算成本降低 70% 以上**（仅 30% 的页面需要 VLM 调用），同时保证复杂页的解析精度。

### 1.2 设计目标

| 维度 | 目标 | 度量指标 |
|---|---|---|
| 精度 | 复杂页（图表/公式/多栏）召回率 ≥ 95% | 字符级 BLEU / 表格列对齐率 |
| 成本 | VLM 调用比例 ≤ 30% | VLM 调用页数 / 总页数 |
| 鲁棒性 | 任一下游引擎故障，系统不中断 | 降级覆盖率 = 100% |
| 可观测 | 每页解析可追溯到路由决策与耗时 | 结构化日志覆盖率 = 100% |
| 可调优 | 分类阈值与线程池参数可热更新 | `@ConfigurationProperties` + `@RefreshScope` |

### 1.3 适用场景

- Agent 工作流中的 RAG 知识入库前处理（PDF → Markdown 分块）
- 报表、学术论文、标书的结构化数据抽取
- 跨页表格与公式的语义级缝合（避免简单拼接导致断裂）
- 大批量 PDF 归档的自动化索引与摘要生成

### 1.4 非目标（Non-Goals）

- 不提供 PDF **生成**与编辑能力
- 不负责向量化与 Embedding（由上游 Agent 工具链负责）
- 不处理加密 PDF 的解密（需上游预先解密）
- 不保证手写体 OCR 精度（依赖 Tesseract 训练数据质量）

---

## 2. 核心功能说明

### 2.1 功能矩阵

| 编号 | 功能 | 输入 | 输出 | 说明 |
|---|---|---|---|---|
| F-1 | 元数据提取 | `PDDocument` | `PageMeta` | 文本密度、图像数、布局坐标方差 |
| F-2 | 复杂度二分类 | `PageMeta` | `PageClass {EASY, HARD}` | 规则分类器，阈值可配置 |
| F-3 | 分级路由执行 | `PageClass + PDPage` | `PageResult` | EASY→PDFBox，HARD→VLM |
| F-4 | 后处理融合 | `List<PageResult>` | `ParsedDocument` | 全局坐标排序、跨段/跨表缝合 |
| F-5 | 降级兜底 | 异常信号 | 降级结果 | VLM 失败→Tesseract；EASY 异常→重路由 VLM |
| F-6 | 结构化日志 | 各阶段事件 | JSON 日志 | 含 `pageIndex`、`route`、`costMs`、`degraded` |

### 2.2 分类规则详解（`PageClassifier`）

分类器采用**规则优先 + 兜底保守**策略：无法明确判定为 EASY 的页面一律归入 HARD，避免漏解析。

| 分类 | 判定条件（AND / OR 逻辑） |
|---|---|
| **EASY** | `textCoverage > easyTextCoverageThreshold`（默认 60%）**AND** `!hasTableAnchor`（无"续表"/"表X 续"）**AND** `layoutVariance < easyLayoutVarianceThreshold` |
| **HARD** | `textCoverage < hardTextCoverageThreshold`（默认 20%）**OR** `embeddedImageCount > 0` **OR** `layoutVariance > hardLayoutVarianceThreshold` |
| **兜底** | 不满足 EASY 条件且不满足 HARD 条件 → 归入 HARD（保守策略） |

**阈值选取逻辑**：

- **60% / 20%**：来源于 PDF 文档的统计分析。纯文本页通常覆盖率 > 60%，扫描件/图表页通常 < 20%。中间灰色区域（20%-60%）一般是混合排版，保守归入 HARD。
- **布局方差**：通过 `PDFTextStripper` 获取所有文本块 `(x, y, w, h)`，计算 `y` 坐标的方差。单栏文档方差小（< 页面高度 × 0.3），多栏文档方差大。
- **表格锚点**：正则匹配 `表\s*[0-9]+\s*[（(]?\s*续\s*[）)]?` 和 `续表`。检测到锚点则强制归入 HARD，因为 PDFBox 无法正确处理跨页表格。

### 2.3 路由执行流程（`ParsingRouter`）

```
┌─────────────────────────────────────────────────────────────────┐
│                     ParsingRouter.route(page, meta)              │
│                                                                  │
│  PageClass == EASY ?                                             │
│    ├── YES ──▶ easyPool.submit()                                │
│    │            ├── PDFTextStripper 提取文本 + 坐标               │
│    │            ├── 简易表格检测（基于对齐的文本块）                │
│    │            ├── 结果校验：字符数 < 10 → 重路由到 VLM          │
│    │            └── 返回 PageResult(route="pdfbox")              │
│    │                                                             │
│    └── NO  ──▶ hardPool.submit()                                │
│                 ├── VlmClient.call(page_image)                   │
│                 │    ├── 成功 → 返回 Markdown                     │
│                 │    ├── 超时(30s) → 重试(指数退避 1s/3s)        │
│                 │    └── 2次重试后仍失败 → TesseractOcrEngine    │
│                 └── 返回 PageResult(route="vlm"|"ocr")           │
└─────────────────────────────────────────────────────────────────┘
```

**关键设计决策**：

- 简单页与复杂页**隔离线程池**，防止 VLM 排队阻塞 PDFBox 的快速处理
- 所有页通过 `CompletableFuture.allOf()` 并行调度，按 `pageIndex` 排序聚合
- 复杂页的 `.exceptionally()` 回调实现 OCR 自动降级

### 2.4 后处理融合（`PostProcessor`）

| 处理项 | 触发条件 | 处理逻辑 |
|---|---|---|
| **全局排序** | 所有页 | 按 `y0` 坐标升序重排，保证多栏混排不乱序 |
| **跨段合并** | 第 N 页末尾为 `-` 或以小写字母结尾 | 将 N 页末行与 N+1 页首行无换行拼接（去除断字符） |
| **跨表合并** | 第 N 页底部匹配 `表\d+\s*续?` | 提取 N 页最后一行表格列数，与 N+1 页表头对齐后按列拼接 |

**跨表合并算法**：

```
1. 扫描每页底部 3 行，正则匹配表续锚点
2. 锚点命中 → 从当前页提取表格最后一行（记录列数 M）
3. 从 N+1 页提取表格第一行（记录列数 N）
4. 如果 M == N：直接拼接
5. 如果 M != N：以 N+1 页的表头列数为准，补全 null 值后拼接
6. 移除锚点行本身，不输出到最终 Markdown
```

---

## 3. 技术架构设计

### 3.1 模块划分（Maven 多模块）

```
pdf-parser-agent/                          ← 顶层 parent pom
├── pdf-parser-core/                       ← 核心引擎，零 Spring 依赖
│   ├── classifier/
│   │   └── PageClassifier.java            ← 复杂度二分类器
│   ├── router/
│   │   └── ParsingRouter.java             ← 路由调度 + 线程池管理
│   ├── postprocess/
│   │   └── PostProcessor.java             ← 跨页缝合与全局排序
│   ├── extractor/
│   │   ├── PdfBoxExtractor.java           ← PDFBox 文本/表格抽取
│   │   └── MetaExtractor.java             ← 页元数据提取
│   ├── model/
│   │   ├── PageMeta.java                  ← 页元数据 record
│   │   ├── PageClass.java                 ← EASY/HARD 枚举
│   │   ├── PageResult.java               ← 单页解析结果 record
│   │   ├── ParsedDocument.java            ← 最终文档 record
│   │   └── ParseOptions.java              ← 解析选项 record
│   └── exception/
│       ├── PdfParseException.java         ← 解析异常基类
│       ├── PageParseException.java        ← 单页解析异常
│       └── AllEnginesFailedException.java ← 所有引擎均失败
│
├── pdf-parser-service/                    ← 业务编排层，依赖 Spring
│   ├── PdfParseService.java               ← 对外统一 Service 接口
│   ├── vlm/
│   │   └── VlmClient.java                 ← VLM HTTP 客户端（WebClient）
│   ├── ocr/
│   │   └── TesseractOcrEngine.java        ← Tesseract 本地 OCR 封装
│   └── config/
│       └── ParserProperties.java          ← @ConfigurationProperties 绑定
│
└── pdf-parser-api/                        ← RESTful 对外暴露
    ├── controller/
    │   └── PdfParseController.java        ← REST 控制器
    ├── dto/
    │   ├── ParseRequest.java              ← 请求 DTO
    │   └── ParseResponse.java             ← 响应 DTO
    ├── config/
    │   └── ThreadPoolConfig.java          ← 线程池 Bean 配置
    └── PdfParserApiApplication.java       ← Spring Boot 入口
```

**依赖方向**：`api → service → core`，`core` 模块不依赖 Spring，可独立单元测试。

### 3.2 核心类图

```
┌──────────────────────┐         uses        ┌──────────────────────┐
│   PdfParseService    │ ──────────────────▶ │    ParsingRouter     │
│   (service 层)       │                     │  - route(page, meta) │
│  + parse(file, opts) │                     │  - easyPool          │
│  + parseAsync(file)  │                     │  - hardPool          │
└──────────┬───────────┘                     └──────────┬───────────┘
           │                                            │
           │ uses                                       │ creates
           ▼                                            ▼
┌──────────────────────┐                     ┌──────────────────────┐
│    PostProcessor     │                     │  CompletableFuture   │
│  + merge(results)    │                     │  <PageResult>[]      │
│  + mergeParagraphs() │                     └──────────┬───────────┘
│  + mergeTables()     │              ┌─────────────────┴─────────────────┐
└──────────────────────┘              ▼                                   ▼
                            ┌──────────────────┐              ┌──────────────────┐
                            │  PdfBoxExtractor │              │    VlmClient     │
                            │  + extractText() │              │  + call(pageImg) │
                            │  + extractTable()│              │  + retry()       │
                            └──────────────────┘              └────────┬─────────┘
                                                                      │ fallback
                                                                      ▼
                                                             ┌──────────────────┐
                                                             │ TesseractOcrEngine│
                                                             │  + ocr(pageImg)  │
                                                             └──────────────────┘
```

### 3.3 关键时序

```
Client ── parse(file) ──▶ PdfParseService
                              │
                              ├──▶ Loader.loadPDF(file)               // PDFBox 3.x
                              │     log: {event: "doc.loaded", pages: N}
                              │
                              ├──▶ for each page (parallel):
                              │     ├── MetaExtractor.extract(page)    // 提取 PageMeta
                              │     ├── PageClassifier.classify(meta)
                              │     │     log: {event: "page.classified", pageClass: X}
                              │     ├── ParsingRouter.route(page, meta)
                              │     │     log: {event: "page.routed", route: X, costMs: Y}
                              │     └── (if degraded) log: {event: "page.degraded"}
                              │
                              ├──▶ CompletableFuture.allOf() → 聚合
                              │
                              ├──▶ PostProcessor.merge(results)
                              │     log: {event: "page.merged"}
                              │
                              └──▶ return ParsedDocument
                                    log: {event: "doc.completed", totalCostMs: T}
```

### 3.4 线程模型

| 池名 | 核心线程数 | 最大线程数 | 队列类型 / 容量 | 拒绝策略 | 用途 |
|---|---|---|---|---|---|
| `easyPool` | CPU × 2 | CPU × 2 | `LinkedBlockingQueue(100)` | `CallerRunsPolicy` | PDFBox 文本抽取（CPU 密集型） |
| `hardPool` | 1 | 2 | `ArrayBlockingQueue(20)` | `CallerRunsPolicy` | VLM HTTP 调用（受显存约束） |
| `ocrPool` | 1 | 1 | `SynchronousQueue` | `CallerRunsPolicy` | Tesseract 兜底 OCR（串行，避免内存叠加） |

**设计理由**：

- `hardPool.maxPoolSize=2` 是防止 VLM 显存溢出（OOM）的核心约束。16GB 显存通常只能承载 2 个并发推理请求。
- `easyPool` 使用 `LinkedBlockingQueue` 是因为 PDFBox 抽取任务可排队，不需要拒绝。
- `ocrPool` 使用 `SynchronousQueue` 确保 OCR 任务严格串行——Tesseract 本身已占用大量内存。

---

## 4. 环境依赖要求

### 4.1 运行环境

| 组件 | 版本要求 | 备注 |
|---|---|---|
| JDK | 17+ | 依赖 `record` 类型、`sealed` 类、`switch` 表达式 |
| Maven | 3.8+ | 多模块构建 |
| Spring Boot | 3.2.x | WebFlux + Validation + Configuration Processor |
| OS | Linux / macOS | Windows 需额外配置 Tesseract 本地库路径 |

### 4.2 核心依赖（`pom.xml`）

| 依赖 | GroupId : ArtifactId | 版本 | 用途 |
|---|---|---|---|
| PDFBox | `org.apache.pdfbox:pdfbox` | `3.0.3` | PDF 加载与文本/坐标抽取 |
| WebFlux | `org.springframework.boot:spring-boot-starter-webflux` | 随 BOM | VLM 异步 HTTP 客户端 |
| Tess4J | `net.sourceforge.tess4j:tess4j` | `5.13.0` | 本地 OCR 降级引擎 |
| SLF4J + Logback | `org.slf4j:slf4j-api` + `ch.qos.logback:logback-classic` | 随 BOM | 结构化日志输出 |
| Config Processor | `org.springframework.boot:spring-boot-configuration-processor` | 随 BOM | 生成 `spring-configuration-metadata.json` |
| Jackson | `com.fasterxml.jackson.core:jackson-databind` | 随 BOM | JSON 序列化 |
| Validation | `org.springframework.boot:spring-boot-starter-validation` | 随 BOM | 请求参数校验 |

> **关键避坑**：PDFBox 3.x 已移除 `PDDocument.load()` 静态方法，必须使用 `Loader.loadPDF(File)` 加载。

### 4.3 父 POM 依赖管理片段

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
            <version>3.0.3</version>
        </dependency>
        <dependency>
            <groupId>net.sourceforge.tess4j</groupId>
            <artifactId>tess4j</artifactId>
            <version>5.13.0</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 4.4 外部服务

| 服务 | 地址（可配置） | 协议 | 兜底策略 |
|---|---|---|---|
| VLM (Qwen-VL) | `http://localhost:8000/v1/chat/completions` | OpenAI 兼容 HTTP | 超时 2 次重试 → Tesseract OCR |
| Tesseract 训练数据 | `/usr/share/tesseract-ocr/4.00/tessdata/` | 本地文件系统 | 无兜底，缺失则启动失败 |

### 4.5 资源配额建议

| 资源 | 建议值 | 说明 |
|---|---|---|
| JVM 堆内存 | `-Xmx4g` | PDFBox 处理大文档时占用较高 |
| GPU 显存 | ≥ 16GB | VLM 推理所需 |
| 磁盘 | ≥ 5GB | 临时 PDF 缓存 + Tesseract 训练数据 |
| 网络 | VLM 服务可达 | 内网延迟 < 50ms 为佳 |

---

## 5. 配置说明

### 5.1 `application.yml` 完整配置

```yaml
# ==============================================================
# 混合分级 PDF 解析 Agent 工具 - 配置
# 所有阈值均支持运行时刷新 (Spring Cloud Config / @RefreshScope)
# ==============================================================

pdf-parser:
  # ── 分类器阈值 ──
  classifier:
    easy:
      text-coverage-threshold: 0.60        # 文本覆盖率 > 此值可归入 EASY
      layout-variance-threshold: 0.30      # 布局方差 < 此值可归入 EASY（归一化值）
    hard:
      text-coverage-threshold: 0.20        # 文本覆盖率 < 此值直接归入 HARD
      layout-variance-threshold: 0.70      # 布局方差 > 此值直接归入 HARD
      table-anchor-pattern: "表\\s*\\d+\\s*续|续表"  # 表格锚点正则

  # ── 路由配置 ──
  router:
    easy:
      min-char-count: 10                   # EASY 页最少字符数，低于此值触发重路由
    hard:
      vlm-timeout-ms: 30000                # VLM 请求超时
      vlm-max-retries: 2                    # VLM 重试次数
      vlm-retry-backoff-ms: 1000           # 首次重试等待
      vlm-retry-backoff-multiplier: 3.0    # 重试退避倍增因子
    ocr:
      timeout-ms: 60000                    # Tesseract 单页超时
      language: "chi_sim+eng"              # 识别语言（中英文混合）

  # ── 线程池配置 ──
  pool:
    easy:
      core-size: ${EASY_POOL_CORE:0}       # 0 = 自动取 CPU*2
      queue-capacity: 100
    hard:
      core-size: 1
      max-size: 2
      queue-capacity: 20
    ocr:
      core-size: 1
      max-size: 1

  # ── 后处理配置 ──
  postprocess:
    hyphen-merge: true                     # 是否合并断字符连词
    table-continue-pattern: "表\\s*\\d+\\s*续"  # 跨表识别正则
    table-continue-lookback-lines: 3       # 表续锚点扫描行数（页末）

  # ── VLM 服务配置 ──
  vlm:
    base-url: "http://localhost:8000"
    endpoint: "/v1/chat/completions"
    model: "qwen-vl-max"
    prompt-template: >
      提取该页面所有内容，以Markdown格式输出，包含表格和公式。
      注意：表格必须保留完整结构，公式使用LaTeX格式。

  # ── 文件限制 ──
  file:
    max-size-mb: 50                        # 单文件最大 50MB
    allowed-extensions:                     # 允许的文件扩展名
      - "pdf"
      - "PDF"

  # ── 日志 ──
  logging:
    structured: true                       # 启用 JSON 结构化日志
    mask-sensitive: true                   # 脱敏处理（API Key 等）

# ── Spring 配置 ──
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 55MB
  webflux:
    base-path: /api/v1

# ── Logback JSON 布局 ──
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
```

### 5.2 `ParserProperties.java` 配置绑定

```java
package com.demo.pdfparser.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * PDF 解析器配置属性，所有阈值均从 application.yml 绑定。
 * 标记 @RefreshScope 后支持运行时热更新。
 */
@Validated
@ConfigurationProperties(prefix = "pdf-parser")
public record ParserProperties(
        Classifier classifier,
        Router router,
        Pool pool,
        Postprocess postprocess,
        Vlm vlm,
        File file,
        Logging logging
) {
    public record Classifier(
            @Min(0) @Max(100) Easy easy,
            @Min(0) @Max(100) Hard hard
    ) {
        public record Easy(
                @Min(0) @Max(100) double textCoverageThreshold,
                @Min(0) @Max(100) double layoutVarianceThreshold
        ) {}
        public record Hard(
                @Min(0) @Max(100) double textCoverageThreshold,
                @Min(0) @Max(100) double layoutVarianceThreshold,
                @NotBlank String tableAnchorPattern
        ) {}
    }

    public record Router(
            Easy easy,
            Hard hard,
            Ocr ocr
    ) {
        public record Easy(@Min(1) int minCharCount) {}
        public record Hard(
                @Min(1000) int vlmTimeoutMs,
                @Min(0) @Max(5) int vlmMaxRetries,
                @Min(0) long vlmRetryBackoffMs,
                @Min(1) double vlmRetryBackoffMultiplier
        ) {}
        public record Ocr(
                @Min(1000) int timeoutMs,
                @NotBlank String language
        ) {}
    }

    public record Pool(
            Easy easy,
            Hard hard,
            Ocr ocr
    ) {
        public record Easy(int coreSize, int queueCapacity) {}
        public record Hard(int coreSize, int maxSize, int queueCapacity) {}
        public record Ocr(int coreSize, int maxSize) {}
    }

    public record Postprocess(
            boolean hyphenMerge,
            @NotBlank String tableContinuePattern,
            @Min(1) @Max(10) int tableContinueLookbackLines
    ) {}

    public record Vlm(
            @NotBlank String baseUrl,
            @NotBlank String endpoint,
            @NotBlank String model,
            @NotBlank String promptTemplate
    ) {}

    public record File(
            @Min(1) @Max(500) int maxSizeMb,
            List<String> allowedExtensions
    ) {}

    public record Logging(
            boolean structured,
            boolean maskSensitive
    ) {}
}
```

---

## 6. 详细开发步骤

### 6.1 步骤总览

| 步骤 | 产出物 | 验证点 | 预计人天 |
|---|---|---|---|
| S-1 | Maven 多模块骨架 | `mvn -q compile` 通过 | 0.5 |
| S-2 | 核心模型 + 异常类 | `PageMeta` / `PageResult` / `ParsedDocument` / 异常层次 | 0.5 |
| S-3 | 分类器 + 配置 | `PageClassifier` + `application.yml` + `ParserProperties` | 1 |
| S-4 | 元数据提取器 | `MetaExtractor`（PDFBox 坐标遍历） | 1 |
| S-5 | PDFBox 抽取器 | `PdfBoxExtractor`（文本 + 简易表格） | 1.5 |
| S-6 | VLM 客户端 | `VlmClient`（WebClient + 重试 + 超时） | 1.5 |
| S-7 | OCR 引擎 | `TesseractOcrEngine`（Tess4J 封装） | 1 |
| S-8 | 路由器 | `ParsingRouter`（双线程池 + CompletableFuture） | 1.5 |
| S-9 | 后处理器 | `PostProcessor`（排序 + 跨段 + 跨表） | 2 |
| S-10 | Service 编排 | `PdfParseService`（端到端管线） | 1 |
| S-11 | REST API | `PdfParseController`（同步 + 异步） | 1 |
| S-12 | 结构化日志 | Logback JSON 布局 + MDC 注入 | 0.5 |
| S-13 | 测试 | 单元测试 + 集成测试 + 契约测试 | 2 |
| S-14 | 文档 + 部署 | `README.md` + Dockerfile + 启动脚本 | 0.5 |

**总计**：约 15.5 人天

### 6.2 步骤详解

#### S-2 核心模型

```java
// pdf-parser-core/src/main/java/com/demo/pdfparser/core/model/PageMeta.java
package com.demo.pdfparser.core.model;

/**
 * 页面元数据，由 MetaExtractor 从 PDDocument 中提取。
 * 所有字段均为不可变，确保线程安全。
 */
public record PageMeta(
        int pageIndex,              // 页码（0-based）
        double textCoverage,        // 文本覆盖率 = 文本字符总面积 / 页面面积
        int embeddedImageCount,     // 嵌入图像数量
        double layoutVariance,      // 文本块 y 坐标方差（归一化到 [0, 1]）
        boolean hasTableAnchor,     // 是否检测到"续表"/"表X 续"锚点
        int totalCharCount          // 页面总字符数（粗略估计）
) {
    public PageMeta {
        if (pageIndex < 0) throw new IllegalArgumentException("pageIndex must be >= 0");
        if (textCoverage < 0 || textCoverage > 1)
            throw new IllegalArgumentException("textCoverage must be in [0, 1]");
        if (layoutVariance < 0 || layoutVariance > 1)
            throw new IllegalArgumentException("layoutVariance must be in [0, 1]");
    }
}
```

```java
// pdf-parser-core/src/main/java/com/demo/pdfparser/core/model/PageClass.java
package com.demo.pdfparser.core.model;

/** 页面复杂度分类。 */
public enum PageClass {
    /** 简单页：纯文本为主，PDFBox 可处理 */
    EASY,
    /** 复杂页：图表/多栏/公式/表格，需要 VLM */
    HARD
}
```

```java
// pdf-parser-core/src/main/java/com/demo/pdfparser/core/model/PageResult.java
package com.demo.pdfparser.core.model;

/**
 * 单页解析结果。
 * route 字段指示实际使用的解析引擎，便于成本追踪。
 */
public record PageResult(
        int pageIndex,
        PageClass pageClass,        // 分类结果
        String route,               // "pdfbox" | "vlm" | "ocr"（实际引擎）
        String content,             // Markdown 或纯文本
        long costMs,                // 解析耗时（毫秒）
        boolean degraded            // 是否触发降级
) {}
```

```java
// pdf-parser-core/src/main/java/com/demo/pdfparser/core/model/ParsedDocument.java
package com.demo.pdfparser.core.model;

import java.util.List;

/**
 * 最终解析结果，包含所有页面的解析结果和融合后的完整 Markdown。
 */
public record ParsedDocument(
        String sourceId,            // 源文件唯一标识（MD5 或 UUID）
        String fileName,            // 原始文件名
        int totalPages,             // 总页数
        List<PageResult> pages,     // 每页解析详情
        String mergedMarkdown,      // 融合后的完整 Markdown
        ParseStats stats            // 统计信息
) {
    public record ParseStats(
            int easyCount,          // EASY 分类页数
            int hardCount,          // HARD 分类页数
            int ocrFallbackCount,   // OCR 降级次数
            int rerouteCount,       // 重路由次数（EASY→VLM）
            long totalCostMs        // 总耗时
    ) {}
}
```

#### S-3 分类器

```java
// pdf-parser-core/src/main/java/com/demo/pdfparser/core/classifier/PageClassifier.java
package com.demo.pdfparser.core.classifier;

import com.demo.pdfparser.core.model.PageClass;
import com.demo.pdfparser.core.model.PageMeta;
import com.demo.pdfparser.service.config.ParserProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 页面复杂度二分类器。
 *
 * <h3>算法说明</h3>
 * <p>采用"规则优先 + 兜底保守"策略：</p>
 * <ol>
 *   <li>先判断是否满足 HARD 条件（优先级最高）</li>
 *   <li>再判断是否满足 EASY 条件</li>
 *   <li>既不满足 EASY 也不满足 HARD → 归入 HARD（保守策略）</li>
 * </ol>
 *
 * <h3>调优建议</h3>
 * <ul>
 *   <li>表格密集型业务：将 easy.textCoverageThreshold 下调至 0.50</li>
 *   <li>扫描件为主：将 hard.textCoverageThreshold 上调至 0.30</li>
 *   <li>观测手段：监控结构化日志中 pageClass 分布，按比例调整</li>
 * </ul>
 */
public class PageClassifier {

    private static final Logger log = LoggerFactory.getLogger(PageClassifier.class);

    private final ParserProperties.Classifier props;
    private final Pattern tableAnchorPattern;

    public PageClassifier(ParserProperties.Classifier props) {
        this.props = props;
        this.tableAnchorPattern = Pattern.compile(props.hard().tableAnchorPattern());
    }

    /**
     * 对页面元数据进行分类。
     *
     * @param meta 页面元数据
     * @return 复杂度分类结果
     */
    public PageClass classify(PageMeta meta) {
        log.debug("classifying page {}: coverage={}, images={}, variance={}, anchor={}",
                meta.pageIndex(), meta.textCoverage(), meta.embeddedImageCount(),
                meta.layoutVariance(), meta.hasTableAnchor());

        // 1. HARD 判定（优先级最高）
        if (isHard(meta)) {
            log.debug("page {} classified as HARD", meta.pageIndex());
            return PageClass.HARD;
        }

        // 2. EASY 判定
        if (isEasy(meta)) {
            log.debug("page {} classified as EASY", meta.pageIndex());
            return PageClass.EASY;
        }

        // 3. 兜底归入 HARD（保守策略）
        log.debug("page {} classified as HARD (fallback)", meta.pageIndex());
        return PageClass.HARD;
    }

    private boolean isHard(PageMeta meta) {
        // 低文本覆盖率（扫描件/图片页）
        if (meta.textCoverage() < props.hard().textCoverageThreshold()) {
            return true;
        }
        // 包含嵌入图像
        if (meta.embeddedImageCount() > 0) {
            return true;
        }
        // 布局方差大（多栏/复杂排版）
        return meta.layoutVariance() > props.hard().layoutVarianceThreshold();
    }

    private boolean isEasy(PageMeta meta) {
        // 高文本覆盖率
        // 无表格锚点
        // 布局方差小
        return meta.textCoverage() > props.easy().textCoverageThreshold()
                && !meta.hasTableAnchor()
                && meta.layoutVariance() < props.easy().layoutVarianceThreshold();
    }
}
```

#### S-4 元数据提取器

```java
// pdf-parser-core/src/main/java/com/demo/pdfparser/core/extractor/MetaExtractor.java
package com.demo.pdfparser.core.extractor;

import com.demo.pdfparser.core.model.PageMeta;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 页面元数据提取器。
 * 遍历 PDF 页面的文本位置和图像资源，计算文本覆盖率、布局方差、图像数等。
 */
public class MetaExtractor {

    private static final Logger log = LoggerFactory.getLogger(MetaExtractor.class);

    private static final Pattern TABLE_ANCHOR = Pattern.compile("表\\s*\\d+\\s*续|续表");

    /**
     * 提取单页元数据。
     *
     * @param page      PDFBox 页面对象
     * @param pageIndex 页码（0-based）
     * @return 页面元数据
     */
    public PageMeta extract(PDPage page, int pageIndex) throws IOException {
        // 1. 提取文本块坐标
        List<TextPosition> positions = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                positions.addAll(textPositions);
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);

        // 需要临时文档来提取
        // 实际实现中，stripper 应绑定到 PDDocument

        // 2. 计算文本覆盖率
        double pageArea = page.getMediaBox().getWidth() * page.getMediaBox().getHeight();
        double textArea = positions.stream()
                .mapToDouble(p -> p.getWidthDirAdj() * p.getHeightDir())
                .sum();
        double textCoverage = pageArea > 0 ? textArea / pageArea : 0;

        // 3. 计算布局方差（y 坐标方差，归一化）
        double pageHeight = page.getMediaBox().getHeight();
        double meanY = positions.stream().mapToDouble(TextPosition::getYDirAdj).average().orElse(0);
        double variance = positions.stream()
                .mapToDouble(p -> Math.pow(p.getYDirAdj() - meanY, 2))
                .average().orElse(0);
        double normalizedVariance = pageHeight > 0 ? Math.sqrt(variance) / pageHeight : 0;

        // 4. 检测嵌入图像
        int imageCount = countEmbeddedImages(page);

        // 5. 检测表格锚点
        String fullText = positions.stream()
                .map(TextPosition::getUnicode)
                .reduce("", String::concat);
        boolean hasTableAnchor = TABLE_ANCHOR.matcher(fullText).find();

        // 6. 总字符数
        int totalChars = fullText.length();

        return new PageMeta(pageIndex, textCoverage, imageCount,
                normalizedVariance, hasTableAnchor, totalChars);
    }

    /**
     * 统计页面中的嵌入图像数量。
     */
    private int countEmbeddedImages(PDPage page) throws IOException {
        int count = 0;
        PDResources resources = page.getResources();
        if (resources != null) {
            for (var name : resources.getXObjectNames()) {
                if (resources.isImageXObject(name)) {
                    count++;
                }
            }
        }
        return count;
    }
}
```

#### S-6 VLM 客户端

```java
// pdf-parser-service/src/main/java/com/demo/pdfparser/service/vlm/VlmClient.java
package com.demo.pdfparser.service.vlm;

import com.demo.pdfparser.service.config.ParserProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * VLM（视觉语言模型）HTTP 客户端。
 * 使用 WebClient 异步调用兼容 OpenAI 接口的 VLM 服务。
 * 支持指数退避重试，失败时抛出异常由上层 ParsingRouter 降级处理。
 */
public class VlmClient {

    private static final Logger log = LoggerFactory.getLogger(VlmClient.class);

    private final WebClient webClient;
    private final ParserProperties.Vlm props;
    private final ParserProperties.Router.Hard routerProps;

    public VlmClient(ParserProperties.Vlm props, ParserProperties.Router.Hard routerProps) {
        this.props = props;
        this.routerProps = routerProps;
        this.webClient = WebClient.builder()
                .baseUrl(props.baseUrl())
                .build();
    }

    /**
     * 调用 VLM 解析页面图像。
     *
     * @param imageBytes 页面渲染后的图像字节（PNG/JPEG）
     * @return VLM 返回的 Markdown 文本
     * @throws VlmException 所有重试均失败
     */
    public String call(byte[] imageBytes) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> requestBody = Map.of(
                "model", props.model(),
                "messages", new Object[]{
                        Map.of("role", "user", "content", new Object[]{
                                Map.of("type", "text", "text", props.promptTemplate()),
                                Map.of("type", "image_url", "image_url",
                                        Map.of("url", "data:image/png;base64," + base64Image))
                        })
                }
        );

        return webClient.post()
                .uri(props.endpoint())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> extractContent(response))
                .retryWhen(Retry.backoff(
                        routerProps.vlmMaxRetries(),
                        Duration.ofMillis(routerProps.vlmRetryBackoffMs())
                ).maxBackoff(Duration.ofSeconds(10)))
                .timeout(Duration.ofMillis(routerProps.vlmTimeoutMs()))
                .onErrorMap(WebClientResponseException.class,
                        e -> new VlmException("VLM HTTP " + e.getStatusCode(), e))
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        e -> new VlmException("VLM timeout after " + routerProps.vlmTimeoutMs() + "ms", e))
                .block();
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        try {
            var choices = (java.util.List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new VlmException("VLM response has no choices");
            }
            var message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (ClassCastException | NullPointerException e) {
            throw new VlmException("VLM response format error: " + e.getMessage(), e);
        }
    }

    /** VLM 调用异常。 */
    public static class VlmException extends RuntimeException {
        public VlmException(String message) { super(message); }
        public VlmException(String message, Throwable cause) { super(message, cause); }
    }
}
```

#### S-8 路由器

```java
// pdf-parser-core/src/main/java/com/demo/pdfparser/core/router/ParsingRouter.java
package com.demo.pdfparser.core.router;

import com.demo.pdfparser.core.classifier.PageClassifier;
import com.demo.pdfparser.core.extractor.PdfBoxExtractor;
import com.demo.pdfparser.core.model.PageClass;
import com.demo.pdfparser.core.model.PageMeta;
import com.demo.pdfparser.core.model.PageResult;
import java.util.concurrent.*;

/**
 * 解析路由器。
 * 根据 PageClassifier 的分类结果，将页面分派到不同线程池执行。
 * 核心职责：分级路由 + 降级编排 + 线程池隔离。
 */
public class ParsingRouter {

    private final PageClassifier classifier;
    private final PdfBoxExtractor pdfBoxExtractor;
    private final ExecutorService easyPool;
    private final ExecutorService hardPool;

    private final int minCharCount;

    public ParsingRouter(PageClassifier classifier,
                         PdfBoxExtractor pdfBoxExtractor,
                         ExecutorService easyPool,
                         ExecutorService hardPool,
                         int minCharCount) {
        this.classifier = classifier;
        this.pdfBoxExtractor = pdfBoxExtractor;
        this.easyPool = easyPool;
        this.hardPool = hardPool;
        this.minCharCount = minCharCount;
    }

    /**
     * 路由单页解析。
     *
     * @param page      PDFBox 页面对象
     * @param meta      页面元数据
     * @param vlmCaller VLM 调用回调（由 service 层注入，避免 core 依赖 Spring）
     * @param ocrCaller OCR 调用回调（同上）
     * @return CompletableFuture，解析完成后返回 PageResult
     */
    public CompletableFuture<PageResult> route(
            Object page,  // PDPage — 用 Object 避免 core 模块直接依赖 PDFBox 具体类型
            PageMeta meta,
            VlmCaller vlmCaller,
            OcrCaller ocrCaller) {

        PageClass cls = classifier.classify(meta);

        return switch (cls) {
            case EASY -> CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                try {
                    String content = pdfBoxExtractor.extractText(page);
                    long costMs = System.currentTimeMillis() - start;

                    // 重路由检查：字符数过少 → 升级 VLM
                    if (content.length() < minCharCount) {
                        return callVlmWithFallback(page, meta, vlmCaller, ocrCaller, true);
                    }

                    return new PageResult(meta.pageIndex(), PageClass.EASY,
                            "pdfbox", content, costMs, false);
                } catch (Exception e) {
                    // PDFBox 异常 → 降级 VLM
                    return callVlmWithFallback(page, meta, vlmCaller, ocrCaller, true);
                }
            }, easyPool);

            case HARD -> CompletableFuture.supplyAsync(() -> {
                return callVlmWithFallback(page, meta, vlmCaller, ocrCaller, false);
            }, hardPool);
        };
    }

    private PageResult callVlmWithFallback(Object page, PageMeta meta,
                                           VlmCaller vlmCaller, OcrCaller ocrCaller,
                                           boolean isReroute) {
        long start = System.currentTimeMillis();
        try {
            String content = vlmCaller.call(page);
            long costMs = System.currentTimeMillis() - start;
            return new PageResult(meta.pageIndex(), meta.textCoverage() > 0.6 ? PageClass.EASY : PageClass.HARD,
                    "vlm", content, costMs, isReroute);
        } catch (Exception vlmEx) {
            // VLM 失败 → Tesseract OCR 降级
            long ocrStart = System.currentTimeMillis();
            try {
                String content = ocrCaller.call(page);
                long costMs = System.currentTimeMillis() - ocrStart;
                return new PageResult(meta.pageIndex(), PageClass.HARD,
                        "ocr", content, costMs, true);
            } catch (Exception ocrEx) {
                throw new com.demo.pdfparser.core.exception.PageParseException(
                        meta.pageIndex(), "All engines failed for page", ocrEx);
            }
        }
    }

    /** VLM 调用回调接口。 */
    @FunctionalInterface
    public interface VlmCaller {
        String call(Object page) throws Exception;
    }

    /** OCR 调用回调接口。 */
    @FunctionalInterface
    public interface OcrCaller {
        String call(Object page) throws Exception;
    }

    public void shutdown() {
        easyPool.shutdown();
        hardPool.shutdown();
    }
}
```

#### S-9 后处理器

```java
// pdf-parser-core/src/main/java/com/demo/pdfparser/core/postprocess/PostProcessor.java
package com.demo.pdfparser.core.postprocess;

import com.demo.pdfparser.core.model.PageResult;
import com.demo.pdfparser.core.model.ParsedDocument;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后处理融合器。
 * 负责：全局坐标排序 → 跨段合并（断字符） → 跨表合并（续表锚点）。
 */
public class PostProcessor {

    private final Pattern tableContinuePattern;
    private final int tableLookbackLines;

    public PostProcessor(String tableContinuePattern, int tableLookbackLines) {
        this.tableContinuePattern = Pattern.compile(tableContinuePattern);
        this.tableLookbackLines = tableLookbackLines;
    }

    /**
     * 融合所有页面结果，生成最终文档。
     */
    public ParsedDocument merge(String sourceId, String fileName,
                                List<PageResult> pages) {
        // 按 pageIndex 排序
        List<PageResult> sorted = new ArrayList<>(pages);
        sorted.sort(Comparator.comparingInt(PageResult::pageIndex));

        // 逐页处理
        StringBuilder merged = new StringBuilder();
        String previousPageEnd = "";

        for (int i = 0; i < sorted.size(); i++) {
            PageResult page = sorted.get(i);
            String content = page.content();

            if (i > 0) {
                // 跨段合并：检查上一页末尾是否需要无换行拼接
                content = mergeParagraphs(previousPageEnd, content);
            }

            // 跨表合并：检查上一页是否有未闭合表格
            if (i > 0 && hasTableContinue(previousPageEnd)) {
                content = mergeTables(previousPageEnd, content);
                // 移除已处理的上一页表续锚点行
                merged.setLength(removeTableAnchor(merged.toString()));
            }

            merged.append(content);
            if (i < sorted.size() - 1) {
                merged.append("\n\n---\n\n");  // 分页标记
            }

            previousPageEnd = getLastLines(content, tableLookbackLines);
        }

        // 统计
        long easyCount = sorted.stream().filter(p -> p.pageClass().name().equals("EASY")).count();
        long hardCount = sorted.size() - easyCount;
        long ocrCount = sorted.stream().filter(p -> "ocr".equals(p.route())).count();
        long rerouteCount = sorted.stream().filter(PageResult::degraded).count() - ocrCount;
        long totalCostMs = sorted.stream().mapToLong(PageResult::costMs).sum();

        return new ParsedDocument(
                sourceId, fileName, sorted.size(), sorted,
                merged.toString(),
                new ParsedDocument.ParseStats(
                        (int) easyCount, (int) hardCount,
                        (int) ocrCount, (int) rerouteCount, totalCostMs)
        );
    }

    /**
     * 跨段合并：如果上一页末尾是连字符或以小写字母结尾，无换行拼接。
     */
    String mergeParagraphs(String prevEnd, String nextStart) {
        if (prevEnd == null || prevEnd.isBlank()) return nextStart;
        if (nextStart == null || nextStart.isBlank()) return nextStart;

        String trimmed = prevEnd.stripTrailing();
        if (trimmed.endsWith("-")) {
            // 去除断字符，拼接
            String merged = trimmed.substring(0, trimmed.length() - 1)
                    + nextStart.stripLeading();
            return merged;
        }
        if (!trimmed.isEmpty() && Character.isLowerCase(trimmed.charAt(trimmed.length() - 1))) {
            // 小写字母结尾 → 可能是跨页断词
            return trimmed + nextStart.stripLeading();
        }
        return nextStart;
    }

    /**
     * 跨表合并：检测续表锚点，对齐列数后拼接。
     */
    String mergeTables(String prevEnd, String nextStart) {
        // 简化实现：找到上一页最后的表格行和下一页第一行表格，按列数对齐
        String[] prevLines = prevEnd.split("\n");
        String[] nextLines = nextStart.split("\n");

        // 找到上一页最后一个表格行（以 | 开头或包含 | 分隔符）
        String prevTableRow = null;
        for (int i = prevLines.length - 1; i >= 0; i--) {
            String line = prevLines[i].trim();
            if (isTableRow(line)) {
                prevTableRow = line;
                break;
            }
        }

        // 找到下一页第一个表格行（跳过表头）
        String nextTableRow = null;
        int nextTableStart = 0;
        for (int i = 0; i < nextLines.length; i++) {
            String line = nextLines[i].trim();
            if (isTableRow(line)) {
                nextTableRow = line;
                nextTableStart = i;
                break;
            }
        }

        if (prevTableRow != null && nextTableRow != null) {
            int prevCols = prevTableRow.split("\\|").length;
            int nextCols = nextTableRow.split("\\|").length;
            if (prevCols != nextCols) {
                // 列数不对齐，以后一页为准
                return nextStart;
            }
            // 跳过下一页表头分隔行（|---|---|），直接拼接数据行
            StringBuilder sb = new StringBuilder();
            for (int i = nextTableStart + 2; i < nextLines.length; i++) {
                sb.append(nextLines[i]).append("\n");
            }
            return sb.toString().trim();
        }

        return nextStart;
    }

    private boolean hasTableContinue(String text) {
        return tableContinuePattern.matcher(text).find();
    }

    private boolean isTableRow(String line) {
        return line.startsWith("|") && line.endsWith("|") && line.contains("|");
    }

    private String getLastLines(String text, int n) {
        String[] lines = text.split("\n");
        int start = Math.max(0, lines.length - n);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }

    private String removeTableAnchor(String text) {
        Matcher m = tableContinuePattern.matcher(text);
        return m.replaceAll("");
    }
}
```

#### S-10 Service 编排

```java
// pdf-parser-service/src/main/java/com/demo/pdfparser/service/PdfParseService.java
package com.demo.pdfparser.service;

import com.demo.pdfparser.core.classifier.PageClassifier;
import com.demo.pdfparser.core.extractor.MetaExtractor;
import com.demo.pdfparser.core.extractor.PdfBoxExtractor;
import com.demo.pdfparser.core.model.PageMeta;
import com.demo.pdfparser.core.model.PageResult;
import com.demo.pdfparser.core.model.ParsedDocument;
import com.demo.pdfparser.core.postprocess.PostProcessor;
import com.demo.pdfparser.core.router.ParsingRouter;
import com.demo.pdfparser.service.config.ParserProperties;
import com.demo.pdfparser.service.ocr.TesseractOcrEngine;
import com.demo.pdfparser.service.vlm.VlmClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * PDF 解析服务（对外统一入口）。
 * 编排整个管线：加载 → 分类 → 路由 → 融合 → 返回。
 */
public class PdfParseService {

    private static final Logger log = LoggerFactory.getLogger(PdfParseService.class);

    private final ParserProperties props;
    private final PageClassifier classifier;
    private final MetaExtractor metaExtractor;
    private final PdfBoxExtractor pdfBoxExtractor;
    private final ParsingRouter router;
    private final VlmClient vlmClient;
    private final TesseractOcrEngine ocrEngine;
    private final PostProcessor postProcessor;

    public PdfParseService(ParserProperties props,
                           PageClassifier classifier,
                           MetaExtractor metaExtractor,
                           PdfBoxExtractor pdfBoxExtractor,
                           ParsingRouter router,
                           VlmClient vlmClient,
                           TesseractOcrEngine ocrEngine,
                           PostProcessor postProcessor) {
        this.props = props;
        this.classifier = classifier;
        this.metaExtractor = metaExtractor;
        this.pdfBoxExtractor = pdfBoxExtractor;
        this.router = router;
        this.vlmClient = vlmClient;
        this.ocrEngine = ocrEngine;
        this.postProcessor = postProcessor;
    }

    /**
     * 解析 PDF 文件。
     *
     * @param file        PDF 文件
     * @param options     解析选项
     * @return 解析结果
     * @throws PdfParseException 解析失败
     */
    public ParsedDocument parse(File file, ParseOptions options) throws IOException {
        String sourceId = UUID.randomUUID().toString();
        MDC.put("sourceId", sourceId);
        long totalStart = System.currentTimeMillis();

        log.info("Starting PDF parse: file={}, size={}", file.getName(), file.length());

        try (PDDocument document = Loader.loadPDF(file)) {
            int totalPages = document.getNumberOfPages();
            log.info("doc.loaded: sourceId={}, pages={}", sourceId, totalPages);

            PDFRenderer renderer = new PDFRenderer(document);

            // 1. 提取元数据 + 分类（串行，轻量操作）
            List<PageMeta> metas = new ArrayList<>();
            for (int i = 0; i < totalPages; i++) {
                PageMeta meta = metaExtractor.extract(document.getPage(i), i);
                PageClass cls = classifier.classify(meta);
                log.info("page.classified: sourceId={}, pageIndex={}, pageClass={}, coverage={}",
                        sourceId, i, cls, String.format("%.2f", meta.textCoverage()));
                metas.add(meta);
            }

            // 2. 并行路由执行
            List<CompletableFuture<PageResult>> futures = new ArrayList<>();
            for (int i = 0; i < totalPages; i++) {
                final int pageIndex = i;
                final PageMeta meta = metas.get(i);

                CompletableFuture<PageResult> future = router.route(
                        document.getPage(pageIndex), meta,
                        // VLM 回调：渲染页面为图像 → 调用 VLM
                        page -> {
                            byte[] imageBytes = renderPageToBytes(renderer, pageIndex);
                            return vlmClient.call(imageBytes);
                        },
                        // OCR 回调
                        page -> {
                            byte[] imageBytes = renderPageToBytes(renderer, pageIndex);
                            return ocrEngine.ocr(imageBytes);
                        }
                ).thenApply(result -> {
                    log.info("page.routed: sourceId={}, pageIndex={}, route={}, costMs={}, degraded={}",
                            sourceId, result.pageIndex(), result.route(),
                            result.costMs(), result.degraded());
                    return result;
                });

                futures.add(future);
            }

            // 3. 等待所有页解析完成
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
            allFutures.get(props.router().hard().vlmTimeoutMs() * 3, TimeUnit.MILLISECONDS);

            List<PageResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .sorted((a, b) -> Integer.compare(a.pageIndex(), b.pageIndex()))
                    .toList();

            // 4. 后处理融合
            ParsedDocument doc = postProcessor.merge(sourceId, file.getName(), results);
            log.info("doc.completed: sourceId={}, pages={}, totalCostMs={}",
                    sourceId, totalPages, System.currentTimeMillis() - totalStart);

            return doc;

        } catch (Exception e) {
            log.error("PDF parse failed: sourceId={}, file={}", sourceId, file.getName(), e);
            throw new PdfParseException("Failed to parse PDF: " + file.getName(), e);
        } finally {
            MDC.remove("sourceId");
        }
    }

    private byte[] renderPageToBytes(PDFRenderer renderer, int pageIndex) throws IOException {
        var image = renderer.renderImageWithDPI(pageIndex, 150);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

    /** 解析异常。 */
    public static class PdfParseException extends RuntimeException {
        public PdfParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

---

## 7. API 接口规范

### 7.1 同步解析

```
POST /api/v1/pdf/parse
Content-Type: multipart/form-data
```

**请求参数**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `file` | File | 是 | — | PDF 文件，≤ 50MB |
| `enable_ocr_fallback` | Boolean | 否 | `true` | 是否启用 Tesseract OCR 降级 |
| `vlm_timeout_ms` | Integer | 否 | 30000 | VLM 单次请求超时（毫秒） |

**响应 200**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "sourceId": "d41d8cd98f00b204e9800998ecf8427e",
    "fileName": "report.pdf",
    "totalPages": 20,
    "mergedMarkdown": "# 第一章 绪论\n\n...",
    "stats": {
      "easyCount": 17,
      "hardCount": 3,
      "ocrFallbackCount": 0,
      "rerouteCount": 0,
      "totalCostMs": 8421
    },
    "pages": [
      {
        "pageIndex": 0,
        "pageClass": "EASY",
        "route": "pdfbox",
        "content": "第一章 绪论\n...",
        "costMs": 120,
        "degraded": false
      },
      {
        "pageIndex": 5,
        "pageClass": "HARD",
        "route": "vlm",
        "content": "## 图 2-1 系统架构\n\n...",
        "costMs": 3200,
        "degraded": false
      }
    ]
  }
}
```

### 7.2 异步解析

```
POST /api/v1/pdf/parse/async
Content-Type: multipart/form-data
```

**响应 202**

```json
{
  "code": 0,
  "message": "accepted",
  "data": {
    "taskId": "task-abc123",
    "status": "PENDING"
  }
}
```

**轮询任务状态**

```
GET /api/v1/pdf/task/{taskId}
```

**响应 200（进行中）**

```json
{
  "code": 0,
  "data": {
    "taskId": "task-abc123",
    "status": "PROCESSING",
    "progress": { "completed": 12, "total": 20 }
  }
}
```

**响应 200（已完成）**

```json
{
  "code": 0,
  "data": {
    "taskId": "task-abc123",
    "status": "COMPLETED",
    "result": { /* 同同步接口的 data 结构 */ }
  }
}
```

### 7.3 错误码全集

| HTTP 状态码 | 业务 code | 含义 | 触发条件 |
|---|---|---|---|
| 200 | 0 | 成功 | 正常完成 |
| 400 | 40001 | 文件格式不支持 | 扩展名非 `pdf` |
| 400 | 40002 | 文件过大 | 超过 `file.max-size-mb` |
| 400 | 40003 | 参数校验失败 | 缺少必填字段 |
| 422 | 42201 | PDF 加载失败 | 文件损坏或加密 |
| 500 | 50001 | 所有引擎均失败 | VLM + OCR 均不可用 |
| 500 | 50002 | 内部错误 | 未知的运行时异常 |
| 504 | 50401 | 整体解析超时 | 超过 5 分钟 |

---

## 8. 使用示例

### 8.1 REST 调用

```bash
# 同步解析
curl -X POST http://localhost:8080/api/v1/pdf/parse \
  -F "file=@./report.pdf" \
  -F "enable_ocr_fallback=true" \
  -F "vlm_timeout_ms=30000"

# 异步解析
curl -X POST http://localhost:8080/api/v1/pdf/parse/async \
  -F "file=@./report.pdf"

# 轮询结果
curl http://localhost:8080/api/v1/pdf/task/task-abc123
```

### 8.2 Java Service 调用

```java
@Autowired
private PdfParseService pdfParseService;

// 解析并获取完整 Markdown
ParsedDocument doc = pdfParseService.parse(
    new File("./report.pdf"),
    ParseOptions.defaults()
);

// 使用解析结果
System.out.println("总页数: " + doc.totalPages());
System.out.println("VLM 调用: " + doc.stats().hardCount() + " 页");
System.out.println("OCR 降级: " + doc.stats().ocrFallbackCount() + " 次");
System.out.println("总耗时: " + doc.stats().totalCostMs() + "ms");

// 逐页查看路由
for (PageResult page : doc.pages()) {
    System.out.printf("第 %d 页: %s → %s (%dms)%n",
        page.pageIndex() + 1, page.pageClass(), page.route(), page.costMs());
}
```

### 8.3 与 AgentScope MCP 集成

```java
// 在 AgentScope 应用中注册为 MCP 工具
mcpClient.registerCustomTool(
    "parse_pdf",
    "解析 PDF 文件，返回结构化 Markdown 文本。"
        + "输出包含完整文档内容、每页解析路由和耗时统计。",
    """
    {
      "type": "object",
      "properties": {
        "path": {
          "type": "string",
          "description": "PDF 文件路径（相对于工作空间根目录）"
        }
      },
      "required": ["path"]
    }
    """,
    args -> {
        String path = String.valueOf(args.getOrDefault("path", ""));
        File file = new File(workspaceBaseDir, path);
        ParsedDocument doc = pdfParseService.parse(file, ParseOptions.defaults());
        return String.format("""
                ## 解析结果: %s
                - 总页数: %d | EASY: %d | HARD: %d | OCR降级: %d
                - 总耗时: %dms

                %s
                """,
                doc.fileName(),
                doc.totalPages(),
                doc.stats().easyCount(),
                doc.stats().hardCount(),
                doc.stats().ocrFallbackCount(),
                doc.stats().totalCostMs(),
                doc.mergedMarkdown());
    }
);
```

### 8.4 在 startas.sh 中集成

```bash
#!/bin/bash
# 启动 VLM 服务（假设已部署）
# docker run -d --gpus all -p 8000:8000 qwen-vl-server

# 设置 VLM 地址
export VLM_BASE_URL="http://localhost:8000"
export VLM_MODEL="qwen-vl-max"

# 启动 PDF 解析服务
java -jar pdf-parser-api/target/pdf-parser-api-1.0.0.jar \
  --pdf-parser.vlm.base-url=$VLM_BASE_URL \
  --pdf-parser.vlm.model=$VLM_MODEL
```

---

## 9. 错误处理机制

### 9.1 异常类层次结构

```
RuntimeException
├── PdfParseException                  ← 解析异常基类（pdf-parser-core）
│   ├── PageParseException             ← 单页解析失败（含 pageIndex）
│   └── AllEnginesFailedException      ← 所有引擎均失败
├── VlmClient.VlmException             ← VLM 调用异常（HTTP/超时/格式错）
├── TesseractOcrEngine.OcrException    ← OCR 引擎异常
└── IllegalArgumentException           ← 参数校验失败（由 @Validated 触发）
```

### 9.2 异常分级与处理策略

| 级别 | 异常类型 | 触发场景 | 处理策略 |
|---|---|---|---|
| **可恢复** | `PageParseException`（字符数 < 10） | EASY 页 PDFBox 抽取内容过少 | **重路由**：升级为 VLM 复判 |
| **可降级** | `VlmException`（超时/格式错） | VLM 服务不可用或返回异常 | **回退**：Tesseract 本地 OCR |
| **可降级** | `OcrException` | Tesseract 引擎故障 | 记录错误，该页返回空内容 |
| **不可降级** | `IllegalArgumentException` | 文件格式不支持、文件过大 | 直接返回 4xx 错误码 |
| **不可降级** | `PdfParseException`（PDF 损坏） | PDF 加载失败 | 直接返回 42201 |
| **系统级** | `AllEnginesFailedException` | VLM + OCR 均不可用 | 返回 50001，触发告警 |

### 9.3 重试与超时策略

| 资源 | 超时 | 最大重试次数 | 退避策略 | 最终兜底 |
|---|---|---|---|---|
| VLM HTTP 调用 | 30s | 2 | 指数退避：1s → 3s | Tesseract OCR |
| Tesseract OCR | 60s/页 | 0 | — | 返回空内容 + 记录 degraded |
| PDFBox 加载 | 10s | 0 | — | 抛出 PdfParseException |
| 全流程 | 5min | 0 | — | 返回 50401 |

### 9.4 结构化日志规范

每条关键日志必须是 JSON 结构，通过 Logback 的 `net.logstash.logback` 编码器输出。

**日志事件枚举**：

| 事件名 | 级别 | 包含字段 |
|---|---|---|
| `doc.loaded` | INFO | `sourceId`, `fileName`, `totalPages`, `fileSize` |
| `page.classified` | INFO | `sourceId`, `pageIndex`, `pageClass`, `coverage`, `imageCount`, `layoutVariance` |
| `page.routed` | INFO | `sourceId`, `pageIndex`, `route`, `pageClass`, `costMs`, `degraded` |
| `page.degraded` | WARN | `sourceId`, `pageIndex`, `fromRoute`, `toRoute`, `reason` |
| `page.merged` | INFO | `sourceId`, `mergedActions`（如 `["paragraph_merge", "table_merge"]`） |
| `doc.completed` | INFO | `sourceId`, `totalPages`, `totalCostMs`, `easyCount`, `hardCount`, `ocrFallbackCount` |

**Logback 配置示例**（`logback-spring.xml`）：

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>sourceId</includeMdcKeyName>
        <includeMdcKeyName>pageIndex</includeMdcKeyName>
    </encoder>
</appender>
```

---

## 10. 测试方法

### 10.1 测试分层

| 层级 | 工具 | 覆盖目标 | 示例用例 |
|---|---|---|---|
| 单元测试 | JUnit 5 + Mockito 5 | 各类独立逻辑 | `PageClassifierTest`、`PostProcessorTest`、`MetaExtractorTest` |
| 集成测试 | Spring Boot Test + Testcontainers | 端到端管线 | `PdfParseServiceTest`（使用真实 PDF 文件） |
| 契约测试 | WireMock | VLM 接口模拟 | `VlmClientContractTest`（模拟超时/格式错/成功） |
| 性能基准 | JMH | 单页吞吐 | `ParsingBenchmark`（单页 PDFBox/VLM 耗时） |

### 10.2 必备用例

#### 10.2.1 PageClassifier 单元测试

```java
@Test
@DisplayName("文本覆盖率 65% 且无表格锚点 → EASY")
void highCoverageNoAnchor() {
    PageMeta meta = new PageMeta(0, 0.65, 0, 0.15, false, 5000);
    assertEquals(PageClass.EASY, classifier.classify(meta));
}

@Test
@DisplayName("文本覆盖率 15% → HARD")
void lowCoverage() {
    PageMeta meta = new PageMeta(0, 0.15, 0, 0.10, false, 200);
    assertEquals(PageClass.HARD, classifier.classify(meta));
}

@Test
@DisplayName("有嵌入图像 → HARD")
void hasEmbeddedImages() {
    PageMeta meta = new PageMeta(0, 0.80, 3, 0.10, false, 8000);
    assertEquals(PageClass.HARD, classifier.classify(meta));
}

@Test
@DisplayName("有表格锚点 → HARD（即使覆盖率达标）")
void hasTableAnchor() {
    PageMeta meta = new PageMeta(0, 0.80, 0, 0.10, true, 8000);
    assertEquals(PageClass.HARD, classifier.classify(meta));
}

@Test
@DisplayName("灰色区域（覆盖率 30%） → HARD（兜底保守）")
void grayAreaFallsBackToHard() {
    PageMeta meta = new PageMeta(0, 0.30, 0, 0.40, false, 3000);
    assertEquals(PageClass.HARD, classifier.classify(meta));
}
```

#### 10.2.2 集成测试用例

| 编号 | 用例描述 | 测试数据 | 期望结果 |
|---|---|---|---|
| T-1 | 纯文本 PDF | 10 页纯文本（无图表） | 全部 EASY，VLM 调用 0 次，costMs < 2s |
| T-2 | 扫描件 PDF | 5 页扫描件（无文本层） | 全部 HARD，走 VLM 或 OCR |
| T-3 | 混合 PDF | 20 页（17 页文本 + 3 页图表） | EASY=17，HARD=3 |
| T-4 | 跨页表格 | 5 页含"续表"锚点 | 跨表合并成功，列对齐 |
| T-5 | VLM 超时 | WireMock 模拟 30s 超时 | 自动降级到 Tesseract，`degraded=true` |
| T-6 | EASY 页字符异常 | PDFBox 抽取 < 10 字符 | 重路由到 VLM，`rerouteCount=1` |
| T-7 | 加密 PDF | 加密的 PDF 文件 | 抛出 PdfParseException，HTTP 422 |
| T-8 | 非 PDF 文件 | 上传 PNG 图片 | HTTP 400，code=40001 |
| T-9 | 超大文件 | 上传 60MB PDF | HTTP 400，code=40002 |
| T-10 | 并发解析 | 同时 3 个请求 | 各自独立，线程池不阻塞 |

### 10.3 性能基准

| 基准 | 指标 | 条件 |
|---|---|---|
| 20 页混合 PDF | < 10s | VLM 并发 = 2，17 EASY + 3 HARD |
| 单页 PDFBox 抽取 | < 200ms | 纯文本，~5000 字符 |
| 单页 VLM 调用 | < 5s | 网络延迟 < 50ms |
| 单页 Tesseract OCR | < 10s | 300 DPI 扫描件 |

---

## 11. 验收标准

### 11.1 功能验收清单

- [ ] 所有 [10.2.2](#1022-集成测试用例) 用例（T-1 ~ T-10）通过
- [ ] `mvn test` 全绿，无 skipped 用例
- [ ] `mvn -pl pdf-parser-api spring-boot:run` 可启动并接受 `curl` 请求
- [ ] 结构化日志包含 `sourceId`、`pageIndex`、`route`、`costMs`、`degraded` 字段
- [ ] 修改 `application.yml` 阈值后无需重启即刻生效

### 11.2 非功能指标

| 指标 | 目标值 | 度量方式 |
|---|---|---|
| 单元测试覆盖率 | ≥ 80% | JaCoCo Report |
| 分支覆盖率 | ≥ 70% | JaCoCo Report |
| VLM 调用占比 | ≤ 30% | 结构化日志聚合（`route=vlm` 页数 / 总页数） |
| 解析准确率 | ≥ 95% | 人工标注 100 页 + 字符级 BLEU |
| 平均解析耗时 | < 500ms/页 | 性能基准测试 |
| 降级覆盖率 | 100% | 所有 VLM 故障场景均触发 OCR 或重路由 |
| 结构化日志覆盖率 | 100% | 日志审计（所有关键步骤有对应事件） |

### 11.3 交付清单

| 编号 | 交付物 | 路径 / 说明 |
|---|---|---|
| D-1 | 源代码 | `pdf-parser-core` / `pdf-parser-service` / `pdf-parser-api` |
| D-2 | 配置文件 | `application.yml`（含完整默认值） |
| D-3 | 单元测试 | `PageClassifierTest` / `PostProcessorTest` / `VlmClientContractTest` 等 |
| D-4 | 集成测试 | `PdfParseServiceTest`（含 20 页样张 PDF） |
| D-5 | API 文档 | 本文档第 7 章 + OpenAPI 3.0 YAML |
| D-6 | 部署说明 | `README.md`（启动命令、环境变量、Dockerfile） |
| D-7 | 测试样张 | `samples/`（纯文本 PDF / 扫描件 PDF / 混合 PDF / 跨页表格 PDF） |
| D-8 | 性能报告 | `benchmark/`（JMH 基准测试结果） |

---

## 12. 附录

### 12.1 术语表

| 术语 | 全称 | 说明 |
|---|---|---|
| VLM | Vision-Language Model | 视觉语言模型，能同时理解图像和文本，如 Qwen-VL、GPT-4V |
| RAG | Retrieval-Augmented Generation | 检索增强生成，Agent 从知识库检索相关内容后生成回答 |
| OCR | Optical Character Recognition | 光学字符识别，将图像中的文字转为可编辑文本 |
| ReAct | Reason + Act | 智能体推理-行动循环模式 |
| PDFBox | Apache PDFBox | Apache 开源 PDF 处理库，Apache 2.0 协议 |
| Tess4J | Tesseract for Java | Google Tesseract OCR 引擎的 Java JNA 封装 |
| MCP | Model Context Protocol | 模型上下文协议，Agent 与工具之间的标准化通信协议 |

### 12.2 PDFBox vs iText 选型说明

| 维度 | PDFBox 3.x | iText 7 社区版 |
|---|---|---|
| 协议 | Apache 2.0（完全免费商用） | AGPL（商用需购买授权） |
| 文本提取 | 成熟，支持坐标级输出 | 成熟 |
| 图像提取 | 原生支持 | 支持 |
| 表格提取 | 需要自行实现 | 内置 `pdfHTML` 模块 |
| 社区活跃度 | 高（Apache 基金会） | 高 |
| **推荐** | **生产环境首选** | 不推荐（AGPL 限制） |

### 12.3 PDFBox 3.x 迁移要点

| 旧 API（2.x） | 新 API（3.x） | 说明 |
|---|---|---|
| `PDDocument.load(File)` | `Loader.loadPDF(File)` | 静态方法已移除 |
| `PDDocument.load(InputStream)` | `Loader.loadPDF(InputStream)` | 同上 |
| `PDFTextStripper` 默认按流顺序 | 需 `setSortByPosition(true)` | 按坐标排序需显式设置 |
| `PDPageContentStream` 构造函数 | 使用 `new PDPageContentStream(doc, page, mode)` | 参数顺序有调整 |

### 12.4 调优指南

#### 分类器阈值调优

| 场景 | 调整建议 | 原因 |
|---|---|---|
| 表格密集型 PDF | `easy.text-coverage-threshold` 下调至 0.50 | 表格区域文本覆盖率天然较低 |
| 学术论文（公式多） | `hard.text-coverage-threshold` 上调至 0.30 | 公式导致文本覆盖率降低但非扫描件 |
| 扫描件为主 | `hard.text-coverage-threshold` 保持 0.20 | 扫描件文本覆盖率极低 |
| 需要极致降本 | `easy.text-coverage-threshold` 下调至 0.45 | 更多页面走 PDFBox 低成本路线 |

#### 线程池调优

| GPU 显存 | `hardPool.maxSize` | 理由 |
|---|---|---|
| 16GB | 2 | 默认值，每个推理约 6-8GB |
| 24GB | 3 | 可承载 3 并发 |
| 32GB | 4 | 可承载 4 并发 |
| 48GB+ | 6 | 高并发场景 |

#### 跨表缝合精度调优

- 如果"续表"锚点检测遗漏，调整 `postprocess.table-continue-pattern` 正则
- 如果列对齐失败，增加 `table-continue-lookback-lines` 到 5

### 12.5 变更记录

| 版本 | 日期 | 变更内容 |
|---|---|---|
| v1.0 | 2026-06-26 | 初版基线（需求描述） |
| v2.0 | 2026-06-29 | 重构为正式开发文档，新增：完整 `application.yml` 配置、`ParserProperties` 配置绑定、所有核心类完整代码实现、异常类层次结构、MCP 集成示例、测试用例代码、分类器调优指南、线程池调优指南、部署脚本 |