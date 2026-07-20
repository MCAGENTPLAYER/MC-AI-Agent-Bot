package com.aibot.mod;

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

public class DeepSeekClient {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final List<Tool> tools = new CopyOnWriteArrayList<>();
    private final Map<String, String> dynamicContexts = new ConcurrentHashMap<>();
    private final List<ChatMessage> history = new CopyOnWriteArrayList<>();

    public DeepSeekClient() {
        history.add(new ChatMessage("system", buildSystemPrompt()));
    }

    public DeepSeekClient(List<Tool> tools) {
        this.tools.addAll(tools);
        history.add(new ChatMessage("system", buildSystemPrompt()));
    }

    public void setTools(List<Tool> tools) {
        this.tools.clear();
        this.tools.addAll(tools);
    }

    public void addTool(Tool tool) {
        tools.add(tool);
    }

    public void setEmotionContext(String emotion) {
        this.dynamicContexts.put("emotion", emotion);
    }

    public void setPersonalityContext(String personality) {
        this.dynamicContexts.put("personality", personality);
    }

    public void setMemoryContext(String memory) {
        this.dynamicContexts.put("memory", memory);
    }

    public void setGameStateContext(String gameState) {
        this.dynamicContexts.put("game_state", gameState);
    }

    public void updateDynamicContext(String key, String value) {
        if (value == null || value.isEmpty()) {
            dynamicContexts.remove(key);
        } else {
            dynamicContexts.put(key, value);
        }
    }

    public CompletableFuture<String> ask(String message) {
        String apiKey = Config.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.completedFuture("未配置API Key");
        }

        history.add(new ChatMessage("user", message));

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
                AiBotMod.LOGGER.debug("[DeepSeek] Sending request to: {}", Config.getApiUrl());

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

                AiBotMod.LOGGER.debug("[DeepSeek] Response status: {}, body: {}", statusCode, responseBody.length());

                if (statusCode != 200) {
                    AiBotMod.LOGGER.error("[DeepSeek] API error {}: {}", statusCode, responseBody);
                    return "API请求失败 (" + statusCode + ")";
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

                AiBotMod.LOGGER.debug("[DeepSeek] AI reply: {}", content);
                return content;
            } catch (Exception e) {
                AiBotMod.LOGGER.error("[DeepSeek] API call failed: {}", e.getMessage());
                return "网络连接失败：" + e.getMessage();
            }
        });
    }

    public void clearHistory() {
        history.clear();
        history.add(new ChatMessage("system", buildSystemPrompt()));
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
            
            【任务链系统】当玩家要求你执行多步骤任务时（如"给我拿铁镐和铁甲来"），使用 plan_tasks 工具创建一个任务链。
            任务链会自动按顺序执行每个步骤，你只需要用 plan_tasks 规划好所有步骤即可。
            步骤类型包括：CRAFT(合成) / MINE(挖掘) / CHOP(砍树) / TELEPORT(传送到玩家) / GIVE(给物品) 等。
            
            【任务链失败处理】
            - 如果某步骤失败，任务链会自动重试（最多 2 次）
            - 如果重试仍失败，会跳过该步骤继续执行
            - 如果玩家说"重试"或"继续"，调用 resume_chain 从失败处重新尝试
            - 如果玩家说"跳过"，调用 skip_step 跳过当前步骤
            - 如果玩家说"取消任务"，调用 cancel_chain 取消整个链
            
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