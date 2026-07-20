package com.aibot.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * 配方扫描器 - 从 Minecraft RecipeManager 读取模组配方
 * 支持森罗物语(kaleidoscope_cookery)等模组的烹饪配方
 */
public class RecipeScanner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 已扫描的模组配方（持久化缓存） */
    public static class ScannedRecipe {
        public String id;           // 配方注册ID，如 kaleidoscope_cookery:pot/xxx
        public String type;         // 配方类型，如 pot, stockpot, chopping_board
        public List<String> ingredients = new ArrayList<>();  // 输入材料ID列表
        public String output;       // 输出物品ID
        public int outputCount = 1;
        public String carrier;      // 容器物品ID（如 bowl）
        public int extraInt;        // 附加整数（翻炒次数/切割次数等）
        public int cookTime;        // 烹饪时间(tick)

        // 中文显示名（运行时由 scanAll 填充，不序列化到缓存）
        public transient String cnOutput = "";
        public transient List<String> cnIngredients = new ArrayList<>();
        public transient String cnCarrier = "";
        public transient String shortName = "";  // 配方短名，如"青椒炒肉盖饭"

        public String cnDisplay() {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(type).append("] ");
            sb.append(String.join(" + ", cnIngredients.isEmpty() ? ingredients : cnIngredients));
            sb.append(" → ").append(cnOutput.isEmpty() ? output : cnOutput);
            if (outputCount > 1) sb.append("x").append(outputCount);
            if (carrier != null && !carrier.isEmpty()) {
                sb.append("（盛具:").append(cnCarrier.isEmpty() ? carrier : cnCarrier).append("）");
            }
            if (extraInt > 0) sb.append(" 翻炒").append(extraInt).append("次");
            if (!shortName.isEmpty()) sb.append(" 【").append(shortName).append("】");
            return sb.toString();
        }

        public String toDisplayString() { return cnDisplay(); }

        /** 可用于模糊匹配的搜索文本（中文名+英文ID） */
        public String searchText() {
            return (shortName + " " + cnOutput + " " + id + " " + type).toLowerCase();
        }
    }

    /**
     * 扫描所有已加载的模组配方（客户端调用，需要已进入世界）
     */
    public static List<ScannedRecipe> scanAll() {
        List<ScannedRecipe> result = new ArrayList<>();
        var level = Minecraft.getInstance().level;
        if (level == null) {
            AiBotMod.LOGGER.warn("[RecipeScanner] 世界未加载，无法扫描配方");
            return result;
        }
        RecipeManager rm = level.getRecipeManager();

        for (Recipe<?> recipe : rm.getRecipes()) {
            ResourceLocation id = recipe.getId();
            String idStr = id.toString();

            // 只处理 kaleidoscope_cookery 配方
            if (!idStr.startsWith("kaleidoscope_cookery:")) continue;

            ScannedRecipe sr = new ScannedRecipe();
            sr.id = idStr;

            // 从 ID 路径提取类型：kaleidoscope_cookery:pot/xxx → pot
            String path = id.getPath();
            int slash = path.indexOf('/');
            if (slash > 0) {
                sr.type = path.substring(0, slash);
            } else {
                sr.type = path;
            }

            // 读取原料列表
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            for (Ingredient ing : ingredients) {
                ItemStack[] stacks = ing.getItems();
                if (stacks.length > 0 && !stacks[0].isEmpty()) { // 跳过空槽/AIR
                    String itemId = stacks[0].getItem().builtInRegistryHolder().key().location().toString();
                    if (!itemId.equals("minecraft:air")) {
                        sr.ingredients.add(itemId);
                    }
                }
            }

            // 读取输出物品
            ItemStack output = recipe.getResultItem(level.registryAccess());
            if (!output.isEmpty()) {
                sr.output = output.getItem().builtInRegistryHolder().key().location().toString();
                sr.outputCount = output.getCount();
            }

            // 尝试读取额外参数（cooking_time 等）
            // 用反射读取 pot 配方的自定义字段
            try {
                Class<?> clz = recipe.getClass();
                // cooking_time / stir_count
                for (String field : new String[]{"cookingTime", "cooking_time", "stirCount", "stir_count", "cutCount", "cut_count"}) {
                    try {
                        var f = clz.getDeclaredField(field);
                        f.setAccessible(true);
                        Object val = f.get(recipe);
                        if (val instanceof Integer) sr.extraInt = (Integer) val;
                        else if (val instanceof Number) sr.extraInt = ((Number) val).intValue();
                        if (sr.extraInt > 0) break;
                    } catch (NoSuchFieldException ignored) {}
                }
                // time
                for (String field : new String[]{"time", "cookTime"}) {
                    try {
                        var f = clz.getDeclaredField(field);
                        f.setAccessible(true);
                        Object val = f.get(recipe);
                        if (val instanceof Integer) sr.cookTime = (Integer) val;
                        else if (val instanceof Number) sr.cookTime = ((Number) val).intValue();
                        if (sr.cookTime > 0) break;
                    } catch (NoSuchFieldException ignored) {}
                }
                // carrier / container
                for (String field : new String[]{"carrier", "container"}) {
                    try {
                        var f = clz.getDeclaredField(field);
                        f.setAccessible(true);
                        Object val = f.get(recipe);
                        if (val instanceof net.minecraft.world.item.crafting.Ingredient) {
                            ItemStack[] stacks = ((net.minecraft.world.item.crafting.Ingredient) val).getItems();
                            if (stacks.length > 0) {
                                sr.carrier = stacks[0].getItem().builtInRegistryHolder().key().location().toString();
                            }
                        }
                        if (sr.carrier != null) break;
                    } catch (NoSuchFieldException ignored) {}
                }
            } catch (Exception e) {
                // 反射失败，跳过额外参数
            }

            // 填充中文名
            try {
                sr.cnOutput = getLocalName(sr.output);
                sr.cnIngredients = new ArrayList<>();
                for (String ingId : sr.ingredients) {
                    sr.cnIngredients.add(getLocalName(ingId));
                }
                if (sr.carrier != null && !sr.carrier.isEmpty()) {
                    sr.cnCarrier = getLocalName(sr.carrier);
                }
                // 从 ID 末尾提取短名（如 stir_fried_pork_with_peppers_rice_bowl）
                String[] pathParts = sr.id.split("/");
                if (pathParts.length >= 2) {
                    sr.shortName = pathParts[pathParts.length - 1];
                }
            } catch (Exception e) {
                // 中文名获取失败，用英文 ID
            }

            result.add(sr);
        }

        AiBotMod.LOGGER.info("[RecipeScanner] 扫描到 {} 个 kaleidoscope_cookery 配方", result.size());
        return result;
    }

    /**
     * 扫描并按类型分组
     */
    public static Map<String, List<ScannedRecipe>> scanGrouped() {
        Map<String, List<ScannedRecipe>> grouped = new LinkedHashMap<>();
        for (ScannedRecipe sr : scanAll()) {
            grouped.computeIfAbsent(sr.type, k -> new ArrayList<>()).add(sr);
        }
        return grouped;
    }

    /**
     * 缓存扫描结果到文件
     */
    public static void saveCache(List<ScannedRecipe> recipes, Path cacheFile) {
        try {
            var json = GSON.toJsonTree(recipes);
            try (FileWriter fw = new FileWriter(cacheFile.toFile())) {
                GSON.toJson(json, fw);
            }
            AiBotMod.LOGGER.info("[RecipeScanner] 缓存 {} 个配方到 {}", recipes.size(), cacheFile);
        } catch (IOException e) {
            AiBotMod.LOGGER.warn("[RecipeScanner] 缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 从缓存加载
     */
    public static List<ScannedRecipe> loadCache(Path cacheFile) {
        try (FileReader fr = new FileReader(cacheFile.toFile())) {
            ScannedRecipe[] arr = GSON.fromJson(fr, ScannedRecipe[].class);
            return new ArrayList<>(List.of(arr));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * 将扫描结果转成知识库可用的常识文本
     */
    public static String toKnowledgeText(List<ScannedRecipe> recipes) {
        if (recipes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Map<String, List<ScannedRecipe>> grouped = new LinkedHashMap<>();
        for (ScannedRecipe sr : recipes) {
            grouped.computeIfAbsent(sr.type, k -> new ArrayList<>()).add(sr);
        }
        for (var entry : grouped.entrySet()) {
            sb.append("=== ").append(entry.getKey()).append(" 配方 ===\n");
            for (ScannedRecipe sr : entry.getValue()) {
                sb.append(sr.cnDisplay()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 获取物品的中文显示名 */
    private static String getLocalName(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "空气";
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        if (rl == null) return itemId;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        if (item == null) return itemId;
        try {
            return Component.translatable(item.getDescriptionId()).getString();
        } catch (Exception e) {
            return itemId;
        }
    }
}
