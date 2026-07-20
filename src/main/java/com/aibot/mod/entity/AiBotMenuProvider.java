package com.aibot.mod.entity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.MenuProvider;

public class AiBotMenuProvider implements MenuProvider {
    private final AiBotEntity bot;

    public AiBotMenuProvider(AiBotEntity bot) {
        this.bot = bot;
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("AI Bot Inventory");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new AiBotMenu(containerId, playerInventory, bot);
    }

    public AiBotEntity getBot() {
        return bot;
    }
}
