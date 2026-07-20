package com.aibot.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * FTB Quests 任务书扫描器
 * 从 config/ftbquests/quests/chapters/ 读取 .snbt 任务章节文件，
 * 配合 kubejs/assets/ftbquestlocalizer/lang/zh_cn.json 语言文件，
 * 解析任务数据为 AI 可用的上下文文本。
 */
public class QuestScanner {
    private static final Logger LOGGER = AiBotMod.LOGGER;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // === 数据结构 ===

    public static class QuestData {
        public List<QuestChapter> chapters = new ArrayList<>();
        public int totalQuests = 0;
        public String error = "";
    }

    public static class QuestChapter {
        public String title = "";
        public List<Quest> quests = new ArrayList<>();
    }

    public static class Quest {
        public String title = "";
        public String description = "";
        public List<String> tasks = new ArrayList<>();
        public List<String> rewards = new ArrayList<>();
        public boolean hasItemTask = false;  // 是否需要收集物品
    }

    // === 入口：扫描所有任务 ===

    public static QuestData scan() {
        QuestData data = new QuestData();
        try {
            // 1. 定位 FTB Quests 章节目录
            Path questDir = findQuestDir();
            if (questDir == null) {
                data.error = "未找到 FTB Quests 任务文件";
                return data;
            }

            // 2. 加载语言文件（中文优先）
            Map<String, String> lang = loadLanguage();

            // 3. 解析每个 .snbt 章节文件
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(questDir, "*.snbt")) {
                for (Path file : stream) {
                    QuestChapter chapter = parseChapter(file, lang);
                    if (chapter != null && !chapter.quests.isEmpty()) {
                        data.chapters.add(chapter);
                        data.totalQuests += chapter.quests.size();
                    }
                }
            }

            LOGGER.info("[QuestScanner] 扫描到 {} 个章节，{} 个任务", data.chapters.size(), data.totalQuests);
        } catch (Exception e) {
            LOGGER.error("[QuestScanner] 扫描失败: {}", e.getMessage());
            data.error = "扫描失败: " + e.getMessage();
        }
        return data;
    }

    // === 查找 FTB Quests 目录 ===

    private static Path findQuestDir() {
        try {
            // 尝试多个可能的路径
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();

            // 路径1: config/ftbquests/quests/chapters/（标准 FTB Quests）
            Path[] candidates = new Path[]{
                gameDir.resolve("config/ftbquests/quests/chapters"),
                gameDir.resolve("config/ftbquests_raw/quests/chapters"),
                gameDir.resolve("defaultconfigs/ftbquests/quests/chapters"),
            };

            for (Path p : candidates) {
                if (Files.isDirectory(p)) {
                    // 确认有 .snbt 文件
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.snbt")) {
                        if (ds.iterator().hasNext()) {
                            return p;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[QuestScanner] 查找目录失败: {}", e.getMessage());
        }
        return null;
    }

    // === 加载语言文件 ===

    private static Map<String, String> loadLanguage() {
        Map<String, String> map = new HashMap<>();
        try {
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();

            // 优先中文，其次英文
            String[] langFiles = {
                "kubejs/assets/ftbquestlocalizer/lang/zh_cn.json",
                "kubejs/assets/ftbquestlocalizer/lang/zh_hk.json",
                "kubejs/assets/ftbquestlocalizer/lang/zh_tw.json",
                "kubejs/assets/ftbquestlocalizer/lang/en_us.json",
                "assets/ftbquestlocalizer/lang/zh_cn.json",
                "assets/ftbquestlocalizer/lang/en_us.json",
            };

            for (String relPath : langFiles) {
                Path file = gameDir.resolve(relPath);
                if (Files.exists(file)) {
                    try (FileReader reader = new FileReader(file.toFile())) {
                        JsonObject json = GSON.fromJson(reader, JsonObject.class);
                        if (json != null) {
                            for (var entry : json.entrySet()) {
                                map.put(entry.getKey(), entry.getValue().getAsString());
                            }
                        }
                    }
                    LOGGER.info("[QuestScanner] 加载语言文件: {} ({} 条)", relPath, map.size());
                    if (!map.isEmpty()) break; // 找到了就停
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[QuestScanner] 加载语言文件失败: {}", e.getMessage());
        }
        return map;
    }

    // === 解析单个章节文件 ===

    private static QuestChapter parseChapter(Path file, Map<String, String> lang) {
        try {
            String content = Files.readString(file);

            QuestChapter chapter = new QuestChapter();

            // 解析章节标题 - 可能是语言键
            String titleRaw = extractField(content, "title");
            chapter.title = resolveLocale(titleRaw, lang);

            // 提取 quests 数组中的所有 quest 块
            List<String> questBlocks = extractBlocksFromArray(content, "quests");
            for (String qb : questBlocks) {
                Quest quest = new Quest();

                // 标题
                String qTitle = extractField(qb, "title");
                quest.title = resolveLocale(qTitle, lang);

                // 副标题（可选）
                String subtitle = extractField(qb, "subtitle");
                if (!subtitle.isEmpty()) {
                    String subResolved = resolveLocale(subtitle, lang);
                    if (!subResolved.isEmpty() && !subResolved.equals(subtitle)) {
                        quest.title = quest.title + " - " + subResolved;
                    }
                }

                // 描述
                quest.description = extractDescription(qb, lang);

                // 任务要求
                List<String> taskBlocks = extractBlocksFromArray(qb, "tasks");
                for (String tb : taskBlocks) {
                    String taskDesc = extractTaskDesc(tb, lang);
                    if (!taskDesc.isEmpty()) {
                        quest.tasks.add(taskDesc);
                        if (tb.contains("type: \"item\"")) {
                            quest.hasItemTask = true;
                        }
                    }
                }

                // 奖励
                List<String> rewardBlocks = extractBlocksFromArray(qb, "rewards");
                for (String rb : rewardBlocks) {
                    String rewardDesc = extractRewardDesc(rb, lang);
                    if (!rewardDesc.isEmpty()) {
                        quest.rewards.add(rewardDesc);
                    }
                }

                if (!quest.title.isEmpty()) {
                    chapter.quests.add(quest);
                }
            }

            return chapter;

        } catch (Exception e) {
            LOGGER.warn("[QuestScanner] 解析章节失败 {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    // === SNBT 解析器 ===

    /** 提取简单字段值（支持引号字符串） */
    private static String extractField(String text, String key) {
        // key: "value" 或 key: "value_with_}"
        Pattern p = Pattern.compile(
            "(?<=^|\\n|[,{])\\s*" + Pattern.quote(key) + "\\s*:\\s*\"([^\"]*)\"\\s*",
            Pattern.MULTILINE
        );
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 提取描述文本（description 数组）：合并所有语言键并解析 */
    private static String extractDescription(String text, Map<String, String> lang) {
        int idx = text.indexOf("description:");
        if (idx < 0) return "";

        int bracketStart = text.indexOf('[', idx);
        if (bracketStart < 0) return "";

        // 找到匹配的 ]
        int bracketEnd = findMatchingBracket(text, bracketStart, '[', ']');
        if (bracketEnd < 0) return "";

        String arrayContent = text.substring(bracketStart + 1, bracketEnd);

        // 提取所有引号字符串
        StringBuilder desc = new StringBuilder();
        Pattern p = Pattern.compile("\"([^\"]*)\"");
        Matcher m = p.matcher(arrayContent);
        while (m.find()) {
            String resolved = resolveLocale(m.group(1), lang);
            // 去除颜色代码（\u0026b, \u0026l, \u0026r 等）
            resolved = stripColorCodes(resolved);
            if (!resolved.isEmpty()) {
                if (desc.length() > 0) desc.append("\n");
                desc.append(resolved);
            }
        }
        return desc.toString();
    }

    /** 从数组中提取所有块（如 quests: [{...}, {...}]） */
    private static List<String> extractBlocksFromArray(String text, String arrayKey) {
        List<String> blocks = new ArrayList<>();
        int idx = text.indexOf(arrayKey + ":");
        if (idx < 0) return blocks;

        int bracketStart = text.indexOf('[', idx);
        if (bracketStart < 0) return blocks;

        int pos = bracketStart + 1;
        int depth = 0;
        int blockStart = -1;

        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '{') {
                if (depth == 0) blockStart = pos;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && blockStart >= 0) {
                    blocks.add(text.substring(blockStart + 1, pos));
                    blockStart = -1;
                }
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth < 0) break;
            }
            pos++;
        }
        return blocks;
    }

    /** 查找匹配的闭合括号 */
    private static int findMatchingBracket(String text, int start, char open, char close) {
        int depth = 1;
        int pos = start + 1;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return pos;
            }
            pos++;
        }
        return -1;
    }

    /** 提取任务描述 */
    private static String extractTaskDesc(String taskText, Map<String, String> lang) {
        String type = extractField(taskText, "type");
        if (type.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        switch (type) {
            case "item" -> {
                String item = extractField(taskText, "item");
                String count = extractField(taskText, "count");
                String itemName = getItemLocalName(item);
                sb.append("收集 ");
                if (!count.isEmpty()) {
                    try {
                        long c = Long.parseLong(count);
                        sb.append(c).append(" 个 ");
                    } catch (NumberFormatException e) {
                        sb.append(count).append(" 个 ");
                    }
                } else {
                    sb.append("1 个 ");
                }
                sb.append(itemName.isEmpty() ? item : itemName);
            }
            case "checkmark" -> sb.append("手动完成（点击勾选）");
            case "kill" -> {
                String entity = extractField(taskText, "entity");
                String count = extractField(taskText, "count");
                String name = getItemLocalName(entity);
                sb.append("击杀 ");
                if (!count.isEmpty()) sb.append(count).append(" 只 ");
                sb.append(name.isEmpty() ? entity : name);
            }
            case "dimension" -> {
                String dim = extractField(taskText, "dimension");
                sb.append("前往维度: ").append(dim);
            }
            case "location" -> {
                String x = extractField(taskText, "x");
                String y = extractField(taskText, "y");
                String z = extractField(taskText, "z");
                String dim = extractField(taskText, "dimension");
                sb.append("到达坐标: ");
                if (!x.isEmpty()) sb.append(x).append(", ").append(y).append(", ").append(z);
                if (!dim.isEmpty()) sb.append(" (").append(dim).append(")");
            }
            case "stat" -> {
                String stat = extractField(taskText, "stat");
                String value = extractField(taskText, "value");
                sb.append("统计: ").append(stat);
                if (!value.isEmpty()) sb.append(" >= ").append(value);
            }
            default -> {
                String title = extractField(taskText, "title");
                sb.append(title.isEmpty() ? type : title);
            }
        }

        return sb.toString();
    }

    /** 提取奖励描述 */
    private static String extractRewardDesc(String rewardText, Map<String, String> lang) {
        String type = extractField(rewardText, "type");
        if (type.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        switch (type) {
            case "item" -> {
                // 物品奖励在 item 字段或 icon 字段
                String item = extractField(rewardText, "item");
                if (item.isEmpty()) item = extractField(rewardText, "icon");
                String count = extractField(rewardText, "count");
                String itemName = getItemLocalName(item);
                sb.append(itemName.isEmpty() ? item : itemName);
                if (!count.isEmpty()) {
                    try {
                        long c = Long.parseLong(count);
                        sb.append(" x").append(c);
                    } catch (NumberFormatException e) {
                        sb.append(" x").append(count);
                    }
                }
            }
            case "xp_levels" -> {
                String levels = extractField(rewardText, "xp_levels");
                sb.append(levels).append(" 级经验");
            }
            case "command" -> {
                sb.append("执行命令奖励");
            }
            case "choice" -> {
                sb.append("选择奖励");
            }
            default -> {
                String title = extractField(rewardText, "title");
                sb.append(title.isEmpty() ? type : title);
            }
        }
        return sb.toString();
    }

    // === 工具方法 ===

    /** 解析语言键：去除花括号后查表，查不到返回原值 */
    private static String resolveLocale(String raw, Map<String, String> lang) {
        if (raw == null || raw.isEmpty()) return "";
        String key = raw;
        // 去除花括号包裹（语言键在 SNBT 中是 "{key}" 格式）
        if (key.startsWith("{") && key.endsWith("}")) {
            key = key.substring(1, key.length() - 1);
        }
        return lang.getOrDefault(key, raw.startsWith("{") ? "" : raw);
    }

    /** 去除 Minecraft 颜色代码（\u0026 + 字母） */
    private static String stripColorCodes(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replaceAll("\u00a7[0-9a-fk-or]|\\\\u0026[0-9a-fk-orl-nr]", "").trim();
    }

    /** 获取物品的中文显示名（通过翻译键） */
    private static String getItemLocalName(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "";
        var rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (rl == null) return itemId;
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(rl);
        if (item == null) return itemId;
        try {
            return net.minecraft.network.chat.Component.translatable(item.getDescriptionId()).getString();
        } catch (Exception e) {
            return itemId;
        }
    }

    // === 格式化输出 ===

    /** 格式化为 AI 可用的上下文文本 */
    public static String formatForAI(QuestData data) {
        if (data == null || data.chapters.isEmpty()) {
            return "（未检测到任务书系统）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前任务（FTB Quests）\n");
        sb.append("共有 ").append(data.totalQuests).append(" 个任务，")
          .append(data.chapters.size()).append(" 个章节\n");

        for (QuestChapter chapter : data.chapters) {
            sb.append("\n### ").append(chapter.title).append("\n");
            int i = 1;
            for (Quest quest : chapter.quests) {
                sb.append(i++).append(". **").append(quest.title).append("**\n");
                if (!quest.description.isEmpty()) {
                    // 仅显示第一行描述作为简介，避免上下文过长
                    String firstLine = quest.description.split("\n")[0];
                    if (firstLine.length() > 80) {
                        firstLine = firstLine.substring(0, 80) + "...";
                    }
                    sb.append("   ").append(firstLine).append("\n");
                }
                if (!quest.tasks.isEmpty()) {
                    sb.append("   ✅ 需求: ");
                    sb.append(String.join("; ", quest.tasks));
                    sb.append("\n");
                }
                if (!quest.rewards.isEmpty()) {
                    sb.append("   🎁 奖励: ");
                    sb.append(String.join(", ", quest.rewards));
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }

    // === 缓存 ===

    public static void saveCache(QuestData data, Path cacheFile) {
        try {
            Files.createDirectories(cacheFile.getParent());
            String json = GSON.toJson(data);
            Files.writeString(cacheFile, json);
            LOGGER.info("[QuestScanner] 缓存已保存: {}", cacheFile);
        } catch (IOException e) {
            LOGGER.warn("[QuestScanner] 缓存保存失败: {}", e.getMessage());
        }
    }

    public static QuestData loadCache(Path cacheFile) {
        try {
            if (Files.exists(cacheFile)) {
                String json = Files.readString(cacheFile);
                return GSON.fromJson(json, QuestData.class);
            }
        } catch (Exception e) {
            LOGGER.warn("[QuestScanner] 缓存加载失败: {}", e.getMessage());
        }
        return null;
    }
}
