package com.aibot.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public class GameControlHandler {
    private static final Minecraft mc = Minecraft.getInstance();
    private static boolean wasBotMode = false;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (mc.player == null) return;

        try {
            if (!mc.player.isAlive() && mc.screen instanceof DeathScreen) {
                mc.player.respawn();
            }

            if (BotMode.isActive() && mc.screen instanceof PauseScreen) {
                mc.setScreen(null);
                releaseMouse();
            }

            if (BotMode.isActive() && !wasBotMode) {
                releaseMouse();
            }
            wasBotMode = BotMode.isActive();
        } catch (Throwable t) {
            AiBotMod.LOGGER.warn("[GameControl] Tick error: {}", t.getMessage());
        }
    }

    private void releaseMouse() {
        try {
            long windowHandle = mc.getWindow().getWindow();
            if (windowHandle != 0) {
                GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            }
        } catch (Throwable t) {
            AiBotMod.LOGGER.warn("[GameControl] Failed to release mouse: {}", t.getMessage());
        }
    }
}
