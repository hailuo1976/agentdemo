#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
workspace 一次性清理脚本。

默认 dry-run，加 --apply 才真正移动/删除文件。

清理策略：
  1. 建立目标目录骨架：reports/{stocks,research,interim} / scripts / data / assets / bak
  2. 按映射表把 workspace 根目录散落的文件归位到对应子目录
  3. reports/research_notes/ 与 reports/research_report/ 内容合并到 reports/research/
     再删除两个老目录（空目录才删，非空保留）
  4. 重复版本（如 get_financials*.py 只保留最新版）移入 bak/

用法：
  python3 scripts/workspace_cleanup.py            # dry-run，只打印计划
  python3 scripts/workspace_cleanup.py --apply    # 执行
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
from pathlib import Path

# ==================== 配置：文件名 → 目标子目录 ====================

# 显式映射表：根目录里的具体文件名 → 目标子目录（相对 workspace/）
FILE_MAPPING: dict[str, str] = {
    # —— 股票分析报告 ——
    "新易盛(300502)风险评估报告.md": "reports/stocks",
    "阳光电源(300274)风险评估报告.md": "reports/stocks",
    "澜起科技(688008)风险评估报告.md": "reports/stocks",
    "阳光电源全球竞争力深度分析报告.md": "reports/stocks",
    "sungrow_ai_full_report.md": "reports/stocks",
    "sungrow_ai_report_part1.md": "bak",
    "sungrow_ai_report_part2.md": "bak",
    "sungrow_ai_report_part3.md": "bak",
    "市场动荡综合分析报告_20260717.md": "reports/stocks",

    # —— 禅修报告：主报告归 reports/research，分章/合并版归 bak ——
    "心经禅修研读大报告_完整版.md": "reports/research",
    "心经禅修研读大报告_2026般若新纪元.md": "bak",
    "心经禅修研读大报告_诸宗会通深度扩展篇.md": "bak",
    "心经禅修报告_序章.md": "bak",
    "心经禅修报告_序言与第一章.md": "bak",
    "心经禅修报告_第二章_经文精解上.md": "bak",
    "心经禅修报告_第二章_经文精解下.md": "bak",
    "心经禅修报告_第二章_经文精解续.md": "bak",
    "心经禅修报告_第三章_五蕴与空性.md": "bak",
    "心经禅修报告_第四章_十八界与十二因缘.md": "bak",
    "心经禅修报告_第五章_究竟涅槃.md": "bak",
    "心经禅修报告_第六章_密咒密义.md": "bak",
    "心经禅修报告_第七章_禅修实修指南.md": "bak",
    "心经禅修报告_第八章_诸宗会通.md": "bak",
    "心经禅修报告_第九章_与现代文明对话.md": "bak",
    "心经禅修报告_第十二章_终极会归.md": "bak",
    "心经禅修报告_第二至五章.md": "bak",
    "心经禅修报告_第六至七章.md": "bak",
    "心经禅修报告_第十至十一章及附录.md": "bak",
    "心经全文.txt": "reports/research",

    # —— AI 模型/企业研究报告 ——
    "AI模型分析报告_KimiK3_Fable5_GPT5.6_最终版.md": "reports/research",
    "AI模型分析报告_KimiK3_Fable5_GPT5.6.md": "bak",
    "AI时代企业AI转型全景蓝图_融合版研究报告.md": "reports/research",
    "AI时代企业应用AI的策略路线研究报告.md": "reports/research",
    "AI转型四问_企业学习_产品迭代_业务智能化_人才跃迁.md": "reports/research",
    "Anthropic_全局工作空间_专业分析报告.md": "reports/research",
    "CodeAgent_Harness_Report.md": "reports/research",
    "Qwen3_27B_vs_35B_Analysis_Report.md": "reports/research",
    "Qwen3_27B_vs_35B_Report.md": "bak",
    "agent_boundary_map.md": "reports/research",
    "agent_improvement.md": "reports/research",
    "agent_improvement_prompt.md": "reports/research",
    "codeagent-enhancement-design.md": "reports/research",
    "生物大脑神经元与AI大模型参数跨域研究报告.md": "reports/research",
    "大模型数学基础笔记.md": "reports/research",
    "零基础炒股完整学习笔记.md": "reports/research",

    # —— 招行系列 ——
    "招商银行AI人才队伍建设策略研究报告.md": "reports/research",
    "招商银行SFT_RLHF技术体系深度剖析报告.md": "reports/research",
    "招商银行人才队伍建设与经验启示.md": "reports/research",
    "招商银行人才队伍建设策略研究报告.md": "bak",

    # —— 脚本：只保留最新版，其他进 bak ——
    "get_full_financials.py": "scripts",
    "get_financials.py": "bak",
    "get_financials2.py": "bak",
    "get_financials3.py": "bak",
    "get_clean_summary.py": "scripts",
    "find_profit.py": "scripts",
    "report_time_checker.py": "scripts",
    "weather_tool.py": "scripts",
    "stock_data_script.py": "bak",
    "stock_data_script2.py": "scripts",  # 保留较新版本

    # —— 数据 ——
    "火锅店训练数据.json": "data",
    "火锅店训练数据_批量.json": "data",
    "benchmark_section.txt": "data",
    "kimi_k2_readme.txt": "data",

    # —— 图片 ——
    "efficiency_comparison.png": "assets",
    "neuron_model_mapping.png": "assets",
    "neuron_vs_model_overview.png": "assets",
    "timeline_comparison.png": "assets",
}

# 两个老目录的内容合并到 reports/research/ 后删除老目录
LEGACY_DIRS: dict[str, str] = {
    "research_report": "reports/research",
    "research_notes": "reports/research",
}

