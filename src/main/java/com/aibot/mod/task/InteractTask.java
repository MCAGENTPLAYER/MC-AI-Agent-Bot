package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;

/**
 * 交互任务 — 对指定方块/实体执行右键操作
 * 使用 coordinator 的 "interact" 动作执行
 */
public class InteractTask extends BaseTask {
    private final String targetBlock;
    private final String heldItem;
    private boolean started = false;
    private int tickSinceStart = 0;

    public InteractTask(String targetBlock, String heldItem) {
        this.targetBlock = targetBlock;
        this.heldItem = heldItem;
    }

    @Override
    public String getName() {
        if (heldItem != null) return "交互: " + targetBlock + " + " + heldItem;
        return "交互: " + targetBlock;
    }

    @Override
    public void tick(AiBotEntity bot) {
        if (state != State.RUNNING) return;
        tickCount++;

        if (!started) {
            started = true;
            tickSinceStart = tickCount;
            setStatus(bot, getName());

            String[] args = heldItem != null
                ? new String[]{targetBlock, heldItem}
                : new String[]{targetBlock};

            if (!bot.coordinator.start("interact", args, bot)) {
                fail("无法交互: " + targetBlock);
                ConversationLogger.logBotCommand("InteractTask 启动失败");
            } else {
                ConversationLogger.logBotCommand("InteractTask 启动: " + getName());
            }
            return;
        }

        boolean justFinished = bot.coordinator.tick(bot);
        if (justFinished) {
            complete();
            ConversationLogger.logBotCommand("InteractTask 完成");
        } else if (tickCount - tickSinceStart > 6000) {
            bot.coordinator.stop(bot);
            fail("交互超时（5分钟）");
        }
    }
}
