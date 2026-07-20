package com.aibot.mod.task;

import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * 烹饪任务 - 支持多种烹具（炒锅/炖锅/砧板）
 * 自动检测炉灶状态、选择合适锅具、执行完整烹饪流程
 */
public class CookTask extends BaseTask {

    /** 烹具类型 */
    public enum CookwareType {
        POT,        // 炒锅：放锅 → 倒油 → 下料 → 翻炒 → 盛出
        STOCKPOT,   // 炖锅：放锅体 → 放桶装物(如有) → 下料 → 盖盖 → 煮 → 取盖 → 盛出
        CHOPPING    // 砧板：放食材 → 用刀切 → 空手取成品
    }

    private enum CookState {
        CHECK_MATERIALS,
        FIND_STOVE,          // 扫描炉灶（pot/stockpot）
        LIGHT_STOVE,         // 点火
        BREAK_COOKWARE,      // 破坏现有锅具
        PLACE_COOKWARE,      // 放锅（pot body / stockpot body）
        ADD_BUCKET_ITEMS,    // 加桶装物品（stockpot 专用，必须第一个放）
        ADD_OIL,             // 倒油（pot 专用）
        ADD_INGREDIENTS,     // 下料
        PLACE_LID,           // 盖盖子（stockpot 专用）
        COOKING,             // 等待煮好（stockpot 专用）
        REMOVE_LID,          // 取走盖子（stockpot 专用）
        STIR,                // 翻炒（pot 专用）
        SERVE,               // 盛出
        FIND_BOARD,          // 找砧板（chopping 用）
        PLACE_INGREDIENT,    // 放食材在砧板上
        CUT_WITH_KNIFE,      // 用刀切
        PICKUP_RESULT,       // 空手取成品
        DONE
    }

    private enum MaterialPhase {
        START, CHECK_INVENTORY, SCAN_CHESTS, GOTO_CHEST, TAKE_FROM_CHEST, ASK_PLAYER, DONE
    }

    // ===== 配方定义 =====
    private final List<String> ingredients;
    private final String recipeName;
    private final String outputItem;
    private final String carrierItem;
    private final CookwareType cookwareType;

    // ===== 常量 =====
    private static final String STOVE_ID = "kaleidoscope_cookery:stove";
    private static final String POT_ID = "kaleidoscope_cookery:pot";
    private static final String STOCKPOT_ID = "kaleidoscope_cookery:stockpot";
    private static final String STOCKPOT_LID_ID = "kaleidoscope_cookery:stockpot_lid";
    private static final String BOARD_ID = "kaleidoscope_cookery:chopping_board";
    private static final String OIL_ID = "kaleidoscope_cookery:oil";
    private static final String SHOVEL_ID = "kaleidoscope_cookery:kitchen_shovel";
    private static final String KNIFE_ID = "kaleidoscope_cookery:iron_kitchen_knife";
    private static final String FLINT_ID = "minecraft:flint_and_steel";
    private static final String BOWL_ID = "minecraft:bowl";
    private static final int COOLDOWN = 15;
    private static final int MAX_STIR = 30;
    private static final int MAX_SERVE = 30;
    private static final int MAX_COOKING_TICKS = 400; // 炖锅最长等待 20秒
    private static final int MAX_MATERIAL_ATTEMPTS = 3;

    // ===== 运行时状态 =====
    private CookState cookState = CookState.CHECK_MATERIALS;
    private MaterialPhase matPhase = MaterialPhase.START;
    private List<BlockPos> stovePositions = null;
    private int stoveIndex = -1;                    // 当前使用第几个炉灶
    private BlockPos stovePos = null;
    private BlockPos cookwarePos = null;            // 锅/炖锅的位置（炉灶上方）
    private BlockPos boardPos = null;               // 砧板位置
    private BlockPos chestPos = null;
    private double[] cachedStandPos = null;         // 缓存站立位置，防止每 tick 切换
    private int ingredientIndex = 0;
    private int stirCount = 0;
    private int serveCount = 0;
    private int cookingTimer = 0;
    private int interactionTimer = 0;
    private List<String> missingItems = new ArrayList<>();
    private String currentNeedItem = null;
    private int chestCheckAttempts = 0;
    private List<String> bucketItems = new ArrayList<>(); // 桶装物材料列表
    private FakePlayer cachedFp = null;
    private ServerLevel cachedLevel = null;

    public CookTask(String recipeName, List<String> ingredients, String outputItem, String carrierItem,
                    CookwareType cookwareType) {
        this.recipeName = recipeName;
        this.cookwareType = cookwareType;

        // 保留所有原料条目（含重复，如7根骨头就要下7次）
        List<String> ings = new ArrayList<>();
        for (String ing : ingredients) {
            if (ing == null || ing.isEmpty() || ing.equals("minecraft:air") || ing.equals("air")) continue;
            ings.add(ing);
        }

        // 分离桶装物（必须先放）
        bucketItems.clear();
        Iterator<String> it = ings.iterator();
        while (it.hasNext()) {
            String ing = it.next();
            if (ing.contains("bucket") || ing.contains("Bucket")) {
                bucketItems.add(ing);
                it.remove();
            }
        }

        if (cookwareType == CookwareType.POT) {
            // 炒锅必须加油
            if (!ings.contains(OIL_ID)) ings.add(0, OIL_ID);
        }

        // 炖锅默认加水（配方没写水桶也加）
        if (cookwareType == CookwareType.STOCKPOT && bucketItems.isEmpty()) {
            bucketItems.add("minecraft:water_bucket");
        }

        this.ingredients = Collections.unmodifiableList(ings);
        this.outputItem = outputItem;
        this.carrierItem = (carrierItem != null && !carrierItem.isEmpty()) ? carrierItem : BOWL_ID;
    }

    /** 构建完整的需求列表 */
    private List<String> allNeededItems() {
        List<String> needed = new ArrayList<>();
        switch (cookwareType) {
            case POT -> {
                for (String ing : ingredients) {
                    if (ing.equals(OIL_ID)) { needed.add(OIL_ID); break; }
                }
                needed.addAll(ingredients.stream().filter(i -> !i.equals(OIL_ID)).toList());
                needed.add(SHOVEL_ID);
                needed.add(carrierItem);
            }
            case STOCKPOT -> {
                needed.addAll(bucketItems);
                needed.addAll(ingredients);
                needed.add(STOCKPOT_LID_ID); // 需要锅盖
                needed.add(carrierItem);
            }
            case CHOPPING -> {
                needed.addAll(ingredients);
                needed.add(KNIFE_ID);
            }
        }
        return needed;
    }

    /** 获取当前烹具的方块ID */
    private String cookwareBlockId() {
        return switch (cookwareType) {
            case POT -> POT_ID;
            case STOCKPOT -> STOCKPOT_ID;
            default -> null;
        };
    }

    /** 对应烹具的中文名 */
    private String cookwareName() {
        return switch (cookwareType) {
            case POT -> "炒锅";
            case STOCKPOT -> "炖锅";
            case CHOPPING -> "砧板";
        };
    }

    @Override public String getName() { return "烹饪: " + recipeName; }

    // ================================================================
    //  主 tick
    // ================================================================

