package com.aibot.mod.network;

import com.aibot.mod.AiBotMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public class AiServerClient {

    private static final Gson GSON = new Gson();

    private WebSocketClient client;
    private String serverUrl = "ws://127.0.0.1:8080/ws";
    private boolean connected = false;
    private long lastPingTime = 0;
    private static final long PING_INTERVAL = 10000;
    private final ConcurrentLinkedQueue<String> messageQueue = new ConcurrentLinkedQueue<>();

    private final Map<String, Consumer<ServerResponse>> responseHandlers = new ConcurrentHashMap<>();
    private final Consumer<String> messageCallback;
    private final Consumer<ServerAction[]> actionCallback;

    public interface ServerResponse {
        String getType();
    }

    public static class ChatResponse implements ServerResponse {
        public String type = "chat";
        public String reply;
        public ActionStep[] actions;
        public String thought;

        @Override
        public String getType() { return type; }
    }

    public static class ActionStep {
        public String type;
        public Map<String, Object> params;
    }

    public static class ServerAction {
        public String type;
        public Map<String, Object> params;
    }

    public static class StateUpdate {
        public Map<String, Object> game_state;
        public Map<String, Object> emotion;
        public Map<String, Object> personality;
    }

    public AiServerClient(Consumer<String> messageCallback, Consumer<ServerAction[]> actionCallback) {
        this.messageCallback = messageCallback;
        this.actionCallback = actionCallback;
    }

    public void setServerUrl(String url) {
        this.serverUrl = url;
    }

    public boolean isConnected() {
        return connected && client != null && client.isOpen();
    }

    public void connect() {
        if (isConnected()) {
            AiBotMod.LOGGER.info("[AiServerClient] Already connected");
            return;
        }

        try {
            URI uri = new URI(serverUrl);
            client = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected = true;
                    lastPingTime = System.currentTimeMillis();
                    AiBotMod.LOGGER.info("[AiServerClient] Connected to server: {}", serverUrl);
                    sendPing();
                    flushMessageQueue();
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected = false;
                    AiBotMod.LOGGER.info("[AiServerClient] Disconnected: {} ({})", reason, code);
                }

                @Override
                public void onError(Exception ex) {
                    connected = false;
                    AiBotMod.LOGGER.error("[AiServerClient] Error: {}", ex.getMessage());
                }
            };

            client.connect();
            AiBotMod.LOGGER.info("[AiServerClient] Connecting to: {}", serverUrl);
        } catch (URISyntaxException e) {
            AiBotMod.LOGGER.error("[AiServerClient] Invalid URL: {}", e.getMessage());
        }
    }

    public void disconnect() {
        if (client != null) {
            client.close();
            connected = false;
        }
    }

    public void sendChat(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "chat");
        payload.put("message", message);
        String json = GSON.toJson(payload);
        
        if (!isConnected()) {
            AiBotMod.LOGGER.warn("[AiServerClient] Not connected, queuing message");
            messageQueue.offer(json);
            return;
        }
        
        client.send(json);
        AiBotMod.LOGGER.debug("[AiServerClient] Sent chat: {}", message);
    }

    public void sendChat(String message, Map<String, Object> gameState, 
                         Map<String, Object> emotion, Map<String, Object> personality) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "chat");
        payload.put("message", message);
        payload.put("game_state", gameState != null ? gameState : Map.of());
        payload.put("emotion", emotion != null ? emotion : Map.of());
        payload.put("personality", personality != null ? personality : Map.of());
        String json = GSON.toJson(payload);
        
        if (!isConnected()) {
            AiBotMod.LOGGER.warn("[AiServerClient] Not connected, queuing message");
            messageQueue.offer(json);
            return;
        }
        
        client.send(json);
        AiBotMod.LOGGER.debug("[AiServerClient] Sent chat with state: {}", message);
    }

    public void sendStateUpdate(Map<String, Object> gameState, 
                                Map<String, Object> emotion, 
                                Map<String, Object> personality) {
        if (!isConnected()) {
            AiBotMod.LOGGER.warn("[AiServerClient] Not connected, cannot send state");
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "state_update");
        payload.put("game_state", gameState != null ? gameState : Map.of());
        payload.put("emotion", emotion != null ? emotion : Map.of());
        payload.put("personality", personality != null ? personality : Map.of());
        client.send(GSON.toJson(payload));
    }

    private void sendPing() {
        if (!isConnected()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ping");
        client.send(GSON.toJson(payload));
    }

    private void flushMessageQueue() {
        String message;
        while ((message = messageQueue.poll()) != null) {
            client.send(message);
            AiBotMod.LOGGER.info("[AiServerClient] Sent queued message");
        }
    }

    private void handleMessage(String message) {
        try {
            AiBotMod.LOGGER.debug("[AiServerClient] Received: {}", message);

            if (message.contains("pong")) {
                lastPingTime = System.currentTimeMillis();
                return;
            }

            ChatResponse response = GSON.fromJson(message, ChatResponse.class);
            if (response == null) return;

            if (response.reply != null && !response.reply.isEmpty()) {
                Minecraft.getInstance().execute(() -> {
                    if (messageCallback != null) {
                        messageCallback.accept(response.reply);
                    }
                });
            }

            if (response.actions != null && response.actions.length > 0) {
                ServerAction[] actions = new ServerAction[response.actions.length];
                for (int i = 0; i < response.actions.length; i++) {
                    ServerAction action = new ServerAction();
                    action.type = response.actions[i].type;
                    action.params = response.actions[i].params;
                    actions[i] = action;
                }
                Minecraft.getInstance().execute(() -> {
                    if (actionCallback != null) {
                        actionCallback.accept(actions);
                    }
                });
            }

        } catch (Exception e) {
            AiBotMod.LOGGER.error("[AiServerClient] Failed to parse message: {}", e.getMessage());
        }
    }

    public void tick() {
        if (!isConnected()) {
            connect();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastPingTime > PING_INTERVAL) {
            sendPing();
            lastPingTime = now;
        }
    }
}
