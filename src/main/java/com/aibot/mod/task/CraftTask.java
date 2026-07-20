package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * 合成任务 — 自动解析配方、收集材料、执行合成
 * <p>
 * 材料优先级：背包 → 箱子 → 请求玩家
 * 不支持主动挖矿/砍树，只消耗已有资源或向玩家索取。
 * AI 只需要输入目标物品和数量，引擎自动完成全套流程。
 * <p>
 * 设计类似 CookTask，使用状态机驱动：
 * RESOLVE → COLLECT → CRAFT_RUN → DONE/FAILED
 */
public class CraftTask extends BaseTask {

    // ==================== 主状态 ====================
    private enum MainState { RESOLVE, COLLECT, CRAFT_RUN, DONE, FAILED }

    // ==================== 收集子状态 ====================
    private enum CollectPhase {
        START,
        CHECK_INVENTORY,
        SCAN_CHESTS,
        GOTO_CHEST,
        TAKE_CHEST,
        ASK_PLAYER,
        WAIT_DROPS
    }

    // ==================== 配方节点（用于解析依赖树） ====================
    static class CraftStep {
        String itemId;
        String displayName;
        int countNeeded;              // 经过配方缩放后需要的数量
        boolean isRawMaterial;        // true = 不可再分解（无配方的基础材料）
        Recipe<?> recipe;             // 合成配方（raw material 时为 null）
        List<CraftStep> ingredients = new ArrayList<>();

        CraftStep(String itemId, int countNeeded) {
            this.itemId = itemId;
            this.countNeeded = countNeeded;
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
            this.displayName = item != null ? item.getName(new ItemStack(item)).getString() : itemId;
        }
    }

    // ==================== 输入参数 ====================
    private final String targetItemId;
    private final int targetCount;

    // ==================== 运行时状态 ====================
    private MainState mainState = MainState.RESOLVE;
    private CollectPhase collectPhase = CollectPhase.START;
    private int collectIndex = 0;
    private int craftIndex = 0;

    private CraftStep rootStep;
    private List<CraftStep> craftPlan = new ArrayList<>();
    private List<CraftStep> missingMaterials = new ArrayList<>();

    private BlockPos chestPos = null;
    private ServerPlayer targetPlayer = null;
    private int waitTicks = 0;
    private double[] cachedStandPos = null;
    private Map<String, Integer> inventorySnapshot = new HashMap<>();
    private ServerLevel cachedLevel = null;
    private List<String> allRawMaterials = new ArrayList<>(); // 扁平化基础材料清单

    private static final int WAIT_TIMEOUT_TICKS = 200; // 10秒
    private static final int MAX_RECURSION_DEPTH = 10;

    public CraftTask(String targetItemId, int targetCount) {
        this.targetItemId = targetItemId;
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String getName() {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(targetItemId));
        if (item != null) return "合成: " + item.getName(new ItemStack(item)).getString() + " x" + targetCount;
        return "合成: " + targetItemId + " x" + targetCount;
    }

    // ================================================================
    //  主 tick
    // ================================================================

    @Override
    public void tick(AiBotEntity bot) {
        if (!(bot.level() instanceof ServerLevel sl)) return;
        cachedLevel = sl;
        tickCount++;

        switch (mainState) {
            case RESOLVE  -> tickResolve(bot);
            case COLLECT  -> tickCollect(bot);
            case CRAFT_RUN -> tickCraftRun(bot);
        }
    }

    // ================================================================
    //  RESOLVE — 解析配方树，构建执行计划
    // ================================================================

    private void tickResolve(AiBotEntity bot) {
        // 1. 解析目标物品 ID
        String resolvedId = resolveItemId(targetItemId);
        if (resolvedId == null) {
            fail("未知物品: " + targetItemId);
            mainState = MainState.FAILED;
            return;
        }

        // 2. 拍摄背包快照
        inventorySnapshot.clear();
        allRawMaterials.clear();
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (!s.isEmpty()) {
                String id = ForgeRegistries.ITEMS.getKey(s.getItem()).toString();
                inventorySnapshot.merge(id, s.getCount(), Integer::sum);
            }
        }