    @Override
    public void tick(AiBotEntity bot) {
        tickCount++;
        if (interactionTimer > 0) {
            interactionTimer--;
            // 冷却期间保持位置
            if (cookState == CookState.ADD_INGREDIENTS || cookState == CookState.STIR
                || cookState == CookState.SERVE || cookState == CookState.COOKING
                || cookState == CookState.REMOVE_LID) {
                stopMoving(bot);
                BlockPos target = (cookwareType == CookwareType.CHOPPING) ? boardPos : cookwarePos;
                if (target != null) lookAt(bot, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
            }
            return;
        }

        switch (cookState) {
            case CHECK_MATERIALS -> tickCheckMaterials(bot);
            case FIND_STOVE -> tickFindStove(bot);
            case LIGHT_STOVE -> tickLightStove(bot);
            case BREAK_COOKWARE -> tickBreakCookware(bot);
            case PLACE_COOKWARE -> tickPlaceCookware(bot);
            case ADD_BUCKET_ITEMS -> tickAddBucketItems(bot);
            case ADD_OIL -> tickAddOil(bot);
            case ADD_INGREDIENTS -> tickAddIngredients(bot);
            case PLACE_LID -> tickPlaceLid(bot);
            case COOKING -> tickCooking(bot);
            case REMOVE_LID -> tickRemoveLid(bot);
            case STIR -> tickStir(bot);
            case SERVE -> tickServe(bot);
            case FIND_BOARD -> tickFindBoard(bot);
            case PLACE_INGREDIENT -> tickPlaceIngredient(bot);
            case CUT_WITH_KNIFE -> tickCutWithKnife(bot);
            case PICKUP_RESULT -> tickPickupResult(bot);
            case DONE -> {}
        }
    }

    // ================================================================
    //  材料检查
    // ================================================================

    private void tickCheckMaterials(AiBotEntity bot) {
        switch (matPhase) {
            case START -> {
                say(bot, "好的，我来做" + recipeName + "（用" + cookwareName() + "），先看看需要什么材料...");
                ConversationLogger.logBotCommand("CookTask: CHECK_MATERIALS START for " + recipeName
                    + " type=" + cookwareType);
                matPhase = MaterialPhase.CHECK_INVENTORY;
            }
            case CHECK_INVENTORY -> {
                List<String> needed = allNeededItems();
                missingItems.clear();
                for (String itemId : needed) {
                    if (!hasItem(bot, itemId)) {
                        missingItems.add(itemId);
                    }
                }
                if (missingItems.isEmpty()) {
                    say(bot, "材料都齐了，开始做菜！");
                    ConversationLogger.logBotCommand("CookTask: 材料齐全 → 开始");
                    advanceToNextPhase(bot);
                } else {
                    String names = missingItems.stream().map(this::itemDisplay)
                        .reduce((a, b) -> a + "、" + b).orElse("");
                    say(bot, "我缺少" + names + "，让我看看附近的箱子里有没有...");
                    ConversationLogger.logBotCommand("CookTask: 缺少材料: " + String.join(", ", missingItems));
                    matPhase = MaterialPhase.SCAN_CHESTS;
                    chestCheckAttempts = 0;
                }
            }
            case SCAN_CHESTS -> {
                if (missingItems.isEmpty()) {
                    advanceToNextPhase(bot);
                    return;
                }
                currentNeedItem = missingItems.get(0);
                BlockPos foundChest = findContainerWithItem(bot, currentNeedItem, 16);
                if (foundChest != null) {
                    chestPos = foundChest;
                    say(bot, "找到了！箱子里有" + itemDisplay(currentNeedItem) + "，我过去拿");
                    ConversationLogger.logBotCommand("CookTask: 在箱子里找到 " + currentNeedItem + " at " + chestPos);
                    matPhase = MaterialPhase.GOTO_CHEST;
                } else {
                    chestCheckAttempts++;
                    if (chestCheckAttempts < MAX_MATERIAL_ATTEMPTS) {
                        missingItems.remove(0);
                        matPhase = MaterialPhase.SCAN_CHESTS;
                    } else {
                        matPhase = MaterialPhase.ASK_PLAYER;
                    }
                }
            }
            case GOTO_CHEST -> {
                if (chestPos == null) { matPhase = MaterialPhase.SCAN_CHESTS; return; }
                if (cachedStandPos == null) {
                    cachedStandPos = findStandPos(bot, chestPos);
                    if (cachedStandPos == null) return; // 等下一 tick
                }
                if (navigateTo(bot, cachedStandPos[0], cachedStandPos[1], cachedStandPos[2], 1.0)) {
                    cachedStandPos = null;
                    matPhase = MaterialPhase.TAKE_FROM_CHEST;
                }
            }
            case TAKE_FROM_CHEST -> {
                if (chestPos == null || currentNeedItem == null) { matPhase = MaterialPhase.SCAN_CHESTS; return; }
                cachedStandPos = null;
                BlockEntity be = bot.level().getBlockEntity(chestPos);
                if (!(be instanceof Container chest)) { matPhase = MaterialPhase.SCAN_CHESTS; return; }
                Item targetItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(currentNeedItem));
                if (targetItem == null) { matPhase = MaterialPhase.SCAN_CHESTS; return; }
                boolean taken = takeFromContainer(bot, chest, targetItem);
                if (taken) {
                    say(bot, "拿到了" + itemDisplay(currentNeedItem));
                    missingItems.remove(currentNeedItem);
                    chestPos = null;
                    currentNeedItem = null;
                    matPhase = MaterialPhase.CHECK_INVENTORY;
                } else {
                    missingItems.remove(currentNeedItem);
                    chestPos = null;
                    currentNeedItem = null;
                    matPhase = MaterialPhase.SCAN_CHESTS;
                }
            }
            case ASK_PLAYER -> {
                if (!missingItems.isEmpty()) {
                    String names = missingItems.stream().map(this::itemDisplay)
                        .reduce((a, b) -> a + "、" + b).orElse("");
                    say(bot, "箱子里也没有，请给我" + names);
                    ConversationLogger.logBotCommand("CookTask: 请求玩家提供: " + String.join(", ", missingItems));
                }
                say(bot, "我先试试看能不能做...");
                advanceToNextPhase(bot);
            }
            case DONE -> advanceToNextPhase(bot);
        }
    }

    /** 材料齐了，进入下一阶段 */
    private void advanceToNextPhase(AiBotEntity bot) {
        switch (cookwareType) {
            case POT, STOCKPOT -> cookState = CookState.FIND_STOVE;
            case CHOPPING -> cookState = CookState.FIND_BOARD;
        }
    }

    // ================================================================
    //  炉灶相关 (POT / STOCKPOT)
    // ================================================================

    /** 扫描所有炉灶，选择最合适的 */
    private void tickFindStove(AiBotEntity bot) {
        if (stovePositions == null) {
            Block stoveBlock = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(STOVE_ID));
            if (stoveBlock == null) { fail("找不到炉灶方块: " + STOVE_ID); return; }
            List<BlockPos> allStoves = scanAllNearby(bot, stoveBlock, 32);
            if (allStoves.isEmpty()) { fail("附近没有炉灶"); return; }

            // 按距离排序
            BlockPos botPos = bot.blockPosition();
            allStoves.sort(Comparator.comparingDouble(p -> p.distSqr(botPos)));
            stovePositions = allStoves;

            // 分析每个炉灶的锅具状态
            String targetBlockId = cookwareBlockId();
            Block targetBlock = (targetBlockId != null) ? ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(targetBlockId)) : null;

