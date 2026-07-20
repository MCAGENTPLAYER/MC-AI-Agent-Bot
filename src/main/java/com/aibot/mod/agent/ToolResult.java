package com.aibot.mod.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

public class ToolResult {
    private static final Gson GSON = new Gson();
    private final boolean success;
    private final String message;
    private final Map<String, Object> data;

    public ToolResult(boolean success, String message, Map<String, Object> data) {
        this.success = success;
        this.message = message;
        this.data = data != null ? data : new HashMap<>();
    }

    public static ToolResult success(String message) {
        return new ToolResult(true, message, null);
    }

    public static ToolResult success(String message, Map<String, Object> data) {
        return new ToolResult(true, message, data);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, message, null);
    }

    public static ToolResult error(String message, Map<String, Object> data) {
        return new ToolResult(false, message, data);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public String toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("success", success);
        obj.addProperty("message", message);
        if (data != null && !data.isEmpty()) {
            JsonObject dataObj = new JsonObject();
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String) {
                    dataObj.addProperty(entry.getKey(), (String) value);
                } else if (value instanceof Number) {
                    dataObj.addProperty(entry.getKey(), (Number) value);
                } else if (value instanceof Boolean) {
                    dataObj.addProperty(entry.getKey(), (Boolean) value);
                } else {
                    dataObj.addProperty(entry.getKey(), String.valueOf(value));
                }
            }
            obj.add("data", dataObj);
        }
        return GSON.toJson(obj);
    }

    public static ToolResult fromJson(String json) {
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        boolean success = obj.has("success") && obj.get("success").getAsBoolean();
        String message = obj.has("message") ? obj.get("message").getAsString() : "";
        Map<String, Object> data = new HashMap<>();
        if (obj.has("data")) {
            JsonObject dataObj = obj.get("data").getAsJsonObject();
            for (String key : dataObj.keySet()) {
                var val = dataObj.get(key);
                if (val.isJsonPrimitive()) {
                    var prim = val.getAsJsonPrimitive();
                    if (prim.isString()) data.put(key, prim.getAsString());
                    else if (prim.isNumber()) data.put(key, prim.getAsNumber());
                    else if (prim.isBoolean()) data.put(key, prim.getAsBoolean());
                }
            }
        }
        return new ToolResult(success, message, data);
    }
}
