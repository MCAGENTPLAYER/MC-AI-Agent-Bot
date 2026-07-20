package com.aibot.mod.entity.actions;

import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;

import java.util.List;

/**
 * 打猎动作 — 找动物 → 导航 → 攻击
 */
public class HuntAction implements BotAction {
    private final AiBotEntity bot;
    private BlockPos targetPos = null;
    private LivingEntity targetAnimal = null;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = "";

    private static final int HUNT_RANGE = 24;

    public HuntAction(AiBotEntity bot) {
        this.bot = bot;
    }

    @Override
    public String getName() { return "hunt"; }

    @Override
    public boolean start(AiBotEntity bot) {
        completed = false;
        failed = false;
        failReason = "";
        targetPos = null;
        targetAnimal = null;

        LivingEntity animal = findNearestAnimal();
        if (animal == null) {
            failed = true;
            failReason = "附近没有动物";
            return false;
        }
        targetAnimal = animal;
        targetPos = animal.blockPosition();
        selectBestWeapon();
        return true;
    }

    @Override
    public boolean tick(AiBotEntity bot) {
        if (completed || failed) return true;

        if (targetAnimal == null || !targetAnimal.isAlive()) {
            LivingEntity next = findNearestAnimal();
            if (next == null) {
                completed = true;
                return true;
            }
            targetAnimal = next;
            targetPos = next.blockPosition();
        }
        if (targetPos == null) { completed = true; return true; }

        double distSq = this.bot.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

        if (distSq > 3.0 * 3.0) {
            this.bot.getNavigation().moveTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.0D);
            return false;
        }

        this.bot.getNavigation().stop();
        this.bot.doHurtTarget(targetAnimal);
        this.bot.swing(InteractionHand.MAIN_HAND);

        if (!targetAnimal.isAlive()) {
            targetAnimal = null;
            targetPos = null;
        }
        return false;
    }

    @Override
    public void stop(AiBotEntity bot) {
        this.bot.getNavigation().stop();
    }

    @Override
    public boolean isCompleted() { return completed; }
    @Override
    public boolean isFailed() { return failed; }
    @Override
    public String getFailReason() { return failReason; }

    private LivingEntity findNearestAnimal() {
        List<Animal> animals = bot.level().getEntitiesOfClass(Animal.class,
                bot.getBoundingBox().inflate(HUNT_RANGE),
                e -> e.isAlive());
        LivingEntity nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Animal animal : animals) {
            double dist = bot.distanceToSqr(animal);
            if (dist < minDist) {
                minDist = dist;
                nearest = animal;
            }
        }
        return nearest;
    }

    private void selectBestWeapon() {
        SimpleContainer inv = bot.getInventory();
        int selectedSlot = bot.getSelectedSlot();
        int bestSlot = -1;
        double bestDamage = 1.0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            double dmg = getAttackDamage(stack);
            if (dmg > bestDamage) {
                bestDamage = dmg;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) return;

        if (bestSlot > 8) {
            ItemStack weapon = inv.getItem(bestSlot);
            ItemStack current = inv.getItem(selectedSlot);
            inv.setItem(bestSlot, current);
            inv.setItem(selectedSlot, weapon);
        }
        bot.setItemSlot(EquipmentSlot.MAINHAND, inv.getItem(selectedSlot).copy());
    }

    private double getAttackDamage(ItemStack stack) {
        var attribs = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        for (var entry : attribs.entries()) {
            if (entry.getKey() == Attributes.ATTACK_DAMAGE) {
                return entry.getValue().getAmount();
            }
        }
        return 1.0;
    }
}
