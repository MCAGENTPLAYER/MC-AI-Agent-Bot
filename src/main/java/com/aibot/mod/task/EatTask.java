package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;

/**
 * 吃东西任务 — 直接调用 bot.eatFood()
 */
public class EatTask extends BaseTask {
    @Override
    public String getName() { return "吃东西"; }

    @Override
    public void tick(AiBotEntity bot) {
        if (state != State.RUNNING) return;
        tickCount++;

        setStatus(bot, getName());
        boolean success = bot.eatFood();
        if (success) {
            complete();
            ConversationLogger.logBotCommand("EatTask 完成");
        } else {
            fail("背包里没有食物");
        }
    }
}
