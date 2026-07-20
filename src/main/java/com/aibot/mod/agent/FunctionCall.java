package com.aibot.mod.agent;

import java.util.ArrayList;
import java.util.List;

public class FunctionCall {
    private final String name;
    private final String[] args;

    public FunctionCall(String name, String[] args) {
        this.name = name;
        this.args = args != null ? args : new String[0];
    }

    public String getName() {
        return name;
    }

    public String[] getArgs() {
        return args;
    }

    public static FunctionCall parse(String text) {
        List<FunctionCall> all = parseAll(text);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 解析文本中所有 <function name="xxx" args="yyy"> 调用 */
    public static List<FunctionCall> parseAll(String text) {
        List<FunctionCall> results = new ArrayList<>();
        if (text == null || text.isEmpty()) return results;

        int searchFrom = 0;
        while (true) {
            int start = text.indexOf("<function", searchFrom);
            if (start == -1) break;

            int end = text.indexOf(">", start);
            if (end == -1) break;

            String tag = text.substring(start, end + 1);
            searchFrom = end + 1;

            int nameStart = tag.indexOf("name=\"");
            if (nameStart == -1) continue;
            nameStart += 6;
            int nameEnd = tag.indexOf("\"", nameStart);
            if (nameEnd == -1) continue;
            String name = tag.substring(nameStart, nameEnd);

            int argsStart = tag.indexOf("args=\"");
            String[] args = new String[0];
            if (argsStart != -1) {
                argsStart += 6;
                int argsEnd = tag.indexOf("\"", argsStart);
                if (argsEnd != -1) {
                    String argsStr = tag.substring(argsStart, argsEnd);
                    String[] rawArgs = argsStr.isEmpty() ? new String[0] : argsStr.split("\\s+");
                    // 容错：AI 可能传 key=value 格式（如 query=炒菜），去掉参数名前缀
                    args = new String[rawArgs.length];
                    for (int i = 0; i < rawArgs.length; i++) {
                        args[i] = rawArgs[i].replaceFirst("^[a-zA-Z_][a-zA-Z0-9_]*=", "");
                    }
                }
            }

            results.add(new FunctionCall(name, args));
        }

        return results;
    }

    public static String stripFunctionCalls(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("<function[^>]+>", "").trim();
    }

    public boolean isValid() {
        return name != null && !name.isEmpty();
    }
}
