package com.aibot.mod.entity.actions;

import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 交互动作 — 找到目标方块/实体 → 导航过去 → 执行右键交互
 */
public class InteractAction implements BotAction {
    private final AiBotEntity bot;
    private final String targetName;
    private final String heldItem;

    private int phase = 0; // 0=init, 1=navigate, 2=interact, 3=done
    private BlockPos targetBlock = null;
    private net.minecraft.world.entity.Entity targetEntity = null;
    private boolean isBlock = true;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = "";
    private int navStuck = 0;

    private static final int SEARCH_RANGE = 32;

    public InteractAction(AiBotEntity bot, String targetName, String heldItem) {
        this.bot = bot;
        this.targetName = targetName;
        this.heldItem = heldItem;
    }

    @Override
    public String getName() { return "interact"; }

    @Override
    public boolean start(AiBotEntity bot) {
        completed = false;
        failed = false;
        phase = 0;
        targetBlock = null;
        targetEntity = null;
        isBlock = true;
        navStuck = 0;

        if (targetName == null || targetName.isEmpty()) {
            failed = true;
            failReason = "未指定交互目标";
            return false;
        }
        return true;
    }

    @Override
    public boolean tick(AiBotEntity bot) {
        if (completed || failed) return true;

        switch (phase) {
            case 0 -> tickInit();
            case 1 -> tickNavigate();
            case 2 -> tickInteract();
            case 3 -> { completed = true; return true; }
        }
        return false;
    }

    private void tickInit() {
        if (!(bot.level() instanceof ServerLevel sl)) { phase = 3; return; }

        // 先找方块
        String search = targetName.toLowerCase();
        BlockPos found = scanBlock(search);
        if (found != null) {
            targetBlock = found;
            isBlock = true;
        } else {
            // 再找实体
            var entity = scanEntity(search);
            if (entity != null) {
                targetEntity = entity;
                targetBlock = entity.blockPosition();
                isBlock = false;
            } else {
                failed = true;
                failReason = "找不到目标: " + targetName;
                phase = 3;
                return;
            }
        }

        // 装备物品
        if (heldItem != null && !heldItem.isEmpty()) {
            equipItem(heldItem);
        }

        phase = 1;
    }

    private void tickNavigate() {
        if (targetBlock == null) { phase = 3; return; }

        double distSq = bot.distanceToSqr(targetBlock.getX() + 0.5, targetBlock.getY() + 0.5, targetBlock.getZ() + 0.5);
        if (distSq > 4.0 * 4.0) {
            bot.getNavigation().moveTo(targetBlock.getX() + 0.5, targetBlock.getY(), targetBlock.getZ() + 0.5, 1.0D);
            return;
        }
        bot.getNavigation().stop();
        phase = 2;
    }

    private void tickInteract() {
        if (!(bot.level() instanceof ServerLevel sl)) { phase = 3; return; }

        boolean success = false;
        try {
            FakePlayer fp = FakePlayerFactory.getMinecraft(sl);
            fp.setItemInHand(InteractionHand.MAIN_HAND, bot.getMainHandItem().copy());
            fp.setPos(bot.getX(), bot.getY(), bot.getZ());

            if (isBlock && targetBlock != null) {
                BlockState state = sl.getBlockState(targetBlock);
                Vec3 hitVec = state.getShape(sl, targetBlock).bounds().getCenter().add(Vec3.atLowerCornerOf(targetBlock));
                BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, targetBlock, false);
                success = fp.gameMode.useItemOn(fp, sl, fp.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit).consumesAction();
            } else if (!isBlock && targetEntity != null && targetEntity.isAlive()) {
                success = targetEntity.interact(fp, InteractionHand.MAIN_HAND).consumesAction();
            }
        } catch (Exception ignored) {}

        // 消耗物品
        if (success && heldItem != null && !heldItem.isEmpty()) {
            ItemStack mainHand = bot.getMainHandItem();
            if (!mainHand.isEmpty()) {
                mainHand.shrink(1);
                if (mainHand.isEmpty()) {
                    bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                }
            }
        }
        phase = 3;
        if (!success && failReason.isEmpty()) failReason = "交互失败";
    }

    @Override
    public void stop(AiBotEntity bot) {
        bot.getNavigation().stop();
        phase = 3;
    }

    @Override
    public boolean isCompleted() { return completed; }
    @Override
    public boolean isFailed() { return failed; }
    @Override
    public String getFailReason() { return failReason; }

    // ========== 辅助方法 ==========

    private BlockPos scanBlock(String name) {
        BlockPos botPos = bot.blockPosition();
        BlockPos closest = null;
        double minDist = Double.MAX_VALUE;
        for (int dx = -SEARCH_RANGE; dx <= SEARCH_RANGE; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -SEARCH_RANGE; dz <= SEARCH_RANGE; dz++) {
                    BlockPos pos = botPos.offset(dx, dy, dz);
                    BlockState state = bot.level().getBlockState(pos);
                    var key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                    if (key != null) {
                        String path = key.getPath().toLowerCase();
                        String display = state.getBlock().getName().getString().toLowerCase();
                        if (path.equals(name) || path.contains(name) || display.contains(name)) {
                            double dist = bot.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
                            if (dist < minDist) { minDist = dist; closest = pos.immutable(); }
                        }
                    }
                }
            }
        }
        return closest;
    }

    private net.minecraft.world.entity.Entity scanEntity(String name) {
        var entities = bot.level().getEntities(bot, bot.getBoundingBox().inflate(SEARCH_RANGE));
        net.minecraft.world.entity.Entity closest = null;
        double minDist = Double.MAX_VALUE;
        for (var e : entities) {
            if (e == bot) continue;
            var key = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
            if (key == null) continue;
            String path = key.getPath().toLowerCase();
            String display = e.getName().getString().toLowerCase();
            if (path.equals(name) || path.contains(name) || display.contains(name)) {
                double dist = bot.distanceToSqr(e.position());
                if (dist < minDist) { minDist = dist; closest = e; }
            }
        }
        return closest;
    }

    private void equipItem(String itemName) {
        ItemStack held = bot.getMainHandItem();
        if (!held.isEmpty() && matchesItem(held, itemName)) return;

        var inv = bot.getInventory();
        int foundSlot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && matchesItem(stack, itemName)) {
                foundSlot = i;
                break;
            }
        }
        if (foundSlot < 0) return;

        ItemStack target = inv.getItem(foundSlot);
        if (foundSlot < 9) {
            // In hotbar - set selected slot
            // Can't directly set selectedSlot from here; swap with main hand
            bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, target.copy());
        } else {
            // Swap with main hand slot (slot 0 in hotbar)
            ItemStack current = inv.getItem(0);
            inv.setItem(0, target.copy());
            inv.setItem(foundSlot, current.isEmpty() ? ItemStack.EMPTY : current.copy());
            if (!current.isEmpty()) current.setCount(0);
            target.setCount(0);
            bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, inv.getItem(0).copy());
        }
    }

    private boolean matchesItem(ItemStack stack, String name) {
        String search = name.toLowerCase();
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) return false;
        return key.toString().equalsIgnoreCase(search)
            || key.getPath().equalsIgnoreCase(search)
            || key.getPath().contains(search)
            || stack.getHoverName().getString().toLowerCase().contains(search);
    }
}
