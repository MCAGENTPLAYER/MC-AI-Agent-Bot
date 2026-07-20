package com.aibot.mod.task;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务链 — 让 AI 可以规划并执行多步骤任务序列。
 * <p>
 * 设计目标：
 * 1. 可靠性 — 持久化存储，崩溃后恢复；每步有超时和重试
 * 2. 可追踪 — 进度可查询，每一步都有状态和结果
 * 3. 可扩展 — 支持 CRAFT / MINE / CHOP / TELEPORT / GIVE 等多种步骤类型
 * 4. 可观察 — AI 和玩家随时能查看链的执行状态
 * <p>
 * 持久化：自动保存到 ai_bot/mind/chain.json
 */
public class TaskChain {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ==================== 步骤类型 ====================
    public enum StepType {
        CRAFT,      // 合成物品（参数: item, count）
        MINE,       // 挖掘（参数: mode, count）
        CHOP,       // 砍树（参数: count, target）
        FARM,       // 种地
        COOK,       // 烹饪（参数: recipe, output, items...）
        HUNT,       // 打猎
        GOTO,       // 移动（参数: x, y, z）
        FOLLOW,     // 跟随
        SLEEP,      // 睡觉
        EAT,        // 进食
        INTERACT,   // 交互（参数: target, item）
        TELEPORT,   // 传送到玩家身边（支持跨维度）
        GIVE,       // 给玩家物品（参数: item, count）
        WAIT        // 等待（参数: ticks）
    }

    // ==================== 步骤状态 ====================
    public enum StepStatus {
        PENDING,    // 等待执行
        RUNNING,    // 正在执行
        COMPLETED,  // 已完成
        FAILED,     // 失败（可重试）
        SKIPPED     // 跳过
    }

    // ==================== 链状态 ====================
    public enum ChainStatus {
        PENDING,    // 已创建，等待开始
        RUNNING,    // 执行中
        COMPLETED,  // 全部完成
        FAILED,     // 失败（无法恢复）
        CANCELLED   // 被玩家取消
    }

    // ==================== 单步定义 ====================
    public static class Step {
        public StepType type;
        public Map<String, String> params = new HashMap<>();
        public StepStatus status = StepStatus.PENDING;
        public int retryCount = 0;
        public int maxRetries = 2;
        public int timeoutTicks = 6000;    // 默认5分钟
        public String result = "";          // 执行结果描述
        public long startedAt = 0;          // 开始时间戳
        public long completedAt = 0;        // 完成时间戳

        public Step() {}

        public Step(StepType type, Map<String, String> params) {
            this.type = type;
            this.params = params;
        }

        public Step(StepType type, String... keyValues) {
            this.type = type;
            for (int i = 0; i < keyValues.length - 1; i += 2) {
                this.params.put(keyValues[i], keyValues[i + 1]);
            }
        }

        public String getParam(String key, String defaultValue) {
            return params.getOrDefault(key, defaultValue);
        }