            boolean foundEmpty = false;
            for (int i = 0; i < allStoves.size(); i++) {
                BlockPos sp = allStoves.get(i);
                BlockPos above = sp.above();
                BlockState aboveState = bot.level().getBlockState(above);
                boolean hasCookware = targetBlock != null && aboveState.getBlock() == targetBlock;

                if (hasCookware) {
                    // 检查锅里有什么
                    if (targetBlockId != null) {
                        Object hasOilOrContent = getBlockProperty(aboveState, "has_oil");
                        if (hasOilOrContent instanceof Boolean && (Boolean) hasOilOrContent) {
                            // 锅里有东西，判断是否煮好了（煮好了就直接用）
                            if (isFoodReady(bot, aboveState, above)) {
                                stoveIndex = i;
                                stovePos = sp;
                                cookwarePos = above;
                                ConversationLogger.logBotCommand("CookTask: 炉灶" + i + "有成品，直接盛出");
                                break;
                            }
                            ConversationLogger.logBotCommand("CookTask: 炉灶" + i + "有锅但含半成品/旧食材");
                            continue; // 跳过这个，找下一个
                        }
                    }
                    // 干净的锅，直接可以用
                    stoveIndex = i;
                    stovePos = sp;
                    cookwarePos = above;
                    say(bot, "找到炉灶了，上面已经有" + cookwareName() + "，直接过去");
                    ConversationLogger.logBotCommand("CookTask: 选择炉灶" + i + "（已有干净锅）");
                    break;
                }
                if (!foundEmpty) {
                    // 第一个空的炉灶（上面没有方块或不是目标锅具）
                    Block aboveBlock = aboveState.getBlock();
                    String aboveId = ForgeRegistries.BLOCKS.getKey(aboveBlock).toString();
                    if (aboveState.isAir() || !aboveId.contains("cookery")) {
                        foundEmpty = true;
                        // 先记下来，看后面有没有更好的
                        stoveIndex = i;
                        stovePos = sp;
                        cookwarePos = above;
                    }
                }
            }

            if (stovePos == null && foundEmpty && stoveIndex >= 0) {
                // 没有找到干净的锅，但有空炉灶
                ConversationLogger.logBotCommand("CookTask: 选择空炉灶" + stoveIndex);
            }

            if (stovePos == null) {
                // 全都有旧食材，用第一个并先破坏
                stoveIndex = 0;
                stovePos = allStoves.get(0);
                cookwarePos = stovePos.above();
                ConversationLogger.logBotCommand("CookTask: 所有炉灶都有旧食材，用炉灶0先破坏");
            }

