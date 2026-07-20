package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;

/**
 * 砍树任务 — 使用已有的 ChopTreeAction 通过 coordinator 执行
 */
public class ChopTask extends BaseTask {
    private final int count;
    private boolean started = false;
    private int tickSinceStart = 0;

    public ChopTask(int count) {
        this.count = Math.max(1, count);
    }

    @Override
    public String getName() {
        return "砍树 x" + count;
    }

    @Override
    public void tick(AiBotEntity bot) {
        if (state != State.RUNNING) return;
        tickCount++;

        if (!started) {
            started = true;
            tickSinceStart = tickCount;
            setStatus(bot, getName());
            if (!bot.coordinator.start("chop", new String[]{String.valueOf(count)}, bot)) {
                fail("无法开始砍树（附近没有树或路径不通）");
                ConversationLogger.logBotCommand("ChopTask 启动失败");
            } else {
                ConversationLogger.logBotCommand("ChopTask 启动: 砍 " + count + " 个木头");
            }
            return;
        }

        // 直接 tick coordinator
        boolean justFinished = bot.coordinator.tick(bot);
        if (justFinished) {
            complete();
            ConversationLogger.logBotCommand("ChopTask 完成");
        } else if (tickCount - tickSinceStart > 6000) {
            bot.coordinator.stop(bot);
            fail("砍树超时（5分钟）");
        }
    }
}
