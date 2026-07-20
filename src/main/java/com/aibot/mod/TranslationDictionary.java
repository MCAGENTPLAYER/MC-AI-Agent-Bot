package com.aibot.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class TranslationDictionary {
    private static final Map<String, String> cnToBlock = new HashMap<>();
    private static final Map<String, String> cnToItem = new HashMap<>();
    private static final Map<String, String> blockToCn = new HashMap<>();
    private static final Map<String, String> itemToCn = new HashMap<>();
    private static boolean initialized = false;

    public static synchronized void init() {
        cnToBlock.clear();
        cnToItem.clear();
        blockToCn.clear();
        itemToCn.clear();

        for (Block block : ForgeRegistries.BLOCKS) {
            var key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null) continue;
            String id = key.toString();
            String cn = block.getName().getString();
            if (!cn.isEmpty() && !cn.contains("tile.")) {
                blockToCn.put(id, cn);
                cnToBlock.put(cn.toLowerCase(), id);
                String[] parts = cn.split("\\s+");
                for (String part : parts) {
                    if (part.length() >= 2) {
                        cnToBlock.put(part.toLowerCase(), id);
                    }
                }
            }
        }

        for (Item item : ForgeRegistries.ITEMS) {
            var key = ForgeRegistries.ITEMS.getKey(item);
            if (key == null) continue;
            String id = key.toString();
            String cn = item.getName(new net.minecraft.world.item.ItemStack(item)).getString();
            if (!cn.isEmpty() && !cn.contains("item.")) {
                itemToCn.put(id, cn);
                cnToItem.put(cn.toLowerCase(), id);
                String[] parts = cn.split("\\s+");
                for (String part : parts) {
                    if (part.length() >= 2) {
                        cnToItem.put(part.toLowerCase(), id);
                    }
                }
            }
        }

        AiBotMod.LOGGER.info("[Translation] Loaded {} block translations, {} item translations",
                cnToBlock.size(), cnToItem.size());
        initialized = true;
    }

    public static void reload() {
        AiBotMod.LOGGER.info("[Translation] Reloading dictionary...");
        initialized = false;
        init();
    }

    public static String translateBlock(String cn) {
        if (!initialized) init();
        if (cn == null || cn.isEmpty()) return null;
        cn = cn.toLowerCase().trim();
        if (cn.contains(":")) return cn;

        String cached = cnToBlock.get(cn);
        if (cached != null) return cached;

        return realtimeSearchBlock(cn);
    }

    public static String translateItem(String cn) {
        if (!initialized) init();
        if (cn == null || cn.isEmpty()) return null;
        cn = cn.toLowerCase().trim();
        if (cn.contains(":")) return cn;

        String cached = cnToItem.get(cn);
        if (cached != null) return cached;

        return realtimeSearchItem(cn);
    }

    public static String getBlockCN(String id) {
        if (!initialized) init();
        String cached = blockToCn.get(id);
        if (cached != null) return cached;

        return realtimeGetBlockCN(id);
    }

    public static String getItemCN(String id) {
        if (!initialized) init();
        String cached = itemToCn.get(id);
        if (cached != null) return cached;

        return realtimeGetItemCN(id);
    }

    public static String findMatch(String query) {
        if (!initialized) init();
        if (query == null || query.isEmpty()) return null;
        String lower = query.toLowerCase().trim();
        if (lower.contains(":")) return lower;

        String block = cnToBlock.get(lower);
        if (block != null) return block;

        String item = cnToItem.get(lower);
        if (item != null) return item;

        for (Map.Entry<String, String> e : cnToBlock.entrySet()) {
            if (e.getKey().contains(lower) || lower.contains(e.getKey())) {
                return e.getValue();
            }
        }
        for (Map.Entry<String, String> e : cnToItem.entrySet()) {
            if (e.getKey().contains(lower) || lower.contains(e.getKey())) {
                return e.getValue();
            }
        }

        String rtBlock = realtimeSearchBlock(lower);
        if (rtBlock != null) return rtBlock;
        String rtItem = realtimeSearchItem(lower);
        if (rtItem != null) return rtItem;

        return null;
    }

    public static List<String> suggest(String query) {
        if (!initialized) init();
        if (query == null || query.isEmpty()) return List.of();
        String lower = query.toLowerCase();
        Set<String> results = new LinkedHashSet<>();

        for (Map.Entry<String, String> e : cnToBlock.entrySet()) {
            if (e.getKey().contains(lower)) {
                results.add(e.getKey() + " -> " + e.getValue());
            }
        }
        for (Map.Entry<String, String> e : cnToItem.entrySet()) {
            if (e.getKey().contains(lower)) {
                results.add(e.getKey() + " -> " + e.getValue());
            }
        }

        realtimeSuggest(lower, results);

        return new ArrayList<>(results).subList(0, Math.min(10, results.size()));
    }

    private static String realtimeSearchBlock(String cn) {
        for (Block block : ForgeRegistries.BLOCKS) {
            var key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null) continue;
            String id = key.toString();
            String display = block.getName().getString().toLowerCase();
            if (display.equals(cn) || display.contains(cn)) {
                blockToCn.put(id, block.getName().getString());
                cnToBlock.put(display, id);
                return id;
            }
        }
        return null;
    }

    private static String realtimeSearchItem(String cn) {
        for (Item item : ForgeRegistries.ITEMS) {
            var key = ForgeRegistries.ITEMS.getKey(item);
            if (key == null) continue;
            String id = key.toString();
            String display = item.getName(new net.minecraft.world.item.ItemStack(item)).getString().toLowerCase();
            if (display.equals(cn) || display.contains(cn)) {
                itemToCn.put(id, item.getName(new net.minecraft.world.item.ItemStack(item)).getString());
                cnToItem.put(display, id);
                return id;
            }
        }
        return null;
    }

    private static String realtimeGetBlockCN(String id) {
        for (Block block : ForgeRegistries.BLOCKS) {
            var key = ForgeRegistries.BLOCKS.getKey(block);
            if (key != null && key.toString().equals(id)) {
                String cn = block.getName().getString();
                blockToCn.put(id, cn);
                cnToBlock.put(cn.toLowerCase(), id);
                return cn;
            }
        }
        return null;
    }

    private static String realtimeGetItemCN(String id) {
        for (Item item : ForgeRegistries.ITEMS) {
            var key = ForgeRegistries.ITEMS.getKey(item);
            if (key != null && key.toString().equals(id)) {
                String cn = item.getName(new net.minecraft.world.item.ItemStack(item)).getString();
                itemToCn.put(id, cn);
                cnToItem.put(cn.toLowerCase(), id);
                return cn;
            }
        }
        return null;
    }

    private static void realtimeSuggest(String lower, Set<String> results) {
        for (Block block : ForgeRegistries.BLOCKS) {
            var key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null) continue;
            String display = block.getName().getString().toLowerCase();
            if (display.contains(lower)) {
                results.add(display + " -> " + key.toString());
            }
        }
        for (Item item : ForgeRegistries.ITEMS) {
            var key = ForgeRegistries.ITEMS.getKey(item);
            if (key == null) continue;
            String display = item.getName(new net.minecraft.world.item.ItemStack(item)).getString().toLowerCase();
            if (display.contains(lower)) {
                results.add(display + " -> " + key.toString());
            }
        }
    }

    public static String exportToJson() {
        List<Map<String, String>> blocksList = new ArrayList<>();
        for (Block block : ForgeRegistries.BLOCKS) {
            var key = ForgeRegistries.BLOCKS.getKey(block);
            if (key == null) continue;
            String id = key.toString();
            String cn = block.getName().getString();
            if (!cn.isEmpty() && !cn.contains("tile.")) {
                Map<String, String> entry = new HashMap<>();
                entry.put("id", id);
                entry.put("name", cn);
                blocksList.add(entry);
            }
        }

        List<Map<String, String>> itemsList = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            var key = ForgeRegistries.ITEMS.getKey(item);
            if (key == null) continue;
            String id = key.toString();
            String cn = item.getName(new net.minecraft.world.item.ItemStack(item)).getString();
            if (!cn.isEmpty() && !cn.contains("item.")) {
                Map<String, String> entry = new HashMap<>();
                entry.put("id", id);
                entry.put("name", cn);
                itemsList.add(entry);
            }
        }

        Map<String, Object> root = new HashMap<>();
        root.put("blocks", blocksList);
        root.put("items", itemsList);
        root.put("total_blocks", blocksList.size());
        root.put("total_items", itemsList.size());

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(root);

        try {
            Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
            Path outputDir = gameDir.resolve("ai_bot");
            if (!outputDir.toFile().exists()) {
                outputDir.toFile().mkdirs();
            }
            Path outputFile = outputDir.resolve("all_items.json");
            try (FileWriter writer = new FileWriter(outputFile.toFile())) {
                writer.write(json);
            }
            AiBotMod.LOGGER.info("[Translation] Exported {} blocks and {} items to {}",
                    blocksList.size(), itemsList.size(), outputFile);
            return "已导出 " + blocksList.size() + " 个方块和 " + itemsList.size() + " 个物品到 " + outputFile;
        } catch (IOException e) {
            AiBotMod.LOGGER.error("[Translation] Failed to export: {}", e.getMessage());
            return "导出失败：" + e.getMessage();
        }
    }
}
