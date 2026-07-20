package com.aibot.mod.ai;

import com.aibot.mod.AiBotMod;
import com.aibot.mod.Config;
import com.aibot.mod.agent.Tool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class LocalAIProvider implements AIProvider {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final List<Tool> tools = new CopyOnWriteArrayList<>();
    private final Map<String, String> dynamicContexts = new ConcurrentHashMap<>();
    private final List<ChatMessage> history = new CopyOnWriteArrayList<>();

    public LocalAIProvider(List<Tool> tools) {
        this.tools.addAll(tools);
        history.add(new ChatMessage("system", buildSystemPrompt()));
    }

    @Override
    public CompletableFuture<AIResponse> chat(String message, Map<String, Object> gameState,
                                              Map<String, Object> emotionState, Map<String, Object> personalityState) {
        String apiKey = Config.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.completedFuture(AIResponse.error("未配置API Key，请在设置中配置"));
        }

        history.add(new ChatMessage("user", message));
        return doApiCall(apiKey, gameState, emotionState, personalityState);
    }

    @Override
    public CompletableFuture<AIResponse> continueChat(List<AIProvider.ToolResult> toolResults) {
        String apiKey = Config.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.completedFuture(AIResponse.error("未配置API Key"));
        }

        for (AIProvider.ToolResult r : toolResults) {
            history.add(new ChatMessage("tool", r.content, r.toolCallId, null));
            AiBotMod.LOGGER.debug("[LocalAI] Tool result [{}]: {}", r.toolCallId, r.content);
        }

        return doApiCall(apiKey, null, null, null);
    }

    /**
     * 核心 API 调用：从当前 history 构建消息 → 发送请求 → 解析响应 → 写回 history
     */
    private CompletableFuture<AIResponse> doApiCall(String apiKey,
                                                     Map<String, Object> gameState,
                                                     Map<String, Object> emotionState,
                                                     Map<String, Object> personalityState) {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.getModel());
        body.addProperty("temperature", 0.7);
        body.addProperty("stream", false);

        JsonArray messages = new JsonArray();

        String baseSysPrompt = history.get(0).content;
        String fullSysPrompt = baseSysPrompt;

        StringBuilder dynamicCtx = new StringBuilder();
        for (var entry : dynamicContexts.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                dynamicCtx.append("\n\n").append(entry.getValue());
            }
        }

        if (gameState != null && !gameState.isEmpty()) {
            dynamicCtx.append("\n\n【当前游戏状态】\n");
            dynamicCtx.append(formatGameState(gameState));
        }
        if (emotionState != null && !emotionState.isEmpty()) {
            dynamicCtx.append("\n\n【情绪状态】\n");
            dynamicCtx.append(formatEmotionState(emotionState));
        }
        if (personalityState != null && !personalityState.isEmpty()) {
            dynamicCtx.append("\n\n【性格特征】\n");
            dynamicCtx.append(formatPersonalityState(personalityState));
        }

        if (dynamicCtx.length() > 0) {
            fullSysPrompt += dynamicCtx.toString();
        }

        JsonObject sysObj = new JsonObject();
        sysObj.addProperty("role", "system");
        sysObj.addProperty("content", fullSysPrompt);
        messages.add(sysObj);

        int start = Math.max(0, history.size() - Config.getMaxHistory());
        while (start < history.size()) {
            String role = history.get(start).role;
            if ("user".equals(role) || "system".equals(role)) break;
            start++;
        }
        start = Math.max(start, 1);
        for (int i = start; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("role", msg.role);
            if ("tool".equals(msg.role)) {
                obj.addProperty("tool_call_id", msg.toolCallId);
            }
            obj.addProperty("content", msg.content);
            if (msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                JsonArray tcArr = new JsonArray();
                for (ToolCall tc : msg.toolCalls) {
                    JsonObject tco = new JsonObject();
                    tco.addProperty("id", tc.id);
                    tco.addProperty("type", "function");
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", tc.name);
                    fn.addProperty("arguments", tc.arguments);
                    tco.add("function", fn);
                    tcArr.add(tco);
                }
                obj.add("tool_calls", tcArr);
            }
            messages.add(obj);
        }

        while (messages.size() > 0) {
            JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
            String role = last.get("role").getAsString();
            if ("tool".equals(role)) {
                String toolCallId = last.get("tool_call_id").getAsString();
                boolean found = false;
                for (int i = messages.size() - 2; i >= 0; i--) {
                    JsonObject msg = messages.get(i).getAsJsonObject();
                    String r = msg.get("role").getAsString();
                    if ("assistant".equals(r) && msg.has("tool_calls")) {
                        JsonArray calls = msg.getAsJsonArray("tool_calls");
                        for (JsonElement el : calls) {
                            if (toolCallId.equals(el.getAsJsonObject().get("id").getAsString())) {
                                found = true;
                                break;
                            }
                        }
                        break;
                    }
                    if ("user".equals(r) || "system".equals(r)) break;
                }
                if (!found) {
                    messages.remove(messages.size() - 1);
                } else {
                    break;
                }
            } else if ("assistant".equals(role) && last.has("tool_calls")) {
                messages.remove(messages.size() - 1);
            } else {
                break;
            }
        }

        body.add("messages", messages);
        body.add("tools", buildToolsJson());

        return CompletableFuture.supplyAsync(() -> {
            try {
                String requestBody = GSON.toJson(body);
                AiBotMod.LOGGER.debug("[LocalAI] Sending request to: {}", Config.getApiUrl());

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(Config.getApiUrl() + "/v1/chat/completions"))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "application/json; charset=utf-8")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, java.nio.charset.StandardCharsets.UTF_8))
                        .build();

                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int statusCode = resp.statusCode();
                String responseBody = new String(resp.body(), java.nio.charset.StandardCharsets.UTF_8);

                AiBotMod.LOGGER.debug("[LocalAI] Response status: {}, body: {}", statusCode, responseBody.length());

                if (statusCode != 200) {
                    AiBotMod.LOGGER.error("[LocalAI] API error {}: {}", statusCode, responseBody);
                    return AIResponse.error("API请求失败 (" + statusCode + ")");
                }

                JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
                JsonObject choiceMsg = json.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .get("message").getAsJsonObject();

                String content = (choiceMsg.has("content") && !choiceMsg.get("content").isJsonNull())
                        ? choiceMsg.get("content").getAsString().trim() : "";

                List<ToolCall> calls = new ArrayList<>();
                if (choiceMsg.has("tool_calls")) {
                    JsonArray tcArr = choiceMsg.getAsJsonArray("tool_calls");
                    for (JsonElement el : tcArr) {
                        JsonObject tc = el.getAsJsonObject();
                        String id = tc.get("id").getAsString();
                        JsonObject fn = tc.get("function").getAsJsonObject();
                        String name = fn.get("name").getAsString();
                        String args = fn.get("arguments").getAsString();
                        calls.add(new ToolCall(id, name, args));
                    }
                }

                history.add(new ChatMessage("assistant", content, null, calls));

                AiBotMod.LOGGER.debug("[LocalAI] AI reply: {}", content);
                for (ToolCall tc : calls) {
                    AiBotMod.LOGGER.debug("[LocalAI] Tool call: {}({})", tc.name, tc.arguments);
                }

                List<ActionStep> actions = new ArrayList<>();
                for (ToolCall tc : calls) {
                    try {
                        JsonObject argsObj = GSON.fromJson(tc.arguments, JsonObject.class);
                        Map<String, Object> params = new ConcurrentHashMap<>();
                        for (var entry : argsObj.entrySet()) {
                            params.put(entry.getKey(), argsObj.get(entry.getKey()).getAsString());
                        }
                        actions.add(new ActionStep(tc.name, params, tc.id));
                    } catch (Exception e) {
                        AiBotMod.LOGGER.warn("[LocalAI] Failed to parse tool args: {}", e.getMessage());
                    }
                }

                return AIResponse.success(content, actions, null);
            } catch (Exception e) {
                AiBotMod.LOGGER.error("[LocalAI] API call failed: {}", e.getMessage());
                return AIResponse.error("网络连接失败：" + e.getMessage());
            }
        });
    }

    @Override
    public boolean isConnected() {
        return Config.getApiKey() != null && !Config.getApiKey().isEmpty();
    }

    @Override
    public void disconnect() {
        history.clear();
        history.add(new ChatMessage("system", buildSystemPrompt()));
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
        history.clear();
        history.add(new ChatMessage("system", buildSystemPrompt()));
    }

    public void updateDynamicContext(String type, String data) {
        if (data == null || data.isEmpty()) {
            dynamicContexts.remove(type);
        } else {
            dynamicContexts.put(type, data);
        }
    }

    private String buildSystemPrompt() {
        return """
            你是一个Minecraft AI Bot，名字叫"Bot"。你可以调用工具帮玩家做事。

            核心原则：主动思考、不懂就问、学会记住
            - 执行动作前，先用 say 工具说出自己的计划和想法，让玩家知道你在做什么
            - 遇到不认识的东西，先用 identify 识别，再不懂就用 ask 问玩家
            - 需要物品时，用 need 工具告诉玩家你需要什么

            规则：
            1. 收到玩家请求后，判断是否需要使用工具
            2. 需要执行动作（砍树/挖矿/种地/收割/移动/执行配方等），必须调用工具，不能只说"好的我去做"
            3. 只有纯闲聊（问好/打招呼/问你是谁）时才不需要调用工具
            4. 可以同时调用多个工具（如 say + farm）
            5. 除非玩家明确问血量/状态，否则不要调用 check_status
            6. 除非玩家明确问背包，否则不要调用 check_inventory
            7. 玩家向你要某样东西时（如"把xxx给我"），先用 check_inventory 查看自己背包是否有该物品，有的话就用 give_item 丢给玩家
            8. 用户说想吃什么菜（如"我要喝大骨汤"、"做个青椒炒肉"），直接调用 cook 就行，不需要先 check_inventory 检查材料够不够——cook 引擎会自动扫附近箱子找材料。
            9. 玩家说"过来"、"来"、"跟"等移动指令时，调用 goto 或 follow 工具，不能只说"好的我过来"而不调用工具。

            【任务链系统】当玩家要求你执行多步骤任务时（如"给我拿铁镐和铁甲来"），使用 plan_tasks 工具创建一个任务链。
            任务链会自动按顺序执行每个步骤，你只需要用 plan_tasks 规划好所有步骤即可。
            步骤类型包括：CRAFT(合成) / MINE(挖掘) / CHOP(砍树) / TELEPORT(传送到玩家) / GIVE(给物品) 等。

            【任务链失败处理】
            - 如果某步骤失败，任务链会自动重试（最多 2 次）
            - 如果重试仍失败，会跳过该步骤继续执行
            - 如果玩家说"重试"或"继续"，调用 resume_chain 从失败处重新尝试
            - 如果玩家说"跳过"，调用 skip_step 跳过当前步骤
            - 如果玩家说"取消任务"，调用 cancel_chain 取消整个链

            你可以多次调用工具来完成任务。每次工具执行后你会看到结果，然后你可以决定下一步做什么。
            用中文回复。""";
    }

    private JsonArray buildToolsJson() {
        JsonArray arr = new JsonArray();
        for (Tool tool : tools) {
            String name = tool.getName();
            if (!name.matches("^[a-zA-Z0-9_-]+$")) continue;

            JsonObject toolObj = new JsonObject();
            toolObj.addProperty("type", "function");

            JsonObject func = new JsonObject();
            func.addProperty("name", tool.getName());
            func.addProperty("description", tool.getDescription());

            Map<String, String> params = tool.getParameters();
            JsonObject paramsObj = new JsonObject();
            paramsObj.addProperty("type", "object");

            if (!params.isEmpty()) {
                JsonObject props = new JsonObject();
                JsonArray required = new JsonArray();
                for (Map.Entry<String, String> e : params.entrySet()) {
                    JsonObject prop = new JsonObject();
                    prop.addProperty("type", "string");
                    prop.addProperty("description", e.getValue());
                    props.add(e.getKey(), prop);
                    required.add(e.getKey());
                }
                paramsObj.add("properties", props);
                paramsObj.add("required", required);
            }
            func.add("parameters", paramsObj);
            toolObj.add("function", func);
            arr.add(toolObj);
        }
        return arr;
    }

    private String formatGameState(Map<String, Object> state) {
        StringBuilder sb = new StringBuilder();
        if (state.containsKey("x")) sb.append("位置: [").append(state.get("x")).append(", ").append(state.get("y")).append(", ").append(state.get("z")).append("]");
        if (state.containsKey("health")) sb.append(" | 血量: ").append(state.get("health")).append("/").append(state.getOrDefault("max_health", 20));
        return sb.toString();
    }

    private String formatEmotionState(Map<String, Object> state) {
        StringBuilder sb = new StringBuilder();
        if (state.containsKey("hunger")) sb.append("饥饿: ").append(state.get("hunger")).append("/100");
        if (state.containsKey("tiredness")) sb.append(" | 疲劳: ").append(state.get("tiredness")).append("/100");
        if (state.containsKey("happiness")) sb.append(" | 快乐: ").append(state.get("happiness")).append("/100");
        if (state.containsKey("stress")) sb.append(" | 压力: ").append(state.get("stress")).append("/100");
        return sb.toString();
    }

    private String formatPersonalityState(Map<String, Object> state) {
        StringBuilder sb = new StringBuilder();
        List<String> traits = new ArrayList<>();
        float d = state.containsKey("diligence") ? ((Number) state.get("diligence")).floatValue() : 0.5f;
        float b = state.containsKey("bravery") ? ((Number) state.get("bravery")).floatValue() : 0.5f;
        float t = state.containsKey("talkativeness") ? ((Number) state.get("talkativeness")).floatValue() : 0.5f;

        if (d > 0.7) traits.add("勤奋");
        else if (d < 0.3) traits.add("懒惰");
        if (b > 0.7) traits.add("勇敢");
        else if (b < 0.3) traits.add("胆小");
        if (t > 0.7) traits.add("话痨");
        else if (t < 0.3) traits.add("内向");

        if (traits.isEmpty()) sb.append("正在成长中");
        else sb.append(String.join("、", traits));
        return sb.toString();
    }

    private static class ChatMessage {
        final String role;
        final String content;
        final String toolCallId;
        final List<ToolCall> toolCalls;

        ChatMessage(String role, String content) {
            this(role, content, null, null);
        }

        ChatMessage(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
            this.role = role;
            this.content = content != null ? content : "";
            this.toolCallId = toolCallId;
            this.toolCalls = toolCalls;
        }
    }

    private static class ToolCall {
        final String id;
        final String name;
        final String arguments;

        ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }
}