package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;

/**
 * 种地任务 — 使用已有的 FarmAction 通过 coordinator 执行
 */
public class FarmTask extends BaseTask {
    private boolean started = false;
    private int tickSinceStart = 0;

    @Override
    public String getName() { return "种地"; }

    @Override
    public void tick(AiBotEntity bot) {
        if (state != State.RUNNING) return;
        tickCount++;

        if (!started) {
            started = true;
            tickSinceStart = tickCount;
            setStatus(bot, getName());
            if (!bot.coordinator.start("farm", new String[0], bot)) {
                fail("无法开始种地（附近没有耕地或种子）");
                ConversationLogger.logBotCommand("FarmTask 启动失败");
            } else {
                ConversationLogger.logBotCommand("FarmTask 启动");
            }
            return;
        }

        boolean justFinished = bot.coordinator.tick(bot);
        if (justFinished) {
            complete();
            ConversationLogger.logBotCommand("FarmTask 完成");
        } else if (tickCount - tickSinceStart > 6000) {
            bot.coordinator.stop(bot);
            fail("种地超时（5分钟）");
        }
    }
}
