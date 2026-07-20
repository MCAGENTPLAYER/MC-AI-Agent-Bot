package com.aibot.mod.entity.actions;

import com.aibot.mod.AiBotMod;
import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

public class FarmAction implements BotAction {
    private final AiBotEntity bot;
    private int farmCount = 0;
    private BlockPos targetBlockPos = null;
    private int navStuckTicks = 0;
    private double lastNavDist = 0;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = "";

    private static final int FARM_RANGE = 32;
    private static final int FARM_SCAN_Y_RANGE = 64;

    public FarmAction(AiBotEntity bot) {
        this.bot = bot;
    }

    @Override
    public boolean start(AiBotEntity bot) {
        completed = false;
        failed = false;
        failReason = "";
        farmCount = 0;
        targetBlockPos = null;
        navStuckTicks = 0;
        lastNavDist = 0;
        return true;
    }

    @Override
    public boolean tick(AiBotEntity bot) {
        AiBotMod.LOGGER.info("[FarmAction] tick called - completed={}, failed={}, targetPos={}, invFull={}", 
            completed, failed, targetBlockPos, bot.isInventoryFull());
        if (completed || failed) return true;

        if (bot.isInventoryFull()) {
            AiBotMod.LOGGER.info("[FarmAction] Inventory full - dropping items to make room");
            // 自动清空背包，确保农场可以继续
            var inv = bot.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    bot.spawnAtLocation(stack);
                    inv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                }
            }
            bot.setStatus("Cleared inventory for farming");
            AiBotMod.LOGGER.info("[FarmAction] Dropped all items, inventory cleared");
        }

        if (targetBlockPos == null) {
            targetBlockPos = findNextFarmAction();
            if (targetBlockPos == null) {
                String reason;
                if (!hasSeedsInInventory() && !hasMatureCropsNearby()) {
                    reason = "No seeds & no mature crops";
                } else if (!hasSeedsInInventory()) {
                    reason = "No seeds left";
                } else {
                    reason = "All farmland is planted";
                }
                AiBotMod.LOGGER.info("[FarmAction] Nothing to do at bot pos [{}, {}, {}]: {}", 
                    (int)bot.getX(), (int)bot.getY(), (int)bot.getZ(), reason);
                bot.setStatus(reason);
                completed = true;
                return true;
            }
            AiBotMod.LOGGER.info("[FarmAction] Found target at [{}, {}, {}] level crop={}", 
                targetBlockPos.getX(), targetBlockPos.getY(), targetBlockPos.getZ(),
                bot.level().getBlockState(targetBlockPos).getBlock());
        }

        BlockState targetState = bot.level().getBlockState(targetBlockPos);
        BlockPos below = targetBlockPos.below();
        BlockState belowState = bot.level().getBlockState(below);

        if (!targetState.isAir() && !isCropBlock(targetState)) {
            targetBlockPos = null;
            return false;
        }
        if (isCropBlock(targetState) && !isMatureCrop(targetState)) {
            targetBlockPos = null;
            return false;
        }
        if (targetState.isAir() && !isFarmland(belowState)) {
            targetBlockPos = null;
            return false;
        }

        double dist = bot.distanceToSqr(
            targetBlockPos.getX() + 0.5,
            targetBlockPos.getY() + 0.5,
            targetBlockPos.getZ() + 0.5);

        if (dist > 3.0 * 3.0) {
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
            bot.setStatus("Moving to farm... " + farmCount);
            return false;
        }

        navStuckTicks = 0;
        bot.getNavigation().stop();
        bot.getLookControl().setLookAt(
            targetBlockPos.getX() + 0.5,
            targetBlockPos.getY() + 0.5,
            targetBlockPos.getZ() + 0.5);

        if (targetState.isAir()) {
            if (!hasSeedsInInventory()) {
                bot.setStatus("No seeds left");
                completed = true;
                return true;
            }
            plantSeed(targetBlockPos);
        } else if (isCropBlock(targetState) && isMatureCrop(targetState)) {
            harvestCrop(targetBlockPos);

            if (bot.isInventoryFull()) {
                bot.setStatus("Inventory full! Farmed: " + farmCount);
                failed = true;
                failReason = "Inventory full";
                return true;
            }
        }

        targetBlockPos = null;
        return false;
    }

    private boolean isFarmland(BlockState state) {
        return state.is(Blocks.FARMLAND) || state.getBlock() instanceof FarmBlock;
    }

    private boolean isCropBlock(BlockState state) {
        return state.is(BlockTags.CROPS)
            || state.getBlock() instanceof CropBlock
            || state.getBlock() instanceof NetherWartBlock;
    }

    private boolean isMatureCrop(BlockState state) {
        var block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return state.getValue(CropBlock.AGE) >= crop.getMaxAge();
        }
        if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        }
        return false;
    }

    private boolean hasSeedsInInventory() {
        return !findSeedInInventory().isEmpty();
    }

    private boolean isSeedItem(Item item) {
        if (item instanceof BlockItem bi) {
            var block = bi.getBlock();
            return block instanceof CropBlock || block instanceof NetherWartBlock
                || block instanceof IPlantable;
        }
        return false;
    }

    private ItemStack findSeedInInventory() {
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty() && isSeedItem(stack.getItem())) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private BlockState getCropForSeed(Item seedItem) {
        if (seedItem instanceof BlockItem bi) {
            var block = bi.getBlock();
            if (block instanceof IPlantable plantable) {
                return plantable.getPlant(bot.level(), BlockPos.ZERO);
            }
            if (block instanceof CropBlock || block instanceof NetherWartBlock) {
                return block.defaultBlockState();
            }
        }
        return null;
    }

    private BlockPos findNextFarmAction() {
        BlockPos botPos = bot.blockPosition();
        AiBotMod.LOGGER.info("[FarmAction] Scanning from bot pos [{}, {}, {}]", botPos.getX(), botPos.getY(), botPos.getZ());
        boolean hasSeeds = hasSeedsInInventory();
        int range = FARM_RANGE;

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                for (int dy = -FARM_SCAN_Y_RANGE; dy <= FARM_SCAN_Y_RANGE; dy++) {
                    BlockPos pos = botPos.offset(dx, dy, dz);
                    BlockState state = bot.level().getBlockState(pos);

                    if (!isFarmland(state)) continue;

                    BlockPos above = pos.above();
                    BlockState aboveState = bot.level().getBlockState(above);

                    if (isCropBlock(aboveState) && isMatureCrop(aboveState)) {
                        return above;
                    }
                }
            }
        }

        if (hasSeeds) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dz = -range; dz <= range; dz++) {
                    for (int dy = -FARM_SCAN_Y_RANGE; dy <= FARM_SCAN_Y_RANGE; dy++) {
                        BlockPos pos = botPos.offset(dx, dy, dz);
                        BlockState state = bot.level().getBlockState(pos);

                        if (!isFarmland(state)) continue;

                        BlockPos above = pos.above();
                        BlockState aboveState = bot.level().getBlockState(above);

                        if (aboveState.isAir()) {
                            return above;
                        }
                    }
                }
            }
        }

        return null;
    }

    private void plantSeed(BlockPos cropPos) {
        ItemStack seedStack = findSeedInInventory();
        if (seedStack.isEmpty()) {
            bot.setStatus("No seeds left");
            completed = true;
            return;
        }

        BlockPos farmlandPos = cropPos.below();
        if (!isFarmland(bot.level().getBlockState(farmlandPos))) {
            targetBlockPos = null;
            return;
        }

        BlockState cropState = getCropForSeed(seedStack.getItem());
        if (cropState == null) {
            targetBlockPos = null;
            return;
        }

        if (bot.level() instanceof ServerLevel serverLevel) {
            var fakePlayer = FakePlayerFactory.getMinecraft(serverLevel);
            fakePlayer.setPos(bot.getX(), bot.getY(), bot.getZ());
            fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, seedStack.copy());

            BlockHitResult hitResult = new BlockHitResult(
                Vec3.atCenterOf(cropPos), Direction.UP, farmlandPos, false);
            fakePlayer.gameMode.useItemOn(fakePlayer, serverLevel,
                fakePlayer.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, hitResult);
        }

        seedStack.shrink(1);
        bot.swing(InteractionHand.MAIN_HAND);
        farmCount++;
        bot.setStatus("Planted! " + farmCount);
    }

    private void harvestCrop(BlockPos cropPos) {
        if (bot.level() instanceof ServerLevel serverLevel) {
            var fakePlayer = FakePlayerFactory.getMinecraft(serverLevel);
            fakePlayer.setPos(bot.getX(), bot.getY(), bot.getZ());

            BlockHitResult hitResult = new BlockHitResult(
                Vec3.atCenterOf(cropPos), Direction.UP, cropPos, false);
            fakePlayer.gameMode.useItemOn(fakePlayer, serverLevel,
                fakePlayer.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, hitResult);
        }
        bot.swing(InteractionHand.MAIN_HAND);
        farmCount++;
        bot.setStatus("Harvested! " + farmCount);
    }

    private boolean hasMatureCropsNearby() {
        BlockPos botPos = bot.blockPosition();
        for (int dx = -FARM_RANGE; dx <= FARM_RANGE; dx++) {
            for (int dz = -FARM_RANGE; dz <= FARM_RANGE; dz++) {
                for (int dy = -FARM_SCAN_Y_RANGE; dy <= FARM_SCAN_Y_RANGE; dy++) {
                    BlockPos pos = botPos.offset(dx, dy, dz);
                    BlockState state = bot.level().getBlockState(pos);
                    if (!isFarmland(state)) continue;
                    BlockPos above = pos.above();
                    BlockState aboveState = bot.level().getBlockState(above);
                    if (isCropBlock(aboveState) && isMatureCrop(aboveState)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String getName() {
        return "Farm";
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

    public int getFarmCount() {
        return farmCount;
    }
}
