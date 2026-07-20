package com.aibot.mod.entity.actions;

import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

public class MineTunnelAction implements BotAction {
    private final AiBotEntity bot;
    private int maxMineCount = 64;
    private int mineCount = 0;
    private int mineProgress = 0;
    private int tunnelStep = 0;
    private Direction tunnelDirection = Direction.NORTH;
    private final Queue<BlockPos> currentMiningBlocks = new LinkedList<>();
    private BlockPos tunnelNextPos = null;
    private boolean isOreMode = false;
    private BlockPos oreTarget = null;
    private int oreScanTimer = 0;
    private boolean resumeEntry = false;
    private int navStuckTicks = 0;
    private double lastNavDist = 0;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = "";
    private int procrastinationTimer = 0;
    private boolean isProcrastinating = false;
    private final Random random = new Random();

    public MineTunnelAction(AiBotEntity bot) {
        this.bot = bot;
        this.tunnelDirection = bot.getDirection();
        if (this.tunnelDirection.getAxis() == Direction.Axis.Y) {
            this.tunnelDirection = Direction.NORTH;
        }
        oreScanTimer = 20;
    }

    public MineTunnelAction(AiBotEntity bot, int maxCount) {
        this(bot);
        this.maxMineCount = maxCount;
    }

    @Override
    public boolean start(AiBotEntity bot) {
        completed = false;
        failed = false;
        failReason = "";
        mineCount = 0;
        mineProgress = 0;
        tunnelStep = 0;
        currentMiningBlocks.clear();
        tunnelNextPos = null;
        isOreMode = false;
        oreTarget = null;
        resumeEntry = false;
        navStuckTicks = 0;
        lastNavDist = 0;
        return true;
    }

    @Override
    public boolean tick(AiBotEntity bot) {
        if (completed || failed) return true;

        if (isProcrastinating) {
            procrastinationTimer--;
            bot.setStatus("休息一下...");
            if (procrastinationTimer <= 0) {
                isProcrastinating = false;
                bot.setStatus("继续挖矿...");
            }
            return false;
        }

        if (mineCount > 0 && random.nextDouble() < 0.015) {
            isProcrastinating = true;
            procrastinationTimer = random.nextInt(30) + 10;
            return false;
        }

        if (resumeEntry) {
            if (tunnelNextPos != null) {
                double distSqr = bot.distanceToSqr(
                    tunnelNextPos.getX() + 0.5, tunnelNextPos.getY() + 0.5, tunnelNextPos.getZ() + 0.5);
                if (distSqr > 0.5 * 0.5) {
                    if (navigateToPos(tunnelNextPos)) {
                        resumeEntry = false;
                        tunnelNextPos = null;
                        bot.setStatus("Entering tunnel...");
                    } else {
                        bot.setStatus("Returning to tunnel...");
                        return false;
                    }
                } else {
                    resumeEntry = false;
                    tunnelNextPos = null;
                    bot.setStatus("Entering tunnel...");
                }
            } else {
                resumeEntry = false;
            }
        }

        if (mineCount >= maxMineCount) {
            bot.setStatus("Finished! Mined " + mineCount + "/" + maxMineCount + " blocks");
            completed = true;
            return true;
        }
        if (bot.isInventoryFull()) {
            bot.setStatus("Inventory full! Mined: " + mineCount);
            failed = true;
            failReason = "Inventory full";
            return true;
        }

        if (isOreMode) {
            handleOreMining();
            return completed || failed;
        }

        if (tunnelStep > 0) {
            oreScanTimer++;
            if (oreScanTimer >= 20) {
                oreScanTimer = 0;
                BlockPos ore = scanForOresOnSameLevel();
                if (ore != null) {
                    isOreMode = true;
                    oreTarget = ore;
                    currentMiningBlocks.clear();
                    tunnelNextPos = null;
                    bot.setStatus("Ore found! Digging toward it...");
                    calcHorizontalTunnelBlocks();
                    return false;
                }
            }
        }

        if (tunnelNextPos != null) {
            if (navigateToPos(tunnelNextPos)) {
                tunnelNextPos = null;
                tunnelStep++;
                bot.setStatus("Tunnel step " + tunnelStep);
            } else {
                bot.setStatus("Moving to next position...");
                return false;
            }
        }

        if (currentMiningBlocks.isEmpty()) {
            addMiningBlocksForStep(tunnelStep, bot.blockPosition());
            if (currentMiningBlocks.isEmpty()) {
                bot.setStatus("Can't mine further");
                failed = true;
                failReason = "Can't mine further";
                return true;
            }
            bot.setStatus("Mining tunnel step " + tunnelStep + "... " + mineCount + "/" + maxMineCount);
        }

        mineNextBlockInQueue();

        if (currentMiningBlocks.isEmpty() && tunnelNextPos == null) {
            if (tunnelStep == 0) {
                tunnelStep++;
            } else {
                tunnelNextPos = calcNextPos();
            }
        }

        return completed || failed;
    }

