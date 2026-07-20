package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;

/**
 * 挖矿任务 — 使用已有的 MineTunnelAction 通过 coordinator 执行
 * 涵盖 mine（地下挖掘）、mine_stone（露天挖石头）、mine_ore（挖矿石）
 */
public class MineTask extends BaseTask {
    private final int count;
    private final String mode; // "mine", "stone", "ore"
    private boolean started = false;
    private int tickSinceStart = 0;

    public MineTask(int count, String mode) {
        this.count = Math.max(1, count);
        this.mode = (mode != null) ? mode : "mine";
    }

    @Override
    public String getName() {
        String label = switch (mode) {
            case "stone" -> "挖石头";
            case "ore" -> "挖矿石";
            default -> "挖掘";
        };
        return label + " x" + count;
    }

    @Override
    public void tick(AiBotEntity bot) {
        if (state != State.RUNNING) return;
        tickCount++;

        if (!started) {
            started = true;
            tickSinceStart = tickCount;
            setStatus(bot, getName());
            if (!bot.coordinator.start("mine", new String[]{String.valueOf(count)}, bot)) {
                fail("无法开始挖掘");
                ConversationLogger.logBotCommand("MineTask 启动失败");
            } else {
                ConversationLogger.logBotCommand("MineTask 启动: " + mode + " " + count);
            }
            return;
        }

        boolean justFinished = bot.coordinator.tick(bot);
        if (justFinished) {
            complete();
            ConversationLogger.logBotCommand("MineTask 完成");
        } else if (tickCount - tickSinceStart > 6000) {
            bot.coordinator.stop(bot);
            fail("挖掘超时（5分钟）");
        }
    }
}