# 需要在 workspace/ 下预创建的子目录
SKELETON_DIRS: list[str] = [
    "reports",
    "reports/stocks",
    "reports/research",
    "reports/interim",
    "scripts",
    "data",
    "assets",
    "bak",
    "tmp",
]


def color(s: str, c: str) -> str:
    """简易 ANSI 颜色，非 tty 自动关闭。"""
    if not sys.stdout.isatty():
        return s
    codes = {"red": "31", "green": "32", "yellow": "33", "blue": "34", "gray": "90"}
    return f"\033[{codes.get(c, '0')}m{s}\033[0m"


def plan(workspace: Path) -> list[tuple[str, str, str, str]]:
    """
    返回 [(action, src, dst, note), ...]
    action ∈ {"move", "skip-missing", "skip-dir", "merge", "rmdir"}
    """
    actions: list[tuple[str, str, str, str]] = []

    # 1) 根目录散落文件按映射表归位
    for fname, target_sub in FILE_MAPPING.items():
        src = workspace / fname
        if not src.exists():
            actions.append(("skip-missing", str(src), "", "映射表里声明但实际不存在"))
            continue
        if src.is_dir():
            actions.append(("skip-dir", str(src), "", "是目录不是文件，跳过"))
            continue
        dst = workspace / target_sub / fname
        actions.append(("move", str(src), str(dst), f"→ {target_sub}/"))

    # 2) legacy 目录合并
    for legacy, target_sub in LEGACY_DIRS.items():
        legacy_dir = workspace / legacy
        if not legacy_dir.is_dir():
            actions.append(("skip-missing", str(legacy_dir), "", "legacy 目录不存在"))
            continue
        for child in sorted(legacy_dir.iterdir()):
            dst = workspace / target_sub / child.name
            if dst.exists():
                # 同名冲突：改名加 .from_<legacy>
                dst = workspace / target_sub / f"{child.name}.from_{legacy}"
            actions.append(("merge", str(child), str(dst), f"from {legacy}/"))

    # 3) legacy 目录删除（先不真删，执行阶段确认空了再删）
    for legacy in LEGACY_DIRS:
        legacy_dir = workspace / legacy
        if legacy_dir.is_dir():
            actions.append(("rmdir", str(legacy_dir), "", "合并完成后删除空目录"))

    return actions


def apply_plan(workspace: Path, actions: list[tuple[str, str, str, str]]) -> tuple[int, int, int]:
    """返回 (moved, merged, removed_dirs)。"""
    # 建骨架
    for sub in SKELETON_DIRS:
        (workspace / sub).mkdir(parents=True, exist_ok=True)

    moved = merged = 0
    legacy_cleared: set[str] = set()

    for action, src, dst, note in actions:
        if action == "move":
            s, d = Path(src), Path(dst)
            d.parent.mkdir(parents=True, exist_ok=True)
            if d.exists():
                # 根目录 → bak/ 这类场景，目标已存在则覆盖（因为源在根目录，意图就是清理）
                # 但为了安全，不覆盖：改名加 .dup
                d = d.with_name(f"{d.stem}.dup{d.suffix}" if d.suffix else f"{d.name}.dup")
            shutil.move(str(s), str(d))
            moved += 1
        elif action == "merge":
            s, d = Path(src), Path(dst)
            d.parent.mkdir(parents=True, exist_ok=True)
            if d.exists():
                d = d.with_name(f"{d.stem}.dup{d.suffix}" if d.suffix else f"{d.name}.dup")
            shutil.move(str(s), str(d))
            merged += 1

    removed_dirs = 0
    for action, src, dst, note in actions:
        if action == "rmdir":
            d = Path(src)
            if d.is_dir() and not any(d.iterdir()):
                d.rmdir()
                removed_dirs += 1
            elif d.is_dir():
                print(color(f"  ! 未删除（非空）: {d}", "yellow"))

    return moved, merged, removed_dirs


def main() -> int:
    parser = argparse.ArgumentParser(description="workspace 一次性清理脚本")
    parser.add_argument(
        "--workspace",
        default=os.environ.get("WORKSPACE_DIR", "workspace"),
        help="workspace 根目录（默认: $WORKSPACE_DIR 或 ./workspace）",
    )
    parser.add_argument("--apply", action="store_true", help="真正执行（默认 dry-run）")
    args = parser.parse_args()

    workspace = Path(args.workspace).resolve()
    if not workspace.is_dir():
        print(color(f"workspace 目录不存在: {workspace}", "red"))
        return 2

    print(color(f"目标 workspace: {workspace}", "blue"))
    print(color(f"模式: {'APPLY' if args.apply else 'DRY-RUN (--apply 来执行)'}", "yellow"))
    print()

    actions = plan(workspace)

    # 打印计划
    by_action: dict[str, int] = {}
    for action, src, dst, note in actions:
        by_action[action] = by_action.get(action, 0) + 1
        if action == "move":
            print(f"  [move ] {Path(src).name}  →  {note}")
        elif action == "merge":
            print(f"  [merge] {Path(src).relative_to(workspace) if workspace in Path(src).parents else Path(src).name}  →  {Path(dst).parent.name}/")
        elif action == "rmdir":
            print(f"  [rmdir] {src}")
        elif action.startswith("skip"):
            print(color(f"  [skip ] {src}  ({note})", "gray"))

    print()
    print(color("汇总:", "blue"))
    for a, n in sorted(by_action.items()):
        print(f"  {a}: {n}")

    if not args.apply:
        print()
        print(color("（dry-run 未执行任何变更）", "yellow"))
        return 0

    # 执行
    moved, merged, removed = apply_plan(workspace, actions)
    print()
    print(color(f"完成: moved={moved}, merged={merged}, removed_dirs={removed}", "green"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
