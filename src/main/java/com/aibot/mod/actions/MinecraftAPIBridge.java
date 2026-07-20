package com.aibot.mod.actions;

import com.aibot.mod.AiBotMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class MinecraftAPIBridge {
    private static Minecraft mc = Minecraft.getInstance();

    public static boolean isAvailable() {
        return mc.player != null && mc.level != null;
    }

    public static boolean gotoBlock(BlockPos pos) {
        if (!isAvailable()) return false;
        try {
            LocalPlayer player = mc.player;
            double x = pos.getX() + 0.5;
            double y = pos.getY() + 1.0;
            double z = pos.getZ() + 0.5;
            player.setPos(x, y, z);
            return true;
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[MinecraftAPIBridge] gotoBlock failed: {}", e.getMessage());
            return false;
        }
    }

    public static boolean gotoPosition(double x, double y, double z) {
        if (!isAvailable()) return false;
        try {
            LocalPlayer player = mc.player;
            player.setPos(x, y, z);
            return true;
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[MinecraftAPIBridge] gotoPosition failed: {}", e.getMessage());
            return false;
        }
    }

    public static boolean mineBlock(BlockPos pos) {
        if (!isAvailable()) return false;
        try {
            BlockState state = mc.level.getBlockState(pos);
            if (state.isAir()) return false;

            LocalPlayer player = mc.player;
            double x = pos.getX() + 0.5 - player.getX();
            double y = pos.getY() + 1.0 - player.getY();
            double z = pos.getZ() + 0.5 - player.getZ();
            double dist = Math.sqrt(x * x + y * y + z * z);

            if (dist > 6.0) {
                AiBotMod.LOGGER.warn("[MinecraftAPIBridge] mineBlock: too far ({:.1f}m), skipped", dist);
                return false;
            }

            mc.gameMode.startDestroyBlock(pos, Direction.UP);
            mc.gameMode.destroyBlock(pos);
            return true;
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[MinecraftAPIBridge] mineBlock failed: {}", e.getMessage());
            return false;
        }
    }

    public static boolean placeBlock(BlockPos pos, Item item) {
        if (!isAvailable()) return false;
        try {
            LocalPlayer player = mc.player;
            
            int slot = findItemInHotbar(item);
            if (slot == -1) return false;
            
            player.getInventory().selected = slot;
            
            Vec3 lookVec = getLookVectorToBlock(pos);
            BlockHitResult hitResult = new BlockHitResult(lookVec, Direction.UP, pos.below(), false);
            
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
            return true;
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[MinecraftAPIBridge] placeBlock failed: {}", e.getMessage());
            return false;
        }
    }

    public static boolean interactBlock(BlockPos pos) {
        if (!isAvailable()) return false;
        try {
            LocalPlayer player = mc.player;
            
            Vec3 lookVec = getLookVectorToBlock(pos);
            BlockHitResult hitResult = new BlockHitResult(lookVec, Direction.UP, pos, false);
            
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
            return true;
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[MinecraftAPIBridge] interactBlock failed: {}", e.getMessage());
            return false;
        }
    }

    public static boolean useItem(Item item) {
        if (!isAvailable()) return false;
        try {
            LocalPlayer player = mc.player;
            
            int slot = findItemInHotbar(item);
            if (slot == -1) return false;
            
            player.getInventory().selected = slot;
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            return true;
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[MinecraftAPIBridge] useItem failed: {}", e.getMessage());
            return false;
        }
    }

    public static int findItemInHotbar(Item item) {
        if (!isAvailable()) return -1;
        LocalPlayer player = mc.player;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    public static boolean hasItem(Item item) {
        return findItemInHotbar(item) != -1;
    }

    public static boolean isPathing() {
        return false;
    }

    public static void waitForPathing() {
        try { Thread.sleep(500); } catch (Exception e) {}
    }

    public static void stop() {
    }

    public static BlockPos getPlayerPos() {
        if (!isAvailable()) return BlockPos.ZERO;
        return mc.player.blockPosition();
    }

    public static boolean isBlockAir(BlockPos pos) {
        if (!isAvailable()) return false;
        return mc.level.getBlockState(pos).isAir();
    }

    public static BlockState getBlockState(BlockPos pos) {
        if (!isAvailable()) return Blocks.AIR.defaultBlockState();
        return mc.level.getBlockState(pos);
    }

    public static boolean isLogBlock(BlockPos pos) {
        if (!isAvailable()) return false;
        BlockState state = mc.level.getBlockState(pos);
        return state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG) ||
               state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.JUNGLE_LOG) ||
               state.is(Blocks.ACACIA_LOG) || state.is(Blocks.DARK_OAK_LOG) ||
               state.is(Blocks.MANGROVE_LOG) || state.is(Blocks.CHERRY_LOG) ||
               state.is(Blocks.BAMBOO_BLOCK);
    }

    public static int getCurrentToolDurability() {
        if (!isAvailable()) return -1;
        ItemStack stack = mc.player.getMainHandItem();
        if (stack.isEmpty()) return -1;
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    public static boolean isInventoryFull() {
        if (!isAvailable()) return true;
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().items.get(i).isEmpty()) {
                empty++;
            }
        }
        return empty == 0;
    }

    public static ItemStack getInventoryStack(int index) {
        if (!isAvailable()) return ItemStack.EMPTY;
        if (index < 0 || index >= 36) return ItemStack.EMPTY;
        return mc.player.getInventory().items.get(index);
    }

    private static Vec3 getLookVectorToBlock(BlockPos pos) {
        LocalPlayer player = mc.player;
        Vec3 playerPos = player.getEyePosition();
        Vec3 blockPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return blockPos.subtract(playerPos).normalize();
    }

    public static void sendChat(String message) {
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.connection.sendChat(message);
            }
        });
    }
}