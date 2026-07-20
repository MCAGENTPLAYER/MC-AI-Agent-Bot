package com.aibot.mod.entity.actions;

import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class FollowAction implements BotAction {
    private final AiBotEntity bot;
    private LivingEntity followTarget = null;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = "";

    public FollowAction(AiBotEntity bot) {
        this.bot = bot;
    }

    public FollowAction(AiBotEntity bot, LivingEntity target) {
        this.bot = bot;
        this.followTarget = target;
    }

    @Override
    public boolean start(AiBotEntity bot) {
        completed = false;
        failed = false;
        failReason = "";
        if (followTarget == null) {
            followTarget = findNearestPlayer();
        }
        return followTarget != null;
    }

    @Override
    public boolean tick(AiBotEntity bot) {
        if (completed || failed) return true;

        if (followTarget == null || !followTarget.isAlive()) {
            followTarget = findNearestPlayer();
            if (followTarget == null) {
                bot.setStatus("No player to follow");
                failed = true;
                failReason = "No player to follow";
                return true;
            }
        }

        double dist = bot.distanceToSqr(followTarget.position());

        if (dist > 1.5 * 1.5) {
            if (dist > 64.0 * 64.0) {
                bot.setStatus("Target too far");
                failed = true;
                failReason = "Target too far";
                return true;
            }

            bot.getNavigation().moveTo(followTarget, 1.0D);
            bot.setStatus("Following " + ((Player) followTarget).getName().getString());
        } else {
            bot.getNavigation().stop();
        }

        return false;
    }

    private LivingEntity findNearestPlayer() {
        return bot.findNearestPlayer();
    }

    @Override
    public String getName() {
        return "Follow";
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

    public LivingEntity getFollowTarget() {
        return followTarget;
    }
}