        // 3. 递归解析配方树
        rootStep = new CraftStep(resolvedId, targetCount);
        resolveRecipeTree(rootStep, 0);

        // 4. 铺平为执行顺序（基础 → 高级）
        craftPlan.clear();
        missingMaterials.clear();
        flattenCraftPlan(rootStep, craftPlan);

        // 5. 计算缺失的基础材料
        for (String rawId : allRawMaterials) {
            int need = countRawNeed(rootStep, rawId);
            int have = inventorySnapshot.getOrDefault(rawId, 0);
            if (need > have) {
                missingMaterials.add(new CraftStep(rawId, need - have));
            }
        }

        // 6. 输出日志
        StringBuilder planLog = new StringBuilder("CraftTask 计划: ");
        for (CraftStep step : craftPlan) planLog.append(step.displayName).append("(").append(step.countNeeded).append(") → ");
        if (craftPlan.isEmpty()) planLog.append("（无需合成，直接检查背包）");
        ConversationLogger.logBotCommand(planLog.toString());

        if (!missingMaterials.isEmpty()) {
            String names = missingMaterials.stream()
                .map(m -> m.displayName + " x" + m.countNeeded)
                .reduce((a, b) -> a + ", " + b).orElse("");
            ConversationLogger.logBotCommand("CraftTask 缺少: " + names);
        }

