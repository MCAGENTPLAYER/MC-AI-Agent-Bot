package com.aibot.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;

    // === AI 接口 ===
    private static String apiKey = "";
    private static String apiUrl = "https://api.deepseek.com";
    private static String model = "deepseek-v4-flash";
    private static String botName = "Bot";
    private static int maxHistory = 20;
    private static boolean debug = false;
    private static String serverUrl = "ws://127.0.0.1:8080/ws";
    private static String aiMode = "local";

    // === Bot 行为设置 ===
    private static boolean homeMode = false;
    private static int homeX = 0;
    private static int homeY = 64;
    private static int homeZ = 0;
    private static int homeRadius = 48;
    private static boolean autoStore = true;

    // === 光环设置 ===
    private static float haloAngle = 30.0F;
    private static float haloOffsetZ = -0.3F;
    private static float haloSize = 0.35F;
    private static float haloHeight = 0.7F;
    private static boolean haloGlow = false;
    private static boolean haloEnabled = true;

    // === 性格与人性化设置 ===
    private static String personalityPreset = "balanced";
    private static int humanLikeLevel = 70;
    private static int diligence = 50;
    private static int bravery = 50;
    private static int talkativeness = 50;

    // === 皮肤 ===
    private static int skin = 1;

    public static void reload() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        File configDir = gameDir.resolve("ai_bot").toFile();
        configFile = configDir.toPath().resolve("config.json").toFile();

        if (!configFile.exists()) {
            createDefaultConfig(configDir, configFile);
            return;
        }

        try (FileReader reader = new FileReader(configFile)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null) throw new IOException("Empty config");

            apiKey = getString(json, "api_key", "");
            apiUrl = getString(json, "api_url", "https://api.deepseek.com");
            model = getString(json, "model", "deepseek-v4-flash");
            botName = getString(json, "bot_name", "Bot");
            maxHistory = getInt(json, "max_history", 20);
            debug = getBool(json, "debug", false);
            serverUrl = getString(json, "server_url", "ws://127.0.0.1:8080/ws");
            aiMode = getString(json, "ai_mode", "local");

            // Bot 行为设置
            homeMode = getBool(json, "home_mode", false);
            homeX = getInt(json, "home_x", 0);
            homeY = getInt(json, "home_y", 64);
            homeZ = getInt(json, "home_z", 0);
            homeRadius = getInt(json, "home_radius", 48);
            autoStore = getBool(json, "auto_store", true);

            // 光环设置
            haloAngle = getFloat(json, "halo_angle", 30.0F);
            haloOffsetZ = getFloat(json, "halo_offset_z", -0.3F);
            haloSize = getFloat(json, "halo_size", 0.35F);
            haloHeight = getFloat(json, "halo_height", 0.7F);
            haloGlow = getBool(json, "halo_glow", false);
            haloEnabled = getBool(json, "halo_enabled", true);

            // 皮肤
            skin = getInt(json, "skin", 1);

            // 性格与人性化设置
            personalityPreset = getString(json, "personality_preset", "balanced");
            humanLikeLevel = getInt(json, "human_like_level", 70);
            diligence = getInt(json, "diligence", 50);
            bravery = getInt(json, "bravery", 50);
            talkativeness = getInt(json, "talkativeness", 50);

            AiBotMod.LOGGER.info("[AI Bot] Config loaded. Bot name: {}", botName);
        } catch (IOException e) {
            AiBotMod.LOGGER.error("[AI Bot] Failed to load config: {}", e.getMessage());
        }
    }

    private static void createDefaultConfig(File configDir, File configFile) {
        configDir.mkdirs();
        saveSettings();
        AiBotMod.LOGGER.info("[AI Bot] Default config created at: {}", configFile.getAbsolutePath());
    }

    /** 将当前所有设置保存到配置文件 */
    public static void saveSettings() {
        if (configFile == null) return;
        JsonObject json = new JsonObject();
        json.addProperty("api_key", apiKey);
        json.addProperty("api_url", apiUrl);
        json.addProperty("model", model);
        json.addProperty("bot_name", botName);
        json.addProperty("max_history", maxHistory);
        json.addProperty("debug", debug);
        json.addProperty("server_url", serverUrl);
        json.addProperty("ai_mode", aiMode);

        json.addProperty("home_mode", homeMode);
        json.addProperty("home_x", homeX);
        json.addProperty("home_y", homeY);
        json.addProperty("home_z", homeZ);
        json.addProperty("home_radius", homeRadius);
        json.addProperty("auto_store", autoStore);

        json.addProperty("halo_angle", haloAngle);
        json.addProperty("halo_offset_z", haloOffsetZ);
        json.addProperty("halo_size", haloSize);
        json.addProperty("halo_height", haloHeight);
        json.addProperty("halo_glow", haloGlow);
        json.addProperty("halo_enabled", haloEnabled);

        json.addProperty("skin", skin);

            // 性格与人性化设置
            json.addProperty("personality_preset", personalityPreset);
            json.addProperty("human_like_level", humanLikeLevel);
            json.addProperty("diligence", diligence);
            json.addProperty("bravery", bravery);
            json.addProperty("talkativeness", talkativeness);

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(json, writer);
        } catch (IOException e) {
            AiBotMod.LOGGER.error("[AI Bot] Failed to save config: {}", e.getMessage());
        }
    }

    // === 辅助方法 ===

    private static String getString(JsonObject json, String key, String def) {
        return json.has(key) ? json.get(key).getAsString() : def;
    }

    private static int getInt(JsonObject json, String key, int def) {
        return json.has(key) ? json.get(key).getAsInt() : def;
    }

    private static boolean getBool(JsonObject json, String key, boolean def) {
        return json.has(key) ? json.get(key).getAsBoolean() : def;
    }

    private static float getFloat(JsonObject json, String key, float def) {
        return json.has(key) ? json.get(key).getAsFloat() : def;
    }

    // === AI 接口 Getter ===

    public static String getApiKey() { return apiKey; }
    public static String getApiUrl() { return apiUrl; }
    public static String getModel() { return model; }
    public static String getBotName() { return botName; }
    public static int getMaxHistory() { return maxHistory; }
    public static boolean isDebug() { return debug; }

    // === Bot 行为 Getter/Setter ===

    public static boolean isHomeMode() { return homeMode; }
    public static void setHomeMode(boolean v) { homeMode = v; saveSettings(); }

    public static int getHomeX() { return homeX; }
    public static int getHomeY() { return homeY; }
    public static int getHomeZ() { return homeZ; }
    public static void setHomePos(int x, int y, int z) { homeX = x; homeY = y; homeZ = z; saveSettings(); }

    public static int getHomeRadius() { return homeRadius; }
    public static void setHomeRadius(int v) { homeRadius = Math.max(16, Math.min(128, v)); saveSettings(); }

    public static boolean isAutoStore() { return autoStore; }
    public static void setAutoStore(boolean v) { autoStore = v; saveSettings(); }

    // === AI 接口 Setter ===
    public static void setApiUrl(String v) { apiUrl = v; saveSettings(); }
    public static void setModel(String v) { model = v; saveSettings(); }
    public static String getServerUrl() { return serverUrl; }
    public static void setServerUrl(String v) { serverUrl = v; saveSettings(); }
    public static String getAiMode() { return aiMode; }
    public static void setAiMode(String v) { aiMode = v; saveSettings(); }

    // === 光环 Getter/Setter ===
    public static float getHaloAngle() { return haloAngle; }
    public static void setHaloAngle(float v) { haloAngle = Math.max(0, Math.min(90, v)); saveSettings(); }
    public static float getHaloOffsetZ() { return haloOffsetZ; }
    public static void setHaloOffsetZ(float v) { haloOffsetZ = Math.max(-1.0F, Math.min(1.0F, v)); saveSettings(); }
    public static float getHaloSize() { return haloSize; }
    public static void setHaloSize(float v) { haloSize = Math.max(0.1F, Math.min(1.0F, v)); saveSettings(); }
    public static float getHaloHeight() { return haloHeight; }
    public static void setHaloHeight(float v) { haloHeight = Math.max(-0.5F, Math.min(2.0F, v)); saveSettings(); }
    public static boolean isHaloGlow() { return haloGlow; }
    public static void setHaloGlow(boolean v) { haloGlow = v; saveSettings(); }
    public static boolean isHaloEnabled() { return haloEnabled; }
    public static void setHaloEnabled(boolean v) { haloEnabled = v; saveSettings(); }

    // === 皮肤 ===
    public static int getSkin() { return skin; }
    public static void setSkin(int v) { skin = (v == 1 || v == 2) ? v : 1; saveSettings(); }

    // === 性格与人性化设置 ===
    public static String getPersonalityPreset() { return personalityPreset; }
    public static void setPersonalityPreset(String v) { 
        personalityPreset = v; 
        applyPreset(v);
        saveSettings(); 
    }

    public static int getHumanLikeLevel() { return humanLikeLevel; }
    public static void setHumanLikeLevel(int v) { 
        humanLikeLevel = Math.max(0, Math.min(100, v)); 
        saveSettings(); 
    }

    public static int getDiligence() { return diligence; }
    public static void setDiligence(int v) { 
        diligence = Math.max(0, Math.min(100, v)); 
        saveSettings(); 
    }

    public static int getBravery() { return bravery; }
    public static void setBravery(int v) { 
        bravery = Math.max(0, Math.min(100, v)); 
        saveSettings(); 
    }

    public static int getTalkativeness() { return talkativeness; }
    public static void setTalkativeness(int v) { 
        talkativeness = Math.max(0, Math.min(100, v)); 
        saveSettings(); 
    }

    private static void applyPreset(String preset) {
        switch (preset.toLowerCase()) {
            case "lazy":
                diligence = 20;
                bravery = 30;
                talkativeness = 60;
                break;
            case "workaholic":
                diligence = 90;
                bravery = 70;
                talkativeness = 30;
                break;
            case "adventurer":
                diligence = 60;
                bravery = 85;
                talkativeness = 70;
                break;
            case "shy":
                diligence = 50;
                bravery = 25;
                talkativeness = 20;
                break;
            case "chatty":
                diligence = 40;
                bravery = 40;
                talkativeness = 90;
                break;
            case "balanced":
            default:
                diligence = 50;
                bravery = 50;
                talkativeness = 50;
                break;
        }
    }
}
