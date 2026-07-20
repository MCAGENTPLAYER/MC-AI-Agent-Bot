package com.aibot.mod;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.LinkedHashMap;
import java.util.regex.Pattern;

@OnlyIn(Dist.CLIENT)
public class ChatHandler {
    private static final Pattern BOT_PATTERN = Pattern.compile(
        "<([^>]+)>\\s*(.*)", Pattern.DOTALL
    );
    private static final long DEDUP_WINDOW_MS = 5000;
    private static final int MAX_DEDUP_ENTRIES = 64;

    private final BotController controller;
    private final LinkedHashMap<String, Long> recentMessages = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> ownMessages = new LinkedHashMap<>();

    public ChatHandler(BotController controller) {
        this.controller = controller;
    }

    private void sendToPlayer(String text) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.execute(() -> mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(text), false));
    }

    public void markOwnMessage(String content) {
        if (content == null || content.isEmpty()) return;
        String key = content.trim();
        synchronized (ownMessages) {
            ownMessages.put(key, System.currentTimeMillis());
            if (ownMessages.size() > MAX_DEDUP_ENTRIES) {
                var it = ownMessages.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    private boolean isOwnMessage(String content) {
        if (content == null) return false;
        String trimmed = content.trim();
        synchronized (ownMessages) {
            Long lastTime = ownMessages.get(trimmed);
            if (lastTime == null) return false;
            if (System.currentTimeMillis() - lastTime > DEDUP_WINDOW_MS) {
                ownMessages.remove(trimmed);
                return false;
            }
            return true;
        }
    }

    private boolean shouldProcess(String sender, String message) {
        String dedupKey = sender + ":" + message;
        long now = System.currentTimeMillis();
        synchronized (recentMessages) {
            Long lastTime = recentMessages.get(dedupKey);
            if (lastTime != null && (now - lastTime) < DEDUP_WINDOW_MS) {
                return false;
            }
            recentMessages.put(dedupKey, now);
            if (recentMessages.size() > MAX_DEDUP_ENTRIES) {
                var it = recentMessages.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
        return true;
    }

    @SubscribeEvent
    public void onClientChat(ClientChatEvent event) {
        String message = event.getMessage();
        if (message == null) return;
        message = message.trim();
        if (message.isEmpty()) return;

        String localName = Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.getName().getString() : "";

        AiBotMod.LOGGER.info("[AI Bot DIAG] onClientChat fired! message={}, player={}", message, localName);
        String lambdaMsg = message;

        if (lambdaMsg.startsWith("!")) {
            AiBotMod.LOGGER.info("[AI Bot] Local command: {}", lambdaMsg);
            event.setCanceled(true);
            controller.executeCommand(lambdaMsg);
        } else {
            AiBotMod.LOGGER.info("[AI Bot] Player message: {}", lambdaMsg);
            controller.processMessage(localName, lambdaMsg);
        }
    }

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        try {
            String raw = event.getMessage().getString();
            if (raw == null || raw.isEmpty()) return;
            if (!raw.startsWith("<")) return;

            var matcher = BOT_PATTERN.matcher(raw);
            if (!matcher.matches()) return;

            String sender = matcher.group(1);
            String message = matcher.group(2).trim();
            if (message.isEmpty()) return;

            String localName = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getName().getString() : "";

            if (sender.equalsIgnoreCase(localName)) {
                return;
            }

            if (message.startsWith("[AI Bot]") || message.startsWith("[Bot]") ||
                message.startsWith("AI Bot:") || message.startsWith("[") || isOwnMessage(message)) {
                return;
            }

            if (message.contains("[AI Bot]") && isOwnMessage(message.substring(message.indexOf("[AI Bot]")))) {
                return;
            }

            if (!shouldProcess(sender, message)) return;

            AiBotMod.LOGGER.info("[AI Bot] Received from {}: {}", sender, message);
            controller.processMessage(sender, message);
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[AI Bot] Error in chat handler: {}", e.getMessage());
        }
    }
}
