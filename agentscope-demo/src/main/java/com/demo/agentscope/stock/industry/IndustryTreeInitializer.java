package com.demo.agentscope.stock.industry;

import com.demo.agentscope.execution.CodeExecutionManager;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * 行业树初始化工具（命令行入口）。
 *
 * 使用方式：
 *   java -cp agentscope-demo-1.0.0.jar \
 *     com.demo.agentscope.stock.industry.IndustryTreeInitializer \
 *     [--force] [--source akshare|tushare]
 */
public class IndustryTreeInitializer {

    public static void main(String[] args) {
        boolean force = Arrays.asList(args).contains("--force");
        String source = "akshare"; // 默认

        for (int i = 0; i < args.length; i++) {
            if ("--source".equals(args[i]) && i + 1 < args.length) {
                source = args[i + 1];
            }
        }

        // 初始化代码执行管理器
        Path workspaceDir = Path.of("workspace").toAbsolutePath();
        CodeExecutionManager executionManager = new CodeExecutionManager(workspaceDir);

        // 创建行业服务
        IndustryService industryService = new IndustryService(executionManager,
            workspaceDir.resolve("cache"));

        if (force) {
            // 强制重新拉取
            System.out.println("强制从 " + source + " 重新拉取行业树...");
            if ("akshare".equals(source)) {
                industryService.fetchFromAkShare();
            } else if ("tushare".equals(source)) {
                industryService.fetchFromTuShare();
            } else {
                System.err.println("不支持的数据源: " + source);
                System.exit(1);
            }
        } else {
            // 正常初始化（检查缓存）
            System.out.println("初始化行业树...");
            industryService.initialize();
        }

        System.out.println("行业树初始化完成");
        System.out.println("一级行业数: " + industryService.listIndustries("l1", null).size());
    }
}
