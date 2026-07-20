package com.aibot.mod.agent;

import java.util.Map;

public interface Tool {
    String getName();
    String getDescription();
    Map<String, String> getParameters();
    ToolResult execute(String[] args);
}