            say(bot, "找到炉灶了，走过去...");
            setStatus(bot, "找到炉灶 " + stovePos.toShortString());
            cachedStandPos = null;
        }

        if (cachedStandPos == null) {
            cachedStandPos = findStandPos(bot, stovePos);
            if (cachedStandPos == null) {
                // 紧邻位置全被堵住，等下一 tick 重试
                return;
            }
        }
        if (navigateTo(bot, cachedStandPos[0], cachedStandPos[1], cachedStandPos[2], 1.0)) {
            lookAt(bot, stovePos.getX() + 0.5, stovePos.getY() + 0.5, stovePos.getZ() + 0.5);
            // 到达后检查炉灶状态
            String targetBlockId = cookwareBlockId();
            Block targetBlock = (targetBlockId != null) ? ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(targetBlockId)) : null;

            BlockState stoveState = bot.level().getBlockState(stovePos);
            Object litProp = getBlockProperty(stoveState, "lit");
            boolean isLit = litProp instanceof Boolean && (Boolean) litProp;

            BlockState cookwareState = bot.level().getBlockState(cookwarePos);
            boolean hasCorrectCookware = targetBlock != null && cookwareState.getBlock() == targetBlock;

            // 检查是否有其他类型的锅具（需要破坏）
            boolean hasOtherCookware = false;
            if (!hasCorrectCookware && !cookwareState.isAir()) {
                String blockId = ForgeRegistries.BLOCKS.getKey(cookwareState.getBlock()).toString();
                hasOtherCookware = blockId.contains("cookery") && (blockId.contains("pot") || blockId.contains("stockpot"));
            }

            if (!isLit) {
                if (!ensureHasItem(bot, FLINT_ID)) {
                    say(bot, "我没有打火石来点火...");
                    fail("炉灶未点燃且缺少打火石");
                    return;
                }
                cookState = CookState.LIGHT_STOVE;
                setStatus(bot, "炉灶未点燃");
                ConversationLogger.logBotCommand("CookTask: 炉灶未点火 → LIGHT_STOVE");
                return;
            }

            // 炉灶已点燃
            if (hasCorrectCookware) {
                // 有正确锅具，检查锅里有什么
                if (targetBlockId != null) {
                    Object hasContent = getBlockProperty(cookwareState, "has_oil");
                    boolean content = hasContent instanceof Boolean && (Boolean) hasContent;
                    if (content) {
                        // 锅里有东西，判断是煮好了还是半成品
                        if (isFoodReady(bot, cookwareState, cookwarePos)) {
                            cookState = CookState.SERVE;
                            serveCount = 0;
                            setStatus(bot, "发现已完成菜品，直接盛出...");
                            ConversationLogger.logBotCommand("CookTask: 锅里有成品 → SERVE");
                        } else {
                            cookState = CookState.BREAK_COOKWARE;
                            setStatus(bot, cookwareName() + "里有半成品/旧食材，先破坏...");
                            ConversationLogger.logBotCommand("CookTask: 锅里有旧食材 → BREAK_COOKWARE");
                        }
                        return;
                    }
                }

                // 检查锅盖上是否有盖子（stockpot）
                if (cookwareType == CookwareType.STOCKPOT && hasLidOnBlock(bot, cookwarePos)) {
                    cookState = CookState.REMOVE_LID;
                    setStatus(bot, "锅盖上还有盖子，先取下来...");
                    ConversationLogger.logBotCommand("CookTask: 有锅盖 → REMOVE_LID");
                    return;
                }

                // 锅是干净的
                if (cookwareType == CookwareType.STOCKPOT && !bucketItems.isEmpty()) {
                    cookState = CookState.ADD_BUCKET_ITEMS;
                    ConversationLogger.logBotCommand("CookTask: 炖锅干净 → ADD_BUCKET_ITEMS");
                } else {
                    cookState = CookState.ADD_OIL;
                    ConversationLogger.logBotCommand("CookTask: 锅干净 → ADD_OIL");
                }
                return;
            }

            if (hasOtherCookware) {
                // 有其他类型的锅具，破坏它
                cookState = CookState.BREAK_COOKWARE;
                setStatus(bot, "需要先清除上面的" + cookwareName());
                ConversationLogger.logBotCommand("CookTask: 有其他锅具 → BREAK_COOKWARE");
                return;
            }

            // 没有锅 → 需要放置
            String neededBlockId = cookwareBlockId();
            if (neededBlockId == null) { fail("未知烹具"); return; }
            if (!ensureHasItem(bot, neededBlockId)) {
                say(bot, "我没有" + cookwareName() + "可以放...");
                fail("缺少" + cookwareBlockId());
                return;
            }
            cookState = CookState.PLACE_COOKWARE;
            setStatus(bot, "准备放" + cookwareName());
            ConversationLogger.logBotCommand("CookTask: 无锅 → PLACE_COOKWARE");
        }
    }

    private void tickLightStove(AiBotEntity bot) {
        BlockState stoveState = bot.level().getBlockState(stovePos);
        Object litProp = getBlockProperty(stoveState, "lit");
        boolean isLit = litProp instanceof Boolean && (Boolean) litProp;

        if (isLit) {
            // 点燃后检查下一步
            afterStoveLit(bot);
            return;
        }

        if (!hasItem(bot, FLINT_ID)) {
            say(bot, "我没有打火石...");
            fail("缺少打火石");
            return;
        }
        boolean clicked = rightClickBlock(bot, stovePos, Direction.UP, FLINT_ID);
        if (clicked) {
            interactionTimer = COOLDOWN;
            BlockState newState = bot.level().getBlockState(stovePos);
            Object newLit = getBlockProperty(newState, "lit");
            if (newLit instanceof Boolean && (Boolean) newLit) {
                afterStoveLit(bot);
            }
        } else {
            interactionTimer = COOLDOWN;
        }
    }

    /** 炉灶已点燃后判断下一步 */
    private void afterStoveLit(AiBotEntity bot) {
        String targetBlockId = cookwareBlockId();
        Block targetBlock = (targetBlockId != null) ? ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(targetBlockId)) : null;
        BlockState cookwareState = bot.level().getBlockState(cookwarePos);
        boolean hasCorrect = targetBlock != null && cookwareState.getBlock() == targetBlock;

        if (hasCorrect) {
            Object hasContent = getBlockProperty(cookwareState, "has_oil");
            boolean hasOldContent = hasContent instanceof Boolean && (Boolean) hasContent;
            if (hasOldContent) {
                if (isFoodReady(bot, cookwareState, cookwarePos)) {
                    cookState = CookState.SERVE;
                    serveCount = 0;
                    ConversationLogger.logBotCommand("CookTask: 点火后锅有成品 → SERVE");
                } else {
                    cookState = CookState.BREAK_COOKWARE;
                    ConversationLogger.logBotCommand("CookTask: 点火后锅有旧食材 → BREAK_COOKWARE");
                }
            } else if (cookwareType == CookwareType.STOCKPOT && hasLidOnBlock(bot, cookwarePos)) {
                cookState = CookState.REMOVE_LID;
                ConversationLogger.logBotCommand("CookTask: 点火后有锅盖 → REMOVE_LID");
            } else {
                if (cookwareType == CookwareType.STOCKPOT && !bucketItems.isEmpty()) {
                    cookState = CookState.ADD_BUCKET_ITEMS;
                } else {
                    cookState = CookState.ADD_OIL;
                }
                ConversationLogger.logBotCommand("CookTask: 点火后锅干净 → 下一阶段");
            }
        } else {
            String neededBlockId = cookwareBlockId();
            if (neededBlockId != null && ensureHasItem(bot, neededBlockId)) {
                cookState = CookState.PLACE_COOKWARE;
                ConversationLogger.logBotCommand("CookTask: 点火后放锅 → PLACE_COOKWARE");
            } else {
                cookState = CookState.BREAK_COOKWARE;
                ConversationLogger.logBotCommand("CookTask: 点火后无锅无材料 → BREAK_COOKWARE");
            }
        }
    }

    /** 破坏现有锅具 */
    private void tickBreakCookware(AiBotEntity bot) {
        if (!(bot.level() instanceof ServerLevel serverLevel)) return;

        BlockState state = bot.level().getBlockState(cookwarePos);
        String targetBlockId = cookwareBlockId();
        Block targetBlock = (targetBlockId != null) ? ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(targetBlockId)) : null;
        boolean hasCorrect = targetBlock != null && state.getBlock() == targetBlock;

        if (hasCorrect) {
            // 已经是正确锅具且干净
            if (cookwareType == CookwareType.STOCKPOT && !bucketItems.isEmpty()) {
                cookState = CookState.ADD_BUCKET_ITEMS;
            } else {
                cookState = CookState.ADD_OIL;
            }
            ConversationLogger.logBotCommand("CookTask: 已有干净锅具 → 继续");
            return;
        }

        // 如果上面没有任何方块或者已经是空气，直接放锅
        if (state.isAir()) {
            cookState = CookState.PLACE_COOKWARE;
            ConversationLogger.logBotCommand("CookTask: 锅位已空 → PLACE_COOKWARE");
            return;
        }

        // 破坏锅具
        try {
            if (cachedFp == null || cachedLevel != serverLevel) {
                cachedFp = FakePlayerFactory.getMinecraft(serverLevel);
                cachedLevel = serverLevel;
            }
            FakePlayer fp = cachedFp;
            fp.setPos(bot.getX(), bot.getY(), bot.getZ());
            fp.gameMode.destroyBlock(cookwarePos);
            interactionTimer = COOLDOWN;
            setStatus(bot, "破坏旧锅中...");
        } catch (Exception e) {
            ConversationLogger.logError("CookTask BREAK_COOKWARE error: " + e.getMessage());
            if (cachedFp != null) {
                cachedFp.getInventory().clearContent();
                cachedFp = null;
                cachedLevel = null;
            }
            interactionTimer = COOLDOWN;
        }
    }

    /** 放置锅具 */
    private void tickPlaceCookware(AiBotEntity bot) {
        String blockId = cookwareBlockId();
        Block targetBlock = blockId != null ? ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(blockId)) : null;

        if (targetBlock != null) {
            BlockState state = bot.level().getBlockState(cookwarePos);
            if (state.getBlock() == targetBlock) {
                // 锅已放好
                if (cookwareType == CookwareType.STOCKPOT && !bucketItems.isEmpty()) {
                    cookState = CookState.ADD_BUCKET_ITEMS;
                } else {
                    cookState = CookState.ADD_OIL;
                }
                ConversationLogger.logBotCommand("CookTask: " + cookwareName() + "已就位");
                return;
            }
        }

        if (blockId != null && rightClickBlock(bot, stovePos, Direction.UP, blockId)) {
            interactionTimer = COOLDOWN;
            if (cookwareType == CookwareType.STOCKPOT && !bucketItems.isEmpty()) {
                cookState = CookState.ADD_BUCKET_ITEMS;
            } else {
                cookState = CookState.ADD_OIL;
            }
            setStatus(bot, cookwareName() + "已放好");
            ConversationLogger.logBotCommand("CookTask: 放" + cookwareName() + " 完成");
        } else {
            interactionTimer = COOLDOWN;
        }
    }

    // ================================================================
    //  STOCKPOT 专用状态
    // ================================================================

    /** 加桶装物品（stockpot 必须第一个放，如水/熔岩） */
    private void tickAddBucketItems(AiBotEntity bot) {
        stopMoving(bot);
        if (cookwarePos != null) lookAt(bot, cookwarePos.getX() + 0.5, cookwarePos.getY() + 0.5, cookwarePos.getZ() + 0.5);

        if (bucketItems.isEmpty()) {
            cookState = CookState.ADD_OIL;
            ConversationLogger.logBotCommand("CookTask: 桶装物已加完 → ADD_OIL");
            return;
        }

        String bucketId = bucketItems.get(0);
        BlockState cookwareState = bot.level().getBlockState(cookwarePos);
        // 检查是否已加过（通过检测对应 block property）
        boolean alreadyAdded = false;
        Object waterLevel = getBlockProperty(cookwareState, "water_level");
        if (waterLevel instanceof Integer && (Integer) waterLevel > 0) {
            alreadyAdded = true;
        }
        if (alreadyAdded) {
            bucketItems.remove(0);
            interactionTimer = COOLDOWN / 3;
            return;
        }

        if (!ensureHasItem(bot, bucketId)) {
            say(bot, "我没有" + itemDisplay(bucketId) + "了...");
            fail("缺少桶装物: " + bucketId);
            return;
        }
        boolean clicked = rightClickBlock(bot, cookwarePos, Direction.UP, bucketId);
        if (clicked) {
            interactionTimer = COOLDOWN;
            bucketItems.remove(0);
            setStatus(bot, "加入" + itemDisplay(bucketId) + " 完成");
            ConversationLogger.logBotCommand("CookTask: 加入桶装物 " + bucketId);
        } else {
            interactionTimer = COOLDOWN;
        }
    }

    /** 倒油（pot）/ 或 pot 的通用加料入口 */
    private void tickAddOil(AiBotEntity bot) {
        if (cookwareType == CookwareType.STOCKPOT && bucketItems.isEmpty()) {
            // stockpot 不需要油，直接下料
            cookState = CookState.ADD_INGREDIENTS;
            ingredientIndex = 0;
            ConversationLogger.logBotCommand("CookTask: 炖锅准备就绪 → ADD_INGREDIENTS");
            return;
        }
        if (cookwareType == CookwareType.CHOPPING) {
            cookState = CookState.FIND_BOARD;
            return;
        }

        // Pot: 倒油
        stopMoving(bot);
        if (cookwarePos != null) lookAt(bot, cookwarePos.getX() + 0.5, cookwarePos.getY() + 0.5, cookwarePos.getZ() + 0.5);

        BlockState potState = bot.level().getBlockState(cookwarePos);
        Object hasOil = getBlockProperty(potState, "has_oil");
        if (hasOil instanceof Boolean && (Boolean) hasOil) {
            cookState = CookState.ADD_INGREDIENTS;
            ingredientIndex = 0;
            setStatus(bot, "油已加好，开始下料");
            ConversationLogger.logBotCommand("CookTask: 油已加好 → ADD_INGREDIENTS");
            return;
        }

        if (!ensureHasItem(bot, OIL_ID)) {
            say(bot, "我没有油了...");
            fail("缺少油");
            return;
        }
        boolean clicked = rightClickBlock(bot, cookwarePos, Direction.UP, OIL_ID);
        if (clicked) {
            interactionTimer = COOLDOWN;
            setStatus(bot, "倒油中...");
        } else {
            interactionTimer = COOLDOWN;
        }
    }

    // ================================================================
    //  下料（POT / STOCKPOT 通用）
    // ================================================================

    private void tickAddIngredients(AiBotEntity bot) {
        stopMoving(bot);
        if (cookwarePos != null) lookAt(bot, cookwarePos.getX() + 0.5, cookwarePos.getY() + 0.5, cookwarePos.getZ() + 0.5);

        // 跳过油（已在 ADD_OIL 阶段处理）
        if (cookwareType == CookwareType.POT) {
            while (ingredientIndex < ingredients.size() && OIL_ID.equals(ingredients.get(ingredientIndex))) {
                ingredientIndex++;
            }
        }

        if (ingredientIndex >= ingredients.size()) {
            // 材料放完
            if (cookwareType == CookwareType.STOCKPOT) {
                cookState = CookState.PLACE_LID;
                setStatus(bot, "材料放完，准备盖盖子");
                ConversationLogger.logBotCommand("CookTask: 材料放完 → PLACE_LID");
            } else {
                cookState = CookState.STIR;
                stirCount = 0;
                setStatus(bot, "开始翻炒");
                if (!ensureHasItem(bot, SHOVEL_ID)) {
                    say(bot, "我没有锅铲了...");
                    fail("缺少锅铲");
                    return;
                }
                ConversationLogger.logBotCommand("CookTask: 材料放完 → STIR");
            }
            return;
        }

        String ingId = ingredients.get(ingredientIndex);
        // 跳过桶装物（已在 ADD_BUCKET_ITEMS 处理）
        if (cookwareType == CookwareType.STOCKPOT && (ingId.contains("bucket") || ingId.contains("Bucket"))) {
            ingredientIndex++;
            return;
        }

        // 检查锅还在
        String blockId = cookwareBlockId();
        if (blockId != null) {
            BlockState cookwareState = bot.level().getBlockState(cookwarePos);
            Block expectedBlock = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(blockId));
            if (expectedBlock != null && cookwareState.getBlock() != expectedBlock) {
                fail(cookwareName() + "不见了！");
                return;
            }
        }

        if (!ensureHasItem(bot, ingId)) {
            say(bot, "我没有" + itemDisplay(ingId) + "了...");
            fail("缺少 " + ingId);
            return;
        }

        boolean clicked = rightClickBlock(bot, cookwarePos, Direction.UP, ingId);
        if (clicked) {
            interactionTimer = COOLDOWN;
            ConversationLogger.logBotCommand("CookTask: 放入材料 " + ingId);
            ingredientIndex++;
            setStatus(bot, "下料 " + ingredientIndex + "/" + ingredients.size());
        } else {
            interactionTimer = COOLDOWN;
        }
    }

    // ================================================================
    //  STOCKPOT 煮制流程
    // ================================================================

    /** 盖盖子 */
    private void tickPlaceLid(AiBotEntity bot) {
        stopMoving(bot);
        if (cookwarePos != null) lookAt(bot, cookwarePos.getX() + 0.5, cookwarePos.getY() + 0.5, cookwarePos.getZ() + 0.5);

        if (!ensureHasItem(bot, STOCKPOT_LID_ID)) {
            say(bot, "我没有锅盖...");
            fail("缺少锅盖");
            return;
        }

        boolean clicked = rightClickBlock(bot, cookwarePos, Direction.UP, STOCKPOT_LID_ID);
        if (clicked) {
            interactionTimer = COOLDOWN;
            cookingTimer = 0;
            cookState = CookState.COOKING;
            setStatus(bot, "盖好盖子，等待炖煮...");
            ConversationLogger.logBotCommand("CookTask: 盖盖 → COOKING");
        } else {
            interactionTimer = COOLDOWN;
        }
    }

    /** 等待煮好（检查 block property 或等冷却） */
    private void tickCooking(AiBotEntity bot) {
        stopMoving(bot);
        if (cookwarePos != null) lookAt(bot, cookwarePos.getX() + 0.5, cookwarePos.getY() + 0.5, cookwarePos.getZ() + 0.5);

        cookingTimer++;
        setStatus(bot, "炖煮中 " + cookingTimer + "tick");

        // 检查锅盖是否还在（煮好了模组可能会自动弹出/移除锅盖）
        boolean hasLid = hasLidOnBlock(bot, cookwarePos);

        // 检查是否有成品可盛出的迹象（has_oil 变 false 或 lid 已消失）
        if (!hasLid || cookingTimer >= MAX_COOKING_TICKS) {
            cookState = CookState.REMOVE_LID;
            setStatus(bot, "煮好了，准备取盖盛出");
            ConversationLogger.logBotCommand("CookTask: 炖煮完成 → REMOVE_LID (" + cookingTimer + "tick)");
            return;
        }

        // 每 15 tick 检查一次锅盖是否自动移除
        if (cookingTimer % 15 == 0) {
            interactionTimer = Math.min(COOLDOWN / 2, 5);
        }
    }

    /** 取走锅盖（开始前/煮完后共用） */
    private void tickRemoveLid(AiBotEntity bot) {
        stopMoving(bot);
        if (cookwarePos != null) lookAt(bot, cookwarePos.getX() + 0.5, cookwarePos.getY() + 0.5, cookwarePos.getZ() + 0.5);

        // 检查锅体 has_lid 属性
        boolean hasLid = hasLidOnBlock(bot, cookwarePos);

        if (!hasLid) {
            if (cookingTimer > 0) {
                // 煮完后取盖 → 盛出
                cookState = CookState.SERVE;
                serveCount = 0;
                setStatus(bot, "锅盖已取走，准备盛出");
                ConversationLogger.logBotCommand("CookTask: 锅盖已移除 → SERVE");
            } else {
                // 开始前取盖 → 继续下一步
                if (cookwareType == CookwareType.STOCKPOT && !bucketItems.isEmpty()) {
                    cookState = CookState.ADD_BUCKET_ITEMS;
                } else {
                    cookState = CookState.ADD_OIL;
                }
                setStatus(bot, "锅盖已取走");
                ConversationLogger.logBotCommand("CookTask: 取盖完成 → 继续");
            }
            return;
        }

        // 直接右键锅体，模组会处理取盖
        rightClickBlock(bot, cookwarePos, Direction.UP, "");
        interactionTimer = COOLDOWN;
        setStatus(bot, "取走锅盖...");
    }

    // ================================================================
    //  翻炒（POT 专用）
    // ================================================================

    private void tickStir(AiBotEntity bot) {
        stopMoving(bot);
        if (cookwarePos != null) lookAt(bot, cookwarePos.getX() + 0.5, cookwarePos.getY() + 0.5, cookwarePos.getZ() + 0.5);

        if (!ensureHasItem(bot, SHOVEL_ID)) {
            say(bot, "我没有锅铲了...");
            fail("缺少锅铲");
            return;
        }

        Item shovelItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(SHOVEL_ID));
        if (shovelItem != null) {
            bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(shovelItem));
        }

        stirCount++;
        if (stirCount > MAX_STIR) {
            fail("翻炒超时(" + MAX_STIR + "次)，可能食材不足");
            return;
        }

        BlockState potState = bot.level().getBlockState(cookwarePos);
        Object showOil = getBlockProperty(potState, "show_oil");

        boolean showGone = !(showOil instanceof Boolean && (Boolean) showOil);
        if (showGone && stirCount > 3) {
            cookState = CookState.SERVE;
            serveCount = 0;
            say(bot, "炒好了！让我盛出来...");
            setStatus(bot, "烹饪完成，盛出中...");
            ensureHasItem(bot, carrierItem);
            Item bowl = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(carrierItem));
            if (bowl != null) bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(bowl));
            else bot.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ConversationLogger.logBotCommand("CookTask: 翻炒完成(" + stirCount + "次) → SERVE");
            return;
        }

        boolean clicked = rightClickBlock(bot, cookwarePos, Direction.UP, SHOVEL_ID);
        if (clicked) {
            interactionTimer = COOLDOWN;
            setStatus(bot, "翻炒中 " + stirCount + "/" + MAX_STIR);
        } else {
            interactionTimer = COOLDOWN;
        }
    }

    // ================================================================
    //  盛出（POT / STOCKPOT 通用）
    // ================================================================

    private void tickServe(AiBotEntity bot) {
        stopMoving(bot);
        if (cookwarePos != null) lookAt(bot, cookwarePos.getX() + 0.5, cookwarePos.getY() + 0.5, cookwarePos.getZ() + 0.5);

        if (!ensureHasItem(bot, carrierItem)) {
            say(bot, "我没有" + itemDisplay(carrierItem) + "了...");
            fail("缺少盛具: " + carrierItem);
            return;
        }

        BlockState cookwareState = bot.level().getBlockState(cookwarePos);

        if (cookwareType == CookwareType.STOCKPOT) {
            // 炖锅：没有 has_oil 属性，直接右键盛出，盛不出就说明锅空了
            boolean clicked = rightClickBlock(bot, cookwarePos, Direction.UP, carrierItem);
            if (clicked) {
                interactionTimer = COOLDOWN;
                serveCount++;
                setStatus(bot, "盛出中 (" + serveCount + ")");
                ConversationLogger.logBotCommand("CookTask: 炖锅盛出 #" + serveCount + " 用 " + carrierItem);
            } else {
                drainFakePlayer(bot);
                if (cookwarePos != null) pickupNearbyItems(bot, cookwarePos, 3.0);
                complete();
                setStatus(bot, "烹饪完成: " + recipeName);
                bot.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                say(bot, recipeName + "做好了！");
                ConversationLogger.logBotCommand("CookTask: 炖锅盛出完成 → DONE");
                return;
            }
        } else {
            // 炒锅：靠 has_oil 属性判断是否盛完
            Object hasOil = getBlockProperty(cookwareState, "has_oil");
            if (!(hasOil instanceof Boolean) || !(Boolean) hasOil) {
                drainFakePlayer(bot);
                if (cookwarePos != null) pickupNearbyItems(bot, cookwarePos, 3.0);
                complete();
                setStatus(bot, "烹饪完成: " + recipeName);
                bot.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                say(bot, recipeName + "做好了！");
                ConversationLogger.logBotCommand("CookTask: 盛出完成 → DONE");
                return;
            }

            boolean clicked = rightClickBlock(bot, cookwarePos, Direction.UP, carrierItem);
            if (clicked) {
                interactionTimer = COOLDOWN;
                serveCount++;
                setStatus(bot, "盛出中 (" + serveCount + ")");
                ConversationLogger.logBotCommand("CookTask: 盛出 #" + serveCount + " 用 " + carrierItem);
            } else {
                interactionTimer = COOLDOWN;
            }
        }

        if (serveCount > MAX_SERVE) {
            fail("盛出失败(" + MAX_SERVE + "次)，请手动取出");
        }
    }

    // ================================================================
    //  砧板（CHOPPING BOARD）
    // ================================================================

    /** 找砧板 */
    private void tickFindBoard(AiBotEntity bot) {
        if (boardPos == null) {
            Block boardBlock = ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(BOARD_ID));
            if (boardBlock == null) { fail("找不到砧板方块: " + BOARD_ID); return; }
            boardPos = scanNearby(bot, boardBlock, 16);
            if (boardPos == null) {
                // 没有砧板，尝试放置
                if (!ensureHasItem(bot, BOARD_ID)) {
                    fail("附近没有砧板，也没有砧板可以放");
                    return;
                }
                // 在面前放一个
                BlockPos placePos = bot.blockPosition().relative(bot.getDirection(), 2);
                boolean placed = rightClickBlock(bot, placePos, Direction.UP, BOARD_ID);
                if (placed) {
                    boardPos = placePos;
                    interactionTimer = COOLDOWN;
                    say(bot, "放了一个砧板");
                } else {
                    fail("无法放置砧板");
                    return;
                }
            }
            say(bot, "找到砧板了，走过去...");
            cachedStandPos = null;
        }

        if (cachedStandPos == null) {
            cachedStandPos = findStandPos(bot, boardPos);
            if (cachedStandPos == null) return; // 等下一 tick
        }
        if (navigateTo(bot, cachedStandPos[0], cachedStandPos[1], cachedStandPos[2], 1.0)) {
            lookAt(bot, boardPos.getX() + 0.5, boardPos.getY() + 0.5, boardPos.getZ() + 0.5);
            cookState = CookState.PLACE_INGREDIENT;
            ingredientIndex = 0;
            ConversationLogger.logBotCommand("CookTask: 到达砧板 → PLACE_INGREDIENT");
        }
    }

    /** 放食材在砧板上：用食材右键砧板 */
    private void tickPlaceIngredient(AiBotEntity bot) {
        stopMoving(bot);
        if (boardPos != null) lookAt(bot, boardPos.getX() + 0.5, boardPos.getY() + 0.5, boardPos.getZ() + 0.5);

        if (ingredientIndex >= ingredients.size()) {
            // 所有食材都已放好
            cookState = CookState.CUT_WITH_KNIFE;
            setStatus(bot, "食材已放好");
            ConversationLogger.logBotCommand("CookTask: 食材放好 → CUT_WITH_KNIFE");
            return;
        }

        String ingId = ingredients.get(ingredientIndex);
        if (!ensureHasItem(bot, ingId)) {
            say(bot, "我没有" + itemDisplay(ingId) + "...");
            fail("缺少 " + ingId);
            return;
        }

        boolean clicked = rightClickBlock(bot, boardPos, Direction.UP, ingId);
        if (clicked) {
            interactionTimer = COOLDOWN;
            ConversationLogger.logBotCommand("CookTask: 砧板放食材 " + ingId);
            ingredientIndex++;
            setStatus(bot, "放食材 " + ingredientIndex + "/" + ingredients.size());
        } else {
            interactionTimer = COOLDOWN;
        }
    }

    /** 用刀切 */
    private void tickCutWithKnife(AiBotEntity bot) {
        stopMoving(bot);
        if (boardPos != null) lookAt(bot, boardPos.getX() + 0.5, boardPos.getY() + 0.5, boardPos.getZ() + 0.5);

        if (!ensureHasItem(bot, KNIFE_ID)) {
            say(bot, "我没有刀...");
            fail("缺少刀: " + KNIFE_ID);
            return;
        }

        // 手持刀视觉
        Item knifeItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(KNIFE_ID));
        if (knifeItem != null) {
            bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(knifeItem));
        }

        boolean clicked = rightClickBlock(bot, boardPos, Direction.UP, KNIFE_ID);
        if (clicked) {
            interactionTimer = COOLDOWN;
            stirCount++;
            setStatus(bot, "切菜中 (" + stirCount + ")");
            ConversationLogger.logBotCommand("CookTask: 切菜 " + stirCount);

            // 检查成品是否出现在砧板上（可通过检查砧板 block entity 或物品）
            if (stirCount > 3) {
                cookState = CookState.PICKUP_RESULT;
                setStatus(bot, "切好了");
                ConversationLogger.logBotCommand("CookTask: 切好 → PICKUP_RESULT");
            }
        } else {
            interactionTimer = COOLDOWN;
        }

        if (stirCount > 15) {
            // 防止死循环
            cookState = CookState.PICKUP_RESULT;
            ConversationLogger.logBotCommand("CookTask: 切菜超时 → PICKUP_RESULT");
        }
    }

    /** 空手取成品 */
    private void tickPickupResult(AiBotEntity bot) {
        stopMoving(bot);
        if (boardPos != null) lookAt(bot, boardPos.getX() + 0.5, boardPos.getY() + 0.5, boardPos.getZ() + 0.5);

        bot.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);

        // 空手右键砧板取成品
        boolean clicked = rightClickBlock(bot, boardPos, Direction.UP, "");
        if (clicked) {
            interactionTimer = COOLDOWN;
            serveCount++;
            setStatus(bot, "取成品中 (" + serveCount + ")");
            ConversationLogger.logBotCommand("CookTask: 取成品 " + serveCount);
            if (serveCount > 5) {
                drainFakePlayer(bot);
                if (boardPos != null) pickupNearbyItems(bot, boardPos, 3.0);
                complete();
                setStatus(bot, "切菜完成: " + recipeName);
                bot.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                say(bot, recipeName + "切好了！");
                ConversationLogger.logBotCommand("CookTask: 砧板完成 → DONE");
            }
        } else {
            interactionTimer = COOLDOWN;
        }

        if (serveCount > 10) {
            fail("取成品失败，请手动取下");
        }
    }

    // ================================================================
    //  辅助方法
    // ================================================================

    private boolean hasItem(AiBotEntity bot, String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return false;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null) return false;
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            if (bot.getInventory().getItem(i).is(item)) return true;
        }
        return false;
    }

    private boolean ensureHasItem(AiBotEntity bot, String itemId) {
        if (hasItem(bot, itemId)) return true;
        BlockPos chest = findContainerWithItem(bot, itemId, 16);
        if (chest != null) {
            BlockEntity be = bot.level().getBlockEntity(chest);
            if (be instanceof Container container) {
                Item target = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(itemId));
                if (target != null && takeFromContainer(bot, container, target)) {
                    ConversationLogger.logBotCommand("CookTask: 从箱子补充 " + itemDisplay(itemId));
                    return true;
                }
            }
        }
        return false;
    }

    private String itemDisplay(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return itemId;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null) return itemId;
        return item.getName(new ItemStack(item)).getString();
    }

    private BlockPos findContainerWithItem(AiBotEntity bot, String itemId, int radius) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return null;
        Item target = ForgeRegistries.ITEMS.getValue(rl);
        if (target == null) return null;
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        BlockPos center = bot.blockPosition();
        int[] radii = {8, 16};
        for (int r : radii) {
            if (r > radius) r = radius;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        BlockEntity be = bot.level().getBlockEntity(mut);
                        if (be instanceof Container chest) {
                            for (int i = 0; i < chest.getContainerSize(); i++) {
                                if (chest.getItem(i).is(target)) {
                                    return mut.immutable();
                                }
                            }
                        }
                    }
                }
            }
            if (r >= radius) break;
        }
        return null;
    }

    private boolean takeFromContainer(AiBotEntity bot, Container chest, Item targetItem) {
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.is(targetItem)) {
                ItemStack taken = stack.split(1);
                if (stack.isEmpty()) chest.setItem(i, ItemStack.EMPTY);
                addToBotInventory(bot, taken);
                chest.setChanged();
                return true;
            }
        }
        return false;
    }

    private void addToBotInventory(AiBotEntity bot, ItemStack stack) {
        SimpleContainer inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack existing = inv.getItem(i);
            if (ItemStack.isSameItemSameTags(existing, stack)) {
                int space = existing.getMaxStackSize() - existing.getCount();
                if (space > 0) {
                    int add = Math.min(space, stack.getCount());
                    existing.grow(add);
                    stack.shrink(add);
                    if (stack.isEmpty()) return;
                }
            }
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.setItem(i, stack.copy());
                return;
            }
        }
    }

    // ================================================================
    //  扫描/属性/交互
    // ================================================================

    private BlockPos scanNearby(AiBotEntity bot, Block targetBlock, int maxRadius) {
        int[] radii = {8, 16, 32, 64};
        BlockPos center = bot.blockPosition();
        for (int r : radii) {
            if (r > maxRadius) r = maxRadius;
            BlockPos found = scanLayer(bot, targetBlock, center, r);
            if (found != null) return found;
            if (r >= maxRadius) break;
        }
        return null;
    }

    /** 扫描附近所有匹配方块 */
    private List<BlockPos> scanAllNearby(AiBotEntity bot, Block targetBlock, int maxRadius) {
        List<BlockPos> results = new ArrayList<>();
        BlockPos center = bot.blockPosition();
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int maxDy = Math.min(maxRadius, 10); // 上下各扫10格足够
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dy = -maxDy; dy <= maxDy; dy++) {
                for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                    mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (bot.level().getBlockState(mut).is(targetBlock)) {
                        results.add(mut.immutable());
                    }
                }
            }
        }
        return results;
    }

    /** 炉灶下方地面层+1 3×3水平扫描找站位，bot身高2格 */
    private double[] findStandPos(AiBotEntity bot, BlockPos blockPos) {
        BlockPos ground = blockPos.below(); // 炉灶下方地面层
        double[] bestPos = null;
        double bestDist = Double.MAX_VALUE;
        double bx = bot.getX(), bz = bot.getZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue; // 炉灶正下方，被炉灶挡住
                // 站位在地面层上方1格（bot站立处）
                BlockPos checkPos = ground.offset(dx, 1, dz);
                // 站位必须空气（bot的脚在这里）
                if (!bot.level().getBlockState(checkPos).isAir()) continue;
                // 头顶1格必须空气
                if (!bot.level().getBlockState(checkPos.above()).isAir()) continue;
                // 下方（地面层）必须是可站立方块（实心or薄层装饰）
                BlockState below = bot.level().getBlockState(checkPos.below());
                if (below.isAir()) continue; // 悬空不行
                // 炉灶高度不能有墙挡着
                if (!hasClearPathBetween(bot, checkPos, blockPos)) continue;

                double cx = checkPos.getX() + 0.5;
                double cz = checkPos.getZ() + 0.5;
                double dist = (cx - bx) * (cx - bx) + (cz - bz) * (cz - bz);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestPos = new double[]{cx, checkPos.getY(), cz};
                }
            }
        }
        return bestPos;
    }

    /** 检查目标方块高度是否有墙挡在中间（只查目标Y层，不查地面层） */
    private boolean hasClearPathBetween(AiBotEntity bot, BlockPos from, BlockPos to) {
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());
        int y = to.getY(); // 只查目标方块高度（墙挡在这个高度才挡住）
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (bx == from.getX() && bz == from.getZ()) continue;
                BlockPos p = new BlockPos(bx, y, bz);
                if (p.equals(to)) continue;
                if (!bot.level().getBlockState(p).isAir()) return false;
            }
        }
        return true;
    }

    private BlockPos scanLayer(AiBotEntity bot, Block targetBlock, BlockPos center, int radius) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (bot.level().getBlockState(mut).is(targetBlock)) {
                        return mut.immutable();
                    }
                }
            }
        }
        return null;
    }

    private Object getBlockProperty(BlockState state, String propName) {
        for (var prop : state.getProperties()) {
            if (prop.getName().equals(propName)) {
                return state.getValue(prop);
            }
        }
        return null;
    }

    private boolean rightClickBlock(AiBotEntity bot, BlockPos pos, Direction face, String itemId) {
        if (!(bot.level() instanceof ServerLevel serverLevel)) return false;

        FakePlayer fp = null;
        try {
            if (cachedFp == null || cachedLevel != serverLevel) {
                cachedFp = FakePlayerFactory.getMinecraft(serverLevel);
                cachedLevel = serverLevel;
            }
            fp = cachedFp;
            fp.setPos(bot.getX(), bot.getY(), bot.getZ());

            // 保存 Bot 背包快照
            SimpleContainer botInv = bot.getInventory();
            int invSize = botInv.getContainerSize();
            ItemStack[] botSnapshot = new ItemStack[invSize];
            for (int i = 0; i < invSize; i++) {
                ItemStack st = botInv.getItem(i);
                botSnapshot[i] = st.isEmpty() ? ItemStack.EMPTY : st.copy();
            }

            syncToFakePlayer(bot, fp);

            // 设置主手物品
            if (itemId != null && !itemId.isEmpty() && !itemId.equals("air")) {
                ResourceLocation rl = ResourceLocation.tryParse(itemId);
                if (rl == null) { failReason = "无法解析物品ID: " + itemId; return false; }
                Item item = ForgeRegistries.ITEMS.getValue(rl);
                if (item == null) { failReason = "物品未注册: " + itemId; return false; }
                boolean found = false;
                for (int i = 0; i < fp.getInventory().items.size(); i++) {
                    ItemStack s = fp.getInventory().items.get(i);
                    if (s.is(item)) {
                        ItemStack handStack = s.copy();
                        handStack.setCount(1);
                        fp.setItemInHand(InteractionHand.MAIN_HAND, handStack);
                        found = true;
                        break;
                    }
                }
                if (!found) { failReason = "背包中没有 " + itemId; return false; }
            } else {
                // 空手
                fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }

            BlockHitResult hit = new BlockHitResult(
                new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                face, pos, false);

            fp.gameMode.useItemOn(fp, serverLevel,
                fp.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, hit);

            // 恢复 Bot 快照 + 扫描新增物品
            for (int i = 0; i < invSize; i++) {
                botInv.setItem(i, botSnapshot[i].isEmpty() ? ItemStack.EMPTY : botSnapshot[i].copy());
            }
            Set<Item> newItemsFound = new HashSet<>();
            for (int i = 0; i < fp.getInventory().items.size(); i++) {
                ItemStack fpStack = fp.getInventory().items.get(i);
                if (fpStack.isEmpty()) continue;
                if (!isInSnapshot(botSnapshot, fpStack)) {
                    addToBotInventory(bot, fpStack.copy());
                    newItemsFound.add(fpStack.getItem());
                }
            }
            ItemStack handItem = fp.getItemInHand(InteractionHand.MAIN_HAND);
            fp.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (!handItem.isEmpty() && !isInSnapshot(botSnapshot, handItem)
                    && !newItemsFound.contains(handItem.getItem())) {
                addToBotInventory(bot, handItem);
            }
            for (int i = 0; i < fp.getInventory().armor.size(); i++) {
                ItemStack stack = fp.getInventory().armor.get(i);
                if (!stack.isEmpty() && !isInSnapshot(botSnapshot, stack)
                        && !newItemsFound.contains(stack.getItem())) {
                    addToBotInventory(bot, stack.copy());
                    newItemsFound.add(stack.getItem());
                }
            }
            ItemStack offhand = fp.getInventory().offhand.get(0);
            if (!offhand.isEmpty() && !isInSnapshot(botSnapshot, offhand)
                    && !newItemsFound.contains(offhand.getItem())) {
                addToBotInventory(bot, offhand.copy());
            }
            fp.getInventory().clearContent();
            return true;
        } catch (Exception e) {
            ConversationLogger.logError("CookTask rightClickBlock failed: " + e.getMessage());
            if (fp != null) {
                fp.getInventory().clearContent();
                cachedFp = null;
                cachedLevel = null;
            }
            return false;
        }
    }

    private boolean isInSnapshot(ItemStack[] snapshot, ItemStack stack) {
        for (ItemStack snap : snapshot) {
            if (!snap.isEmpty() && snap.is(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    private void syncToFakePlayer(AiBotEntity bot, FakePlayer fp) {
        fp.getInventory().clearContent();
        SimpleContainer botInv = bot.getInventory();
        int count = Math.min(botInv.getContainerSize(), fp.getInventory().items.size());
        for (int i = 0; i < count; i++) {
            ItemStack stack = botInv.getItem(i);
            if (!stack.isEmpty()) {
                fp.getInventory().items.set(i, stack.copy());
            }
        }
    }

    private void drainFakePlayer(AiBotEntity bot) {
        if (cachedFp == null) return;
        FakePlayer fp = cachedFp;
        for (int i = 0; i < fp.getInventory().getContainerSize(); i++) {
            ItemStack stack = fp.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                addToBotInventory(bot, stack.copy());
                fp.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private void pickupNearbyItems(AiBotEntity bot, BlockPos center, double radius) {
        if (!(bot.level() instanceof ServerLevel serverLevel)) return;
        AABB area = new AABB(center).inflate(radius);
        for (ItemEntity itemEntity : serverLevel.getEntitiesOfClass(ItemEntity.class, area)) {
            ItemStack stack = itemEntity.getItem();
            if (!stack.isEmpty()) {
                addToBotInventory(bot, stack.copy());
                itemEntity.discard();
            }
        }
    }

    private ItemStack takeFromBot(AiBotEntity bot, Item item) {
        SimpleContainer inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                ItemStack taken = s.split(1);
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 检查炖锅是否有锅盖（检测锅体 has_lid 属性） */
    private boolean hasLidOnBlock(AiBotEntity bot, BlockPos pos) {
        BlockState state = bot.level().getBlockState(pos);
        Object hasLid = getBlockProperty(state, "has_lid");
        return hasLid instanceof Boolean && (Boolean) hasLid;
    }

    /** 返回锅体位置（取盖直接右键此位置） */
    private BlockPos getLidPosition(AiBotEntity bot, BlockPos pos) {
        return hasLidOnBlock(bot, pos) ? pos : null;
    }

    /**
     * 判断锅里的食物是否已经做好了（可盛出）。
     * POT: show_oil 为 false（油已煮干）→ 菜做好了（has_oil 仍为 true）
     * STOCKPOT: has_oil 为 true 但锅盖已不在 → 炖好了
     */
    private boolean isFoodReady(AiBotEntity bot, BlockState cookwareState, BlockPos cookwarePos) {
        if (cookwareType == CookwareType.POT) {
            Object showOil = getBlockProperty(cookwareState, "show_oil");
            return showOil instanceof Boolean && !(Boolean) showOil;
        } else if (cookwareType == CookwareType.STOCKPOT) {
            // 炖锅煮好后盖子通常会弹出或被移除
            return !hasLidOnBlock(bot, cookwarePos);
        }
        return false;
    }
}