        // 进入收集阶段
        collectPhase = CollectPhase.START;
        mainState = MainState.COLLECT;
    }

    /** 递归解析配方（返回 true = 有配方可合成） */
    private boolean resolveRecipeTree(CraftStep step, int depth) {
        if (depth > MAX_RECURSION_DEPTH) return false;
        Item targetItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(step.itemId));
        if (targetItem == null) return false;

        // 找配方
        for (Recipe<?> r : cachedLevel.getRecipeManager().getRecipes()) {
            if (r.getType() != RecipeType.CRAFTING) continue;
            if (r.getResultItem(cachedLevel.registryAccess()).getItem() == targetItem) {
                step.recipe = r;
                break;
            }
        }
        if (step.recipe == null) {
            step.isRawMaterial = true;
            allRawMaterials.add(step.itemId);
            return false;
        }

        int outputCount = Math.max(1, step.recipe.getResultItem(cachedLevel.registryAccess()).getCount());
        int craftsNeeded = (step.countNeeded + outputCount - 1) / outputCount; // 向上取整

        for (Ingredient ing : step.recipe.getIngredients()) {
            if (ing == Ingredient.EMPTY) continue;
            ItemStack[] variants = ing.getItems();
            if (variants.length == 0) continue;

            // 取第一个变体 + 该材料在配方中的消耗量
            String ingId = ForgeRegistries.ITEMS.getKey(variants[0].getItem()).toString();
            int perCraftUsage = variants[0].getCount(); // 通常为1

            CraftStep child = new CraftStep(ingId, craftsNeeded * perCraftUsage);
            resolveRecipeTree(child, depth + 1);
            step.ingredients.add(child);
        }
        return true;
    }

    /** 按依赖顺序铺平（先合成基础材料） */
    private void flattenCraftPlan(CraftStep step, List<CraftStep> plan) {
        for (CraftStep ing : step.ingredients) flattenCraftPlan(ing, plan);
        if (!step.isRawMaterial && step.recipe != null) plan.add(step);
    }

    /** 统计某个基础材料的总需要量 */
    private int countRawNeed(CraftStep step, String rawId) {
        if (step.isRawMaterial) return step.itemId.equals(rawId) ? step.countNeeded : 0;
        int sum = 0;
        for (CraftStep ing : step.ingredients) sum += countRawNeed(ing, rawId);
        return sum;
    }

    /** 解析物品 ID（minecraft:xxx / 简写 / 模糊匹配） */
    private String resolveItemId(String input) {
        if (input == null || input.isEmpty()) return null;
        if (input.contains(":")) {
            return ForgeRegistries.ITEMS.containsKey(ResourceLocation.tryParse(input)) ? input : null;
        }
        String prefixed = "minecraft:" + input;
        if (ForgeRegistries.ITEMS.containsKey(ResourceLocation.tryParse(prefixed))) return prefixed;
        for (var entry : ForgeRegistries.ITEMS.getEntries()) {
            if (entry.getKey().location().getPath().equals(input))
                return entry.getKey().location().toString();
        }
        return null;
    }

    // ================================================================
    //  COLLECT — 收集缺失材料（背包 → 箱子 → 玩家）
    // ================================================================

    private void tickCollect(AiBotEntity bot) {
        switch (collectPhase) {
            case START -> {
                if (missingMaterials.isEmpty()) {
                    // 无缺失，直接开始合成
                    mainState = MainState.CRAFT_RUN;
                    return;
                }
                String names = missingMaterials.stream()
                    .map(m -> m.displayName + " x" + m.countNeeded)
                    .reduce((a, b) -> a + ", " + b).orElse("");
                say(bot, "缺一些材料: " + names + "，先找找箱子...");
                collectIndex = 0;
                collectPhase = CollectPhase.CHECK_INVENTORY;
            }

            case CHECK_INVENTORY -> {
                if (collectIndex >= missingMaterials.size()) {
                    // 所有缺失已处理
                    missingMaterials.removeIf(m -> m.countNeeded <= 0);
                    if (missingMaterials.isEmpty()) {
                        say(bot, "材料齐了，开始合成！");
                        mainState = MainState.CRAFT_RUN;
                    } else {
                        collectPhase = CollectPhase.SCAN_CHESTS;
                    }
                    return;
                }
                CraftStep m = missingMaterials.get(collectIndex);
                // 再检查一次背包
                int have = inventorySnapshot.getOrDefault(m.itemId, 0);
                int actual = countItemInInv(bot, m.itemId);
                if (have >= m.countNeeded || actual >= m.countNeeded) {
                    collectIndex++;
                    return;
                }
                // 更新快照
                m.countNeeded -= actual;
                collectPhase = CollectPhase.SCAN_CHESTS;
            }

            case SCAN_CHESTS -> {
                CraftStep m = missingMaterials.get(collectIndex);
                BlockPos found = findChestWithItem(bot, m.itemId, 16);
                if (found != null) {
                    chestPos = found;
                    say(bot, "在箱子里找到" + m.displayName + "，去拿");
                    cachedStandPos = null;
                    collectPhase = CollectPhase.GOTO_CHEST;
                } else {
                    collectPhase = CollectPhase.ASK_PLAYER;
                }
            }

            case GOTO_CHEST -> {
                if (chestPos == null) { collectPhase = CollectPhase.SCAN_CHESTS; return; }
                if (cachedStandPos == null) {
                    cachedStandPos = findStandPos(bot, chestPos);
                }
                if (cachedStandPos != null && navigateTo(bot, cachedStandPos[0], cachedStandPos[1], cachedStandPos[2], 1.0)) {
                    cachedStandPos = null;
                    collectPhase = CollectPhase.TAKE_CHEST;
                }
            }

            case TAKE_CHEST -> {
                if (chestPos == null) { collectPhase = CollectPhase.SCAN_CHESTS; return; }
                CraftStep m = missingMaterials.get(collectIndex);
                BlockEntity be = bot.level().getBlockEntity(chestPos);
                if (!(be instanceof Container chest)) { chestPos = null; collectPhase = CollectPhase.SCAN_CHESTS; return; }

                Item targetItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(m.itemId));
                if (targetItem == null) { chestPos = null; collectPhase = CollectPhase.SCAN_CHESTS; return; }

                int taken = 0;
                int need = m.countNeeded;
                for (int i = 0; i < chest.getContainerSize() && taken < need; i++) {
                    ItemStack stack = chest.getItem(i);
                    if (stack.is(targetItem)) {
                        int take = Math.min(stack.getCount(), need - taken);
                        ItemStack part = stack.split(take);
                        taken += part.getCount();
                        if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
                        // 塞入 bot 背包
                        ItemStack remaining = part.copy();
                        for (int j = 0; j < bot.getInventory().getContainerSize() && !remaining.isEmpty(); j++) {
                            ItemStack slot = bot.getInventory().getItem(j);
                            if (slot.isEmpty()) {
                                bot.getInventory().setItem(j, remaining.copy());
                                remaining.setCount(0);
                            } else if (ItemStack.isSameItemSameTags(slot, remaining)) {
                                int space = slot.getMaxStackSize() - slot.getCount();
                                if (space > 0) {
                                    int add = Math.min(space, remaining.getCount());
                                    slot.grow(add);
                                    remaining.shrink(add);
                                }
                            }
                        }
                    }
                }
                chest.setChanged();

                if (taken > 0) {
                    say(bot, "拿到" + m.displayName + " x" + taken);
                    inventorySnapshot.merge(m.itemId, taken, Integer::sum);
                    m.countNeeded -= taken;
                }
                chestPos = null;
                if (m.countNeeded <= 0) collectIndex++;
                collectPhase = CollectPhase.CHECK_INVENTORY;
            }

            case ASK_PLAYER -> {
                if (targetPlayer == null) {
                    targetPlayer = findNearestPlayer(bot);
                    if (targetPlayer == null) { fail("没有玩家可以求助"); mainState = MainState.FAILED; return; }
                }
                CraftStep m = missingMaterials.get(collectIndex);
                double d = bot.distanceToSqr(targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ());
                if (d > 4.0 * 4.0) {
                    navigateTo(bot, targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ(), 1.0);
                } else {
                    stopMoving(bot);
                    say(bot, "请给我" + m.displayName + " x" + m.countNeeded + "，丢在地上，等你10秒");
                    waitTicks = 0;
                    collectPhase = CollectPhase.WAIT_DROPS;
                }
            }

            case WAIT_DROPS -> {
                stopMoving(bot);
                CraftStep m = missingMaterials.get(collectIndex);
                if (targetPlayer != null) lookAt(bot, targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ());
                waitTicks++;

                // 超时
                if (waitTicks > WAIT_TIMEOUT_TICKS) {
                    say(bot, "算了，" + m.displayName + "不要了，任务取消");
                    ConversationLogger.logBotCommand("CraftTask 等待玩家投递超时: " + m.itemId);
                    fail("玩家未提供" + m.displayName);
                    mainState = MainState.FAILED;
                    return;
                }

                // 检查周围掉落物
                Item targetItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(m.itemId));
                AABB area = new AABB(bot.blockPosition()).inflate(5.0);
                if (targetItem != null && cachedLevel != null) {
                    for (ItemEntity ie : cachedLevel.getEntitiesOfClass(ItemEntity.class, area)) {
                        ItemStack stack = ie.getItem();
                        if (stack.is(targetItem)) {
                            int take = Math.min(stack.getCount(), m.countNeeded);
                            say(bot, "收到" + m.displayName + " x" + take + "，谢谢！");
                            // 捡起
                            ItemStack toAdd = stack.copy();
                            toAdd.setCount(take);
                            stack.shrink(take);
                            if (stack.isEmpty()) ie.discard();
                            // 入背包
                            ItemStack remaining = toAdd.copy();
                            for (int j = 0; j < bot.getInventory().getContainerSize() && !remaining.isEmpty(); j++) {
                                ItemStack slot = bot.getInventory().getItem(j);
                                if (slot.isEmpty()) {
                                    bot.getInventory().setItem(j, remaining.copy());
                                    remaining.setCount(0);
                                } else if (ItemStack.isSameItemSameTags(slot, remaining)) {
                                    int space = slot.getMaxStackSize() - slot.getCount();
                                    if (space > 0) {
                                        int add = Math.min(space, remaining.getCount());
                                        slot.grow(add);
                                        remaining.shrink(add);
                                    }
                                }
                            }
                            inventorySnapshot.merge(m.itemId, take, Integer::sum);
                            m.countNeeded -= take;
                            targetPlayer = null;
                            if (m.countNeeded <= 0) collectIndex++;
                            collectPhase = CollectPhase.CHECK_INVENTORY;
                            return;
                        }
                    }
                }
                if (waitTicks % 40 == 0) {
                    say(bot, "还差" + m.displayName + " x" + m.countNeeded + "，丢在地上就行");
                }
            }
        }
    }

    // ================================================================
    //  CRAFT_RUN — 按计划依次执行合成
    // ================================================================

    private void tickCraftRun(AiBotEntity bot) {
        // 全部合成步骤完成
        if (craftIndex >= craftPlan.size()) {
            Item finalItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(rootStep.itemId));
            if (finalItem == null) { fail("物品已不存在"); mainState = MainState.FAILED; return; }
            int have = countItemInInv(bot, rootStep.itemId);
            if (have >= targetCount) {
                say(bot, rootStep.displayName + " x" + targetCount + " 合成完成！");
                ConversationLogger.logBotCommand("CraftTask 成功: " + rootStep.itemId + " x" + targetCount);
                complete();
                mainState = MainState.DONE;
            } else {
                // 可能材料消耗后产出不够，重新解析
                ConversationLogger.logBotCommand("CraftTask 产出不足(" + have + "/" + targetCount + ")，重新收集");
                mainState = MainState.RESOLVE;
            }
            return;
        }

        CraftStep step = craftPlan.get(craftIndex);
        Item targetItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(step.itemId));
        if (targetItem == null) { craftIndex++; return; }

        // 重新查找配方
        Recipe<?> recipe = null;
        for (Recipe<?> r : cachedLevel.getRecipeManager().getRecipes()) {
            if (r.getType() != RecipeType.CRAFTING) continue;
            if (r.getResultItem(cachedLevel.registryAccess()).getItem() == targetItem) {
                recipe = r;
                break;
            }
        }
        if (recipe == null) { craftIndex++; return; }

        // 检查材料是否足够
        if (!hasAllIngredients(bot, recipe)) {
            ConversationLogger.logBotCommand("CraftTask 合成" + step.displayName + "材料不足，重回收集");
            snapshotInventory(bot);
            // 重新计算缺失
            missingMaterials.clear();
            for (String rawId : allRawMaterials) {
                int need = countRawNeed(rootStep, rawId);
                int have = inventorySnapshot.getOrDefault(rawId, 0);
                if (need > have) {
                    missingMaterials.add(new CraftStep(rawId, need - have));
                }
            }
            if (missingMaterials.isEmpty()) {
                craftIndex++;
                return;
            }
            collectPhase = CollectPhase.START;
            mainState = MainState.COLLECT;
            return;
        }

        // 消耗材料并产出成品
        if (doOneCraft(bot, recipe)) {
            setStatus(bot, "合成 " + step.displayName);
            craftIndex++;
        } else {
            // 合成失败，重试
            if (tickCount % 20 == 0) {
                ConversationLogger.logBotCommand("CraftTask 合成失败: " + step.itemId);
                craftIndex++;
            }
        }
    }

    // ================================================================
    //  合成辅助方法（直接操作背包）
    // ================================================================

    /** 检查配方所有材料是否足够 */
    private boolean hasAllIngredients(AiBotEntity bot, Recipe<?> recipe) {
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing == Ingredient.EMPTY) continue;
            boolean ok = false;
            for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                ItemStack s = bot.getInventory().getItem(i);
                if (!s.isEmpty() && ing.test(s)) { ok = true; break; }
            }
            if (!ok) return false;
        }
        return true;
    }

    /** 执行一次合成（消耗材料 + 产出成品） */
    private boolean doOneCraft(AiBotEntity bot, Recipe<?> recipe) {
        ItemStack result = recipe.getResultItem(cachedLevel.registryAccess());
        if (result.isEmpty()) return false;

        // 消耗材料
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing == Ingredient.EMPTY) continue;
            boolean consumed = false;
            for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
                ItemStack s = bot.getInventory().getItem(i);
                if (!s.isEmpty() && ing.test(s)) {
                    s.shrink(1);
                    if (s.isEmpty()) bot.getInventory().setItem(i, ItemStack.EMPTY);
                    consumed = true;
                    break;
                }
            }
            if (!consumed) return false;
        }

        // 产出成品
        ItemStack output = result.copy();
        ItemStack remaining = output.copy();
        for (int j = 0; j < bot.getInventory().getContainerSize() && !remaining.isEmpty(); j++) {
            ItemStack slot = bot.getInventory().getItem(j);
            if (slot.isEmpty()) {
                bot.getInventory().setItem(j, remaining.copy());
                remaining.setCount(0);
            } else if (ItemStack.isSameItemSameTags(slot, remaining)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space > 0) {
                    int add = Math.min(space, remaining.getCount());
                    slot.grow(add);
                    remaining.shrink(add);
                }
            }
        }
        return true;
    }

    /** 统计背包中某物品数量 */
    private int countItemInInv(AiBotEntity bot, String itemId) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
        if (item == null) return 0;
        int c = 0;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (s.is(item)) c += s.getCount();
        }
        return c;
    }

    /** 拍摄背包快照 */
    private void snapshotInventory(AiBotEntity bot) {
        inventorySnapshot.clear();
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (!s.isEmpty()) {
                String id = ForgeRegistries.ITEMS.getKey(s.getItem()).toString();
                inventorySnapshot.merge(id, s.getCount(), Integer::sum);
            }
        }
    }

    // ================================================================
    //  辅助方法
    // ================================================================

    private ServerPlayer findNearestPlayer(AiBotEntity bot) {
        List<ServerPlayer> players = bot.getServer().getPlayerList().getPlayers();
        ServerPlayer best = null;
        double minDist = Double.MAX_VALUE;
        for (ServerPlayer p : players) {
            double d = bot.distanceToSqr(p.getX(), p.getY(), p.getZ());
            if (d < minDist) { minDist = d; best = p; }
        }
        return best;
    }

    private BlockPos findChestWithItem(AiBotEntity bot, String itemId, int radius) {
        Item target = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
        if (target == null) return null;
        BlockPos center = bot.blockPosition();
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int[] radii = {8, 16};
        for (int r : radii) {
            if (r > radius) r = radius;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        BlockEntity be = bot.level().getBlockEntity(mut);
                        if (be instanceof Container chest) {
                            for (int i = 0; i < chest.getContainerSize(); i++) {
                                if (chest.getItem(i).is(target)) return mut.immutable();
                            }
                        }
                    }
                }
            }
            if (r >= radius) break;
        }
        return null;
    }

    private double[] findStandPos(AiBotEntity bot, BlockPos pos) {
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        double best = Double.MAX_VALUE;
        double[] bestPos = null;
        double bx = bot.getX(), bz = bot.getZ();
        for (int[] d : dirs) {
            BlockPos p = pos.offset(d[0], 0, d[1]);
            if (bot.level().getBlockState(p).isAir()
                && bot.level().getBlockState(p.above()).isAir()
                && bot.level().getBlockState(p.below()).isSolid()) {
                double cx = p.getX() + 0.5, cz = p.getZ() + 0.5;
                double dist = (cx-bx)*(cx-bx) + (cz-bz)*(cz-bz);
                if (dist < best) { best = dist; bestPos = new double[]{cx, p.getY(), cz}; }
            }
        }
        return bestPos != null ? bestPos : new double[]{pos.getX()+0.5, pos.getY(), pos.getZ()+0.5};
    }

    @Override
    protected void stopMoving(AiBotEntity bot) {
        bot.getNavigation().stop();
    }
}
