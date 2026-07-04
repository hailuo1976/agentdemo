package com.demo.agentscope.stock.industry;

import com.demo.agentscope.execution.CodeExecutionManager;
import com.demo.agentscope.stock.model.IndustryNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 行业服务。
 * 负责申万行业分类树的加载、解析、查询。支持三级结构（一级31个、二级134个、三级386个），
 * 提供行业代码↔名称映射、父子关系查询、模糊匹配等能力。
 */
public class IndustryService {

    private static final Logger log = LoggerFactory.getLogger(IndustryService.class);

    /** 行业树根节点（内存缓存） */
    private List<IndustryNode> industryTree;

    /** 行业代码→名称映射（快速查找） */
    private Map<String, String> codeToNameMap;

    /** 行业名称→代码映射（支持模糊匹配） */
    private Map<String, String> nameToCodeMap;

    /** 代码执行管理器 */
    private final CodeExecutionManager executionManager;

    /** 缓存目录 */
    private final Path cacheDir;

    /** Jackson ObjectMapper */
    private final ObjectMapper objectMapper;

    public IndustryService(CodeExecutionManager executionManager, Path cacheDir) {
        this.executionManager = executionManager;
        this.cacheDir = cacheDir;
        this.objectMapper = new ObjectMapper();
        this.codeToNameMap = new HashMap<>();
        this.nameToCodeMap = new HashMap<>();
        this.industryTree = new ArrayList<>();
    }

    /**
     * 初始化：从 workspace/cache/industry_tree.json 加载行业树。
     * 首次启动时从数据源拉取并缓存。
     * 如果初始化失败，降级为空行业树，应用仍可启动。
     */
    public void initialize() {
        Path treeFile = cacheDir.resolve("industry_tree.json");

        if (Files.exists(treeFile)) {
            try {
                loadFromCache(treeFile);
                log.info("行业树从缓存加载，共 {} 个一级行业", industryTree.size());
                return;
            } catch (Exception e) {
                log.warn("从缓存加载行业树失败: {}，将尝试重新拉取", e.getMessage());
            }
        }

        // 尝试从数据源拉取
        try {
            fetchAndCacheIndustryTree();
        } catch (Exception e) {
            log.error("行业树初始化失败，将使用内置行业数据: {}", e.getMessage());
            log.error("提示: 可通过 pip install akshare 或 pip install tushare 安装数据源后重试");
            // 降级为内置行业树，应用仍可启动
            loadBuiltinIndustryTree();
        }
    }

    /**
     * 加载内置的申万一级行业树（兜底数据）。
     * 当网络不可用且无缓存时使用。
     */
    private void loadBuiltinIndustryTree() {
        industryTree = new ArrayList<>();
        codeToNameMap = new HashMap<>();
        nameToCodeMap = new HashMap<>();

        // 申万一级行业（31个）
        String[][] builtinIndustries = {
            {"801010", "农林牧渔"}, {"801020", "采掘"}, {"801030", "化工"},
            {"801040", "钢铁"}, {"801050", "有色金属"}, {"801080", "电子"},
            {"801110", "家用电器"}, {"801120", "食品饮料"}, {"801130", "纺织服饰"},
            {"801140", "轻工制造"}, {"801150", "医药生物"}, {"801160", "公用事业"},
            {"801170", "交通运输"}, {"801180", "房地产"}, {"801200", "商贸零售"},
            {"801210", "社会服务"}, {"801230", "综合"}, {"801710", "建筑材料"},
            {"801720", "建筑装饰"}, {"801730", "电力设备"}, {"801740", "国防军工"},
            {"801750", "计算机"}, {"801760", "传媒"}, {"801770", "通信"},
            {"801780", "银行"}, {"801790", "非银金融"}, {"801880", "汽车"},
            {"801890", "机械设备"}, {"801950", "煤炭"}, {"801960", "石油石化"},
            {"801970", "环保"}
        };

        for (String[] ind : builtinIndustries) {
            IndustryNode node = new IndustryNode();
            node.setCode(ind[0]);
            node.setName(ind[1]);
            node.setLevel(1);
            node.setChildren(new ArrayList<>());
            industryTree.add(node);

            codeToNameMap.put(ind[0], ind[1]);
            nameToCodeMap.put(ind[1], ind[0]);
        }

        log.info("已加载内置行业树，共 {} 个一级行业", industryTree.size());
    }

    /**
     * 从数据源拉取行业树并缓存。
     * 支持 akshare 和 tushare 两种数据源。
     */
    public void fetchAndCacheIndustryTree() {
        // 优先使用 akshare
        try {
            fetchFromAkShare();
            log.info("行业树从 akshare 拉取成功");
            return;
        } catch (Exception e) {
            log.warn("akshare 拉取失败: {}，尝试 tushare", e.getMessage());
        }

        // 降级到 tushare
        try {
            fetchFromTuShare();
            log.info("行业树从 tushare 拉取成功");
        } catch (Exception e) {
            log.error("tushare 拉取也失败", e);
            throw new RuntimeException("行业树初始化失败：akshare 和 tushare 均不可用");
        }
    }

