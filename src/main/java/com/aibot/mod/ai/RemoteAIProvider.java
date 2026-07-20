package com.aibot.mod.ai;

import com.aibot.mod.AiBotMod;
import com.aibot.mod.Config;
import com.aibot.mod.network.AiServerClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class RemoteAIProvider implements AIProvider {

    private static final Gson GSON = new Gson();

    private WebSocketClient client;
    private String serverUrl;
    private boolean connected = false;
    private long lastPingTime = 0;
    private static final long PING_INTERVAL = 10000;
    private final ConcurrentLinkedQueue<String> messageQueue = new ConcurrentLinkedQueue<>();

    private final Map<String, CompletableFuture<AIResponse>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, String> dynamicContexts = new ConcurrentHashMap<>();
    private long requestIdCounter = 0;

    public RemoteAIProvider() {
        this.serverUrl = Config.getServerUrl();
    }

    @Override
    public CompletableFuture<AIResponse> chat(String message, Map<String, Object> gameState,
                                              Map<String, Object> emotionState, Map<String, Object> personalityState) {
        String requestId = "req_" + (++requestIdCounter);
        CompletableFuture<AIResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "chat");
        payload.put("message", message);
        payload.put("request_id", requestId);

        if (gameState != null && !gameState.isEmpty()) {
            payload.put("game_state", gameState);
        }
        if (emotionState != null && !emotionState.isEmpty()) {
            payload.put("emotion", emotionState);
        }
        if (personalityState != null && !personalityState.isEmpty()) {
            payload.put("personality", personalityState);
        }

        String json = GSON.toJson(payload);

        if (!isConnected()) {
            AiBotMod.LOGGER.warn("[RemoteAI] Not connected, queuing message");
            messageQueue.offer(json);
        } else {
            client.send(json);
            AiBotMod.LOGGER.debug("[RemoteAI] Sent chat: {}", message);
        }

        return future;
    }

    @Override
    public CompletableFuture<AIResponse> continueChat(List<AIProvider.ToolResult> toolResults) {
        String requestId = "req_" + (++requestIdCounter);
        CompletableFuture<AIResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "continue_chat");
        payload.put("request_id", requestId);

        List<Map<String, String>> results = new ArrayList<>();
        for (AIProvider.ToolResult r : toolResults) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("tool_call_id", r.toolCallId);
            item.put("content", r.content);
            results.add(item);
        }
        payload.put("tool_results", results);

        String json = GSON.toJson(payload);

        if (!isConnected()) {
            AiBotMod.LOGGER.warn("[RemoteAI] Not connected, cannot continue chat");
            future.complete(AIResponse.error("后端未连接"));
        } else {
            client.send(json);
            AiBotMod.LOGGER.debug("[RemoteAI] Sent continue_chat with {} results", toolResults.size());
        }

        return future;
    }

    @Override
    public boolean isConnected() {
        return connected && client != null && client.isOpen();
    }

    @Override
    public void disconnect() {
        if (client != null) {
            client.close();
            connected = false;
        }
        pendingRequests.forEach((id, future) -> future.complete(AIResponse.error("连接已断开")));
        pendingRequests.clear();
    }

    @Override
    public void setDynamicContext(String context) {
        if (context == null || context.isEmpty()) {
            dynamicContexts.remove("default");
        } else {
            dynamicContexts.put("default", context);
        }
    }

    @Override
    public void clearHistory() {
        sendStateUpdate(Map.of(), Map.of(), Map.of());
    }

    public void updateDynamicContext(String type, String data) {
        if (data == null || data.isEmpty()) {
            dynamicContexts.remove(type);
        } else {
            dynamicContexts.put(type, data);
        }
    }

    public void connect() {
        if (isConnected()) {
            AiBotMod.LOGGER.info("[RemoteAI] Already connected");
            return;
        }

        try {
            URI uri = new URI(serverUrl);
            client = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected = true;
                    lastPingTime = System.currentTimeMillis();
                    AiBotMod.LOGGER.info("[RemoteAI] Connected to server: {}", serverUrl);
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
                    AiBotMod.LOGGER.info("[RemoteAI] Disconnected: {} ({})", reason, code);
                    pendingRequests.forEach((id, future) -> {
                        if (!future.isDone()) {
                            future.complete(AIResponse.error("连接已断开"));
                        }
                    });
                    pendingRequests.clear();
                }

                @Override
                public void onError(Exception ex) {
                    connected = false;
                    AiBotMod.LOGGER.error("[RemoteAI] Error: {}", ex.getMessage());
                    pendingRequests.forEach((id, future) -> {
                        if (!future.isDone()) {
                            future.complete(AIResponse.error("连接错误: " + ex.getMessage()));
                        }
                    });
                    pendingRequests.clear();
                }
            };

            client.connect();
            AiBotMod.LOGGER.info("[RemoteAI] Connecting to: {}", serverUrl);
        } catch (URISyntaxException e) {
            AiBotMod.LOGGER.error("[RemoteAI] Invalid URL: {}", e.getMessage());
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

    public void sendStateUpdate(Map<String, Object> gameState,
                                Map<String, Object> emotion,
                                Map<String, Object> personality) {
        if (!isConnected()) {
            AiBotMod.LOGGER.warn("[RemoteAI] Not connected, cannot send state");
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
            AiBotMod.LOGGER.info("[RemoteAI] Sent queued message");
        }
    }

    private void handleMessage(String message) {
        try {
            AiBotMod.LOGGER.debug("[RemoteAI] Received: {}", message);

            if (message.contains("pong")) {
                lastPingTime = System.currentTimeMillis();
                return;
            }

            JsonObject json = GSON.fromJson(message, JsonObject.class);

            if (json.has("request_id")) {
                String requestId = json.get("request_id").getAsString();
                CompletableFuture<AIResponse> future = pendingRequests.remove(requestId);
                if (future != null) {
                    String reply = json.has("reply") ? json.get("reply").getAsString() : "";
                    String thought = json.has("thought") ? json.get("thought").getAsString() : null;

                    List<AIProvider.ActionStep> actions = new ArrayList<>();
                    if (json.has("actions") && json.get("actions").isJsonArray()) {
                        JsonArray actionsArray = json.getAsJsonArray("actions");
                        for (int i = 0; i < actionsArray.size(); i++) {
                            JsonObject actionObj = actionsArray.get(i).getAsJsonObject();
                            String type = actionObj.has("type") ? actionObj.get("type").getAsString() : "";
                            Map<String, Object> params = new LinkedHashMap<>();
                            if (actionObj.has("params") && actionObj.get("params").isJsonObject()) {
                                JsonObject paramsObj = actionObj.getAsJsonObject("params");
                                for (var entry : paramsObj.entrySet()) {
                                    params.put(entry.getKey(), paramsObj.get(entry.getKey()).getAsString());
                                }
                            }
                            actions.add(new AIProvider.ActionStep(type, params));
                        }
                    }

                    future.complete(AIResponse.success(reply, actions, thought));
                }
            } else if (json.has("type") && "chat".equals(json.get("type").getAsString())) {
                String reply = json.has("reply") ? json.get("reply").getAsString() : "";

                List<AIProvider.ActionStep> actions = new ArrayList<>();
                if (json.has("actions") && json.get("actions").isJsonArray()) {
                    JsonArray actionsArray = json.getAsJsonArray("actions");
                    for (int i = 0; i < actionsArray.size(); i++) {
                        JsonObject actionObj = actionsArray.get(i).getAsJsonObject();
                        String type = actionObj.has("type") ? actionObj.get("type").getAsString() : "";
                        Map<String, Object> params = new LinkedHashMap<>();
                        if (actionObj.has("params") && actionObj.get("params").isJsonObject()) {
                            JsonObject paramsObj = actionObj.getAsJsonObject("params");
                            for (var entry : paramsObj.entrySet()) {
                                params.put(entry.getKey(), paramsObj.get(entry.getKey()).getAsString());
                            }
                        }
                        actions.add(new AIProvider.ActionStep(type, params));
                    }
                }

                pendingRequests.forEach((id, future) -> {
                    if (!future.isDone()) {
                        future.complete(AIResponse.success(reply, actions, null));
                    }
                });
                pendingRequests.clear();
            }

        } catch (Exception e) {
            AiBotMod.LOGGER.error("[RemoteAI] Failed to parse message: {}", e.getMessage());
            pendingRequests.forEach((id, future) -> {
                if (!future.isDone()) {
                    future.complete(AIResponse.error("消息解析失败: " + e.getMessage()));
                }
            });
            pendingRequests.clear();
        }
    }
}