package com.aibot.mod.entity.actions;

import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

public class ChopTreeAction implements BotAction {
    private final AiBotEntity bot;
    private int maxChopCount = 27;
    private int chopCount = 0;
    private int mineProgress = 0;
    private BlockPos targetBlockPos = null;
    private int navStuckTicks = 0;
    private double lastNavDist = 0;
    private int leafBreakTimer = 0;
    private final Set<BlockPos> excludedLogs = new HashSet<>();
    private boolean needsPickupAfterChop = false;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = "";
    private int procrastinationTimer = 0;
    private boolean isProcrastinating = false;
    private final Random random = new Random();

    // ===== 补种 =====
    private BlockPos replantPos = null;
    private boolean needsReplant = false;
    private int replantStuckTicks = 0;
    private Item replantSaplingType = null;
    private BlockPos pendingTreeBase = null;   // 待补种位置（刚砍的树底）
    private Item pendingSaplingType = null;    // 待补种树苗类型

    public ChopTreeAction(AiBotEntity bot) {
        this.bot = bot;
    }

    public ChopTreeAction(AiBotEntity bot, int maxCount) {
        this.bot = bot;
        this.maxChopCount = maxCount;
    }

    @Override
    public boolean start(AiBotEntity bot) {
        completed = false;
        failed = false;
        failReason = "";
        chopCount = 0;
        mineProgress = 0;
        targetBlockPos = null;
        navStuckTicks = 0;
        lastNavDist = 0;
        leafBreakTimer = 0;
        excludedLogs.clear();
        needsPickupAfterChop = false;
        replantPos = null;
        needsReplant = false;
        replantStuckTicks = 0;
        replantSaplingType = null;
        pendingTreeBase = null;
        pendingSaplingType = null;
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
                bot.setStatus("继续砍树...");
            }
            return false;
        }

        if (chopCount > 0 && random.nextDouble() < 0.02) {
            isProcrastinating = true;
            procrastinationTimer = random.nextInt(20) + 10;
            return false;
        }

        // ===== 补种阶段 =====
        if (needsReplant && replantPos != null) {
            return handleReplant();
        }

        if (chopCount >= maxChopCount) {
            bot.setStatus("Finished! " + chopCount + "/" + maxChopCount + " logs");
            completed = true;
            return true;
        }
        if (bot.isInventoryFull()) {
            bot.setStatus("Inventory full! " + chopCount + "/" + maxChopCount);
            failed = true;
            failReason = "Inventory full";
            return true;
        }

        if (needsPickupAfterChop) {
            BlockPos self = bot.blockPosition();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 3; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos p = self.offset(dx, dy, dz);
                        if (isLeafBlock(bot.level().getBlockState(p)))
                            bot.level().destroyBlock(p, true, bot);
                    }
                }
            }
            List<ItemEntity> drops = bot.level().getEntitiesOfClass(ItemEntity.class,
                new AABB(bot.blockPosition()).inflate(8.0D));
            drops.removeIf(e -> !e.isAlive() || e.getItem().isEmpty());
            if (!drops.isEmpty()) {
                ItemEntity nearest = drops.get(0);
                double minDist = bot.distanceToSqr(nearest.position());
                for (ItemEntity item : drops) {
                    double dist = bot.distanceToSqr(item.position());
                    if (dist < minDist) {
                        minDist = dist;
                        nearest = item;
                    }
                }
                bot.getNavigation().moveTo(nearest, 0.8D);
                bot.setStatus("Picking up drops... " + chopCount + "/" + maxChopCount);
                return false;
            }
            needsPickupAfterChop = false;
            // 砍完一棵树 + 捡完掉落 → 触发补种
            if (replantPos == null && pendingTreeBase != null && pendingSaplingType != null && hasSapling(pendingSaplingType)) {
                replantPos = pendingTreeBase;
                replantSaplingType = pendingSaplingType;
                needsReplant = true;
            }
            pendingTreeBase = null;
            pendingSaplingType = null;
            targetBlockPos = null;
        }

        if (!bot.getNavigation().isInProgress() || navStuckTicks > 20) {
            BlockPos self = bot.blockPosition();
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -2; dy <= 3; dy++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos p = self.offset(dx, dy, dz);
                        if (dx*dx + dy*dy + dz*dz > 9) continue;
                        if (isLeafBlock(bot.level().getBlockState(p)))
                            bot.level().destroyBlock(p, true, bot);
                    }
                }
            }
            if (targetBlockPos == null) {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dy = -2; dy <= 3; dy++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            BlockPos p = self.offset(dx, dy, dz);
                            if (p.equals(self)) continue;
                            if (dx*dx + dy*dy + dz*dz > 9) continue;
                            if (isLogBlock(bot.level().getBlockState(p))) {
                                targetBlockPos = p;
                                break;
                            }
                        }
                        if (targetBlockPos != null) break;
                    }
                    if (targetBlockPos != null) break;
                }
            }
            if (navStuckTicks > 20) navStuckTicks = 0;
        }

        if (targetBlockPos == null) {
            targetBlockPos = findNearestTreeBase();
            if (targetBlockPos == null) {
                bot.setStatus("No tree found");
                failed = true;
                failReason = "No tree found";
                return true;
            }
            return false;
        }

        BlockState targetState = bot.level().getBlockState(targetBlockPos);
        if (!isLogBlock(targetState)) {
            excludedLogs.add(targetBlockPos);
            targetBlockPos = findNearestTreeBase();
            if (targetBlockPos == null) {
                bot.setStatus("No more trees");
                failed = true;
                failReason = "No more trees";
                return true;
            }
            return false;
        }

        double horizontalDist = Math.sqrt(
            Math.pow(targetBlockPos.getX() + 0.5 - bot.getX(), 2) +
            Math.pow(targetBlockPos.getZ() + 0.5 - bot.getZ(), 2));

        if (leafBreakTimer++ >= 10) {
            BlockPos center = targetBlockPos;
            ItemStack held = bot.getMainHandItem();
            if (!held.isEmpty()) bot.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 3; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos p = center.offset(dx, dy, dz);
                        if (isLeafBlock(bot.level().getBlockState(p)))
                            bot.level().destroyBlock(p, true, bot);
                    }
                }
            }
            if (!held.isEmpty()) bot.setItemSlot(EquipmentSlot.MAINHAND, held);
            leafBreakTimer = 0;
        }

        if (horizontalDist > 2.0) {
            if (horizontalDist > 64.0) {
                targetBlockPos = findNearestTreeBase();
                return false;
            }

            boolean started = bot.getNavigation().moveTo(
                targetBlockPos.getX() + 0.5, targetBlockPos.getY() - 1, targetBlockPos.getZ() + 0.5, 1.0D);
            if (!started) {
                navStuckTicks++;
                if (navStuckTicks > 20) {
                    targetBlockPos = findNearestTreeBase();
                    navStuckTicks = 0;
                }
                return false;
            }

            if (horizontalDist < lastNavDist - 0.1) {
                navStuckTicks = 0;
            } else {
                navStuckTicks++;
                if (navStuckTicks > 100) {
                    targetBlockPos = findNearestTreeBase();
                    navStuckTicks = 0;
                }
            }
            lastNavDist = horizontalDist;
            bot.setStatus("Moving to tree... " + chopCount + "/" + maxChopCount);
            return false;
        }

        navStuckTicks = 0;
        bot.getNavigation().stop();
        bot.getLookControl().setLookAt(
            targetBlockPos.getX() + 0.5, targetBlockPos.getY() + 0.5, targetBlockPos.getZ() + 0.5);

        if (!canReachBlock(targetBlockPos)) {
            excludedLogs.add(targetBlockPos);
            targetBlockPos = findNearestTreeBase();
            if (targetBlockPos == null) {
                bot.setStatus("No more trees");
                failed = true;
                failReason = "No more trees";
                return true;
            }
            return false;
        }

        equipBestAxe();
        bot.swing(InteractionHand.MAIN_HAND);
        mineProgress++;

        if (mineProgress >= 20) {
            equipBestAxe();

            // 砍树前记录原木类型和树底位置（用于补种）
            pendingTreeBase = findTreeBaseForReplant(targetBlockPos);
            pendingSaplingType = getSaplingForLog(bot.level().getBlockState(targetBlockPos));

            int destroyed = destroyTreeChain(targetBlockPos);
            chopCount += destroyed;
            ItemStack axe = bot.getMainHandItem();
            if (!axe.isEmpty()) {
                axe.hurtAndBreak(destroyed, bot, (e) -> {});
            }
            bot.setStatus("Chopped! " + chopCount + "/" + maxChopCount);
            mineProgress = 0;

            if (chopCount >= maxChopCount) {
                bot.setStatus("Finished! " + chopCount + "/" + maxChopCount + " logs");
                completed = true;
                return true;
            }
            if (bot.isInventoryFull()) {
                bot.setStatus("Inventory full! " + chopCount + "/" + maxChopCount);
                failed = true;
                failReason = "Inventory full";
                return true;
            }

            List<ItemEntity> drops = bot.level().getEntitiesOfClass(ItemEntity.class,
                new AABB(targetBlockPos).inflate(8.0D));
            drops.removeIf(e -> !e.isAlive() || e.getItem().isEmpty());
            if (!drops.isEmpty()) {
                needsPickupAfterChop = true;
                ItemEntity nearest = drops.get(0);
                double minDist = bot.distanceToSqr(nearest.position());
                for (ItemEntity item : drops) {
                    double dist = bot.distanceToSqr(item.position());
                    if (dist < minDist) {
                        minDist = dist;
                        nearest = item;
                    }
                }
                bot.getNavigation().moveTo(nearest, 0.8D);
                bot.setStatus("Picking up drops... " + chopCount + "/" + maxChopCount);
                targetBlockPos = null;
                return false;
            }

            targetBlockPos = null;
        } else {
            float progress = (float) mineProgress / 20.0F;
            bot.setStatus("Chopping... " + (int)(progress * 100) + "% " + chopCount + "/" + maxChopCount);
        }

        return false;
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

    private BlockPos findNearestLog() {
        BlockPos playerPos = bot.blockPosition();
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        int[] ranges = {8, 16, 32};

        for (int range : ranges) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dy = -10; dy <= 20; dy++) {
                    for (int dz = -range; dz <= range; dz++) {
                        BlockPos pos = playerPos.offset(dx, dy, dz);
                        if (excludedLogs.contains(pos)) continue;
                        BlockState state = bot.level().getBlockState(pos);
                        if (isLogBlock(state)) {
                            double dist = pos.distSqr(playerPos);
                            if (dist < minDist) {
                                minDist = dist;
                                nearest = pos;
                            }
                        }
                    }
                }
            }
            if (nearest != null) break;
        }
        return nearest;
    }

    private int destroyTreeChain(BlockPos start) {
        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        int count = 0;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (isLogBlock(bot.level().getBlockState(pos))) {
                bot.level().destroyBlock(pos, true, bot);
                count++;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = pos.offset(dx, dy, dz);
                        if (!visited.contains(neighbor) && isLogBlock(bot.level().getBlockState(neighbor))) {
                            queue.add(neighbor);
                            visited.add(neighbor);
                        }
                    }
                }
            }
        }
        return count;
    }

    private boolean isLeafBlock(BlockState state) {
        return state.is(BlockTags.LEAVES);
    }

    private BlockPos findNearestTreeBase() {
        BlockPos nearestLog = findNearestLog();
        if (nearestLog == null) return null;

        Queue<BlockPos> queue = new LinkedList<>();
        Set<BlockPos> visited = new HashSet<>();
        queue.add(nearestLog);
        visited.add(nearestLog);
        BlockPos lowest = nearestLog;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();

            if (!bot.level().getBlockState(pos.below()).isAir()) {
                return pos;
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = pos.offset(dx, dy, dz);
                        if (!visited.contains(neighbor) && isLogBlock(bot.level().getBlockState(neighbor))) {
                            visited.add(neighbor);
                            queue.add(neighbor);
                            if (neighbor.getY() < lowest.getY()) lowest = neighbor;
                        }
                    }
                }
            }
        }
        return lowest;
    }

    private boolean isLogBlock(BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    private void equipBestAxe() {
        ItemStack current = bot.getItemInHand(InteractionHand.MAIN_HAND);
        if (!current.isEmpty() && (current.is(Items.WOODEN_AXE) || current.is(Items.STONE_AXE) || 
            current.is(Items.IRON_AXE) || current.is(Items.GOLDEN_AXE) || 
            current.is(Items.DIAMOND_AXE) || current.is(Items.NETHERITE_AXE))) {
            return;
        }

        ItemStack bestAxe = bot.findAndRemoveTool(
                Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.GOLDEN_AXE,
                Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE);

        if (!bestAxe.isEmpty()) {
            bot.setItemInHand(InteractionHand.MAIN_HAND, bestAxe);
        }
    }

    // ========== 补种 ==========

    /** BFS 查找树底部（最低的原木方块坐标），砍完后在此位置种树苗 */
    private BlockPos findTreeBaseForReplant(BlockPos logPos) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        queue.add(logPos);
        visited.add(logPos);
        BlockPos lowest = logPos;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (pos.getY() < lowest.getY()) lowest = pos;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = pos.offset(dx, dy, dz);
                        if (!visited.contains(neighbor) && isLogBlock(bot.level().getBlockState(neighbor))) {
                            visited.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        return lowest;
    }

    /** 原木类型 → 对应树苗（先匹配原版，再通过注册名推导模组树苗） */
    private Item getSaplingForLog(BlockState logState) {
        // 原版硬编码映射
        if (logState.is(Blocks.OAK_LOG)) return Items.OAK_SAPLING;
        if (logState.is(Blocks.BIRCH_LOG)) return Items.BIRCH_SAPLING;
        if (logState.is(Blocks.SPRUCE_LOG)) return Items.SPRUCE_SAPLING;
        if (logState.is(Blocks.JUNGLE_LOG)) return Items.JUNGLE_SAPLING;
        if (logState.is(Blocks.ACACIA_LOG)) return Items.ACACIA_SAPLING;
        if (logState.is(Blocks.DARK_OAK_LOG)) return Items.DARK_OAK_SAPLING;
        if (logState.is(Blocks.CHERRY_LOG)) return Items.CHERRY_SAPLING;
        if (logState.is(Blocks.MANGROVE_LOG)) return Items.MANGROVE_PROPAGULE;

        // 模组树：rubber_log → rubber_sapling
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(logState.getBlock());
        if (key != null && key.getPath().endsWith("_log")) {
            String saplingPath = key.getPath().substring(0, key.getPath().length() - 4) + "_sapling";
            ResourceLocation saplingKey = new ResourceLocation(key.getNamespace(), saplingPath);
            Item item = ForgeRegistries.ITEMS.getValue(saplingKey);
            if (item != null && item != Items.AIR) return item;
        }
        return null;
    }

    /** 检查背包是否有指定树苗 */
    private boolean hasSapling(Item saplingType) {
        if (saplingType == null) return false;
        SimpleContainer inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(saplingType)) return true;
        }
        return false;
    }

    /** 从背包取出树苗 ItemStack */
    private ItemStack findSaplingStack(Item saplingType) {
        if (saplingType == null) return ItemStack.EMPTY;
        SimpleContainer inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(saplingType)) return stack;
        }
        return ItemStack.EMPTY;
    }

    /** 补种处理：走到树底位置 → 种下树苗 */
    private boolean handleReplant() {
        if (replantPos == null) {
            needsReplant = false;
            return false;
        }

        double dist = bot.distanceToSqr(replantPos.getX() + 0.5, replantPos.getY() + 0.5, replantPos.getZ() + 0.5);

        if (dist > 2.5) {
            boolean started = bot.getNavigation().moveTo(
                replantPos.getX() + 0.5, replantPos.getY(), replantPos.getZ() + 0.5, 1.0D);
            if (!started) {
                replantStuckTicks++;
                if (replantStuckTicks > 40) {
                    needsReplant = false;
                    replantPos = null;
                    replantSaplingType = null;
                    replantStuckTicks = 0;
                }
                return false;
            }
            if (dist < 4.0) {
                bot.getNavigation().stop();
            }
            bot.setStatus("去补种...");
            return false;
        }

        // 到达位置，种树苗
        bot.getNavigation().stop();
        ItemStack saplingStack = findSaplingStack(replantSaplingType);
        if (!saplingStack.isEmpty() && saplingStack.getItem() instanceof BlockItem blockItem) {
            if (bot.level().getBlockState(replantPos).isAir()) {
                bot.level().setBlock(replantPos, blockItem.getBlock().defaultBlockState(), 3);
                saplingStack.shrink(1);
                bot.setStatus("补种完成");
            }
        }

        needsReplant = false;
        replantPos = null;
        replantSaplingType = null;
        replantStuckTicks = 0;
        return false;
    }

    @Override
    public String getName() {
        return "Chop Tree";
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

    public int getChopCount() {
        return chopCount;
    }

    public int getMaxChopCount() {
        return maxChopCount;
    }
}
