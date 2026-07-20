package com.aibot.mod.task;

/**
 * 单个 FTB Quests 任务进度追踪。
 * 记录每个任务的扫描-分析-执行-完成全生命周期状态。
 */
public class QuestProgress {
    public enum Status {
        NOT_STARTED,    // 未开始（默认）
        IN_PROGRESS,    // 正在执行
        COMPLETED,      // 已完成
        BLOCKED         // 受阻（需玩家帮助）
    }

    /** 任务标题（QuestScanner.Quest.title） */
    public String questTitle;
    /** 所属章节标题 */
    public String chapterTitle;
    /** 当前状态 */
    public Status status = Status.NOT_STARTED;
    /** 进度 0–100 */
    public int progress = 0;
    /** 受阻原因（如 "需要去下界"、"缺少材料"） */
    public String blockedReason = "";
    /** 任务类型分类：item / kill / dimension / location / checkmark / other */
    public String taskType = "other";
    /** 最后更新时间（System.currentTimeMillis） */
    public long lastUpdated = System.currentTimeMillis();

    public QuestProgress() {}

    public QuestProgress(String questTitle, String chapterTitle) {
        this.questTitle = questTitle;
        this.chapterTitle = chapterTitle;
    }

    /** 标记为进行中 */
    public void start() {
        this.status = Status.IN_PROGRESS;
        this.lastUpdated = System.currentTimeMillis();
    }

    /** 标记为已完成 */
    public void complete() {
        this.status = Status.COMPLETED;
        this.progress = 100;
        this.lastUpdated = System.currentTimeMillis();
    }

    /** 标记为受阻 */
    public void block(String reason) {
        this.status = Status.BLOCKED;
        this.blockedReason = reason;
        this.lastUpdated = System.currentTimeMillis();
    }

    /** 更新进度 */
    public void setProgress(int pct) {
        this.progress = Math.max(0, Math.min(100, pct));
        this.lastUpdated = System.currentTimeMillis();
    }
}
