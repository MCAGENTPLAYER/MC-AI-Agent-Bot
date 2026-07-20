package com.aibot.mod.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface AIProvider {

    CompletableFuture<AIResponse> chat(String message, Map<String, Object> gameState,
                                       Map<String, Object> emotionState, Map<String, Object> personalityState);

    /** 将工具执行结果喂回 AI，触发 ReAct 循环的下一轮思考 */
    CompletableFuture<AIResponse> continueChat(List<ToolResult> toolResults);

    boolean isConnected();

    void disconnect();

    void setDynamicContext(String context);

    void clearHistory();

    class AIResponse {
        public final String reply;
        public final List<ActionStep> actions;
        public final String thought;
        public final boolean success;

        public AIResponse(String reply, List<ActionStep> actions, String thought, boolean success) {
            this.reply = reply != null ? reply : "";
            this.actions = actions != null ? actions : List.of();
            this.thought = thought;
            this.success = success;
        }

        public static AIResponse success(String reply, List<ActionStep> actions, String thought) {
            return new AIResponse(reply, actions, thought, true);
        }

        public static AIResponse success(String reply) {
            return new AIResponse(reply, List.of(), null, true);
        }

        public static AIResponse error(String message) {
            return new AIResponse(message, List.of(), null, false);
        }
    }

    class ActionStep {
        public final String type;
        public final Map<String, Object> params;
        public final String toolCallId;

        public ActionStep(String type, Map<String, Object> params) {
            this(type, params, null);
        }

        public ActionStep(String type, Map<String, Object> params, String toolCallId) {
            this.type = type;
            this.params = params != null ? params : Map.of();
            this.toolCallId = toolCallId;
        }
    }

    class ToolResult {
        public final String toolCallId;
        public final String content;

        public ToolResult(String toolCallId, String content) {
            this.toolCallId = toolCallId;
            this.content = content != null ? content : "";
        }
    }
}