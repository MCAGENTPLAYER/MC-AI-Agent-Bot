package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;

/**
 * 移动任务 — 使用已有的 GotoAction 通过 coordinator 执行
 */
public class GotoTask extends BaseTask {
    private final double x, y, z;
    private boolean started = false;
    private int tickSinceStart = 0;

    public GotoTask(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public String getName() {
        return "移动到 (" + (int)x + ", " + (int)y + ", " + (int)z + ")";
    }

    @Override
    public void tick(AiBotEntity bot) {
        if (state != State.RUNNING) return;
        tickCount++;

        if (!started) {
            started = true;
            tickSinceStart = tickCount;
            setStatus(bot, getName());
            if (!bot.coordinator.start("goto", new String[]{String.valueOf((int)x), String.valueOf((int)y), String.valueOf((int)z)}, bot)) {
                fail("无法移动到目标位置");
                ConversationLogger.logBotCommand("GotoTask 启动失败");
            } else {
                ConversationLogger.logBotCommand("GotoTask 启动: " + (int)x + " " + (int)y + " " + (int)z);
            }
            return;
        }

        boolean justFinished = bot.coordinator.tick(bot);
        if (justFinished) {
            complete();
            ConversationLogger.logBotCommand("GotoTask 完成");
        } else if (tickCount - tickSinceStart > 6000) {
            bot.coordinator.stop(bot);
            fail("移动超时（5分钟）");
        }
    }
}
