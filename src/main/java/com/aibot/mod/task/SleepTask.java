package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;

/**
 * 睡觉任务 — 使用已有的 SleepAction 通过 coordinator 执行
 */
public class SleepTask extends BaseTask {
    private boolean started = false;
    private int tickSinceStart = 0;

    @Override
    public String getName() { return "睡觉"; }

    @Override
    public void tick(AiBotEntity bot) {
        if (state != State.RUNNING) return;
        tickCount++;

        if (!started) {
            started = true;
            tickSinceStart = tickCount;
            setStatus(bot, getName());
            if (!bot.coordinator.start("sleep", new String[0], bot)) {
                fail("无法开始睡觉（附近没有床）");
                ConversationLogger.logBotCommand("SleepTask 启动失败");
            } else {
                ConversationLogger.logBotCommand("SleepTask 启动");
            }
            return;
        }

        boolean justFinished = bot.coordinator.tick(bot);
        if (justFinished) {
            complete();
            ConversationLogger.logBotCommand("SleepTask 完成");
        } else if (tickCount - tickSinceStart > 6000) {
            bot.coordinator.stop(bot);
            fail("睡觉超时（5分钟）");
        }
    }
}
