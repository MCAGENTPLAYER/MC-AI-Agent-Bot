package com.aibot.mod.task;

import com.aibot.mod.QuestScanner;
import com.aibot.mod.QuestScanner.QuestData;
import com.aibot.mod.QuestScanner.QuestChapter;
import com.aibot.mod.QuestScanner.Quest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 任务系统核心。
 * 基于 FTB Quests 扫描结果，分析可行性、分队列管理、追踪进度、注入 AI 上下文。
 * 生命周期：load() → scanAndAnalyze() → formatForAI() → doTask() → save()
 */
public class TaskQuestSystem {

    private static final Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("TaskQuestSystem");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // === 状态 ===

    /** 最近一次扫描的任务书数据 */
    private QuestData questData = null;

    /** 进度映射：questTitle → QuestProgress */
    private final Map<String, QuestProgress> progressMap = new LinkedHashMap<>();

    /** 正在执行的任务标题（null 表示空闲） */
    private String activeQuestTitle = null;

    /** 当前正在执行的任务进度检查冷却（tick 计数） */
    private int checkProgressTick = 0;

    /** 最近一次自动反馈的时间（防刷屏） */
    private long lastAutoFeedbackTime = 0;

    // === 队列缓存（由 scanAndAnalyze() 刷新） ===

    private final List<Quest> doableQuests = new ArrayList<>();
    private final List<Quest> plannedQuests = new ArrayList<>();
    private final List<Quest> blockedQuests = new ArrayList<>();
    private final List<Quest> completedQuests = new ArrayList<>();

    // === 路径 ===

    private Path getDataDir() {
        return Path.of(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "ai_bot", "mind");
    }

    private Path getProgressFile() {
        return getDataDir().resolve("quest_progress.json");
    }

    private Path getCacheFile() {
        return Path.of(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "ai_bot", "quests_cache.json");
    }

    // ======================== 生命周期 ========================

    /** 加载进度缓存 */
    public void load() {
        Path file = getProgressFile();
        if (Files.exists(file)) {
            try (FileReader reader = new FileReader(file.toFile())) {
                List<QuestProgress> list = GSON.fromJson(reader, new TypeToken<List<QuestProgress>>(){}.getType());
                if (list != null) {
                    progressMap.clear();
                    for (QuestProgress p : list) {
                        progressMap.put(p.questTitle, p);
                    }
                    LOGGER.info("[TaskQuestSystem] 加载 {} 条任务进度", progressMap.size());
                }
            } catch (Exception e) {
                LOGGER.warn("[TaskQuestSystem] 加载进度失败: {}", e.getMessage());
            }
        }
    }

    /** 保存进度缓存 */
    public void save() {
        Path dir = getDataDir();
        try {
            Files.createDirectories(dir);
            try (FileWriter w = new FileWriter(getProgressFile().toFile())) {
                GSON.toJson(new ArrayList<>(progressMap.values()), w);
            }
        } catch (IOException e) {
            LOGGER.warn("[TaskQuestSystem] 保存进度失败: {}", e.getMessage());
        }
    }

    /** 扫描任务书并重新分析所有任务。返回格式化结果文本。 */
    public String scanAndAnalyze() {
        // 1. 重新扫描
        questData = QuestScanner.scan();
        if (questData == null || questData.chapters.isEmpty()) {
            return "未检测到 FTB Quests 任务书系统。" +
                (questData != null && !questData.error.isEmpty() ? " (" + questData.error + ")" : "");
        }

        // 2. 缓存扫描结果
        QuestScanner.saveCache(questData, getCacheFile());

        // 3. 确保进度映射覆盖所有任务
        for (QuestChapter chapter : questData.chapters) {
            for (Quest quest : chapter.quests) {
                progressMap.putIfAbsent(quest.title, new QuestProgress(quest.title, chapter.title));
            }
        }

        // 4. 重新分类
        classifyQuests();

        save();
        return formatSummaryForAI();
    }

    /** 基于缓存的扫描结果重新分类（不重新扫描）。返回格式化结果。 */
    public String reclassifyFromCache() {
        if (questData == null) {
            questData = QuestScanner.loadCache(getCacheFile());
            if (questData == null) return "";
        }
        classifyQuests();
        return formatSummaryForAI();
    }

    // ======================== 分析分类 ========================