        public int getIntParam(String key, int defaultValue) {
            try {
                return Integer.parseInt(params.getOrDefault(key, String.valueOf(defaultValue)));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        public double getDoubleParam(String key, double defaultValue) {
            try {
                return Double.parseDouble(params.getOrDefault(key, String.valueOf(defaultValue)));
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        /** 获取步骤的显示名称 */
        public String getDisplayName() {
            return switch (type) {
                case CRAFT -> "合成 " + getParam("item", "?") + " x" + getIntParam("count", 1);
                case MINE -> "挖掘 " + getParam("mode", "mine") + " x" + getIntParam("count", 1);
                case CHOP -> "砍树 x" + getIntParam("count", 1);
                case FARM -> "种地";
                case COOK -> "烹饪 " + getParam("recipe", "?");
                case HUNT -> "打猎";
                case GOTO -> "移动到 [" + getParam("x", "?") + ", " + getParam("y", "?") + ", " + getParam("z", "?") + "]";
                case FOLLOW -> "跟随玩家";
                case SLEEP -> "睡觉";
                case EAT -> "进食";
                case INTERACT -> "交互 " + getParam("target", "?");
                case TELEPORT -> "传送到玩家身边";
                case GIVE -> "给予 " + getParam("item", "?") + " x" + getIntParam("count", 1);
                case WAIT -> "等待 " + getIntParam("ticks", 20) + " tick";
            };
        }
    }

    // ==================== 链数据 ====================
    private String chainId;
    private String name;
    private List<Step> steps = new ArrayList<>();
    private int currentStepIndex = 0;
    private ChainStatus status = ChainStatus.PENDING;
    private long createdAt;
    private long completedAt;
    private String failReason = "";

    // 回调标记（避免重复通知 AI）
    private transient boolean notifiedCompletion = false;

    public TaskChain() {
        this.chainId = UUID.randomUUID().toString().substring(0, 8);
        this.createdAt = System.currentTimeMillis();
    }

    public TaskChain(String name, List<Step> steps) {
        this();
        this.name = name;
        this.steps = steps;
    }

    // ==================== 构建器方法 ====================

    public TaskChain setName(String name) { this.name = name; return this; }
    public TaskChain addStep(Step step) { steps.add(step); return this; }

    public static Step craft(String item, int count) {
        return new Step(StepType.CRAFT, "item", item, "count", String.valueOf(count));
    }

    public static Step mine(int count, String mode) {
        return new Step(StepType.MINE, "mode", mode, "count", String.valueOf(count));
    }

    public static Step chop(int count) {
        return new Step(StepType.CHOP, "count", String.valueOf(count));
    }

    public static Step teleport() {
        return new Step(StepType.TELEPORT);
    }

    public static Step give(String item, int count) {
        return new Step(StepType.GIVE, "item", item, "count", String.valueOf(count));
    }

    public static Step wait(int ticks) {
        return new Step(StepType.WAIT, "ticks", String.valueOf(ticks));
    }

    // ==================== 状态管理 ====================

    public String getChainId() { return chainId; }
    public String getName() { return name; }
    public List<Step> getSteps() { return steps; }
    public int getCurrentStepIndex() { return currentStepIndex; }
    public ChainStatus getStatus() { return status; }
    public String getFailReason() { return failReason; }
    public boolean isDone() { return status == ChainStatus.COMPLETED || status == ChainStatus.FAILED || status == ChainStatus.CANCELLED; }
    public boolean isNotifiedCompletion() { return notifiedCompletion; }
    public void markNotified() { notifiedCompletion = true; }

    /** 当前执行的步骤 */
    public Step getCurrentStep() {
        if (currentStepIndex < 0 || currentStepIndex >= steps.size()) return null;
        return steps.get(currentStepIndex);
    }

    /** 开始执行链 */
    public void start() {
        this.status = ChainStatus.RUNNING;
        this.currentStepIndex = 0;
        if (!steps.isEmpty()) {
            steps.get(0).status = StepStatus.RUNNING;
            steps.get(0).startedAt = System.currentTimeMillis();
        }
    }

    /** 前进到下一步。返回 false 表示已到末尾。 */
    public boolean advance() {
        if (currentStepIndex >= steps.size() - 1) {
            // 全部完成（仅当链未失败时标记完成）
            if (status != ChainStatus.FAILED && status != ChainStatus.CANCELLED) {
                status = ChainStatus.COMPLETED;
            }
            completedAt = System.currentTimeMillis();
            return false;
        }
        currentStepIndex++;
        Step next = steps.get(currentStepIndex);
        next.status = StepStatus.RUNNING;
        next.startedAt = System.currentTimeMillis();
        return true;
    }

    /** 标记当前步骤失败（可重试） */
    public boolean failCurrentStep(String reason) {
        Step current = getCurrentStep();
        if (current == null) return false;
        current.retryCount++;
        current.result = reason;
        if (current.retryCount < current.maxRetries) {
            // 重置状态，等待重试
            current.status = StepStatus.PENDING;
            return true; // 可以重试
        }
        // 超过重试次数
        current.status = StepStatus.FAILED;
        return false; // 不可恢复
    }

    /** 跳过当前步骤 */
    public void skipCurrentStep() {
        Step current = getCurrentStep();
        if (current != null) {
            current.status = StepStatus.SKIPPED;
            current.result = "已跳过";
        }
    }

    /** 标记链为失败 */
    public void failChain(String reason) {
        this.status = ChainStatus.FAILED;
        this.failReason = reason;
        this.completedAt = System.currentTimeMillis();
        Step current = getCurrentStep();
        if (current != null && current.status == StepStatus.RUNNING) {
            current.status = StepStatus.FAILED;
            current.result = reason;
        }
    }

    /** 取消链 */
    public void cancel() {
        this.status = ChainStatus.CANCELLED;
        this.completedAt = System.currentTimeMillis();
        Step current = getCurrentStep();
        if (current != null && current.status == StepStatus.RUNNING) {
            current.status = StepStatus.FAILED;
            current.result = "已取消";
        }
    }

    /** 从失败/取消状态恢复。将当前步骤重置为 PENDING 让引擎重试。 */
    public void resume() {
        if (status == ChainStatus.COMPLETED) return; // 已完成的链不能恢复
        status = ChainStatus.RUNNING;
        failReason = "";
        notifiedCompletion = false;
        Step current = getCurrentStep();
        if (current != null && (current.status == StepStatus.FAILED || current.status == StepStatus.SKIPPED)) {
            current.status = StepStatus.PENDING;
            current.retryCount = 0;
            current.result = "";
        } else if (current == null) {
            // 没有当前步骤，从头开始
            currentStepIndex = 0;
            if (!steps.isEmpty()) {
                steps.get(0).status = StepStatus.PENDING;
                steps.get(0).retryCount = 0;
            }
        }
    }

    /** 当前步骤已完成 */
    public void completeCurrentStep(String result) {
        Step current = getCurrentStep();
        if (current != null) {
            current.status = StepStatus.COMPLETED;
            current.result = result;
            current.completedAt = System.currentTimeMillis();
        }
    }

    // ==================== 进度信息 ====================

    /** 计算总步骤数（不含 SKIPPED） */
    public int getActiveStepCount() {
        return (int) steps.stream().filter(s -> s.status != StepStatus.SKIPPED).count();
    }

    /** 已完成步骤数 */
    public int getCompletedStepCount() {
        return (int) steps.stream().filter(s -> s.status == StepStatus.COMPLETED).count();
    }

    /** 获取进度百分比 */
    public int getProgressPercent() {
        int total = steps.size();
        if (total == 0) return 0;
        return getCompletedStepCount() * 100 / total;
    }

    /** 格式化为 AI 可读的进度文本 */
    public String formatProgressForAI() {
        if (steps.isEmpty()) return "（空任务链）";

        StringBuilder sb = new StringBuilder();
        sb.append("## 任务链：").append(name != null ? name : "未命名").append("\n");
        sb.append("状态：").append(getStatusChinese()).append("\n");
        sb.append("进度：").append(getProgressPercent()).append("%（").append(getCompletedStepCount())
          .append("/").append(steps.size()).append(" 步）\n\n");

        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            String marker = switch (s.status) {
                case PENDING -> "  ⏳";
                case RUNNING -> "  ▶";
                case COMPLETED -> "  ✅";
                case FAILED -> "  ❌";
                case SKIPPED -> "  ⏭";
            };
            sb.append(marker).append(" Step ").append(i + 1).append("/").append(steps.size())
              .append(": ").append(s.getDisplayName());
            if (!s.result.isEmpty()) {
                sb.append(" — ").append(s.result);
            }
            sb.append("\n");
        }

        if (status == ChainStatus.FAILED && !failReason.isEmpty()) {
            sb.append("\n失败原因：").append(failReason).append("\n");
        }

        return sb.toString();
    }

    /** 格式化为简短状态（用于动态上下文注入） */
    public String formatShortStatus() {
        if (status == ChainStatus.PENDING) return "";
        if (isDone()) return "";

        Step current = getCurrentStep();
        if (current == null) return "";

        return String.format("当前任务链：%s — Step %d/%d [%s]",
            name != null ? name : "未命名",
            currentStepIndex + 1, steps.size(),
            current.getDisplayName());
    }

    private String getStatusChinese() {
        return switch (status) {
            case PENDING -> "待开始";
            case RUNNING -> "执行中";
            case COMPLETED -> "已完成 🎉";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
        };
    }

    // ==================== 持久化 ====================

    public void save(Path saveDir) {
        try {
            Files.createDirectories(saveDir);
            Path file = saveDir.resolve("chain.json");
            try (FileWriter w = new FileWriter(file.toFile())) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            com.aibot.mod.AiBotMod.LOGGER.error("[TaskChain] 保存失败: {}", e.getMessage());
        }
    }

    public static TaskChain load(Path saveDir) {
        Path file = saveDir.resolve("chain.json");
        if (!Files.exists(file)) return null;
        try (FileReader r = new FileReader(file.toFile())) {
            return GSON.fromJson(r, TaskChain.class);
        } catch (IOException e) {
            com.aibot.mod.AiBotMod.LOGGER.error("[TaskChain] 加载失败: {}", e.getMessage());
            return null;
        }
    }

    public static void deleteSave(Path saveDir) {
        try {
            Files.deleteIfExists(saveDir.resolve("chain.json"));
        } catch (IOException e) {
            com.aibot.mod.AiBotMod.LOGGER.error("[TaskChain] 删除存档失败: {}", e.getMessage());
        }
    }
}
