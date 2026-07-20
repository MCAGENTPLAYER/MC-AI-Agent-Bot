package com.aibot.mod.entity.actions;

import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

public class SleepAction implements BotAction {
    private final AiBotEntity bot;
    private BlockPos targetBlockPos = null;
    private int navStuckTicks = 0;
    private double lastNavDist = 0;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = "";

    public SleepAction(AiBotEntity bot) {
        this.bot = bot;
    }

    @Override
    public boolean start(AiBotEntity bot) {
        completed = false;
        failed = false;
        failReason = "";
        targetBlockPos = null;
        navStuckTicks = 0;
        lastNavDist = 0;

        if (!bot.level().isNight()) {
            failed = true;
            failReason = "It's daytime";
            return false;
        }
        return true;
    }

    @Override
    public boolean tick(AiBotEntity bot) {
        if (completed || failed) return true;

        if (bot.isSleeping()) {
            bot.getNavigation().stop();
            bot.setDeltaMovement(0, 0, 0);
            if (bot.level().isDay()) {
                bot.stopSleeping();
                bot.setStatus("Good morning!");
                completed = true;
                return true;
            }
            return false;
        }

        if (!bot.level().isNight()) {
            bot.setStatus("It's daytime, can't sleep");
            failed = true;
            failReason = "It's daytime";
            return true;
        }

        if (targetBlockPos == null) {
            targetBlockPos = findNearestBed();
            if (targetBlockPos == null) {
                bot.setStatus("No bed found nearby");
                failed = true;
                failReason = "No bed found";
                return true;
            }
        }

        BlockState state = bot.level().getBlockState(targetBlockPos);
        if (!state.is(BlockTags.BEDS)) {
            bot.setStatus("Bed is gone, looking for new one...");
            targetBlockPos = null;
            return false;
        }

        double dist = bot.distanceToSqr(
            targetBlockPos.getX() + 0.5,
            targetBlockPos.getY() + 0.5,
            targetBlockPos.getZ() + 0.5);

        if (dist > 2.5 * 2.5) {
            if (dist > 64.0 * 64.0) {
                targetBlockPos = null;
                return false;
            }

            boolean started = bot.getNavigation().moveTo(
                targetBlockPos.getX() + 0.5,
                targetBlockPos.getY(),
                targetBlockPos.getZ() + 0.5,
                1.0D);

            if (!started) {
                navStuckTicks++;
                if (navStuckTicks > 20) {
                    targetBlockPos = null;
                    navStuckTicks = 0;
                }
                return false;
            }

            if (dist < lastNavDist - 0.1) {
                navStuckTicks = 0;
            } else {
                navStuckTicks++;
                if (navStuckTicks > 100) {
                    targetBlockPos = null;
                    navStuckTicks = 0;
                }
            }
            lastNavDist = dist;
            bot.setStatus("Going to bed...");
            return false;
        }

        navStuckTicks = 0;
        bot.getNavigation().stop();
        bot.getLookControl().setLookAt(
            targetBlockPos.getX() + 0.5,
            targetBlockPos.getY() + 0.5,
            targetBlockPos.getZ() + 0.5);

        bot.startSleeping(targetBlockPos);
        bot.setStatus("Sleeping... Zzz");
        return false;
    }

    private BlockPos findNearestBed() {
        BlockPos botPos = bot.blockPosition();
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        int range = 32;

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = botPos.offset(dx, dy, dz);
                    BlockState state = bot.level().getBlockState(pos);
                    if (state.is(BlockTags.BEDS)) {
                        double dist = pos.distSqr(botPos);
                        if (dist < minDist) {
                            minDist = dist;
                            nearest = pos;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    @Override
    public String getName() {
        return "Sleep";
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
        if (bot.isSleeping()) {
            bot.stopSleeping();
        }
    }
}
