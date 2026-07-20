package com.aibot.mod.entity;

import com.aibot.mod.AiBotMod;
import com.aibot.mod.Config;
import com.aibot.mod.ConversationLogger;
import com.aibot.mod.TranslationDictionary;
import com.aibot.mod.entity.actions.ActionCoordinator;
import com.aibot.mod.entity.actions.BotAction;
import com.aibot.mod.entity.actions.ChopTreeAction;
import com.aibot.mod.entity.actions.MineTunnelAction;
import com.aibot.mod.entity.actions.FarmAction;
import com.aibot.mod.entity.actions.SleepAction;
import com.aibot.mod.entity.actions.FollowAction;
import com.aibot.mod.entity.actions.GotoAction;
import com.aibot.mod.entity.actions.HuntAction;
import com.aibot.mod.entity.actions.InteractAction;
import com.aibot.mod.task.BaseTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.IPlantable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.Item;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;
import com.google.gson.Gson;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class AiBotEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> DATA_STATUS =
            SynchedEntityData.defineId(AiBotEntity.class, EntityDataSerializers.STRING);

    private String currentAction = "";
    private int actionTimer = 0;
    private BlockPos targetBlockPos = null;
    private int mineProgress = 0;
    private int chopCount = 0;
    private int mineCount = 0;
    private int maxChopCount = 27;
    private int maxMineCount = 64;
    private static final int DEFAULT_MAX_CHOP = 27;
    private static final int DEFAULT_MAX_MINE = 64;
    private int navStuckTicks = 0;
    private double lastNavDist = 0;

    // ===== 露天挖掘系统 =====
    private BlockPos surfaceMineTarget = null;                // 当前挖掘目标
    private int surfaceMineProgress = 0;                      // 挖掘进度

    // ===== 任务列表系统（plan 命令使用） =====
    private List<Task> taskList = null;
    private int taskIndex = 0;
    private int taskStartCount = -1;

    private final SimpleContainer inventory = new SimpleContainer(36);
    private int selectedSlot = 0;                           // 当前选中的快捷栏格子 (0-8)，控制主手显示
    private LivingEntity followTarget = null;               // 跟随目标玩家

    // ===== 本地引擎任务系统 =====
    private static final java.util.concurrent.ConcurrentHashMap<Integer, com.aibot.mod.task.BaseTask> pendingTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Integer, String> pendingCommands = new java.util.concurrent.ConcurrentHashMap<>();
    private com.aibot.mod.task.BaseTask currentTask = null;

    // ===== 行为模式 =====
    private boolean homeMode = false;
    private BlockPos homePos = null;
    private static final int HOME_RADIUS = 48;

    // === 玩家绑定 ===
    private String ownerUuid = null;
    private String ownerName = null;

    // === 注视玩家 ===
    private int lookAtPlayerTimer = 0;
    private int lookAtPlayerCooldown = 0;

    // === 动作总控 ===
    public final ActionCoordinator coordinator = new ActionCoordinator();

    // ===== 状态管理 =====
    private String craftTargetItem = null;
    private int craftTargetCount = 0;
    private int craftPhase = 0;  // 0=init, 1=find_table, 2=gototable, 3=docraft, 4=done

    // ===== 交互系统（对任意方块/实体右键操作） =====
    private String interactTarget = null;      // 目标注册名（方块或实体）
    private String interactItem = null;        // 手持物品注册名（可空）
    private int interactPhase = 0;             // 0=init, 1=find, 2=holditem, 3=gototarget, 4=interact, 5=done
    private String interactType = "BLOCK";     // BLOCK / ENTITY
    private BlockPos interactBlockPos = null;
    private net.minecraft.world.entity.Entity interactEntity = null;

    public AiBotEntity(EntityType<? extends PathfinderMob> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!this.level().isClientSide) {
            initCoordinator();
        }
    }

    private void initCoordinator() {
        // 注册所有动作工具
        coordinator.register("chop", (bot, args) -> {
            int count = DEFAULT_MAX_CHOP;
            if (args.length >= 1) try { count = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
            return new ChopTreeAction(bot, count);
        });
        coordinator.register("mine", (bot, args) -> {
            int count = DEFAULT_MAX_MINE;
            if (args.length >= 1) try { count = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
            return new MineTunnelAction(bot, count);
        });
        coordinator.register("farm", (bot, args) -> new FarmAction(bot));
        coordinator.register("sleep", (bot, args) -> new SleepAction(bot));
        coordinator.register("follow", (bot, args) -> new FollowAction(bot));
        coordinator.register("goto", (bot, args) -> {
            if (args.length >= 3) {
                try {
                    int x = Integer.parseInt(args[0]);
                    int y = Integer.parseInt(args[1]);
                    int z = Integer.parseInt(args[2]);
                    return new GotoAction(bot, new BlockPos(x, y, z));
                } catch (NumberFormatException ignored) {}
            }
            return null;
        });
        coordinator.register("hunt", (bot, args) -> new HuntAction(bot));
        coordinator.register("interact", (bot, args) -> {
            String target = args.length >= 1 ? args[0] : "";
            String item = args.length >= 2 ? args[1] : null;
            return new InteractAction(bot, target, item);
        });
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 64.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_STATUS, "Idle");
    }

    /** 防止玩家远离时自然清除 */
    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(1, new AiBotActionGoal(this));
        this.goalSelector.addGoal(2, new AiBotPickupGoal(this));
        this.goalSelector.addGoal(3, new AiBotWanderGoal(this));
    }

    @Override
    public InteractionResult interactAt(Player pPlayer, Vec3 pVec3, InteractionHand pHand) {
        if (pPlayer instanceof ServerPlayer serverPlayer) {
            net.minecraftforge.network.NetworkHooks.openScreen(serverPlayer, new AiBotMenuProvider(this), buf -> buf.writeInt(this.getId()));
        }
        return InteractionResult.SUCCESS;
    }

    /** 主动注视附近的玩家，类似村民行为 */
    @Override
    public void customServerAiStep() {
        super.customServerAiStep();

        if (lookAtPlayerCooldown > 0) {
            lookAtPlayerCooldown--;
            return;
        }

        Player nearest = this.level().getNearestPlayer(this, 3.0D);
        if (nearest != null && nearest.isAlive()) {
            this.getLookControl().setLookAt(nearest, 30.0F, 30.0F);

            if (lookAtPlayerTimer <= 0) {
                lookAtPlayerTimer = 20 + this.random.nextInt(40); // 1~3 秒（20~60 ticks）
            }

            lookAtPlayerTimer--;
            if (lookAtPlayerTimer <= 0) {
                lookAtPlayerCooldown = 100 + this.random.nextInt(500); // 5~30 秒冷却
            }
        } else {
            lookAtPlayerTimer = 0;
        }
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    public int getSelectedSlot() { return selectedSlot; }

    /** 切换快捷栏选中格 (0-8)，主手自动同步 */
    public void setSelectedSlot(int slot) {
        if (slot >= 0 && slot < 9) {
            this.selectedSlot = slot;
        }
    }

    /** 同步主手显示 = 当前选中快捷栏物品 */
    private void syncSelectedSlot() {
        ItemStack held = inventory.getItem(selectedSlot);
        this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, held.isEmpty() ? ItemStack.EMPTY : held.copy());
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) {
            com.aibot.mod.AiBotMod.LOGGER.debug("[Entity] aiStep running, tickCount={}, hasTask={}, pendingTask={}",
                this.tickCount, currentTask != null, pendingTasks.containsKey(this.getId()));
            pickUpItemsNearby();

            if (this.tickCount % 40 == 0) {
                autoEquip();
            }

            // 处理内部命令（由工具通过 sendCommand 发送）
            String cmd = pendingCommands.remove(this.getId());
            if (cmd != null) {
                handleInternalCommand(cmd);
            }

            // 处理引擎任务（由工具通过 setTask 发送）
            BaseTask pending = pendingTasks.remove(this.getId());
            if (pending != null) {
                com.aibot.mod.AiBotMod.LOGGER.info("[Entity] Task picked up: {}", pending.getName());
                if (currentTask != null) {
                    ConversationLogger.logBotCommand("打断当前任务: " + currentTask.getName());
                }
                coordinator.stop(this);
                taskList = null;
                taskIndex = 0;
                currentTask = pending;
                ConversationLogger.logBotCommand("引擎任务开始: " + currentTask.getName());
            }
            if (currentTask != null && !currentTask.isDone()) {
                com.aibot.mod.AiBotMod.LOGGER.info("[Entity] Ticking task: {} (done={}, success={})",
                    currentTask.getName(), currentTask.isDone(), currentTask.isSuccess());
                currentTask.tick(this);
                if (currentTask.isDone()) {
                    ConversationLogger.logBotCommand("Task " + currentTask.getName()
                        + (currentTask.isSuccess() ? " 完成" : " 失败: " + currentTask.getFailReason()));
                    currentTask = null;
                }
                return;
            }

            syncSelectedSlot();
        }
    }

    /** 在 64 格范围内找到最近的玩家 */
    public LivingEntity findNearestPlayer() {
        List<Player> players = this.level().getEntitiesOfClass(Player.class,
                this.getBoundingBox().inflate(64.0D));
        LivingEntity nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Player p : players) {
            double dist = this.distanceToSqr(p);
            if (dist < minDist) {
                minDist = dist;
                nearest = p;
            }
        }
        return nearest;
    }
    
    @Override
    public int getMaxFallDistance() {
        return 3; // Allow pathfinding through 3-block drops
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanFloat(false);
        nav.setCanOpenDoors(true);
        return nav;
    }

    private void pickUpItemsNearby() {
        List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class,
                new AABB(this.blockPosition()).inflate(3.0D, 3.0D, 3.0D));
        
        for (ItemEntity item : items) {
            if (item.isAlive() && !item.hasPickUpDelay()) {
                ItemStack stack = item.getItem();
                if (addToInventory(stack.copy())) {
                    stack.shrink(stack.getCount());
                    if (stack.isEmpty()) {
                        item.discard();
                    } else {
                        item.setItem(stack);
                    }
                }
            }
        }
    }

    private boolean addToInventory(ItemStack stack) {
        if (stack.isEmpty()) return false;
        
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty()) {
                inventory.setItem(i, stack);
                return true;
            }
            if (ItemStack.isSameItemSameTags(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int toAdd = Math.min(space, stack.getCount());
                slot.grow(toAdd);
                stack.shrink(toAdd);
                if (stack.isEmpty()) return true;
            }
        }
        return false;
    }

    private int getInventoryCount() {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            count += inventory.getItem(i).getCount();
        }
        return count;
    }

    public boolean isInventoryFull() {
        int occupied = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) occupied++;
        }
        return occupied >= 36; // 36+ occupied slots = full
    }

    private ItemStack findItemInInventory(ItemStack match) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItem(slot, match)) {
                return slot;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 由客户端工具调用，通过静态 ConcurrentHashMap 传到服务端 */
    public static void sendCommand(int entityId, String command) {
        if (command != null && !command.isEmpty()) {
            pendingCommands.put(entityId, command.toLowerCase());
        }
    }

    /** 分配引擎任务（由 BotController 在客户端调用，通过 pendingTasks 传到服务端） */
    public void setTask(com.aibot.mod.task.BaseTask task) {
        if (task != null) {
            pendingTasks.put(this.getId(), task);
            ConversationLogger.logBotCommand("分配引擎任务(客户端): " + task.getName());
        }
    }

    public com.aibot.mod.task.BaseTask getCurrentTask() { return currentTask; }

    /** 内部命令处理（仅配置/管理类命令，不处理动作类命令） */
    private void handleInternalCommand(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length == 0) return;

        String action = parts[0];
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        switch (action) {
            case "停止", "stop" -> {
                coordinator.stop(this);
                currentTask = null;
                taskList = null;
                taskIndex = 0;
                setStatus("Idle");
            }
            case "plan_now" -> {
                currentTask = null;
                taskList = null;
                taskIndex = 0;
                setStatus("Idle");
            }
            case "plan" -> {
                // plan <json> 格式：从 AI 接收任务列表
                if (args.length < 1) {
                    setStatus("Plan: no tasks");
                    break;
                }
                String json = String.join(" ", args);
                try {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    com.google.gson.reflect.TypeToken<List<Task>> type =
                        new com.google.gson.reflect.TypeToken<List<Task>>() {};
                    List<Task> parsed = gson.fromJson(json, type.getType());
                    if (parsed == null || parsed.isEmpty()) {
                        setStatus("Plan: empty tasks");
                        break;
                    }
                    taskList = parsed;
                    taskIndex = 0;
                    taskStartCount = -1;
                    currentAction = "TASK";
                    setStatus("Plan: " + taskList.get(0).desc);
                } catch (Exception e) {
                    setStatus("Plan parse error: " + e.getMessage());
                }
            }
            case "sethome" -> {
                if (args.length >= 3) {
                    try {
                        int x = Integer.parseInt(args[0]);
                        int y = Integer.parseInt(args[1]);
                        int z = Integer.parseInt(args[2]);
                        homePos = new BlockPos(x, y, z);
                        setStatus("Home set to " + x + "," + y + "," + z);
                    } catch (NumberFormatException e) {
                        setStatus("Invalid coordinates for sethome");
                    }
                } else {
                    homePos = this.blockPosition();
                    setStatus("Home set to current position");
                }
            }
            case "homemode" -> {
                homeMode = !homeMode;
                if (homeMode && homePos == null) {
                    homePos = this.blockPosition();
                }
                setStatus(homeMode ? "Home mode ON (radius " + HOME_RADIUS + ")" : "Home mode OFF");
            }
            case "bind" -> {
                LivingEntity player = findNearestPlayer();
                if (player instanceof Player p) {
                    this.ownerUuid = p.getUUID().toString();
                    this.ownerName = p.getName().getString();
                    setStatus("Bound to " + this.ownerName);
                } else {
                    setStatus("No player nearby to bind");
                }
            }
            case "unbind" -> {
                this.ownerUuid = null;
                this.ownerName = null;
                setStatus("Unbound");
            }
            default -> setStatus("Unknown command: " + action);
        }
    }

    private boolean canReachBlock(BlockPos pos) {
        double horizontalDist = Math.sqrt(
            Math.pow(pos.getX() + 0.5 - this.getX(), 2) + 
            Math.pow(pos.getZ() + 0.5 - this.getZ(), 2));
        double verticalDist = Math.abs(pos.getY() + 0.5 - (this.getY() + this.getBbHeight() / 2));
        double reach = 5.0D;
        double maxVerticalReach = 7.0D;
        return horizontalDist <= reach && verticalDist <= maxVerticalReach;
    }

    // breakLeavesAround, findNearestLog, destroyTreeChain removed — replaced by ActionCoordinator/ChopTreeAction

    private boolean isLeafBlock(BlockState state) {
        return state.is(Blocks.OAK_LEAVES) || state.is(Blocks.BIRCH_LEAVES) ||
               state.is(Blocks.SPRUCE_LEAVES) || state.is(Blocks.JUNGLE_LEAVES) ||
               state.is(Blocks.ACACIA_LEAVES) || state.is(Blocks.DARK_OAK_LEAVES) ||
               state.is(Blocks.MANGROVE_LEAVES) || state.is(Blocks.CHERRY_LEAVES) ||
               state.is(Blocks.AZALEA_LEAVES) || state.is(Blocks.FLOWERING_AZALEA_LEAVES);
    }

    // findNearestTreeBase removed — replaced by ActionCoordinator/ChopTreeAction

    private BlockPos findNearestOre() {
        BlockPos playerPos = this.blockPosition();
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        int[] ranges = {8, 16, 32};

        for (int range : ranges) {
            for (int dx = -range; dx <= range; dx++) {
                for (int dy = -20; dy <= 5; dy++) {
                    for (int dz = -range; dz <= range; dz++) {
                        BlockPos pos = playerPos.offset(dx, dy, dz);
                        BlockState state = this.level().getBlockState(pos);
                        if (isOreBlock(state)) {
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

    private boolean isLogBlock(BlockState state) {
        return state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG) ||
               state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.JUNGLE_LOG) ||
               state.is(Blocks.ACACIA_LOG) || state.is(Blocks.DARK_OAK_LOG) ||
               state.is(Blocks.MANGROVE_LOG) || state.is(Blocks.CHERRY_LOG) ||
               state.is(Blocks.BAMBOO_BLOCK);
    }

    private boolean isOreBlock(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.IRON_ORE) ||
               state.is(Blocks.COPPER_ORE) || state.is(Blocks.GOLD_ORE) ||
               state.is(Blocks.LAPIS_ORE) || state.is(Blocks.REDSTONE_ORE) ||
               state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.EMERALD_ORE) ||
               state.is(Blocks.DEEPSLATE_COAL_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE) ||
               state.is(Blocks.DEEPSLATE_COPPER_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE) ||
               state.is(Blocks.DEEPSLATE_LAPIS_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE) ||
               state.is(Blocks.DEEPSLATE_DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE) ||
               state.is(Blocks.NETHER_GOLD_ORE) || state.is(Blocks.NETHER_QUARTZ_ORE);
    }

    private boolean isStoneBlock(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.ANDESITE) ||
               state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE) ||
               state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF) ||
               state.is(Blocks.COBBLESTONE);
    }

    // chopTree removed — replaced by ActionCoordinator/ChopTreeAction

    // ========== 矿道挖掘系统 removed — replaced by ActionCoordinator/MineTunnelAction ==========

    /** 导航到指定位置，到达返回 true */
    private boolean navigateToPos(BlockPos pos) {
        double dist = this.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        // 已经站在目标位置
        if (dist <= 0.5 * 0.5) return true;

        // 导航正在进行中
        if (this.getNavigation().isInProgress()) {
            // 导航认为已完成
            if (this.getNavigation().isDone()) {
                double distAfter = this.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distAfter <= 2.5 * 2.5) return true;
                // 导航说完成了但实际没到（被阻挡），重新尝试
                this.getNavigation().stop();
            }
            // 检查是否卡住（距离不再缩小）
            if (dist < lastNavDist - 0.05) {
                navStuckTicks = 0;
            } else {
                navStuckTicks++;
                if (navStuckTicks > 100) {
                    navStuckTicks = 0;
                    this.getNavigation().stop();
                    return true; // 卡死太久，跳过
                }
            }
            lastNavDist = dist;
            return false;
        }

        // 导航未启动，启动导航
        boolean started = this.getNavigation().moveTo(
            pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0D);

        if (!started) {
            navStuckTicks++;
            if (navStuckTicks > 20) {
                navStuckTicks = 0;
                return true; // 无法寻路，跳过
            }
            return false;
        }
        lastNavDist = dist;
        return false;
    }

    // scanForOresOnSameLevel, calcHorizontalTunnelBlocks removed

    // ========== 露天挖掘系统 ==========

    /** 扫描附近指定类型的方块 */
    private BlockPos scanSurfaceBlock(int rangeH, int rangeV, java.util.function.Predicate<BlockState> matcher) {
        BlockPos botPos = this.blockPosition();
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        for (int dx = -rangeH; dx <= rangeH; dx++) {
            for (int dy = -rangeV; dy <= rangeV; dy++) {
                for (int dz = -rangeH; dz <= rangeH; dz++) {
                    BlockPos pos = botPos.offset(dx, dy, dz);
                    BlockState state = this.level().getBlockState(pos);
                    if (matcher.test(state)) {
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

    /** 挖掉一个目标方块（露天挖掘通用逻辑） */
    private boolean breakSurfaceBlock(BlockPos target) {
        BlockState state = this.level().getBlockState(target);
        if (state.isAir()) {
            surfaceMineTarget = null;
            surfaceMineProgress = 0;
            return false;
        }

        double dist = this.distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        if (dist > 4.5 * 4.5) {
            boolean started = this.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0D);
            if (!started || dist > 64.0 * 64.0) {
                surfaceMineTarget = null;
            }
            return false;
        }

        if (!canReachBlock(target)) {
            surfaceMineTarget = null;
            return false;
        }

        this.getNavigation().stop();
        this.getLookControl().setLookAt(
            target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        equipBestPickaxe();
        this.swing(InteractionHand.MAIN_HAND);
        surfaceMineProgress++;

        if (surfaceMineProgress >= 20) {
            this.level().destroyBlock(target, true, this);
            mineCount++;
            surfaceMineProgress = 0;
            surfaceMineTarget = null;

            ItemStack pickaxe = this.getMainHandItem();
            if (!pickaxe.isEmpty()) {
                pickaxe.hurtAndBreak(1, this, (e) -> {});
            }
            setStatus("Mined " + mineCount + "/" + maxMineCount);
            return true;
        } else {
            float pct = (float) surfaceMineProgress / 20.0F;
            setStatus("Mining... " + (int) (pct * 100) + "% " + mineCount + "/" + maxMineCount);
            return false;
        }
    }

    /** 露天挖石头（MINE_STONE） */
    private void simpleMineStone() {
        if (mineCount >= maxMineCount) {
            setStatus("Done! Stone: " + mineCount + "/" + maxMineCount);
            currentAction = "";
            return;
        }
        if (isInventoryFull()) {
            setStatus("Inventory full! Stone: " + mineCount);
            currentAction = "";
            return;
        }

        // 有目标则继续挖
        if (surfaceMineTarget != null) {
            breakSurfaceBlock(surfaceMineTarget);
            return;
        }

        // 找新的石头
        BlockPos stone = scanSurfaceBlock(24, 8, this::isStoneBlock);
        if (stone == null) {
            setStatus("No stone found nearby");
            currentAction = "";
            return;
        }
        surfaceMineTarget = stone;
        setStatus("Found stone, moving...");
    }

    /** 露天挖矿石（MINE_ORE）：找裸露在地表的矿石 */
    private void simpleMineOre() {
        if (mineCount >= maxMineCount) {
            setStatus("Done! Ore: " + mineCount + "/" + maxMineCount);
            currentAction = "";
            return;
        }
        if (isInventoryFull()) {
            setStatus("Inventory full! Ore: " + mineCount);
            currentAction = "";
            return;
        }

        if (surfaceMineTarget != null) {
            breakSurfaceBlock(surfaceMineTarget);
            return;
        }

        BlockPos ore = scanSurfaceBlock(24, 6, this::isOreBlock);
        if (ore == null) {
            setStatus("No surface ore found nearby");
            currentAction = "";
            return;
        }
        surfaceMineTarget = ore;
        setStatus("Found ore, moving...");
    }

    private void equipBestAxe() {
        ItemStack current = this.getItemInHand(InteractionHand.MAIN_HAND);
        if (!current.isEmpty() && (current.is(Items.WOODEN_AXE) || current.is(Items.STONE_AXE) || 
            current.is(Items.IRON_AXE) || current.is(Items.GOLDEN_AXE) || 
            current.is(Items.DIAMOND_AXE) || current.is(Items.NETHERITE_AXE))) {
            return;
        }
        
        ItemStack bestAxe = findAndRemoveTool(
                Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.GOLDEN_AXE,
                Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE);
        
        if (!bestAxe.isEmpty()) {
            this.setItemInHand(InteractionHand.MAIN_HAND, bestAxe);
        }
    }

    private void equipBestPickaxe() {
        ItemStack current = this.getItemInHand(InteractionHand.MAIN_HAND);
        if (!current.isEmpty() && (current.is(Items.WOODEN_PICKAXE) || current.is(Items.STONE_PICKAXE) || 
            current.is(Items.IRON_PICKAXE) || current.is(Items.GOLDEN_PICKAXE) || 
            current.is(Items.DIAMOND_PICKAXE) || current.is(Items.NETHERITE_PICKAXE))) {
            return;
        }
        
        ItemStack bestPickaxe = findAndRemoveTool(
                Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.GOLDEN_PICKAXE,
                Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE);
        
        if (!bestPickaxe.isEmpty()) {
            this.setItemInHand(InteractionHand.MAIN_HAND, bestPickaxe);
        }
    }

    private ItemStack findBestTool(net.minecraft.world.item.Item... tools) {
        for (net.minecraft.world.item.Item tool : tools) {
            ItemStack found = findItemInInventory(new ItemStack(tool));
            if (!found.isEmpty()) {
                return found;
            }
        }
        return ItemStack.EMPTY;
    }

    public ItemStack findAndRemoveTool(net.minecraft.world.item.Item... tools) {
        for (net.minecraft.world.item.Item tool : tools) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack slot = inventory.getItem(i);
                if (!slot.isEmpty() && slot.is(tool)) {
                    ItemStack toolStack = slot.split(1);
                    return toolStack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /** 自动穿戴：背包里可穿戴的物品（盔甲/头盔/鞘翅等）自动穿到对应槽位 */
    private void autoEquip() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            EquipmentSlot slot = Mob.getEquipmentSlotForItem(stack);
            if (slot.getType() != EquipmentSlot.Type.ARMOR) continue;

            ItemStack current = this.getItemBySlot(slot);

            // 槽位空→直接穿；有东西→用防御值比较，都0防御（装饰品）就不替换
            if (current.isEmpty()) {
                this.setItemSlot(slot, stack.copy());
                stack.setCount(0);
                inventory.setItem(i, ItemStack.EMPTY);
            } else {
                int newDef = getArmorDefense(stack);
                int curDef = getArmorDefense(current);
                if (newDef > curDef) {
                    addToInventory(current);
                    this.setItemSlot(slot, stack.copy());
                    stack.setCount(0);
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    private int getArmorDefense(ItemStack stack) {
        if (stack.getItem() instanceof ArmorItem armor) {
            return armor.getDefense();
        }
        return 0;
    }

    // gotoPosition removed — replaced by ActionCoordinator/GotoAction

    private void updateStatus() {
        if (!currentAction.isEmpty()) {
            setStatus(currentAction + " - " + chopCount + "/" + maxChopCount);
        }
    }

    public void setStatus(String status) {
        this.entityData.set(DATA_STATUS, status);
    }

    public String getStatus() {
        return this.entityData.get(DATA_STATUS);
    }

    public String getCurrentAction() { return currentAction; }
    public boolean hasActivePlan() {
        return taskList != null && taskIndex < taskList.size();
    }
    public boolean isIdle() {
        return currentAction.isEmpty()
            && (taskList == null || taskIndex >= (taskList == null ? 0 : taskList.size()))
            && coordinator.isIdle();
    }

    // === 玩家绑定 ===
    public String getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public boolean hasOwner() { return ownerUuid != null; }
    public boolean isOwnedBy(Player player) { return ownerUuid != null && ownerUuid.equals(player.getUUID().toString()); }
    public void setOwner(String uuid, String name) { this.ownerUuid = uuid; this.ownerName = name; }
    public void clearOwner() { this.ownerUuid = null; this.ownerName = null; }

    public BlockPos getHomePos() {
        return homePos;
    }

    public void say(String message) {
        if (this.level() instanceof ServerLevel serverLevel) {
            net.minecraft.network.chat.Component component = net.minecraft.network.chat.Component.literal(message);
            serverLevel.getServer().getPlayerList().broadcastSystemMessage(component, false);
            AiBotMod.LOGGER.info("[AI Bot] Says: {}", message);
        }
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource source) {
        super.die(source);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        CompoundTag invTag = new CompoundTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                invTag.put(String.valueOf(i), stack.save(new CompoundTag()));
            }
        }
        tag.put("Inventory", invTag);
        if (ownerUuid != null) {
            tag.putString("OwnerUuid", ownerUuid);
            tag.putString("OwnerName", ownerName);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Inventory")) {
            CompoundTag invTag = tag.getCompound("Inventory");
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (invTag.contains(String.valueOf(i))) {
                    inventory.setItem(i, ItemStack.of(invTag.getCompound(String.valueOf(i))));
                }
            }
        }
        if (tag.contains("OwnerUuid")) {
            ownerUuid = tag.getString("OwnerUuid");
            ownerName = tag.getString("OwnerName");
        }
    }

    // ========== 种地系统 ==========

    /** 检查方块是否为耕地 */
    private boolean isFarmland(BlockState state) {
        return state.is(Blocks.FARMLAND) || state.getBlock() instanceof FarmBlock;
    }

    /** 检查方块是否为作物（支持所有 CropBlock + NetherWartBlock + BlockTags.CROPS） */
    private boolean isCropBlock(BlockState state) {
        return state.is(BlockTags.CROPS)
            || state.getBlock() instanceof CropBlock
            || state.getBlock() instanceof NetherWartBlock;
    }

    /** 检查作物是否成熟（通用：取最大年龄对比） */
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

    /** 检查背包中是否有种子 */
    private boolean hasSeedsInInventory() {
        return !findSeedInInventory().isEmpty();
    }

    /** 判断物品是否为种子类（CropBlock / IPlantable / NetherWartBlock） */
    private boolean isSeedItem(Item item) {
        if (item instanceof BlockItem bi) {
            var block = bi.getBlock();
            return block instanceof CropBlock || block instanceof NetherWartBlock
                || block instanceof IPlantable;
        }
        return false;
    }

    /** 在背包中查找一个种子 */
    private ItemStack findSeedInInventory() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && isSeedItem(stack.getItem())) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 根据种子获取作物方块状态（通用：IPlantable / CropBlock / NetherWartBlock） */
    private BlockState getCropForSeed(Item seedItem) {
        if (seedItem instanceof BlockItem bi) {
            var block = bi.getBlock();
            if (block instanceof IPlantable plantable) {
                return plantable.getPlant(this.level(), BlockPos.ZERO);
            }
            if (block instanceof CropBlock || block instanceof NetherWartBlock) {
                return block.defaultBlockState();
            }
        }
        return null;
    }

    // findNextFarmAction removed — replaced by ActionCoordinator/FarmAction

    // plantSeed removed — replaced by ActionCoordinator/FarmAction

    // harvestCrop removed — replaced by ActionCoordinator/FarmAction

    // farmAction removed — replaced by ActionCoordinator/FarmAction

    // hasMatureCropsNearby removed — replaced by ActionCoordinator/FarmAction

    // ========== 睡觉系统 ==========

    /** 检查方块是否为床 */
    private boolean isBed(BlockState state) {
        return state.is(BlockTags.BEDS);
    }

    /** 寻找 32 格范围内最近的床 */
    private BlockPos findNearestBed() {
        BlockPos botPos = this.blockPosition();
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        int range = 32;

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = botPos.offset(dx, dy, dz);
                    BlockState state = this.level().getBlockState(pos);
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

    // sleepAction removed — replaced by ActionCoordinator/SleepAction

    // ========== 任务列表执行器 ==========

    /** 任务主循环（每tick调用） */
    private void taskLoop() {
        if (taskList == null || taskIndex >= taskList.size()) {
            // 任务列表完成
            taskList = null;
            taskIndex = 0;
            currentAction = "";
            setStatus("All tasks complete!");
            return;
        }

        Task task = taskList.get(taskIndex);
        if (task == null) {
            taskIndex++;
            return;
        }

        // 执行当前任务
        boolean done = executeTask(task);
        if (done) {
            taskIndex++;
            taskStartCount = -1;
            if (taskIndex < taskList.size()) {
                setStatus("Task: " + taskList.get(taskIndex).desc);
            }
        }
    }

    /** 执行单个任务，返回 true 表示任务完成 */
    private boolean executeTask(Task task) {
        if (task == null) return true;

        switch (task.type) {
            case "chop_wood", "chop" -> {
                if (chopCount >= task.count) return true;
                if (!currentAction.equals("CHOP_TREE")) {
                    chopCount = 0;
                    currentAction = "CHOP_TREE";
                    coordinator.start("chop", new String[]{String.valueOf(task.count)}, this);
                    if (taskStartCount < 0) taskStartCount = 0;
                }
                return false; // coordinator 在运行
            }
            case "mine" -> {
                if (mineCount >= task.count) return true;
                if (!currentAction.equals("MINE")) {
                    mineCount = 0;
                    currentAction = "MINE";
                    coordinator.start("mine", new String[]{String.valueOf(task.count)}, this);
                    taskStartCount = mineCount;
                }
                return false; // coordinator 在运行
            }
            case "mine_stone" -> {
                if (mineCount >= task.count) return true;
                if (!currentAction.equals("MINE_STONE")) {
                    mineCount = 0; mineProgress = 0;
                    surfaceMineTarget = null; surfaceMineProgress = 0;
                    currentAction = "MINE_STONE";
                    taskStartCount = mineCount;
                }
                return false;
            }
            case "mine_ore" -> {
                if (mineCount >= task.count) return true;
                if (!currentAction.equals("MINE_ORE")) {
                    mineCount = 0; mineProgress = 0;
                    surfaceMineTarget = null; surfaceMineProgress = 0;
                    currentAction = "MINE_ORE";
                    taskStartCount = mineCount;
                }
                return false;
            }
            case "farm" -> {
                if (!currentAction.equals("FARM")) {
                    currentAction = "FARM";
                    coordinator.start("farm", new String[0], this);
                    taskStartCount = 0;
                }
                if (coordinator.isIdle() && currentAction.isEmpty()) return true;
                return false;
            }
            case "sleep" -> {
                if (!currentAction.equals("SLEEP")) {
                    currentAction = "SLEEP";
                    coordinator.start("sleep", new String[0], this);
                }
                if (coordinator.isIdle() && currentAction.isEmpty()) return true;
                return false;
            }
            case "eat" -> {
                return eatFood();
            }
            case "craft" -> {
                return craftItem(task.item);
            }
            default -> {
                return true; // 未知任务类型，跳过
            }
        }
    }

    // ========== 合成系统 ==========

    /** 合成物品（工作台/工具等），返回 true 表示完成 */
    private boolean craftItem(String itemName) {
        if (itemName == null || itemName.isEmpty()) return true;
        if (hasItem(itemName)) return true; // 已经有了

        setStatus("Crafting " + itemName + "...");
        // 尝试合成
        return tryCraft(itemName);
    }

    /** 检查背包是否有某个物品 */
    private boolean hasItem(String itemName) {
        String normalized = itemName.toLowerCase()
            .replace("_", "").replace(" ", "");
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String name = stack.getItem().getName(stack).getString()
                .toLowerCase().replace("_", "").replace(" ", "");
            if (name.contains(normalized)) return true;
        }
        return false;
    }

    /** 尝试在2x2合成格合成物品 */
    private boolean tryCraft(String itemName) {
        // 记录尝试次数避免死循环
        if (craftRetryCount > 10) {
            craftRetryCount = 0;
            setStatus("Craft failed: can't make " + itemName);
            return true; // 跳过
        }
        craftRetryCount++;

        // 检查是否有工作台，必要时先尝试合成工作台
        // 简单合成: 木棍、木板、工作台、木镐、木斧等
        return switch (itemName.toLowerCase()) {
            case "stick" -> simpleCraft("stick", 4, 
                new String[]{"oak_planks", "spruce_planks", "birch_planks"}, 2);
            case "crafting_table" -> simpleCraft("crafting_table", 1,
                new String[]{"oak_planks", "spruce_planks", "birch_planks"}, 4);
            case "wooden_pickaxe" -> simpleCraft("wooden_pickaxe", 1,
                new String[]{"oak_planks", "spruce_planks", "birch_planks", "stick"}, 3);
            default -> {
                setStatus("Don't know how to craft " + itemName);
                yield true;
            }
        };
    }

    private int craftRetryCount = 0;

    /** 简单合成（用背包材料在2x2格合成） */
    private boolean simpleCraft(String resultItem, int resultCount, String[] materialNames, int materialTotal) {
        // 检查是否有足够材料
        int found = 0;
        for (String mat : materialNames) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                String name = stack.getItem().getName(stack).getString()
                    .toLowerCase().replace("_", "").replace(" ", "");
                if (name.contains(mat.toLowerCase().replace("_", "").replace(" ", ""))) {
                    found += stack.getCount();
                    break;
                }
            }
        }
        if (found < materialTotal) {
            setStatus("Need " + materialTotal + " materials for " + resultItem + ", have " + found);
            return false; // 材料不足，等前面的任务收集
        }

        // 消耗材料（简化：每种消耗一个）
        int remaining = materialTotal;
        for (String mat : materialNames) {
            if (remaining <= 0) break;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                String name = stack.getItem().getName(stack).getString()
                    .toLowerCase().replace("_", "").replace(" ", "");
                if (name.contains(mat.toLowerCase().replace("_", "").replace(" ", ""))) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.shrink(take);
                    remaining -= take;
                    if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
                    break;
                }
            }
        }

        // 生产物品（简化：直接给一个结果物品）
        // 这里用注册名找物品
        String registryName = switch (resultItem.toLowerCase()) {
            case "stick" -> "minecraft:stick";
            case "crafting_table" -> "minecraft:crafting_table";
            case "wooden_pickaxe" -> "minecraft:wooden_pickaxe";
            default -> "minecraft:" + resultItem.toLowerCase();
        };

        ItemStack result = new ItemStack(
            net.minecraftforge.registries.ForgeRegistries.ITEMS
                .getValue(new net.minecraft.resources.ResourceLocation(registryName)),
            resultCount);

        if (result.isEmpty()) {
            setStatus("Unknown item: " + resultItem);
            return true;
        }

        // 放入背包
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, result);
                craftRetryCount = 0;
                setStatus("Crafted " + resultItem);
                return true;
            }
        }
        setStatus("Inventory full, can't store " + resultItem);
        return true;
    }

    // ========== 吃东西 ==========

    /** 吃背包里的食物 */
    public boolean eatFood() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem().isEdible()) {
                String foodName = stack.getItem().getName(stack).getString();
                // 吃掉
                stack.shrink(1);
                if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
                // 治疗饥饿效果
                this.heal(2);
                // 吃食物动画
                this.swing(InteractionHand.MAIN_HAND);
                setStatus("Ate " + foodName);
                return true;
            }
        }
        setStatus("No food to eat");
        return true; // 没食物也跳过
    }

    // ========== 合成系统 ==========

    /** 寻找附近 32 格内的工作台 */
    private BlockPos findNearestCraftingTable() {
        BlockPos botPos = this.blockPosition();
        int range = 32;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = botPos.offset(dx, dy, dz);
                    if (this.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /** 根据用户输入匹配物品（精确匹配注册名 → 模糊匹配中文名/路径名） */
    private net.minecraft.world.item.Item matchCraftItem(String input) {
        if (input.contains(":")) {
            ResourceLocation rl = ResourceLocation.tryParse(input);
            if (rl != null) {
                net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(rl);
                if (item != null && item != net.minecraft.world.item.Items.AIR) return item;
            }
        }
        // 模糊匹配
        String lower = input.toLowerCase();
        for (var entry : ForgeRegistries.ITEMS.getEntries()) {
            String key = entry.getKey().location().toString();
            String path = entry.getKey().location().getPath();
            String displayName = entry.getValue().getName(entry.getValue().getDefaultInstance()).getString().toLowerCase();
            if (key.equalsIgnoreCase(lower) || path.equalsIgnoreCase(lower)
                    || path.contains(lower) || displayName.contains(lower)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 从背包消耗匹配 Ingredient 的一份材料 */
    private boolean consumeOne(net.minecraft.world.item.crafting.Ingredient ing) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ing.test(stack)) {
                stack.shrink(1);
                if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    /** 合成主循环（状态机：找工作台 → 导航 → 合成） */
    private void craftAction() {
        if (craftPhase == 4) { // DONE
            currentAction = "";
            return;
        }

        if (this.level() instanceof ServerLevel serverLevel) {
            // Phase 0: 初始化，查找匹配的配方
            if (craftPhase == 0) {
                net.minecraft.world.item.Item targetItem = matchCraftItem(craftTargetItem);
                if (targetItem == null) {
                    setStatus("Unknown item: " + craftTargetItem);
                    craftPhase = 4;
                    return;
                }

                // 从 RecipeManager 查找匹配的合成配方
                boolean found = false;
                for (net.minecraft.world.item.crafting.Recipe<?> recipe : serverLevel.getRecipeManager().getRecipes()) {
                    if (recipe.getType() != net.minecraft.world.item.crafting.RecipeType.CRAFTING) continue;
                    ItemStack result = recipe.getResultItem(serverLevel.registryAccess());
                    if (result.getItem() == targetItem) {
                        // 检查材料是否足够
                        var ingredients = recipe.getIngredients();
                        boolean hasAll = true;
                        for (var ing : ingredients) {
                            if (ing == net.minecraft.world.item.crafting.Ingredient.EMPTY) continue;
                            boolean hasOne = false;
                            for (int i = 0; i < inventory.getContainerSize(); i++) {
                                if (!inventory.getItem(i).isEmpty() && ing.test(inventory.getItem(i))) {
                                    hasOne = true;
                                    break;
                                }
                            }
                            if (!hasOne) { hasAll = false; break; }
                        }

                        if (hasAll) {
                            craftTargetItem = targetItem.builtInRegistryHolder().key().location().toString();
                            found = true;
                            break;
                        }
                    }
                }

                if (!found) {
                    setStatus("Missing materials for " + craftTargetItem);
                    craftPhase = 4;
                    return;
                }
                craftPhase = 1;
            }

            // Phase 1: 扫描附近是否有工作台
            if (craftPhase == 1) {
                BlockPos table = findNearestCraftingTable();
                if (table != null) {
                    targetBlockPos = table;
                    craftPhase = 2;
                    setStatus("Found crafting table, going there...");
                } else {
                    craftPhase = 3; // 没工作台，直接合成
                    setStatus("No table nearby, crafting directly...");
                }
            }

            // Phase 2: 导航到工作台
            if (craftPhase == 2) {
                if (targetBlockPos == null) { craftPhase = 3; return; }
                double dist = this.distanceToSqr(targetBlockPos.getX() + 0.5, targetBlockPos.getY() + 0.5, targetBlockPos.getZ() + 0.5);
                if (dist > 3.0 * 3.0) {
                    if (dist > 64.0 * 64.0) { craftPhase = 3; targetBlockPos = null; return; }
                    this.getNavigation().moveTo(targetBlockPos.getX() + 0.5, targetBlockPos.getY(), targetBlockPos.getZ() + 0.5, 1.0D);
                    setStatus("Moving to crafting table...");
                    return;
                }
                this.getNavigation().stop();
                craftPhase = 3;
            }

            // Phase 3: 执行合成
            if (craftPhase == 3) {
                net.minecraft.world.item.Item targetItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(craftTargetItem));
                if (targetItem == null || targetItem == net.minecraft.world.item.Items.AIR) {
                    setStatus("Unknown item: " + craftTargetItem);
                    craftPhase = 4;
                    return;
                }

                int crafted = 0;
                for (int round = 0; round < craftTargetCount && round < 64; round++) {
                    // 重新找匹配的配方
                    net.minecraft.world.item.crafting.Recipe<?> matchedRecipe = null;
                    for (net.minecraft.world.item.crafting.Recipe<?> recipe : serverLevel.getRecipeManager().getRecipes()) {
                        if (recipe.getType() != net.minecraft.world.item.crafting.RecipeType.CRAFTING) continue;
                        ItemStack result = recipe.getResultItem(serverLevel.registryAccess());
                        if (result.getItem() == targetItem) {
                            matchedRecipe = recipe;
                            break;
                        }
                    }

                    if (matchedRecipe == null) break;

                    // 消耗材料
                    boolean canCraft = true;
                    var ingredients = matchedRecipe.getIngredients();
                    // 先验证一次是否足够
                    var tempInv = new java.util.ArrayList<ItemStack>();
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        ItemStack s = inventory.getItem(i);
                        tempInv.add(s.isEmpty() ? ItemStack.EMPTY : s.copy());
                    }
                    for (var ing : ingredients) {
                        if (ing == net.minecraft.world.item.crafting.Ingredient.EMPTY) continue;
                        boolean found = false;
                        for (int j = 0; j < tempInv.size(); j++) {
                            ItemStack s = tempInv.get(j);
                            if (!s.isEmpty() && ing.test(s)) {
                                s.shrink(1);
                                found = true;
                                break;
                            }
                        }
                        if (!found) { canCraft = false; break; }
                    }

                    if (!canCraft) break;

                    // 实际消耗
                    for (var ing : ingredients) {
                        if (ing == net.minecraft.world.item.crafting.Ingredient.EMPTY) continue;
                        consumeOne(ing);
                    }

                    // 产出
                    ItemStack result = matchedRecipe.getResultItem(serverLevel.registryAccess());
                    if (!addToInventory(result.copy())) {
                        // 背包满了，丢地上
                        spawnAtLocation(result.copy(), 0.0F);
                    }
                    crafted++;
                }

                setStatus("Crafted " + crafted + "x " + craftTargetItem);
                craftPhase = 4;
            }
        } else {
            // 客户端 tick 不做任何事
        }
    }

    // ========== 交互系统 ==========

    /** 扫描附近 range 格内匹配注册名的方块 */
    private BlockPos findNearestRegisteredBlock(String registryName, int range) {
        if (registryName == null || registryName.isEmpty()) return null;
        BlockPos botPos = this.blockPosition();
        BlockPos closest = null;
        double minDist = Double.MAX_VALUE;

        String searchPath = registryName.toLowerCase();
        String translatedId = TranslationDictionary.translateBlock(searchPath);
        if (translatedId == null) {
            translatedId = TranslationDictionary.findMatch(searchPath);
        }

        if (translatedId != null && translatedId.contains(":")) {
            ResourceLocation rl = ResourceLocation.tryParse(translatedId);
            if (rl != null) {
                String path = rl.getPath();
                for (int dx = -range; dx <= range; dx++) {
                    for (int dy = -5; dy <= 5; dy++) {
                        for (int dz = -range; dz <= range; dz++) {
                            BlockPos pos = botPos.offset(dx, dy, dz);
                            var state = this.level().getBlockState(pos);
                            var key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                            if (key != null && key.getPath().equals(path)) {
                                double dist = this.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
                                if (dist < minDist) { minDist = dist; closest = pos.immutable(); }
                            }
                        }
                    }
                }
            }
            if (closest != null) return closest;
        }

        if (searchPath.contains(":")) {
            ResourceLocation rl = ResourceLocation.tryParse(searchPath);
            if (rl != null) {
                String path = rl.getPath();
                for (int dx = -range; dx <= range; dx++) {
                    for (int dy = -5; dy <= 5; dy++) {
                        for (int dz = -range; dz <= range; dz++) {
                            BlockPos pos = botPos.offset(dx, dy, dz);
                            var state = this.level().getBlockState(pos);
                            var key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                            if (key != null && key.getPath().equals(path)) {
                                double dist = this.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
                                if (dist < minDist) { minDist = dist; closest = pos.immutable(); }
                            }
                        }
                    }
                }
            }
        } else {
            for (int dx = -range; dx <= range; dx++) {
                for (int dy = -5; dy <= 5; dy++) {
                    for (int dz = -range; dz <= range; dz++) {
                        BlockPos pos = botPos.offset(dx, dy, dz);
                        var state = this.level().getBlockState(pos);
                        var key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
                        if (key != null) {
                            String path = key.getPath().toLowerCase();
                            String display = state.getBlock().getName().getString().toLowerCase();
                            if (path.equals(searchPath) || path.contains(searchPath) || display.contains(searchPath)) {
                                double dist = this.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
                                if (dist < minDist) { minDist = dist; closest = pos.immutable(); }
                            }
                        }
                    }
                }
            }
        }
        return closest;
    }

    /** 扫描附近 32 格内匹配注册名/类型的实体 */
    private net.minecraft.world.entity.Entity findNearestEntity(String typeName) {
        if (typeName == null || typeName.isEmpty()) return null;
        String search = typeName.toLowerCase();
        var entities = this.level().getEntities(this, this.getBoundingBox().inflate(32));
        net.minecraft.world.entity.Entity closest = null;
        double minDist = Double.MAX_VALUE;
        for (var e : entities) {
            if (e == this) continue;
            var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
            if (key == null) continue;
            String path = key.getPath().toLowerCase();
            String display = e.getName().getString().toLowerCase();
            if (path.equals(search) || path.contains(search) || display.contains(search)) {
                double dist = this.distanceToSqr(e.position());
                if (dist < minDist) { minDist = dist; closest = e; }
            }
        }
        return closest;
    }

    /** 判断 ItemStack 是否匹配指定注册名或中文路径 */
    private boolean matchItem(ItemStack stack, String name) {
        if (stack == null || stack.isEmpty() || name == null || name.isEmpty()) return false;
        String search = name.toLowerCase();
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) return false;

        String translatedId = TranslationDictionary.translateItem(search);
        if (translatedId == null) {
            translatedId = TranslationDictionary.findMatch(search);
        }

        if (translatedId != null) {
            if (key.toString().equalsIgnoreCase(translatedId)) {
                return true;
            }
        }

        return key.toString().equalsIgnoreCase(search)
            || key.getPath().equalsIgnoreCase(search)
            || key.getPath().contains(search)
            || stack.getHoverName().getString().toLowerCase().contains(search);
    }

    /** 交互动作状态机 */
    private void interactAction() {
        if (interactPhase == 5) { currentAction = ""; return; }
        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        // Phase 0: 初始化 → 判断目标是方块还是实体
        if (interactPhase == 0) {
            interactBlockPos = null;
            interactEntity = null;
            // 先尝试当方块找，找不到再当实体
            String target = interactTarget != null ? interactTarget.toLowerCase() : "";
            BlockPos testPos = findNearestRegisteredBlock(target, 1);
            if (testPos != null) {
                interactType = "BLOCK";
                interactBlockPos = testPos;
            } else {
                var testEntity = findNearestEntity(target);
                if (testEntity != null) {
                    interactType = "ENTITY";
                    interactEntity = testEntity;
                    interactBlockPos = testEntity.blockPosition();
                } else {
                    // 还没找到，让后续 phase 做扫描
                }
            }
            interactPhase = 1;
        }

        // Phase 1: 搜索目标（32格范围内）
        if (interactPhase == 1) {
            String target = interactTarget != null ? interactTarget.toLowerCase() : "";
            if ("BLOCK".equals(interactType)) {
                if (interactBlockPos == null || !this.level().getBlockState(interactBlockPos).is(ForgeRegistries.BLOCKS.getValue(ResourceLocation.tryParse(target)))) {
                    interactBlockPos = findNearestRegisteredBlock(target, 32);
                }
                if (interactBlockPos == null) {
                    // 尝试当实体找
                    var entity = findNearestEntity(target);
                    if (entity != null) {
                        interactType = "ENTITY";
                        interactEntity = entity;
                        interactBlockPos = entity.blockPosition();
                    } else {
                        setStatus("Cannot find " + interactTarget + " nearby");
                        interactPhase = 5;
                        return;
                    }
                }
            } else {
                if (interactEntity == null || !interactEntity.isAlive()) {
                    interactEntity = findNearestEntity(target);
                }
                if (interactEntity == null) {
                    setStatus("Cannot find " + interactTarget + " nearby");
                    interactPhase = 5;
                    return;
                }
                interactBlockPos = interactEntity.blockPosition();
            }
            interactPhase = 2;
        }

        // Phase 2: 装备物品到主手
        if (interactPhase == 2) {
            if (interactItem != null && !interactItem.isEmpty()) {
                ItemStack held = this.getMainHandItem();
                if (!matchItem(held, interactItem)) {
                    // 从背包找
                    int foundSlot = -1;
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        ItemStack stack = inventory.getItem(i);
                        if (!stack.isEmpty() && matchItem(stack, interactItem)) {
                            foundSlot = i;
                            break;
                        }
                    }
                    if (foundSlot < 0) {
                        setStatus("No " + interactItem + " in inventory");
                        interactPhase = 5;
                        return;
                    }
                    // 装备到主手
                    ItemStack targetStack = inventory.getItem(foundSlot);
                    if (foundSlot < 9) {
                        // 在快捷栏
                        selectedSlot = foundSlot;
                        syncSelectedSlot();
                    } else {
                        // 背包区 → 换到当前快捷栏
                        ItemStack currentHotbar = inventory.getItem(selectedSlot);
                        inventory.setItem(selectedSlot, targetStack.copy());
                        inventory.setItem(foundSlot, currentHotbar.isEmpty() ? ItemStack.EMPTY : currentHotbar.copy());
                        if (!currentHotbar.isEmpty()) currentHotbar.setCount(0);
                        targetStack.setCount(0);
                    }
                    // 同步到实体
                    this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, inventory.getItem(selectedSlot).copy());
                }
            }
            interactPhase = 3;
        }

        // Phase 3: 导航到目标位置
        if (interactPhase == 3) {
            if (interactBlockPos == null) { interactPhase = 5; return; }
            double distSq = this.distanceToSqr(interactBlockPos.getX() + 0.5, interactBlockPos.getY() + 0.5, interactBlockPos.getZ() + 0.5);
            if (distSq > 4.0 * 4.0) {
                if (distSq > 64.0 * 64.0) { interactPhase = 5; return; }
                // 找旁边地面位置
                BlockPos standPos = findStandPos(interactBlockPos);
                if (standPos != null) {
                    this.getNavigation().moveTo(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5, 1.0D);
                } else {
                    this.getNavigation().moveTo(interactBlockPos.getX() + 0.5, interactBlockPos.getY(), interactBlockPos.getZ() + 0.5, 1.0D);
                }
                setStatus("Moving to " + interactTarget + "...");
                return;
            }
            this.getNavigation().stop();
            interactPhase = 4;
        }

        // Phase 4: 执行交互
        if (interactPhase == 4) {
            boolean success = false;
            try {
                FakePlayer fp = FakePlayerFactory.getMinecraft(serverLevel);
                fp.setItemInHand(InteractionHand.MAIN_HAND, this.getMainHandItem().copy());
                fp.setPos(this.getX(), this.getY(), this.getZ());

                if ("BLOCK".equals(interactType) && interactBlockPos != null) {
                    // 右键方块
                    var blockState = serverLevel.getBlockState(interactBlockPos);
                    var shape = blockState.getShape(serverLevel, interactBlockPos);
                    Vec3 hitVec = shape.isEmpty() ?
                        Vec3.atCenterOf(interactBlockPos) :
                        shape.bounds().getCenter().add(Vec3.atLowerCornerOf(interactBlockPos));
                    BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, interactBlockPos, false);
                    var resultCode = fp.gameMode.useItemOn(fp, serverLevel,
                        fp.getItemInHand(InteractionHand.MAIN_HAND),
                        InteractionHand.MAIN_HAND, hitResult);
                    success = resultCode.consumesAction();
                } else if ("ENTITY".equals(interactType) && interactEntity != null && interactEntity.isAlive()) {
                    // 右键实体
                    var result = interactEntity.interact(fp, InteractionHand.MAIN_HAND);
                    success = result.consumesAction();
                }
            } catch (Exception e) {
                // 交互静默失败
            }

            // 消耗主手物品
            if (success && interactItem != null && !interactItem.isEmpty()) {
                ItemStack mainHand = this.getMainHandItem();
                if (!mainHand.isEmpty()) {
                    mainHand.shrink(1);
                    if (mainHand.isEmpty()) {
                        this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    }
                }
            }

            setStatus(success ? "Interacted with " + interactTarget : "Failed to interact with " + interactTarget);
            interactPhase = 5;
        }
    }

    /** 在目标方块旁边找可站立的地面位置 */
    private BlockPos findStandPos(BlockPos target) {
        // 4 个水平方向找到的目标 Y 层同层的地面空位
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int y = target.getY();
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : dirs) {
            pos.set(target.getX() + d[0], y, target.getZ() + d[1]);
            if (this.level().getBlockState(pos).isAir() && this.level().getBlockState(pos.below()).isSolid()) {
                double dist = this.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = pos.immutable();
                }
            }
        }
        if (best == null) {
            // 再往下找一层
            for (int[] d : dirs) {
                pos.set(target.getX() + d[0], y - 1, target.getZ() + d[1]);
                if (this.level().getBlockState(pos).isAir() && this.level().getBlockState(pos.below()).isSolid()) {
                    double dist = this.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    // ========== 自主模式系统 ==========

    /** 自主行为决策：每 40 tick 调用一次，autoMode 开启时生效 */
    private void handleAutoBehavior() {
        if (!currentAction.isEmpty()) return;
        if (currentTask != null) return; // 执行任务期间不触发自动行为

        // 自动存箱（背包空位 ≤ 5 时触发）
        if (Config.isAutoStore()) {
            int emptySlots = 0;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).isEmpty()) emptySlots++;
            }
            if (emptySlots <= 5) {
                currentAction = "STORE";
                setStatus("Auto storing...");
            }
        }
    }

    /** 检查坐标是否在家的范围内（非住家模式则始终返回 true） */
    private boolean isWithinHomeRange(BlockPos pos) {
        if (!homeMode || homePos == null) return true;
        return pos.distManhattan(homePos) <= HOME_RADIUS;
    }

    /** 寻找附近 24 格内的被动动物实体 */
    private LivingEntity findNearestAnimal() {
        List<Animal> animals = this.level().getEntitiesOfClass(Animal.class,
                this.getBoundingBox().inflate(24.0D),
                e -> e.isAlive());
        LivingEntity nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Animal animal : animals) {
            double dist = this.distanceToSqr(animal);
            if (dist < minDist) {
                minDist = dist;
                nearest = animal;
            }
        }
        return nearest;
    }

    /** 寻找附近 32 格内的箱子/陷阱箱/木桶 */
    private BlockPos findNearestContainer() {
        BlockPos botPos = this.blockPosition();
        BlockPos nearest = null;
        double minDist = Double.MAX_VALUE;
        int range = 32;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = botPos.offset(dx, dy, dz);
                    BlockState state = this.level().getBlockState(pos);
                    if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)) {
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

    /** 判断物品是否应存入箱子（食物/作物/生肉） */
    private boolean isStoreableItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Item item = stack.getItem();
        // 食物
        if (item.isEdible()) return true;
        // 作物
        return item == Items.WHEAT || item == Items.CARROT || item == Items.POTATO
            || item == Items.BEETROOT || item == Items.NETHER_WART;
    }

    /** 在背包中找攻击力最高的武器并切换到快捷栏选中 */
    private void selectBestWeapon() {
        int bestSlot = -1;
        double bestDamage = 1.0; // 空手伤害

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            double dmg = getAttackDamage(stack);
            if (dmg > bestDamage) {
                bestDamage = dmg;
                bestSlot = i;
            }
        }

        if (bestSlot < 0) return; // 没有更好的武器

        // 如果武器不在快捷栏 (0-8)，和当前选中格交换
        if (bestSlot > 8) {
            ItemStack weapon = inventory.getItem(bestSlot);
            ItemStack current = inventory.getItem(selectedSlot);
            inventory.setItem(bestSlot, current);
            inventory.setItem(selectedSlot, weapon);
            // 武器已在 selectedSlot
        } else {
            selectedSlot = bestSlot;
        }
        syncSelectedSlot();
    }

    /** 获取物品在主手的攻击伤害值 */
    private double getAttackDamage(ItemStack stack) {
        var attribs = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        for (var entry : attribs.entries()) {
            if (entry.getKey() == Attributes.ATTACK_DAMAGE) {
                return entry.getValue().getAmount();
            }
        }
        return 1.0; // 空手
    }

    // huntAction removed — replaced by ActionCoordinator/HuntAction

    /** 存储行动：找箱子 → 导航 → 存入食物和作物 */
    private void storeAction() {
        if (targetBlockPos == null) {
            // 先检查背包是否有可存的物品
            boolean hasStoreable = false;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (isStoreableItem(inventory.getItem(i))) {
                    hasStoreable = true;
                    break;
                }
            }
            if (!hasStoreable) {
                setStatus("Nothing to store");
                currentAction = "";
                return;
            }
            targetBlockPos = findNearestContainer();
            if (targetBlockPos == null) {
                setStatus("No chest nearby to store items");
                currentAction = "";
                return;
            }
        }

        double dist = this.distanceToSqr(targetBlockPos.getX() + 0.5, targetBlockPos.getY() + 0.5, targetBlockPos.getZ() + 0.5);

        if (dist > 3.0 * 3.0) {
            if (dist > 64.0 * 64.0) {
                targetBlockPos = null;
                return;
            }
            boolean started = this.getNavigation().moveTo(targetBlockPos.getX() + 0.5, targetBlockPos.getY(), targetBlockPos.getZ() + 0.5, 1.0D);
            if (!started) {
                navStuckTicks++;
                if (navStuckTicks > 20) {
                    targetBlockPos = null;
                    navStuckTicks = 0;
                }
            } else {
                navStuckTicks = 0;
            }
            setStatus("Moving to chest...");
            return;
        }

        // 到达箱子 → 存入物品
        navStuckTicks = 0;
        this.getNavigation().stop();

        if (this.level() instanceof ServerLevel serverLevel) {
            BlockEntity be = serverLevel.getBlockEntity(targetBlockPos);
            if (be instanceof Container chest) {
                int stored = 0;
                for (int i = 0; i < inventory.getContainerSize(); i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (!isStoreableItem(stack)) continue;
                    ItemStack toStore = stack.copy();
                    // 手动找箱子空位或可堆叠的格子塞入
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                        ItemStack chestStack = chest.getItem(slot);
                        if (chestStack.isEmpty()) {
                            chest.setItem(slot, toStore);
                            inventory.setItem(i, ItemStack.EMPTY);
                            stored += toStore.getCount();
                            break;
                        } else if (ItemStack.isSameItemSameTags(chestStack, toStore) && chestStack.getCount() < chestStack.getMaxStackSize()) {
                            int space = chestStack.getMaxStackSize() - chestStack.getCount();
                            int toAdd = Math.min(space, toStore.getCount());
                            chestStack.grow(toAdd);
                            stored += toAdd;
                            toStore.shrink(toAdd);
                            if (toStore.isEmpty()) {
                                inventory.setItem(i, ItemStack.EMPTY);
                                break;
                            } else {
                                inventory.setItem(i, toStore);
                            }
                        }
                    }
                }
                setStatus("Stored " + stored + " items in chest");
            } else {
                // 目标不是容器（被挖了？）
                targetBlockPos = null;
                return;
            }
        }

        // 检查背包是否还有可存物品，没有则结束
        boolean hasMore = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isStoreableItem(inventory.getItem(i))) {
                hasMore = true;
                break;
            }
        }
        if (!hasMore) {
            currentAction = "";
        } else {
            targetBlockPos = null; // 重新找箱子（可能当前箱子满了）
        }
    }

    class AiBotActionGoal extends Goal {
        private final AiBotEntity bot;

        public AiBotActionGoal(AiBotEntity bot) {
            this.bot = bot;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return !currentAction.isEmpty();
        }

        @Override
        public void start() {
        }

        @Override
        public void tick() {
            if (currentAction.isEmpty()) return;

            // 1. 协调器调度（执行 BotAction）
            if (!coordinator.isIdle()) {
                BotAction before = coordinator.getCurrentAction();
                boolean justFinished = coordinator.tick(AiBotEntity.this);
                if (justFinished) {
                    // 同步计数到旧系统，供 executeTask 检查
                    if (before instanceof ChopTreeAction cta) chopCount = cta.getChopCount();
                    else if (before instanceof MineTunnelAction mta) mineCount = mta.getMineCount();

                    if (taskList != null && taskIndex < taskList.size()) {
                        currentAction = "TASK";
                    } else {
                        currentAction = "";
                    }
                }
                return;
            }

            // 2. 任务列表执行（plan 系统）
            if ("TASK".equals(currentAction)) {
                taskLoop();
            }

            // 3. 动作完成后，检查是否有后续任务
            if (currentAction.isEmpty() && taskList != null && taskIndex < taskList.size()) {
                currentAction = "TASK";
            }
        }

        @Override
        public boolean canContinueToUse() {
            return !currentAction.isEmpty();
        }
    }

    class AiBotPickupGoal extends Goal {
        private final AiBotEntity bot;

        public AiBotPickupGoal(AiBotEntity bot) {
            this.bot = bot;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            if (isInventoryFull()) return false;
            // 有计划执行时不拾取，避免导航冲突
            if (taskList != null && taskIndex < taskList.size()) return false;
            if (!currentAction.isEmpty()) return false;
            List<ItemEntity> items = bot.level().getEntitiesOfClass(ItemEntity.class,
                    new AABB(bot.blockPosition()).inflate(10.0D, 6.0D, 10.0D));
            return !items.isEmpty();
        }

        @Override
        public void start() {
            List<ItemEntity> items = bot.level().getEntitiesOfClass(ItemEntity.class,
                    new AABB(bot.blockPosition()).inflate(8.0D));
            
            if (!items.isEmpty()) {
                ItemEntity nearest = items.get(0);
                double minDist = bot.distanceToSqr(nearest.position());
                
                for (ItemEntity item : items) {
                    double dist = bot.distanceToSqr(item.position());
                    if (dist < minDist) {
                        minDist = dist;
                        nearest = item;
                    }
                }
                
                bot.getNavigation().moveTo(nearest, 0.8D);
                setStatus("Picking up...");
            }
        }

        @Override
        public boolean canContinueToUse() {
            if (taskList != null && taskIndex < taskList.size()) return false;
            if (!currentAction.isEmpty()) return false;
            return !isInventoryFull() && bot.getNavigation().isInProgress();
        }
    }

    class AiBotWanderGoal extends Goal {
        private final AiBotEntity bot;

        public AiBotWanderGoal(AiBotEntity bot) {
            this.bot = bot;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return currentAction.isEmpty() && !bot.getNavigation().isInProgress() &&
                   bot.getRandom().nextInt(100) == 0;
        }

        @Override
        public void start() {
            BlockPos wanderTarget;
            if (homeMode && homePos != null) {
                // 在家范围内闲逛
                int rx = bot.getRandom().nextInt(10) - 5;
                int rz = bot.getRandom().nextInt(10) - 5;
                wanderTarget = homePos.offset(rx, 0, rz);
            } else {
                wanderTarget = bot.blockPosition().offset(
                        bot.getRandom().nextInt(10) - 5,
                        0,
                        bot.getRandom().nextInt(10) - 5);
            }
            bot.getNavigation().moveTo(wanderTarget.getX() + 0.5, wanderTarget.getY() + 0.5, wanderTarget.getZ() + 0.5, 0.5D);
        }
    }
}
