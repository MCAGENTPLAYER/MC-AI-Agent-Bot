package com.aibot.mod;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public class HudOverlay {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final int CROSSHAIR_SIZE = 10;
    private static final int CROSSHAIR_THICKNESS = 1;

    @SubscribeEvent
    public void onRenderGui(RenderGuiOverlayEvent.Pre event) {
        if (!BotMode.isActive()) return;
        if (mc.player == null) return;
        int focused = org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(
                mc.getWindow().getWindow(),
                org.lwjgl.glfw.GLFW.GLFW_FOCUSED);
        if (focused == 0) return;

        try {
            var guiGraphics = event.getGuiGraphics();
            var window = mc.getWindow();
            int width = window.getGuiScaledWidth();
            int height = window.getGuiScaledHeight();
            int centerX = width / 2;
            int centerY = height / 2;

            long elapsed = System.currentTimeMillis() - BotMode.getToggleTime();
            float pulse = elapsed < 500 ? (float) Math.sin(elapsed / 80.0) * 0.3f + 0.7f : 1.0f;
            int alpha = (int) (pulse * 255);
            int color = (alpha << 24) | 0x00AAFF;

            guiGraphics.fill(
                centerX - CROSSHAIR_THICKNESS / 2,
                centerY - CROSSHAIR_SIZE,
                centerX + CROSSHAIR_THICKNESS / 2 + 1,
                centerY + CROSSHAIR_SIZE,
                color
            );
            guiGraphics.fill(
                centerX - CROSSHAIR_SIZE,
                centerY - CROSSHAIR_THICKNESS / 2,
                centerX + CROSSHAIR_SIZE,
                centerY + CROSSHAIR_THICKNESS / 2 + 1,
                color
            );

            String text = "BOT MODE";
            var font = mc.font;
            int textWidth = font.width(text);
            int textX = centerX - textWidth / 2;
            int textY = centerY - CROSSHAIR_SIZE - 15;
            if (textY > 0) {
                guiGraphics.drawString(font, text, textX, textY, color);
            }
        } catch (Throwable t) {
            AiBotMod.LOGGER.warn("[HudOverlay] Render error: {}", t.getMessage());
        }
    }
}
