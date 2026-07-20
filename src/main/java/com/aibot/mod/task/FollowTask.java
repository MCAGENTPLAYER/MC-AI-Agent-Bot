package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;

/**
 * 跟随任务 — 使用已有的 FollowAction 通过 coordinator 执行
 */
public class FollowTask extends BaseTask {
    private boolean started = false;
    private int tickSinceStart = 0;

    public FollowTask() {}

    @Override
    public String getName() { return "跟随玩家"; }

    @Override
    public void tick(AiBotEntity bot) {
        if (state != State.RUNNING) return;
        tickCount++;

        if (!started) {
            started = true;
            tickSinceStart = tickCount;
            setStatus(bot, getName());
            if (!bot.coordinator.start("follow", new String[0], bot)) {
                fail("无法开始跟随（附近没有玩家）");
                ConversationLogger.logBotCommand("FollowTask 启动失败");
            } else {
                ConversationLogger.logBotCommand("FollowTask 启动");
            }
            return;
        }

        boolean justFinished = bot.coordinator.tick(bot);
        if (justFinished) {
            complete();
            ConversationLogger.logBotCommand("FollowTask 完成");
        }
        // 跟随不设超时，玩家手动 stop 即可
    }
}
