package com.aibot.mod.script;

import com.aibot.mod.AiBotMod;
import com.aibot.mod.Config;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Simple script engine that reads JSON action definitions from ai_bot/scripts/.
 * 
 * Users can define new bot behaviors by creating .json files in the scripts folder.
 * Each file defines an action with a name, description, and sequence of steps.
 * 
 * Example farm.json:
 * {
 *   "name": "!myfarm",
 *   "description": "Custom farming action",
 *   "steps": [
 *     { "type": "goto", "x": 100, "y": 64, "z": 200 },
 *     { "type": "rightclick" },
 *     { "type": "say", "message": "Done!" }
 *   ]
 * }
 * 
 * Future: Will also support .js scripts via Nashorn/GraalVM for full scripting.
 */
public class ScriptEngine {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, ScriptAction> scripts = new HashMap<>();

    public static void reload() {
        scripts.clear();
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        File scriptsDir = gameDir.resolve("ai_bot").resolve("scripts").toFile();

        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs();
            createDefaultScripts(scriptsDir);
            return;
        }

        File[] files = scriptsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                ScriptAction action = GSON.fromJson(reader, ScriptAction.class);
                if (action != null && action.name != null && !action.name.isEmpty()) {
                    scripts.put(action.name.toLowerCase(), action);
                    AiBotMod.LOGGER.info("[Script] Loaded script: {} ({})", action.name, file.getName());
                }
            } catch (IOException e) {
                AiBotMod.LOGGER.error("[Script] Failed to load {}: {}", file.getName(), e.getMessage());
            }
        }
    }

    public static ScriptAction get(String name) {
        return scripts.get(name.toLowerCase());
    }

    public static Map<String, ScriptAction> getAll() {
        return Collections.unmodifiableMap(scripts);
    }

    private static void createDefaultScripts(File scriptsDir) {
        // Copy default scripts from mod resources to user's scripts folder
        AiBotMod.LOGGER.info("[Script] Scripts directory created: {}", scriptsDir.getAbsolutePath());
    }

    public record ScriptAction(
        String name,
        String description,
        List<ScriptStep> steps
    ) {}

    public record ScriptStep(
        String type,
        String message,
        Double x, Double y, Double z,
        String blockName,
        Integer count,
        Integer range
    ) {}
}