    /**
     * 从 akshare 拉取行业树。
     */
    public void fetchFromAkShare() {
        String pythonCode = """
            import akshare as ak
            import json
            
            # 一级行业
            df_l1 = ak.stock_board_industry_name_em()
            l1_list = []
            for _, row in df_l1.iterrows():
                l1_list.append({
                    "code": str(row['板块代码']),
                    "name": row['板块名称'],
                    "level": 1,
                    "children": []
                })
            
            # 二级行业
            df_l2 = ak.stock_board_industry_name_em(symbol="二级")
            for _, row in df_l2.iterrows():
                l2_node = {
                    "code": str(row['板块代码']),
                    "name": row['板块名称'],
                    "level": 2,
                    "children": [],
                    "parent": row['上级行业']
                }
                # 找到对应的一级行业，添加为子节点
                for l1 in l1_list:
                    if l1['name'] == l2_node['parent']:
                        l1['children'].append(l2_node)
                        break
            
            print(json.dumps(l1_list, ensure_ascii=False))
            """;

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("akshare 调用失败: " + result.getStderr());
        }

        parseAndSave(result.getStdout());
    }

    /**
     * 从 tushare 拉取行业树。
     */
    public void fetchFromTuShare() {
        String pythonCode = """
            import tushare as ts
            import json
            import os
            
            # 从环境变量获取 tushare token
            token = os.getenv('TUSHARE_TOKEN')
            if not token:
                raise Exception("TUSHARE_TOKEN 环境变量未设置")
            
            pro = ts.pro_api(token)
            
            # 申万一级行业
            df_l1 = pro.index_classify(level='L1', src='SW2021')
            l1_list = []
            for _, row in df_l1.iterrows():
                l1_list.append({
                    "code": row['index_code'],
                    "name": row['industry_name'],
                    "level": 1,
                    "children": []
                })
            
            # 申万二级行业
            df_l2 = pro.index_classify(level='L2', src='SW2021')
            for _, row in df_l2.iterrows():
                l2_node = {
                    "code": row['index_code'],
                    "name": row['industry_name'],
                    "level": 2,
                    "children": [],
                    "parent_code": row['parent_code']
                }
                # 找到对应的一级行业
                for l1 in l1_list:
                    if l1['code'] == l2_node['parent_code']:
                        l1['children'].append(l2_node)
                        break
            
            print(json.dumps(l1_list, ensure_ascii=False))
            """;

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("tushare 调用失败: " + result.getStderr());
        }

        parseAndSave(result.getStdout());
    }

    /**
     * 解析行业树 JSON 并保存到缓存。
     */
    private void parseAndSave(String json) {
        try {
            industryTree = objectMapper.readValue(json,
                new TypeReference<List<IndustryNode>>() {});

            // 构建索引
            buildIndex();

            // 保存到缓存文件
            Path treeFile = cacheDir.resolve("industry_tree.json");
            Files.createDirectories(treeFile.getParent());
            Files.writeString(treeFile, json);

            log.info("行业树已缓存到: {}", treeFile);
        } catch (IOException e) {
            throw new RuntimeException("行业树解析失败", e);
        }
    }

    /**
     * 从缓存文件加载行业树。
     */
    private void loadFromCache(Path treeFile) throws IOException {
        String json = Files.readString(treeFile);
        industryTree = objectMapper.readValue(json,
            new TypeReference<List<IndustryNode>>() {});
        buildIndex();
    }

    /**
     * 构建内存索引。
     */
    private void buildIndex() {
        codeToNameMap = new HashMap<>();
        nameToCodeMap = new HashMap<>();

        for (IndustryNode l1 : industryTree) {
            codeToNameMap.put(l1.getCode(), l1.getName());
            nameToCodeMap.put(l1.getName(), l1.getCode());

            if (l1.getChildren() == null) continue;
            for (IndustryNode l2 : l1.getChildren()) {
                codeToNameMap.put(l2.getCode(), l2.getName());
                nameToCodeMap.put(l2.getName(), l2.getCode());

                if (l2.getChildren() == null) continue;
                for (IndustryNode l3 : l2.getChildren()) {
                    codeToNameMap.put(l3.getCode(), l3.getName());
                    nameToCodeMap.put(l3.getName(), l3.getCode());
                }
            }
        }
    }

    /**
     * 列出指定层级的行业。
     */
    public List<IndustryNode> listIndustries(String level, String parent) {
        if (industryTree == null || industryTree.isEmpty()) {
            return Collections.emptyList();
        }

        if ("l1".equals(level) || "all".equals(level)) {
            return industryTree;
        }

        if (parent == null || parent.isBlank()) {
            return Collections.emptyList();
        }

        // 找到父行业
        IndustryNode parentNode = findNodeByName(parent);
        if (parentNode == null) {
            return Collections.emptyList();
        }

        if ("l2".equals(level)) {
            return parentNode.getChildren() != null ? parentNode.getChildren() : Collections.emptyList();
        } else if ("l3".equals(level)) {
            List<IndustryNode> l3Nodes = new ArrayList<>();
            if (parentNode.getChildren() != null) {
                for (IndustryNode l2 : parentNode.getChildren()) {
                    if (l2.getChildren() != null) {
                        l3Nodes.addAll(l2.getChildren());
                    }
                }
            }
            return l3Nodes;
        }

        return Collections.emptyList();
    }

    /**
     * 根据行业名称（支持模糊匹配）查找行业节点。
     */
    public IndustryNode findNodeByName(String name) {
        if (industryTree == null || name == null) return null;

        // 精确匹配
        for (IndustryNode l1 : industryTree) {
            if (l1.getName().equals(name)) return l1;
            if (l1.getChildren() == null) continue;
            for (IndustryNode l2 : l1.getChildren()) {
                if (l2.getName().equals(name)) return l2;
                if (l2.getChildren() == null) continue;
                for (IndustryNode l3 : l2.getChildren()) {
                    if (l3.getName().equals(name)) return l3;
                }
            }
        }

        // 模糊匹配（包含关键字）
        for (IndustryNode l1 : industryTree) {
            if (l1.getName().contains(name)) return l1;
            if (l1.getChildren() == null) continue;
            for (IndustryNode l2 : l1.getChildren()) {
                if (l2.getName().contains(name)) return l2;
                if (l2.getChildren() == null) continue;
                for (IndustryNode l3 : l2.getChildren()) {
                    if (l3.getName().contains(name)) return l3;
                }
            }
        }

        return null;
    }

    /**
     * 获取指定行业下的全部股票代码。
     */
    public List<String> getStockCodesByIndustry(String industryName, int level) {
        IndustryNode node = findNodeByName(industryName);
        if (node == null) {
            throw new IllegalArgumentException("行业不存在: " + industryName);
        }

        // 如果节点已有股票代码列表，直接返回
        if (node.getStockCodes() != null && !node.getStockCodes().isEmpty()) {
            return node.getStockCodes();
        }

        // 否则从数据源拉取
        return fetchStockCodesByIndustry(node);
    }

    /**
     * 从数据源拉取行业成分股。
     */
    private List<String> fetchStockCodesByIndustry(IndustryNode node) {
        // 优先 akshare
        try {
            return fetchFromAkShare(node);
        } catch (Exception e) {
            log.warn("akshare 拉取行业成分股失败: {}，尝试 tushare", e.getMessage());
        }

        // 降级 tushare
        try {
            return fetchFromTuShare(node);
        } catch (Exception e) {
            log.error("tushare 拉取也失败", e);
            throw new RuntimeException("获取行业成分股失败: " + node.getName());
        }
    }

    private List<String> fetchFromAkShare(IndustryNode node) {
        String pythonCode = """
            import akshare as ak
            import json
            
            df = ak.stock_board_industry_cons_em(symbol="%s")
            codes = df['代码'].tolist()
            print(json.dumps(codes))
            """;
        pythonCode = pythonCode.replace("%s", node.getName());

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("akshare 调用失败");
        }

        try {
            return objectMapper.readValue(result.getStdout(),
                new TypeReference<List<String>>() {});
        } catch (IOException e) {
            throw new RuntimeException("解析股票代码失败", e);
        }
    }

    private List<String> fetchFromTuShare(IndustryNode node) {
        String pythonCode = """
            import tushare as ts
            import json
            import os
            
            token = os.getenv('TUSHARE_TOKEN')
            pro = ts.pro_api(token)
            
            df = pro.index_member(index_code='%s')
            codes = df['member_code'].tolist()
            print(json.dumps(codes))
            """;
        pythonCode = pythonCode.replace("%s", node.getCode());

        CodeExecutionManager.ExecutionResult result = executionManager.executePython(pythonCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("tushare 调用失败");
        }

        try {
            return objectMapper.readValue(result.getStdout(),
                new TypeReference<List<String>>() {});
        } catch (IOException e) {
            throw new RuntimeException("解析股票代码失败", e);
        }
    }

    // Getters
    public List<IndustryNode> getIndustryTree() {
        return industryTree;
    }

    public Map<String, String> getCodeToNameMap() {
        return codeToNameMap;
    }

    public Map<String, String> getNameToCodeMap() {
        return nameToCodeMap;
    }
}
