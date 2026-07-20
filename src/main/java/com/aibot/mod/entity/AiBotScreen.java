package com.aibot.mod.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
public class AiBotScreen extends AbstractContainerScreen<AiBotMenu> {
    private static final ResourceLocation INVENTORY_BG =
            new ResourceLocation("minecraft", "textures/gui/container/inventory.png");

    public AiBotScreen(AiBotMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        // 没有玩家背包区，隐藏 inventory label
        this.inventoryLabelY = Integer.MAX_VALUE;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShaderTexture(0, INVENTORY_BG);

        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;

        // 渲染完整玩家背包背景（含盔甲栏、合成区、背包、快捷栏）
        guiGraphics.blit(INVENTORY_BG, x, y, 0, 0, this.imageWidth, this.imageHeight);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 只渲染 title，不渲染空白的 "Inventory" label
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 4210752, false);
    }

}
