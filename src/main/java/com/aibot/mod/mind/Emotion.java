package com.aibot.mod.mind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class Emotion {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private int hunger = 50;
    private int tiredness = 30;
    private int boredom = 40;
    private int happiness = 50;
    private int fear = 20;
    private int stress = 30;

    private int hungerDecayRate = 1;
    private int tirednessDecayRate = 1;
    private int boredomDecayRate = 1;
    private int happinessDecayRate = 2;
    private int fearDecayRate = 3;
    private int stressDecayRate = 2;

    // 上一次检查时的值（用于计算变化量）
    private int lastCheckHunger = 50;
    private int lastCheckTiredness = 30;
    private int lastCheckBoredom = 40;
    private int lastCheckHappiness = 50;
    private int lastCheckFear = 20;
    private int lastCheckStress = 30;

    public int getHunger() { return clamp(hunger); }
    public int getTiredness() { return clamp(tiredness); }
    public int getBoredom() { return clamp(boredom); }
    public int getHappiness() { return clamp(happiness); }
    public int getFear() { return clamp(fear); }
    public int getStress() { return clamp(stress); }

    public void setHunger(int value) { hunger = clamp(value); }
    public void setTiredness(int value) { tiredness = clamp(value); }
    public void setBoredom(int value) { boredom = clamp(value); }
    public void setHappiness(int value) { happiness = clamp(value); }
    public void setFear(int value) { fear = clamp(value); }
    public void setStress(int value) { stress = clamp(value); }

    public void addHunger(int delta) { hunger = clamp(hunger + delta); }
    public void addTiredness(int delta) { tiredness = clamp(tiredness + delta); }
    public void addBoredom(int delta) { boredom = clamp(boredom + delta); }
    public void addHappiness(int delta) { happiness = clamp(happiness + delta); }
    public void addFear(int delta) { fear = clamp(fear + delta); }
    public void addStress(int delta) { stress = clamp(stress + delta); }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public void tick() {
        hunger = clamp(hunger - hungerDecayRate);
        tiredness = clamp(tiredness + tirednessDecayRate);
        boredom = clamp(boredom + boredomDecayRate);
        happiness = clamp(happiness - happinessDecayRate);
        fear = clamp(fear - fearDecayRate);
        stress = clamp(stress - stressDecayRate);
    }

    /**
     * 格式化情绪描述给 AI 使用，含自然语言标签。
     */
    public String formatForAI() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前情绪：").append("\n");
        sb.append("  - 饥饿：").append(labelHunger()).append("（").append(hunger).append("/100）").append("\n");
        sb.append("  - 疲劳：").append(labelTiredness()).append("（").append(tiredness).append("/100）").append("\n");
        sb.append("  - 快乐：").append(labelHappiness()).append("（").append(happiness).append("/100）").append("\n");
        sb.append("  - 无聊：").append(labelBoredom()).append("（").append(boredom).append("/100）").append("\n");
        sb.append("  - 恐惧：").append(labelFear()).append("（").append(fear).append("/100）").append("\n");
        sb.append("  - 压力：").append(labelStress()).append("（").append(stress).append("/100）");
        return sb.toString();
    }

    /**
     * 返回一个单词的情绪基调，用于 AI prompt。
     */
    public String getEmotionalTone() {
        if (fear > 60) return "scared";
        if (hunger < 20) return "starving";
        if (tiredness > 70) return "exhausted";
        if (happiness < 20) return "sad";
        if (boredom > 70) return "bored";
        if (happiness > 70 && tiredness < 30) return "happy";
        if (hunger < 40) return "hungry";
        if (tiredness > 50) return "tired";
        if (stress > 60) return "stressed";
        return "neutral";
    }

    private String labelHunger() {
        if (hunger < 10) return "极度饥饿";
        if (hunger < 25) return "非常饿";
        if (hunger < 45) return "有点饿";
        if (hunger < 65) return "正常";
        return "饱了";
    }

    private String labelTiredness() {
        if (tiredness > 80) return "极度疲劳";
        if (tiredness > 60) return "非常累";
        if (tiredness > 40) return "有点累";
        if (tiredness > 20) return "还行";
        return "精力充沛";
    }

    private String labelHappiness() {
        if (happiness < 15) return "非常难过";
        if (happiness < 30) return "不开心";
        if (happiness < 50) return "一般";
        if (happiness < 75) return "开心";
        return "非常开心";
    }

    private String labelBoredom() {
        if (boredom > 80) return "极度无聊";
        if (boredom > 60) return "很无聊";
        if (boredom > 40) return "有点无聊";
        if (boredom > 20) return "还行";
        return "很有趣";
    }

    private String labelFear() {
        if (fear > 80) return "极度恐惧";
        if (fear > 60) return "很害怕";
        if (fear > 40) return "有点不安";
        if (fear > 20) return "稍微紧张";
        return "安心";
    }

    private String labelStress() {
        if (stress > 80) return "压力巨大";
        if (stress > 60) return "压力很大";
        if (stress > 40) return "有点压力";
        if (stress > 20) return "还行";
        return "轻松";
    }

    /**
     * 检测情绪变化是否超过阈值。
     * 一级（变化≥40-50）：返回描述文本，可用于触发 AI 主动说话。
     * 二级（变化≥30）：返回 null，仅标记为"有变化但不触发说话"。
     * 无变化：返回 null。
     */
    public String checkSignificantChange() {
        int dh = Math.abs(hunger - lastCheckHunger);
        int dt = Math.abs(tiredness - lastCheckTiredness);
        int db = Math.abs(boredom - lastCheckBoredom);
        int dhap = Math.abs(happiness - lastCheckHappiness);
        int df = Math.abs(fear - lastCheckFear);
        int ds = Math.abs(stress - lastCheckStress);

        // 一级触发：变化 ≥ 40-50
        if (dh >= 50) return hunger < lastCheckHunger ? "饿到受不了了" : "吃得很饱";
        if (dt >= 50) return tiredness > lastCheckTiredness ? "累到不行了" : "休息好了";
        if (dhap >= 40) return happiness < lastCheckHappiness ? "特别不开心" : "特别开心";
        if (df >= 40) return fear > lastCheckFear ? "被吓到了" : "感觉安全了";
        if (db >= 50) return "无聊得要命";
        if (ds >= 50) return stress > lastCheckStress ? "压力好大" : "感觉轻松多了";

        return null;
    }

    /** 同步当前值为检查基准值。每次检查变化后应调用此方法。 */
    public void syncCheckBaseline() {
        lastCheckHunger = hunger;
        lastCheckTiredness = tiredness;
        lastCheckBoredom = boredom;
        lastCheckHappiness = happiness;
        lastCheckFear = fear;
        lastCheckStress = stress;
    }

    public void save(Path saveDir) {
        File file = saveDir.resolve("emotion.json").toFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            com.aibot.mod.AiBotMod.LOGGER.error("[Emotion] Failed to save: {}", e.getMessage());
        }
    }

    public static Emotion load(Path saveDir) {
        File file = saveDir.resolve("emotion.json").toFile();
        if (!file.exists()) return new Emotion();
        try (FileReader reader = new FileReader(file)) {
            return GSON.fromJson(reader, Emotion.class);
        } catch (IOException e) {
            com.aibot.mod.AiBotMod.LOGGER.error("[Emotion] Failed to load: {}", e.getMessage());
            return new Emotion();
        }
    }
}
