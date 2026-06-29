package com.demo.agentscope.ui;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队进度跟踪器。
 * <p>
 * 专门用于跟踪多智能体团队的执行进度，包括：
 * - 领导者规划过程
 * - 工作者创建和任务分配
 * - 智能体间通信
 * - 任务依赖关系
 * - 时间线视图
 * </p>
 */
public class TeamProgressTracker {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 团队ID */
    private final String teamId;

    /** 领导者名称 */
    private final String leaderName;

    /** 工作者状态映射 */
    private final Map<String, WorkerStatus> workers;

    /** 任务时间线 */
    private final List<TimelineEntry> timeline;

    /** 任务依赖关系 */
    private final Map<String, Set<String>> dependencies;

    /** 详细程度 */
    private final VerbosityLevel verbosity;

    public TeamProgressTracker(String teamId, String leaderName, VerbosityLevel verbosity) {
        this.teamId = teamId;
        this.leaderName = leaderName;
        this.workers = new ConcurrentHashMap<>();
        this.timeline = Collections.synchronizedList(new ArrayList<>());
        this.dependencies = new ConcurrentHashMap<>();
        this.verbosity = verbosity;
    }

    /**
     * 记录领导者规划开始。
     */
    public void onLeaderPlanningStart(String goal) {
        if (verbosity == VerbosityLevel.MINIMAL) return;

        TimelineEntry entry = new TimelineEntry(
                LocalDateTime.now(),
                leaderName,
                "PLANNING",
                "开始规划: " + truncate(goal, 60)
        );
        timeline.add(entry);

        System.out.println();
        System.out.println("\u001B[1m\u001B[35m  👑 领导者规划\u001B[0m");
        System.out.println("\u001B[35m  ┌──────────────────────────────────────────────────\u001B[0m");
        System.out.println("\u001B[35m  │ 🎯 目标: \u001B[0m" + goal);
    }

    /**
     * 记录领导者决策（创建工作者）。
     */
    public void onLeaderCreateWorker(String workerName, String role) {
        TimelineEntry entry = new TimelineEntry(
                LocalDateTime.now(),
                leaderName,
                "CREATE_WORKER",
                "创建工作者: " + workerName + " (" + role + ")"
        );
        timeline.add(entry);

        WorkerStatus status = new WorkerStatus(workerName, role);
        workers.put(workerName, status);

        if (verbosity.ordinal() >= VerbosityLevel.STANDARD.ordinal()) {
            System.out.println("\u001B[35m  │ 🤖 创建: \u001B[0m" + workerName + " - " + role);
        }
    }

    /**
     * 记录领导者分配任务。
     */
    public void onLeaderAssignTask(String workerName, String task) {
        WorkerStatus status = workers.get(workerName);
        if (status != null) {
            status.currentTask = task;
            status.status = TaskStatus.IN_PROGRESS;
            status.taskStartTime = LocalDateTime.now();
        }

        TimelineEntry entry = new TimelineEntry(
                LocalDateTime.now(),
                leaderName,
                "ASSIGN_TASK",
                "分配任务给 " + workerName + ": " + truncate(task, 50)
        );
        timeline.add(entry);

        if (verbosity.ordinal() >= VerbosityLevel.STANDARD.ordinal()) {
            System.out.println("\u001B[35m  │ 📨 分配: \u001B[0m" + workerName + " ← " + truncate(task, 40));
        }
    }

    /**
     * 记录规划完成。
     */
    public void onLeaderPlanningComplete(int workerCount) {
        TimelineEntry entry = new TimelineEntry(
                LocalDateTime.now(),
                leaderName,
                "PLANNING_COMPLETE",
                "规划完成，创建 " + workerCount + " 个工作者"
        );
        timeline.add(entry);

        if (verbosity.ordinal() >= VerbosityLevel.STANDARD.ordinal()) {
            System.out.println("\u001B[35m  │ ✅ 规划完成: \u001B[0m" + workerCount + " 个工作者");
            System.out.println("\u001B[35m  └──────────────────────────────────────────────────\u001B[0m");
            System.out.println();
        }
    }

    /**
     * 记录工作者开始执行任务。
     */
    public void onWorkerStart(String workerName, String task) {
        WorkerStatus status = workers.get(workerName);
        if (status != null) {
            status.status = TaskStatus.IN_PROGRESS;
            status.currentTask = task;
        }

        TimelineEntry entry = new TimelineEntry(
                LocalDateTime.now(),
                workerName,
                "TASK_START",
                "开始执行: " + truncate(task, 50)
        );
        timeline.add(entry);

        if (verbosity == VerbosityLevel.VERBOSE || verbosity == VerbosityLevel.DEBUG) {
            System.out.println("\u001B[36m  🔧 [" + workerName + "] \u001B[0m开始执行任务");
        }
    }

    /**
     * 记录工作者工具调用。
     */
    public void onWorkerToolCall(String workerName, String toolName, int iteration) {
        WorkerStatus status = workers.get(workerName);
        if (status != null) {
            status.toolCallCount++;
            status.currentIteration = iteration;
        }

        if (verbosity == VerbosityLevel.VERBOSE || verbosity == VerbosityLevel.DEBUG) {
            System.out.println("\u001B[36m  🔧 [" + workerName + "] \u001B[0m调用工具: " + toolName + " (迭代 " + iteration + ")");
        }
    }

