package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;

/**
 * 打猎任务 — 使用 coordinator 的 "hunt" 动作执行
 */
public class HuntTask extends BaseTask {
    private boolean started = false;
    private int tickSinceStart = 0;

    @Override
    public String getName() { return "打猎"; }

    @Override
    public void tick(AiBotEntity bot) {
        if (state != State.RUNNING) return;
        tickCount++;

        if (!started) {
            started = true;
            tickSinceStart = tickCount;
            setStatus(bot, getName());
            if (!bot.coordinator.start("hunt", new String[0], bot)) {
                fail("无法开始打猎（附近没有动物）");
                ConversationLogger.logBotCommand("HuntTask 启动失败");
            } else {
                ConversationLogger.logBotCommand("HuntTask 启动");
            }
            return;
        }

        boolean justFinished = bot.coordinator.tick(bot);
        if (justFinished) {
            complete();
            ConversationLogger.logBotCommand("HuntTask 完成");
        } else if (tickCount - tickSinceStart > 6000) {
            bot.coordinator.stop(bot);
            fail("打猎超时（5分钟）");
        }
    }
}