    private void addMiningBlocksForStep(int step, BlockPos botPos) {
        currentMiningBlocks.clear();

        if (step == 0) {
            currentMiningBlocks.add(botPos.below());
            return;
        }

        BlockPos front = botPos.relative(tunnelDirection);

        switch (step) {
            case 1 -> {
                currentMiningBlocks.add(front);
                currentMiningBlocks.add(front.below());
            }
            case 2 -> {
                currentMiningBlocks.add(front);
                currentMiningBlocks.add(front.below());
                currentMiningBlocks.add(front.below(2));
            }
            default -> {
                int off = step - 2;
                currentMiningBlocks.add(front.below(off));
                currentMiningBlocks.add(front.below(off + 1));
                currentMiningBlocks.add(front.below(off + 2));
            }
        }
    }

    private BlockPos calcNextPos() {
        BlockPos pos = bot.blockPosition();
        if (tunnelStep == 0) return pos.below();
        if (tunnelStep == 1 || tunnelStep == 2) return pos.relative(tunnelDirection);
        return pos.relative(tunnelDirection).below();
    }

    private boolean navigateToPos(BlockPos pos) {
        double dist = bot.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (dist <= 0.5 * 0.5) return true;

        if (bot.getNavigation().isInProgress()) {
            if (bot.getNavigation().isDone()) {
                double distAfter = bot.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distAfter <= 2.5 * 2.5) return true;
                bot.getNavigation().stop();
            }
            if (dist < lastNavDist - 0.05) {
                navStuckTicks = 0;
            } else {
                navStuckTicks++;
                if (navStuckTicks > 100) {
                    navStuckTicks = 0;
                    bot.getNavigation().stop();
                    return true;
                }
            }
            lastNavDist = dist;
            return false;
        }

        boolean started = bot.getNavigation().moveTo(
            pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0D);

