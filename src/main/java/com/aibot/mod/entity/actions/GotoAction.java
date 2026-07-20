package com.aibot.mod.entity.actions;

import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;

public class GotoAction implements BotAction {
    private final AiBotEntity bot;
    private final BlockPos targetPos;
    private int navStuckTicks = 0;
    private double lastNavDist = 0;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = "";

    public GotoAction(AiBotEntity bot, BlockPos targetPos) {
        this.bot = bot;
        this.targetPos = targetPos;
    }

    @Override
    public boolean start(AiBotEntity bot) {
        completed = false;
        failed = false;
        failReason = "";
        navStuckTicks = 0;
        lastNavDist = 0;
        if (targetPos == null) {
            failed = true;
            failReason = "No target position";
            return false;
        }
        return true;
    }

    @Override
    public boolean tick(AiBotEntity bot) {
        if (completed || failed) return true;

        if (targetPos == null) {
            failed = true;
            failReason = "No target position";
            return true;
        }

        double dist = bot.distanceToSqr(
            targetPos.getX() + 0.5, 
            targetPos.getY() + 0.5, 
            targetPos.getZ() + 0.5);

        if (dist > 2.0 * 2.0) {
            if (dist > 64.0 * 64.0) {
                bot.setStatus("Target too far");
                failed = true;
                failReason = "Target too far";
                navStuckTicks = 0;
                return true;
            }

            boolean started = bot.getNavigation().moveTo(
                targetPos.getX() + 0.5, 
                targetPos.getY() + 0.5, 
                targetPos.getZ() + 0.5, 
                1.0D);

            if (!started) {
                navStuckTicks++;
                if (navStuckTicks > 20) {
                    bot.setStatus("Can't reach destination");
                    failed = true;
                    failReason = "Can't reach destination";
                    navStuckTicks = 0;
                    return true;
                }
                return false;
            }

            if (dist < lastNavDist - 0.1) {
                navStuckTicks = 0;
            } else {
                navStuckTicks++;
                if (navStuckTicks > 200) {
                    bot.setStatus("Stuck! Giving up");
                    failed = true;
                    failReason = "Stuck";
                    navStuckTicks = 0;
                    return true;
                }
            }
            lastNavDist = dist;
            bot.setStatus("Moving... " + (int) Math.sqrt(dist) + "m");
        } else {
            navStuckTicks = 0;
            bot.setStatus("Arrived!");
            completed = true;
            return true;
        }

        return false;
    }

    @Override
    public String getName() {
        return "Go To";
    }

    @Override
    public boolean isCompleted() {
        return completed;
    }

    @Override
    public boolean isFailed() {
        return failed;
    }

    @Override
    public String getFailReason() {
        return failReason;
    }

    @Override
    public void stop(AiBotEntity bot) {
        completed = true;
        bot.getNavigation().stop();
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }
}
