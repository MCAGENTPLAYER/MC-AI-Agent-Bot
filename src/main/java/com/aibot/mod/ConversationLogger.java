package com.aibot.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 完整对话日志系统
 * 每次启动在 ai_bot/logs/ 下创建新日志文件，记录所有交互详情
 */
public class ConversationLogger {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILENAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static Path logFile = null;
    private static Path logDir = null;
    private static boolean initialized = false;
    private static String filePrefix = "";

    public static void init(String gameDir) {
        try {
            logDir = Paths.get(gameDir, "ai_bot", "logs");
            Files.createDirectories(logDir);
            String name = "conversation_" + LocalDateTime.now().format(FILENAME) + ".log";
            logFile = logDir.resolve(name);
            Files.write(logFile, new byte[0]); // 创建空文件
            initialized = true;
            filePrefix = "[" + LocalDateTime.now().format(TIMESTAMP) + "] ";
            write("╔════════════════════════════════════════════════╗");
            write("║        AI Bot 完整对话日志 (新会话)           ║");
            write("╚════════════════════════════════════════════════╝");
            write("");
            AiBotMod.LOGGER.info("[ConversationLogger] Logging to {}", logFile);
        } catch (IOException e) {
            AiBotMod.LOGGER.error("[ConversationLogger] Failed to init: {}", e.getMessage());
        }
    }

    private static void write(String line) {
        if (logFile == null) return;
        try {
            Files.write(logFile, (line + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AiBotMod.LOGGER.error("[ConversationLogger] Write failed: {}", e.getMessage());
        }
    }

    private static String ts() {
        return "[" + LocalDateTime.now().format(TIMESTAMP) + "]";
    }

    /** 空行 */
    public static void blank() {
        write("");
    }

    /** 分隔线 */
    public static void separator() {
        write("  ────────────────────────────────────────────────");
    }

    /** 玩家消息 */
    public static void logUser(String sender, String message) {
        write(ts() + " 💬 玩家 [" + sender + "]: " + message);
    }

    /** AI 原始回复（包含 <function> 标签的完整内容） */
    public static void logAI(String reply) {
        write(ts() + " 🤖 AI 回复:");
        for (String line : reply.split("\n")) {
            write("      | " + line);
        }
    }

    /** AI 说的文本（去掉 function call 后） */
    public static void logAIChat(String text) {
        write(ts() + " 🗣️ AI 说: " + text);
    }

    /** 工具调用 */
    public static void logTool(String name, String args, String result) {
        separator();
        write(ts() + " 🔧 工具调用: " + name);
        write("      参数: " + args);
        // 结果可能多行，格式化输出
        String[] resultLines = result.split("\n");
        write("      结果: " + resultLines[0]);
        for (int i = 1; i < resultLines.length; i++) {
            write("            " + resultLines[i]);
        }
    }

    /** API 请求/响应（请求体截断，避免 system prompt 撑爆日志） */
    public static void logAPI(String requestBody, int statusCode, String responseBody) {
        separator();
        // 请求体只记录摘要：长度 + 开头 300 字符
        write(ts() + " 🌐 API 请求 [" + requestBody.length() + " 字节]:");
        int maxPreview = 300;
        String preview = requestBody.length() > maxPreview
                ? requestBody.substring(0, maxPreview) + "...[截断，全长" + requestBody.length() + "字节]"
                : requestBody;
        for (String line : preview.split("\n")) {
            write("      | " + line);
        }
        write(ts() + " 🌐 API 响应 [状态码=" + statusCode + "]:");
        for (String line : responseBody.split("\n")) {
            write("      | " + line);
        }
    }

    /** 系统事件 */
    public static void logSystem(String message) {
        write(ts() + " ⚙️ 系统: " + message);
    }

    /** 重要事件（高亮） */
    public static void logEvent(String message) {
        separator();
        write(ts() + " 📌 " + message);
    }

    /** 录制相关 */
    public static void logRecording(String message) {
        write(ts() + " 🎬 录制: " + message);
    }

    /** Bot 执行命令 */
    public static void logBotCommand(String command) {
        write(ts() + " 🤖 Bot 命令: " + command);
    }

    /** 错误信息 */
    public static void logError(String message) {
        write(ts() + " ❌ 错误: " + message);
    }

    /** 获取当前日志文件路径 */
    public static Path getLogFile() {
        return logFile;
    }

    /** 获取日志目录 */
    public static Path getLogDir() {
        return logDir;
    }
}
