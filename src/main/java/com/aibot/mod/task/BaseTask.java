package com.aibot.mod.task;

import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.network.chat.Component;

/**
 * 任务基类 — 所有引擎任务的抽象框架
 * 每个任务是一个状态机，由 Bot 的 AI 循环驱动的 tick()
 */
public abstract class BaseTask {

    public enum State { RUNNING, COMPLETED, FAILED }

    protected State state = State.RUNNING;
    protected String failReason = "";
    protected String statusMessage = "";
    protected int tickCount = 0;           // 状态内计时器
    protected int stuckTimer = 0;          // 导航超时计时器

    /** 每个 AI tick 调用 */
    public abstract void tick(AiBotEntity bot);

    /** 任务名称（显示用） */
    public abstract String getName();

    public State getState() { return state; }
    public boolean isDone() { return state != State.RUNNING; }
    public boolean isSuccess() { return state == State.COMPLETED; }
    public String getFailReason() { return failReason; }
    public String getStatusMessage() { return statusMessage; }

    protected void complete() {
        state = State.COMPLETED;
    }

    protected void fail(String reason) {
        state = State.FAILED;
        failReason = reason;
        statusMessage = reason;
    }

    /**
     * 导航到目标位置，返回 true 表示已到达（距离<2.5格）
     */
    protected boolean navigateTo(AiBotEntity bot, double x, double y, double z, double speed) {
        double dist = bot.distanceToSqr(x, y, z);
        if (dist < 2.5) {
            bot.getNavigation().stop();
            stuckTimer = 0;
            return true;
        }
        stuckTimer++;
        // 首帧立即导航，之后每1秒刷新一次
        if (stuckTimer == 1 || stuckTimer % 20 == 0) {
            bot.getNavigation().moveTo(x, y, z, speed);
        }
        return false;
    }

    /**
     * 看向目标
     */
    protected void lookAt(AiBotEntity bot, double x, double y, double z) {
        bot.getLookControl().setLookAt(x, y, z);
    }

    protected void setStatus(AiBotEntity bot, String msg) {
        statusMessage = msg;
        bot.setCustomName(Component.literal(msg));
    }

    /** 让 Bot 在聊天栏发言 */
    protected void say(AiBotEntity bot, String msg) {
        if (bot.getServer() != null) {
            String name = bot.getName().getString();
            bot.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("\u00a7e[" + name + "]\u00a7f " + msg), false);
        }
    }

    /** 停止移动（让 Bot 原地不动） */
    protected void stopMoving(AiBotEntity bot) {
        bot.getNavigation().stop();
    }
}
