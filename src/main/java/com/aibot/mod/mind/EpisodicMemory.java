package com.aibot.mod.mind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class EpisodicMemory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_MEMORIES = 200;
    /** 每 200 tick（≈10 秒）衰减 1 点强度，原为每 tick 1 点（5 秒忘光） */
    private static final int DECAY_INTERVAL_TICKS = 200;

    private List<MemoryEntry> memories = new ArrayList<>();
    private int decayCounter = 0;

    // 去重缓存：eventType -> 上次记录时间戳（毫秒）
    private final Map<String, Long> dedupCache = new HashMap<>();

    public void addMemory(String type, String content, int importance) {
        addMemory(type, content, importance, 0, 0, 0, "");
    }

    public void addMemory(String type, String content) {
        addMemory(type, content, 50);
    }

    public void addImportantMemory(String type, String content) {
        addMemory(type, content, 80);
    }

    public void addMemory(String type, String content, int importance, int posX, int posY, int posZ, String dimension) {
        MemoryEntry entry = new MemoryEntry();
        entry.id = System.currentTimeMillis();
        entry.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        entry.type = type;
        entry.content = content;
        entry.importance = importance;
        entry.strength = 100;
        entry.posX = posX;
        entry.posY = posY;
        entry.posZ = posZ;
        entry.dimension = dimension;

        memories.add(entry);
        while (memories.size() > MAX_MEMORIES) {
            memories.remove(0);
        }
    }

    /**
     * 自动记录事件，10 分钟内同类事件不重复记录。
     * 用于动作系统在完成任务后自动写入记忆。
     */
    public void autoRecord(String eventType, String content, int posX, int posY, int posZ, String dimension) {
        long now = System.currentTimeMillis();
        Long lastRecord = dedupCache.get(eventType);
        if (lastRecord != null && now - lastRecord < 600000) { // 10 分钟
            return;
        }
        dedupCache.put(eventType, now);
        addMemory(eventType, content, 50, posX, posY, posZ, dimension);
    }

    public void autoRecord(String eventType, String content) {
        autoRecord(eventType, content, 0, 0, 0, "");
    }

    /**
     * 记录重要事件（importance=90，不衰减）。
     */
    public void addImportantMemory(String type, String content, int posX, int posY, int posZ, String dimension) {
        addMemory(type, content, 90, posX, posY, posZ, dimension);
    }

    public void tick() {
        decayCounter++;
        if (decayCounter < DECAY_INTERVAL_TICKS) return;
        decayCounter = 0;

        for (MemoryEntry entry : memories) {
            // 高重要性（>=80）不衰减
            if (entry.importance >= 80) continue;
            entry.strength = Math.max(0, entry.strength - 1);
        }
        memories.removeIf(m -> m.strength <= 0);
    }

    public List<MemoryEntry> getRecentMemories(int count) {
        return memories.stream()
                .sorted((a, b) -> Long.compare(b.id, a.id))
                .limit(count)
                .collect(Collectors.toList());
    }

    public List<MemoryEntry> getRelatedMemories(String keyword) {
        return memories.stream()
                .filter(m -> m.content.toLowerCase().contains(keyword.toLowerCase()))
                .sorted((a, b) -> Integer.compare(b.strength, a.strength))
                .limit(10)
                .collect(Collectors.toList());
    }

    public List<MemoryEntry> getStrongestMemories(int count) {
        return memories.stream()
                .filter(m -> m.strength > 50)
                .sorted((a, b) -> Integer.compare(b.strength, a.strength))
                .limit(count)
                .collect(Collectors.toList());
    }

    /**
     * 格式化记忆摘要给 AI 使用，含坐标和重要性标记。
     */
    public String formatForAI() {
        if (memories.isEmpty()) return "（暂无特别记忆）";

        StringBuilder sb = new StringBuilder();
        sb.append("近期经历：\n");

        var recent = getRecentMemories(10);
        for (MemoryEntry m : recent) {
            sb.append("  - ").append(m.content);
            if (m.importance >= 80) sb.append("（非常重要）");
            else if (m.strength < 50) sb.append("（记忆模糊）");
            if (m.posX != 0 || m.posY != 0 || m.posZ != 0) {
                sb.append(" 在 [").append(m.posX).append(", ").append(m.posY).append(", ").append(m.posZ).append("]");
                if (!m.dimension.isEmpty()) sb.append(" (").append(m.dimension).append(")");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /** 保留原方法名兼容性，内部调用 formatForAI。 */
    public String getMemorySummary() {
        return formatForAI();
    }

    public void save(Path saveDir) {
        File file = saveDir.resolve("memories.json").toFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(memories, writer);
        } catch (IOException e) {
            com.aibot.mod.AiBotMod.LOGGER.error("[EpisodicMemory] Failed to save: {}", e.getMessage());
        }
    }

    public static EpisodicMemory load(Path saveDir) {
        EpisodicMemory em = new EpisodicMemory();
        File file = saveDir.resolve("memories.json").toFile();
        if (!file.exists()) return em;
        try (FileReader reader = new FileReader(file)) {
            MemoryEntry[] arr = GSON.fromJson(reader, MemoryEntry[].class);
            if (arr != null) {
                em.memories = new ArrayList<>(List.of(arr));
            }
        } catch (IOException e) {
            com.aibot.mod.AiBotMod.LOGGER.error("[EpisodicMemory] Failed to load: {}", e.getMessage());
        }
        return em;
    }

    public static class MemoryEntry {
        public long id;
        public String timestamp;
        public String type;
        public String content;
        public int importance;
        public int strength;
        public int posX;
        public int posY;
        public int posZ;
        public String dimension = "";
    }
}
