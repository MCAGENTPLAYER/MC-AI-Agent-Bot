package com.aibot.mod.entity;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;

public class AiBotMenu extends AbstractContainerMenu {
    private final AiBotEntity bot;
    private final SimpleContainer botInventory;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    // 服务端构造
    public AiBotMenu(int containerId, Inventory playerInventory, AiBotEntity bot) {
        super(ModEntities.AI_BOT_MENU.get(), containerId);
        this.bot = bot;
        this.botInventory = bot.getInventory();
        initBotSlots();
    }

    // 客户端构造（从网络包反序列化）
    public AiBotMenu(int containerId, Inventory playerInventory, FriendlyByteBuf data) {
        super(ModEntities.AI_BOT_MENU.get(), containerId);
        int entityId = data.readInt();
        Player player = playerInventory.player;
        if (player.level().getEntity(entityId) instanceof AiBotEntity b) {
            this.bot = b;
            // 优先从集成服务端读取真实背包数据（单机模式），
            // 因为客户端 SimpleContainer 不会自动同步服务端变更
            this.botInventory = getServerInventory(b);
        } else {
            this.bot = null;
            this.botInventory = new SimpleContainer(36);
        }
        initBotSlots();
    }

    /** 在单机集成服务端模式下，获取服务端实体的真实背包数据 */
    private static SimpleContainer getServerInventory(AiBotEntity clientBot) {
        try {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            if (server != null && mc.level != null) {
                var serverLevel = server.getLevel(mc.level.dimension());
                if (serverLevel != null) {
                    var entity = serverLevel.getEntity(clientBot.getId());
                    if (entity instanceof AiBotEntity serverBot) {
                        return serverBot.getInventory();
                    }
                }
            }
        } catch (Exception ignored) {
            // 非单机模式或异常，回退到客户端数据
        }
        return clientBot.getInventory();
    }

    private void initBotSlots() {
        // === AI 盔甲栏（4 格，左侧纵向排列）===
        int armorX = 8;
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            addSlot(new Slot(new SimpleContainer(1) {
                @Override public ItemStack getItem(int s) {
                    return bot != null ? bot.getItemBySlot(ARMOR_SLOTS[idx]) : ItemStack.EMPTY;
                }
                @Override public void setItem(int s, ItemStack stack) {
                    if (bot != null) bot.setItemSlot(ARMOR_SLOTS[idx], stack);
                }
                @Override public void setChanged() {
                    if (bot != null) bot.getInventory().setChanged();
                }
                @Override public int getContainerSize() { return 1; }
                @Override public boolean isEmpty() { return getItem(0).isEmpty(); }
                @Override public ItemStack removeItem(int s, int count) {
                    ItemStack stack = getItem(s);
                    if (!stack.isEmpty()) {
                        ItemStack result = stack.split(count);
                        if (stack.isEmpty()) setItem(s, ItemStack.EMPTY);
                        return result;
                    }
                    return ItemStack.EMPTY;
                }
                @Override public ItemStack removeItemNoUpdate(int s) {
                    ItemStack stack = getItem(s);
                    setItem(s, ItemStack.EMPTY);
                    return stack;
                }
            }, 0, armorX, 8 + i * 18));
        }

        // === AI 副手（盔甲栏下方偏右）===
        addSlot(new Slot(new SimpleContainer(1) {
            @Override public ItemStack getItem(int s) {
                return bot != null ? bot.getItemBySlot(EquipmentSlot.OFFHAND) : ItemStack.EMPTY;
            }
            @Override public void setItem(int s, ItemStack stack) {
                if (bot != null) bot.setItemSlot(EquipmentSlot.OFFHAND, stack);
            }
            @Override public void setChanged() {
                if (bot != null) bot.getInventory().setChanged();
            }
            @Override public int getContainerSize() { return 1; }
            @Override public boolean isEmpty() { return getItem(0).isEmpty(); }
            @Override public ItemStack removeItem(int s, int count) {
                ItemStack stack = getItem(s);
                if (!stack.isEmpty()) {
                    ItemStack result = stack.split(count);
                    if (stack.isEmpty()) setItem(s, ItemStack.EMPTY);
                    return result;
                }
                return ItemStack.EMPTY;
            }
            @Override public ItemStack removeItemNoUpdate(int s) {
                ItemStack stack = getItem(s);
                setItem(s, ItemStack.EMPTY);
                return stack;
            }
        }, 0, 77, 62));

        // === AI 主背包（3×9，对应 Bot 背包 9-35）===
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int index = 9 + row * 9 + col;
                addSlot(new Slot(botInventory, index, 8 + col * 18, 84 + row * 18));
            }
        }

        // === AI 快捷栏（1×9，对应 Bot 背包 0-8）===
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(botInventory, col, 8 + col * 18, 142 + 0 * 18));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = this.slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        ItemStack stack = slot.getItem();
        ItemStack result = stack.copy();

        // 直接往玩家背包里加，加不下的留在 Bot 槽位
        boolean ok = player.getInventory().add(stack);
        slot.set(stack); // stack 会被 add 消费掉
        slot.setChanged();

        if (ok && stack.getCount() < result.getCount()) {
            return result;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // 拦截对 Bot 槽位的普通点击，直接转到玩家背包
        if (clickType == ClickType.PICKUP && slotId >= 0 && slotId < this.slots.size()) {
            Slot slot = this.slots.get(slotId);
            if (slot != null && slot.hasItem()) {
                ItemStack stack = slot.getItem();

                if (button == 0) {
                    // 左键：整组转移到玩家背包
                    player.getInventory().add(stack);
                    slot.set(stack); // add 会修改 stack 为剩余部分
                    slot.setChanged();
                    if (!stack.isEmpty()) {
                        // 装不下的放到光标上
                        this.setCarried(stack);
                        slot.set(ItemStack.EMPTY);
                    }
                    return;
                } else if (button == 1) {
                    // 右键：转移一半到玩家背包
                    int half = (stack.getCount() + 1) / 2;
                    ItemStack toTransfer = stack.copy();
                    toTransfer.setCount(half);
                    stack.shrink(half);
                    slot.setChanged();

                    player.getInventory().add(toTransfer);
                    // 没装下的合并回槽位
                    int added = half - toTransfer.getCount();
                    if (added > 0) {
                        slot.setChanged();
                    }
                    stack.grow(toTransfer.getCount());
                    slot.setChanged();
                    return;
                }
            }
        }
        super.clicked(slotId, button, clickType, player);
    }
}