    /** 对所有任务进行可行性分析，填入四个队列 */
    private void classifyQuests() {
        doableQuests.clear();
        plannedQuests.clear();
        blockedQuests.clear();
        completedQuests.clear();

        if (questData == null || questData.chapters.isEmpty()) return;

        for (QuestChapter chapter : questData.chapters) {
            for (Quest quest : chapter.quests) {
                QuestProgress prog = progressMap.get(quest.title);
                if (prog == null) {
                    prog = new QuestProgress(quest.title, chapter.title);
                    progressMap.put(quest.title, prog);
                }

                // 已完成任务不进分析
                if (prog.status == QuestProgress.Status.COMPLETED) {
                    completedQuests.add(quest);
                    continue;
                }

                // 进行中的任务
                if (prog.status == QuestProgress.Status.IN_PROGRESS) {
                    doableQuests.add(quest);
                    continue;
                }

                // 受阻任务
                if (prog.status == QuestProgress.Status.BLOCKED) {
                    blockedQuests.add(quest);
                    continue;
                }

                // 分析任务类型和可行性
                String type = classifyTaskType(quest);
                prog.taskType = type;
                AnalysisResult result = analyzeQuest(quest, type);

                if (result.doable) {
                    prog.status = QuestProgress.Status.NOT_STARTED;
                    doableQuests.add(quest);
                } else if (result.isPlanned) {
                    prog.status = QuestProgress.Status.NOT_STARTED;
                    plannedQuests.add(quest);
                } else {
                    prog.block(result.blockedReason);
                    blockedQuests.add(quest);
                }
            }
        }

        // 按章节顺序排序
        sortByChapterOrder(doableQuests);
        sortByChapterOrder(plannedQuests);
    }

    /** 判断任务类型 */
    private String classifyTaskType(Quest quest) {
        for (String task : quest.tasks) {
            if (task.startsWith("收集 ")) return "item";
            if (task.startsWith("击杀 ")) return "kill";
            if (task.startsWith("前往维度")) return "dimension";
            if (task.startsWith("到达坐标")) return "location";
            if (task.startsWith("手动完成")) return "checkmark";
            if (task.contains("收集") || task.contains("合成") || task.contains("获得")) return "item";
        }
        // 根据任务描述辅助判断
        if (quest.tasks.isEmpty()) return "checkmark";
        return "other";
    }

    /** 单个任务分析结果 */
    private static class AnalysisResult {
        boolean doable;      // 现在就能做
        boolean isPlanned;   // 需要准备（如合成中间材料），但目测可做
        String blockedReason = "";
    }

    /** 分析单个任务是否可执行 */
    private AnalysisResult analyzeQuest(Quest quest, String type) {
        AnalysisResult r = new AnalysisResult();
        switch (type) {
            case "item" -> {
                // 物品类任务：检查是否需要跨维度材料
                String desc = String.join(" ", quest.tasks).toLowerCase();
                if (desc.contains("下界") || desc.contains("末地") || desc.contains("末影")
                    || desc.contains("凋零") || desc.contains("龙") || desc.contains("潜影")
                    || desc.contains("netherite") || desc.contains("下界合金")) {
                    r.isPlanned = true;
                    r.blockedReason = "需要去下界或末地获取材料";
                } else {
                    // 常规物品：Bot 可以挖/砍/打/合成，标记为可做
                    r.doable = true;
                }
            }
            case "kill" -> {
                String desc = String.join(" ", quest.tasks).toLowerCase();
                if (desc.contains("凋零") || desc.contains("烈焰") || desc.contains("恶魂")
                    || desc.contains("猪灵") || desc.contains("僵尸猪灵")) {
                    r.isPlanned = true;
                    r.blockedReason = "需要去下界击杀";
                } else if (desc.contains("末影") || desc.contains("潜影") || desc.contains("龙")) {
                    r.isPlanned = true;
                    r.blockedReason = "需要去末地击杀";
                } else {
                    r.doable = true; // 主世界生物，可以尝试
                }
            }
            case "dimension" -> {
                r.blockedReason = "需要玩家带我前往其他维度";
            }
            case "location" -> {
                // 简化处理：当前在主世界则标记为可尝试
                r.doable = true;
            }
            case "checkmark", "other" -> {
                r.doable = true;
            }
        }
        return r;
    }

    /** 按章节原始顺序排序 */
    private void sortByChapterOrder(List<Quest> list) {
        if (questData == null) return;
        // 建立章节索引
        Map<String, Integer> chapterOrder = new HashMap<>();
        for (int i = 0; i < questData.chapters.size(); i++) {
            chapterOrder.put(questData.chapters.get(i).title, i);
        }
        list.sort((a, b) -> {
            QuestProgress pa = progressMap.get(a.title);
            QuestProgress pb = progressMap.get(b.title);
            int ca = pa != null ? chapterOrder.getOrDefault(pa.chapterTitle, 999) : 999;
            int cb = pb != null ? chapterOrder.getOrDefault(pb.chapterTitle, 999) : 999;
            return Integer.compare(ca, cb);
        });
    }

    // ======================== AI 上下文注入 ========================

