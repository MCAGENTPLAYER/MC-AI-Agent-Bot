package com.aibot.mod.entity.actions;

import com.aibot.mod.entity.AiBotEntity;

/**
 * AI Bot 动作接口。
 * 
 * 生命周期: start() → tick()×N → 完成(tick返回true) 或 stop()(手动中断)
 * 每个动作是一个自包含的工具，由 ActionCoordinator 统一调度。
 */
public interface BotAction {
    /** 动作名称，用于日志和状态显示 */
    String getName();

    /**
     * 启动动作。
     * @param bot 执行的 Bot 实体
     * @return false 表示无法启动（原因见 getFailReason）
     */
    boolean start(AiBotEntity bot);

    /**
     * 每 tick 执行一次。
     * @param bot 执行的 Bot 实体
     * @return true 表示动作已完成，总控会自动清理
     */
    boolean tick(AiBotEntity bot);

    /** 强制停止（被中断时调用） */
    void stop(AiBotEntity bot);

    /** 是否已完成 */
    boolean isCompleted();

    /** 是否已失败 */
    boolean isFailed();

    /** 失败原因 */
    String getFailReason();
}