        if (!started) {
            navStuckTicks++;
            if (navStuckTicks > 20) {
                navStuckTicks = 0;
                return true;
            }
            return false;
        }
        lastNavDist = dist;
        return false;
    }

    private BlockPos scanForOresOnSameLevel() {
        BlockPos botPos = bot.blockPosition();
        int botY = botPos.getY();
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        int range = 32;

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                if (dx * dx + dz * dz > range * range) continue;
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos pos = new BlockPos(botPos.getX() + dx, botY + dy, botPos.getZ() + dz);
                    BlockState state = bot.level().getBlockState(pos);
                    if (isOreBlock(state)) {
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

    private void calcHorizontalTunnelBlocks() {
        currentMiningBlocks.clear();
        if (oreTarget == null) return;

        BlockPos botPos = bot.blockPosition();
        int dx = oreTarget.getX() - botPos.getX();
        int dz = oreTarget.getZ() - botPos.getZ();

        Direction mineDir;
        if (Math.abs(dx) > Math.abs(dz)) {
            mineDir = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            mineDir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        } else {
            oreTarget = null;
            return;
        }

        tunnelDirection = mineDir;
        BlockPos front = botPos.relative(mineDir);
        if (bot.level().getBlockState(front).isAir()) {
            front = front.below();
        }
        currentMiningBlocks.add(front);
        currentMiningBlocks.add(front.above());
    }

    private void handleOreMining() {
        if (oreTarget == null) {
            isOreMode = false;
            tunnelStep = 0;
            tunnelNextPos = null;
            currentMiningBlocks.clear();
            bot.setStatus("Lost ore, returning to tunnel");
            return;
        }

        double distToOre = bot.distanceToSqr(
            oreTarget.getX() + 0.5, oreTarget.getY() + 0.5, oreTarget.getZ() + 0.5);

        if (distToOre <= 4.5 * 4.5) {
            int mined = destroyOreVein(oreTarget);
            mineCount += mined;
            bot.setStatus("Vein mined! " + mined + " ores, total " + mineCount);

            if (mineCount >= maxMineCount || bot.isInventoryFull()) {
                bot.setStatus(mineCount >= maxMineCount ?
                    "Finished! " + mineCount + "/" + maxMineCount : "Inventory full!");
                completed = mineCount >= maxMineCount;
                failed = !completed;
                if (failed) failReason = "Inventory full";
                return;
            }

            isOreMode = false;
            oreTarget = null;
            tunnelStep = 0;
            tunnelNextPos = null;
            currentMiningBlocks.clear();
            bot.setStatus("Returning to tunnel mining...");
            return;
        }

        if (tunnelNextPos != null) {
            if (navigateToPos(tunnelNextPos)) {
                tunnelNextPos = null;
                calcHorizontalTunnelBlocks();
                if (currentMiningBlocks.isEmpty()) {
                    isOreMode = false;
                    oreTarget = null;
                }
            } else {
                bot.setStatus("Moving toward ore...");
                return;
            }
        }

        if (currentMiningBlocks.isEmpty()) {
            calcHorizontalTunnelBlocks();
            if (currentMiningBlocks.isEmpty()) {
                isOreMode = false;
                oreTarget = null;
                return;
            }
        }

        mineNextBlockInQueue();

        if (currentMiningBlocks.isEmpty() && tunnelNextPos == null) {
            tunnelNextPos = bot.blockPosition().relative(tunnelDirection);
        }
    }

    private int destroyOreVein(BlockPos start) {
        BlockState startState = bot.level().getBlockState(start);
        if (!isOreBlock(startState)) return 0;

        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();
        var targetBlock = startState.getBlock();
        queue.add(start);
        visited.add(start);
        int count = 0;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            BlockState state = bot.level().getBlockState(pos);
            if (state.is(targetBlock) && isOreBlock(state)) {
                bot.level().destroyBlock(pos, true, bot);
                count++;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = pos.offset(dx, dy, dz);
                        if (!visited.contains(neighbor) && bot.level().getBlockState(neighbor).is(targetBlock)) {
                            queue.add(neighbor);
                            visited.add(neighbor);
                        }
                    }
                }
            }
        }
        return count;
    }

    private void mineNextBlockInQueue() {
        if (currentMiningBlocks.isEmpty()) return;

        BlockPos target = currentMiningBlocks.peek();
        BlockState state = bot.level().getBlockState(target);

        if (state.isAir()) {
            currentMiningBlocks.poll();
            return;
        }

        double dist = bot.distanceToSqr(
            target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        if (dist > 4.5 * 4.5) {
            boolean started = bot.getNavigation().moveTo(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0D);
            if (!started || dist > 64.0 * 64.0) {
                currentMiningBlocks.poll();
            }
            return;
        }

        if (!canReachBlock(target)) {
            currentMiningBlocks.poll();
            return;
        }

        navStuckTicks = 0;
        bot.getNavigation().stop();
        bot.getLookControl().setLookAt(
            target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        equipBestPickaxe();
        bot.swing(InteractionHand.MAIN_HAND);
        mineProgress++;

        if (mineProgress >= 20) {
            bot.level().destroyBlock(target, true, bot);
            mineCount++;
            mineProgress = 0;
            currentMiningBlocks.poll();

            checkAndClearFallingBlocks(target);

            ItemStack pickaxe = bot.getMainHandItem();
            if (!pickaxe.isEmpty()) {
                pickaxe.hurtAndBreak(1, bot, (e) -> {});
            }

            bot.setStatus("Mining... " + mineCount + "/" + maxMineCount);
        } else {
            float pct = (float) mineProgress / 20.0F;
            bot.setStatus("Mining... " + (int) (pct * 100) + "% " + mineCount + "/" + maxMineCount);
        }
    }

    private boolean canReachBlock(BlockPos pos) {
        double horizontalDist = Math.sqrt(
            Math.pow(pos.getX() + 0.5 - bot.getX(), 2) + 
            Math.pow(pos.getZ() + 0.5 - bot.getZ(), 2));
        double verticalDist = Math.abs(pos.getY() + 0.5 - (bot.getY() + bot.getBbHeight() / 2));
        double reach = 5.0D;
        double maxVerticalReach = 7.0D;
        return horizontalDist <= reach && verticalDist <= maxVerticalReach;
    }

    private boolean isGravityBlock(BlockState state) {
        return state.getBlock() instanceof FallingBlock;
    }

    private void checkAndClearFallingBlocks(BlockPos minedPos) {
        for (int i = 1; i <= 5; i++) {
            BlockPos above = minedPos.above(i);
            BlockState state = bot.level().getBlockState(above);
            if (state.isAir()) break;
            if (isGravityBlock(state)) {
                ((LinkedList<BlockPos>) currentMiningBlocks).addFirst(above);
            }
        }
    }

    private boolean isOreBlock(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.IRON_ORE) ||
               state.is(Blocks.COPPER_ORE) || state.is(Blocks.GOLD_ORE) ||
               state.is(Blocks.LAPIS_ORE) || state.is(Blocks.REDSTONE_ORE) ||
               state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.EMERALD_ORE);
    }

    private void equipBestPickaxe() {
        ItemStack current = bot.getItemInHand(InteractionHand.MAIN_HAND);
        if (!current.isEmpty() && (current.is(Items.WOODEN_PICKAXE) || current.is(Items.STONE_PICKAXE) || 
            current.is(Items.IRON_PICKAXE) || current.is(Items.GOLDEN_PICKAXE) || 
            current.is(Items.DIAMOND_PICKAXE) || current.is(Items.NETHERITE_PICKAXE))) {
            return;
        }

        ItemStack bestPickaxe = bot.findAndRemoveTool(
                Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.GOLDEN_PICKAXE,
                Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE);

        if (!bestPickaxe.isEmpty()) {
            bot.setItemInHand(InteractionHand.MAIN_HAND, bestPickaxe);
        }
    }

    @Override
    public String getName() {
        return "Mine Tunnel";
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

    public int getMineCount() {
        return mineCount;
    }

    public int getMaxMineCount() {
        return maxMineCount;
    }
}