    /** 格式化当前任务状态摘要供 AI 查看（简短版） */
    public String formatSummaryForAI() {
        if (questData == null || questData.chapters.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前任务状态\n");
        sb.append("共 ").append(questData.totalQuests).append(" 个任务，")
          .append(questData.chapters.size()).append(" 个章节\n");

        if (activeQuestTitle != null) {
            QuestProgress active = progressMap.get(activeQuestTitle);
            if (active != null && active.status == QuestProgress.Status.IN_PROGRESS) {
                sb.append("▶ 正在执行: **").append(activeQuestTitle)
                  .append("** (进度 ").append(active.progress).append("%)\n");
            }
        }

        sb.append("\n✅ 现在就能做 (").append(doableQuests.size()).append(" 个):\n");
        int i = 1;
        for (Quest q : doableQuests) {
            QuestProgress p = progressMap.get(q.title);
            if (p != null && p.status == QuestProgress.Status.IN_PROGRESS) continue; // 已在执行
            sb.append("  ").append(i++).append(". ").append(q.title).append("\n");
        }
        if (i == 1) sb.append("  （无）\n");

        sb.append("\n📋 需条件后做 (").append(plannedQuests.size()).append(" 个):\n");
        for (Quest q : plannedQuests) {
            sb.append("  - ").append(q.title).append(" (受阻: ")
              .append(progressMap.get(q.title) != null ? progressMap.get(q.title).blockedReason : "需准备")
              .append(")\n");
        }

        sb.append("\n⛔ 当前做不了 (").append(blockedQuests.size()).append(" 个):\n");
        for (Quest q : blockedQuests) {
            sb.append("  - ").append(q.title).append(" (原因: ")
              .append(progressMap.get(q.title) != null ? progressMap.get(q.title).blockedReason : "未知")
              .append(")\n");
        }

        return sb.toString();
    }

    /** 格式化完整任务状态供 AI 注入（长版，含 doable 任务详情） */
    public String formatFullForAI() {
        if (questData == null || questData.chapters.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[可做的任务]\n");
        for (Quest q : doableQuests) {
            QuestProgress p = progressMap.get(q.title);
            if (p != null && p.status == QuestProgress.Status.IN_PROGRESS) continue;
            sb.append("- ").append(q.title);
            if (!q.tasks.isEmpty()) {
                sb.append(" (").append(String.join("; ", q.tasks)).append(")");
            }
            sb.append("\n");
        }

        if (activeQuestTitle != null) {
            QuestProgress active = progressMap.get(activeQuestTitle);
            if (active != null && active.status == QuestProgress.Status.IN_PROGRESS) {
                sb.append("\n[当前执行]\n▶ ").append(activeQuestTitle)
                  .append(" (").append(active.progress).append("%)\n");
            }
        }

        return sb.toString();
    }

    /** 注入到动态上下文的文本（每次对话附带） */
    public String getContextForAI() {
        if (doableQuests.isEmpty() && plannedQuests.isEmpty() && blockedQuests.isEmpty()) {
            return "";
        }
        return formatFullForAI();
    }

    // ======================== 任务执行 ========================

    /**
     * 开始执行指定任务。
     * 返回执行指令文本（Human-readable，AI 会据此调用工具），
     * 或 null 表示任务不存在/无法执行。
     */
    public String startTask(String questTitle) {
        Quest quest = findQuest(questTitle);
        if (quest == null) return null;

        QuestProgress prog = progressMap.get(questTitle);
        if (prog == null) return null;

        // 如果已完成，跳过
        if (prog.status == QuestProgress.Status.COMPLETED) {
            return "任务 \"" + questTitle + "\" 已经完成了！";
        }

        // 如果受阻，提示原因
        if (prog.status == QuestProgress.Status.BLOCKED) {
            return "任务 \"" + questTitle + "\" 目前做不了：" + prog.blockedReason;
        }

        // 标记进行中
        prog.start();
        activeQuestTitle = questTitle;
        save();

        // 生成可读指令
        return buildTaskInstructions(quest, prog);
    }

    /** 生成任务的执行指令文本 */
    private String buildTaskInstructions(Quest quest, QuestProgress prog) {
        StringBuilder sb = new StringBuilder();
        sb.append("开始执行任务: **").append(quest.title).append("**\n\n");

        if (!quest.tasks.isEmpty()) {
            sb.append("需求:\n");
            for (String task : quest.tasks) {
                sb.append("- ").append(task).append("\n");
            }
        }

        // 根据任务类型建议执行操作
        sb.append("\n执行建议:\n");
        boolean hasAction = false;

        for (String task : quest.tasks) {
            String t = task.toLowerCase();
            if (task.startsWith("收集 ")) {
                String item = task.substring(3).replaceAll("^\\d+ 个 ", "").trim();
                if (item.contains("原木") || item.contains("木头") || item.contains("木板")) {
                    sb.append("- 砍树获取 ").append(item).append("\n");
                    hasAction = true;
                } else if (item.contains("圆石") || item.contains("石头") || item.contains("煤炭")
                    || item.contains("铁") || item.contains("铜") || item.contains("金")) {
                    sb.append("- 挖矿获取 ").append(item).append("\n");
                    hasAction = true;
                } else if (item.contains("小麦") || item.contains("胡萝卜") || item.contains("土豆")
                    || item.contains("种子")) {
                    sb.append("- 种地获取 ").append(item).append("\n");
                    hasAction = true;
                } else if (item.contains("羊毛") || item.contains("皮革") || item.contains("鸡肉")
                    || item.contains("牛肉") || item.contains("猪肉")) {
                    sb.append("- 打猎获取 ").append(item).append("\n");
                    hasAction = true;
                } else {
                    sb.append("- 收集 ").append(item).append("（尝试合成或挖掘）\n");
                    hasAction = true;
                }
            } else if (task.startsWith("击杀 ")) {
                sb.append("- 打猎/战斗\n");
                hasAction = true;
            }
        }

        if (!hasAction) {
            sb.append("- 需求已满足或无需动作，请检查确认\n");
        }

        sb.append("\n完成后我会检查进度并更新状态。");
        return sb.toString();
    }

    /** 根据任务标题查找 Quest 对象 */
    private Quest findQuest(String title) {
        if (questData == null) return null;
        for (QuestChapter ch : questData.chapters) {
            for (Quest q : ch.quests) {
                if (q.title.equals(title)) return q;
            }
        }
        return null;
    }

    /** 获取当前正在执行的任务标题 */
    public String getActiveQuestTitle() {
        return activeQuestTitle;
    }

    /** 获取当前可做任务列表（供工具返回） */
    public String getDoableNames() {
        return doableQuests.stream()
            .filter(q -> {
                QuestProgress p = progressMap.get(q.title);
                return p == null || p.status != QuestProgress.Status.IN_PROGRESS;
            })
            .map(q -> q.title)
            .collect(Collectors.joining("、"));
    }

    /** 获取可做任务数量 */
    public int getDoableCount() {
        return (int) doableQuests.stream()
            .filter(q -> {
                QuestProgress p = progressMap.get(q.title);
                return p == null || p.status != QuestProgress.Status.IN_PROGRESS;
            })
            .count();
    }

    // ======================== 进度检查 ========================

    /**
     * 检查当前执行中的任务是否已完成。
     * 由 BotController tick 调用。
     */
    public QuestProgress checkActiveProgress() {
        if (activeQuestTitle == null) return null;

        QuestProgress prog = progressMap.get(activeQuestTitle);
        if (prog == null || prog.status != QuestProgress.Status.IN_PROGRESS) {
            activeQuestTitle = null;
            return null;
        }

        return prog;
    }

    /** 手动标记任务为已完成（玩家确认/AI反馈） */
    public String markCompleted(String questTitle) {
        QuestProgress prog = progressMap.get(questTitle);
        if (prog == null) return "任务不存在";

        if (prog.status == QuestProgress.Status.COMPLETED) {
            return "任务 \"" + questTitle + "\" 已经是完成状态了";
        }

        prog.complete();
        if (questTitle.equals(activeQuestTitle)) {
            activeQuestTitle = null;
        }
        save();

        // 重新分类，更新队列
        classifyQuests();

        return "任务 \"" + questTitle + "\" 已完成！";
    }

    // ======================== 反馈机制 ========================

    /**
     * 生成 Elapsed 之后的反馈文本（供 AI 主动告知玩家）。
     * 返 null 表示无更新。
     */
    public String getFeedbackText(long cooldownMs) {
        long now = System.currentTimeMillis();
        if (now - lastAutoFeedbackTime < cooldownMs) return null;

        // 检查是否有任务刚完成
        if (activeQuestTitle == null) {
            // 空闲状态，看看有没有可做的
            if (!doableQuests.isEmpty()) {
                lastAutoFeedbackTime = now;
                return "当前没有在做的任务。有 " + getDoableCount() + " 个任务可以做，要分配一个吗？";
            }
            return null;
        }

        QuestProgress active = progressMap.get(activeQuestTitle);
        if (active == null) return null;

        // 只在进度有变化时反馈
        if (active.progress > 0 && active.progress < 100) {
            lastAutoFeedbackTime = now;
            return "任务 \"" + activeQuestTitle + "\" 进度 " + active.progress + "%。";
        }

        return null;
    }

    // ======================== 重置 ========================

    /** 重置所有任务进度 */
    public void resetAll() {
        progressMap.clear();
        doableQuests.clear();
        plannedQuests.clear();
        blockedQuests.clear();
        completedQuests.clear();
        activeQuestTitle = null;
        questData = null;
        save();
    }
}