    /**
     * 记录工作者任务完成。
     */
    public void onWorkerComplete(String workerName, boolean success, long durationMs) {
        WorkerStatus status = workers.get(workerName);
        if (status != null) {
            status.status = success ? TaskStatus.COMPLETED : TaskStatus.FAILED;
            status.taskEndTime = LocalDateTime.now();
            status.durationMs = durationMs;
        }

        TimelineEntry entry = new TimelineEntry(
                LocalDateTime.now(),
                workerName,
                success ? "TASK_COMPLETE" : "TASK_FAILED",
                (success ? "✅ 完成" : "❌ 失败") + " (" + durationMs + "ms)"
        );
        timeline.add(entry);

        if (verbosity.ordinal() >= VerbosityLevel.STANDARD.ordinal()) {
            String icon = success ? "\u001B[32m✅\u001B[0m" : "\u001B[31m❌\u001B[0m";
            System.out.println("  " + icon + " [" + workerName + "] 任务" + (success ? "完成" : "失败") + " (" + durationMs + "ms)");
        }
    }

    /**
     * 记录智能体间通信。
     */
    public void onAgentCommunication(String from, String to, String message) {
        TimelineEntry entry = new TimelineEntry(
                LocalDateTime.now(),
                from,
                "COMMUNICATION",
                "→ " + to + ": " + truncate(message, 40)
        );
        timeline.add(entry);

        if (verbosity == VerbosityLevel.DEBUG) {
            System.out.println("\u001B[33m  📨 " + from + " → " + to + ": \u001B[0m" + truncate(message, 60));
        }
    }

    /**
     * 记录任务依赖关系。
     */
    public void addDependency(String task, String dependsOn) {
        dependencies.computeIfAbsent(task, k -> new HashSet<>()).add(dependsOn);
    }

    /**
     * 打印团队进度总览。
     */
    public void printTeamOverview() {
        System.out.println();
        System.out.println("\u001B[1m  👥 团队进度总览\u001B[0m");
        System.out.println("\u001B[2m  ──────────────────────────────────────────────────\u001B[0m");

        // 领导者状态
        System.out.println("  \u001B[35m👑 " + leaderName + "\u001B[0m (领导者)");

        // 工作者状态
        if (!workers.isEmpty()) {
            System.out.println();
            System.out.println("  \u001B[1m工作者 (" + workers.size() + "):\u001B[0m");
            for (WorkerStatus status : workers.values()) {
                String statusIcon = switch (status.status) {
                    case PENDING -> "\u001B[33m⏳\u001B[0m";
                    case IN_PROGRESS -> "\u001B[36m🔄\u001B[0m";
                    case COMPLETED -> "\u001B[32m✅\u001B[0m";
                    case FAILED -> "\u001B[31m❌\u001B[0m";
                };
                System.out.println("    " + statusIcon + " " + status.name + " (" + status.role + ")");
                if (status.currentTask != null) {
                    System.out.println("       任务: " + truncate(status.currentTask, 50));
                }
                if (status.durationMs > 0) {
                    System.out.println("       耗时: " + status.durationMs + "ms");
                }
            }
        }

        // 时间线（仅 DEBUG 模式）
        if (verbosity == VerbosityLevel.DEBUG && !timeline.isEmpty()) {
            System.out.println();
            System.out.println("  \u001B[1m时间线:\u001B[0m");
            int showCount = Math.min(10, timeline.size());
            for (int i = timeline.size() - showCount; i < timeline.size(); i++) {
                TimelineEntry entry = timeline.get(i);
                System.out.println("    [" + entry.time.format(TIME_FMT) + "] " +
                        entry.agent + " - " + entry.event);
            }
        }

        System.out.println("\u001B[2m  ──────────────────────────────────────────────────\u001B[0m");
        System.out.println();
    }

    /**
     * 打印任务依赖图。
     */
    public void printDependencyGraph() {
        if (dependencies.isEmpty()) {
            System.out.println("\u001B[33m  ℹ 无任务依赖关系\u001B[0m");
            return;
        }

        System.out.println();
        System.out.println("\u001B[1m  🔗 任务依赖关系\u001B[0m");
        System.out.println("\u001B[2m  ──────────────────────────────────────────────────\u001B[0m");

        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            String task = entry.getKey();
            Set<String> deps = entry.getValue();
            System.out.println("  " + task + " ← " + String.join(", ", deps));
        }

        System.out.println("\u001B[2m  ──────────────────────────────────────────────────\u001B[0m");
        System.out.println();
    }

    /**
     * 获取时间线快照。
     */
    public List<TimelineEntry> getTimelineSnapshot() {
        return new ArrayList<>(timeline);
    }

    /**
     * 获取工作者状态快照。
     */
    public Map<String, WorkerStatus> getWorkersSnapshot() {
        return new HashMap<>(workers);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 工作者状态。
     */
    public static class WorkerStatus {
        public final String name;
        public final String role;
        public TaskStatus status = TaskStatus.PENDING;
        public String currentTask;
        public LocalDateTime taskStartTime;
        public LocalDateTime taskEndTime;
        public long durationMs;
        public int toolCallCount;
        public int currentIteration;

        public WorkerStatus(String name, String role) {
            this.name = name;
            this.role = role;
        }
    }

    /**
     * 任务状态枚举。
     */
    public enum TaskStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    /**
     * 时间线条目。
     */
    public static class TimelineEntry {
        public final LocalDateTime time;
        public final String agent;
        public final String eventType;
        public final String event;

        public TimelineEntry(LocalDateTime time, String agent, String eventType, String event) {
            this.time = time;
            this.agent = agent;
            this.eventType = eventType;
            this.event = event;
        }
    }
}
