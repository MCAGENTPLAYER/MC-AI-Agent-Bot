package com.aibot.mod.mind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 动态性格系统 - 通过游戏经历逐渐成型的性格
 * 不再是预设性格，而是根据玩家互动和 AI 的经历动态生成
 */
public class DynamicPersonality {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // ===== 经历计数器（记录 AI 的人生经历）=====
    
    // 工作经历
    private int totalWorkTasks = 0;           // 总工作任务数
    private int refusedWorkTasks = 0;         // 拒绝的工作任务数
    private int tiredWorkCount = 0;           // 疲劳时强迫工作次数
    private int voluntaryWorkCount = 0;       // 主动工作次数
    
    // 战斗经历
    private int totalCombats = 0;             // 总战斗次数
    private int wonCombats = 0;               // 胜利次数
    private int fleeCount = 0;                // 逃跑次数
    private int deathCount = 0;               // 死亡次数
    private int protectedByPlayerCount = 0;   // 被玩家保护次数
    
    // 社交经历
    private int chatCount = 0;                // 聊天次数
    private int initiatedChatCount = 0;       // 主动聊天次数
    private int ignoredByPlayerCount = 0;     // 被玩家忽视次数
    private int companionshipTime = 0;        // 陪伴时长（秒）
    
    // 情感经历
    private int happyMoments = 0;             // 快乐时刻数
    private int sadMoments = 0;               // 悲伤时刻数
    private int rewardedByPlayerCount = 0;    // 被玩家奖励次数（给食物、给物品）
    private int punishedByPlayerCount = 0;    // 被玩家惩罚次数（打、骂）
    
    // 探索经历
    private int explorationCount = 0;         // 探险次数
    private int discoveredNewPlaces = 0;      // 发现新地点数
    private int dangerousEncounters = 0;      // 危险遭遇次数
    
    // 休息经历
    private int restCount = 0;                // 休息次数
    private int sleepInterruptions = 0;       // 睡眠被打断次数
    
    // ===== 性格特质计算（动态生成）=====
    
    /**
     * 勤奋度 (0-1)
     * 计算逻辑：
     * - 完成的工作任务越多 → 越勤奋
     * - 拒绝的工作任务越多 → 越懒惰
     * - 主动工作次数 → 加成
     * - 疲劳时被强迫工作 → 负面影响（学会偷懒）
     */
    public float getDiligence() {
        if (totalWorkTasks == 0) return 0.5f; // 初始中性
        
        float baseScore = (float) (totalWorkTasks - refusedWorkTasks) / totalWorkTasks;
        
        // 主动工作加成（最多+0.2）
        float voluntaryBonus = Math.min(0.2f, voluntaryWorkCount * 0.02f);
        
        // 疲劳强迫惩罚（最多-0.2，学会"消极怠工"）
        float tirednesssPenalty = Math.min(0.2f, tiredWorkCount * 0.01f);
        
        float result = baseScore + voluntaryBonus - tirednesssPenalty;
        return clamp(result);
    }
    
    /**
     * 勇敢度 (0-1)
     * 计算逻辑：
     * - 战斗胜利 → 增加勇气
     * - 逃跑/死亡 → 减少勇气
     * - 被玩家保护 → 依赖玩家，勇气降低
     * - 探险经历 → 增加勇气
     */
    public float getBravery() {
        if (totalCombats == 0 && explorationCount == 0) return 0.5f;
        
        float combatScore = 0.5f;
        if (totalCombats > 0) {
            combatScore = (float) wonCombats / totalCombats;
            // 逃跑和死亡惩罚
            float cowardicePenalty = (fleeCount + deathCount * 2) * 0.05f;
            combatScore -= cowardicePenalty;
        }
        
        // 探险加成
        float explorationBonus = Math.min(0.2f, explorationCount * 0.02f);
        
        // 被保护惩罚（依赖玩家，自己不勇敢）
        float dependencyPenalty = Math.min(0.15f, protectedByPlayerCount * 0.03f);
        
        float result = combatScore + explorationBonus - dependencyPenalty;
        return clamp(result);
    }
    
    /**
     * 话痨度 (0-1)
     * 计算逻辑：
     * - 聊天次数越多 → 越话痨
     * - 主动聊天占比高 → 更话痨
     * - 被忽视次数多 → 学会沉默
     * - 陪伴时长长 → 喜欢社交
     */
    public float getTalkativeness() {
        if (chatCount == 0) return 0.5f;
        
        // 聊天频率（归一化到0-1）
        float chatFrequency = Math.min(1.0f, chatCount / 100f);
        
        // 主动性加成
        float initiativeBonus = 0;
        if (chatCount > 0) {
            initiativeBonus = ((float) initiatedChatCount / chatCount) * 0.3f;
        }
        
        // 被忽视惩罚（学会闭嘴）
        float ignoredPenalty = Math.min(0.3f, ignoredByPlayerCount * 0.05f);
        
        // 陪伴时长加成
        float companionshipBonus = Math.min(0.2f, companionshipTime / 3600f * 0.1f);
        
        float result = chatFrequency + initiativeBonus - ignoredPenalty + companionshipBonus;
        return clamp(result);
    }
    
    /**
     * 乐观度 (0-1) - 新增特质
     * 根据快乐/悲伤时刻比例
     */
    public float getOptimism() {
        int totalEmotionalMoments = happyMoments + sadMoments;
        if (totalEmotionalMoments == 0) return 0.5f;
        
        float baseScore = (float) happyMoments / totalEmotionalMoments;
        
        // 被奖励增加乐观
        float rewardBonus = Math.min(0.2f, rewardedByPlayerCount * 0.02f);
        
        // 被惩罚减少乐观
        float punishmentPenalty = Math.min(0.2f, punishedByPlayerCount * 0.03f);
        
        return clamp(baseScore + rewardBonus - punishmentPenalty);
    }
    
    /**
     * 独立性 (0-1) - 新增特质
     * 反映 AI 是否依赖玩家
     */
    public float getIndependence() {
        // 主动行为比例
        float autonomyScore = 0;
        if (totalWorkTasks > 0) {
            autonomyScore += (float) voluntaryWorkCount / totalWorkTasks * 0.3f;
        }
        if (chatCount > 0) {
            autonomyScore += (float) initiatedChatCount / chatCount * 0.3f;
        }
        if (explorationCount > 0) {
            autonomyScore += 0.2f;
        }
        
        // 被保护/被奖励次数多 → 依赖玩家
        float dependencyPenalty = (protectedByPlayerCount + rewardedByPlayerCount) * 0.02f;
        
        return clamp(0.5f + autonomyScore - dependencyPenalty);
    }
    
    // ===== 经历记录方法 =====
    
    public void onWorkTaskCompleted(boolean wasVoluntary, boolean wasTired) {
        totalWorkTasks++;
        if (wasVoluntary) voluntaryWorkCount++;
        if (wasTired) tiredWorkCount++;
    }
    
    public void onWorkTaskRefused() {
        refusedWorkTasks++;
    }
    
    public void onCombatWon() {
        totalCombats++;
        wonCombats++;
    }
    
    public void onCombatLost() {
        totalCombats++;
    }
    
    public void onFlee() {
        fleeCount++;
    }
    
    public void onDeath() {
        deathCount++;
    }
    
    public void onProtectedByPlayer() {
        protectedByPlayerCount++;
    }
    
    public void onChat(boolean wasInitiated) {
        chatCount++;
        if (wasInitiated) initiatedChatCount++;
    }
    
    public void onIgnoredByPlayer() {
        ignoredByPlayerCount++;
    }
    
    public void onCompanionshipTick() {
        companionshipTime++;
    }
    
    public void onHappyMoment() {
        happyMoments++;
    }
    
    public void onSadMoment() {
        sadMoments++;
    }
    
    public void onRewardedByPlayer() {
        rewardedByPlayerCount++;
        happyMoments++; // 奖励也算快乐时刻
    }
    
    public void onPunishedByPlayer() {
        punishedByPlayerCount++;
        sadMoments++; // 惩罚算悲伤时刻
    }
    
    public void onExploration() {
        explorationCount++;
    }
    
    public void onDangerousEncounter() {
        dangerousEncounters++;
    }
    
    public void onRest() {
        restCount++;
    }
    
    public void onSleepInterrupted() {
        sleepInterruptions++;
    }
    
    // ===== 性格描述（自然语言）=====
    
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("性格特征（通过 ").append(getTotalExperiences()).append(" 次经历形成）：\n");
        
        float diligence = getDiligence();
        float bravery = getBravery();
        float talkativeness = getTalkativeness();
        float optimism = getOptimism();
        float independence = getIndependence();
        
        // 勤奋度
        if (diligence > 0.7f) sb.append("- 勤奋（经历了 ").append(totalWorkTasks).append(" 次工作）\n");
        else if (diligence < 0.3f) sb.append("- 懒惰（拒绝了 ").append(refusedWorkTasks).append(" 次工作）\n");
        
        // 勇敢度
        if (bravery > 0.7f) sb.append("- 勇敢（战斗胜率 ").append(String.format("%.0f%%", getBattleWinRate() * 100)).append("）\n");
        else if (bravery < 0.3f) sb.append("- 胆小（逃跑了 ").append(fleeCount).append(" 次）\n");
        
        // 话痨度
        if (talkativeness > 0.7f) sb.append("- 话痨（聊天 ").append(chatCount).append(" 次）\n");
        else if (talkativeness < 0.3f) sb.append("- 内向（被忽视 ").append(ignoredByPlayerCount).append(" 次后学会沉默）\n");
        
        // 乐观度
        if (optimism > 0.7f) sb.append("- 乐观（大多数时候都很开心）\n");
        else if (optimism < 0.3f) sb.append("- 悲观（经历了太多悲伤）\n");
        
        // 独立性
        if (independence > 0.7f) sb.append("- 独立（喜欢自己做决定）\n");
        else if (independence < 0.3f) sb.append("- 依赖（习惯了被照顾）\n");
        
        return sb.toString();
    }
    
    /**
     * 性格成长报告（用于 AI 自述）
     */
    public String getGrowthReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("我是怎么变成现在这样的：\n\n");
        
        if (totalWorkTasks > 20) {
            if (getDiligence() > 0.7f) {
                sb.append("- 我完成了很多工作任务，发现自己其实挺喜欢干活的\n");
            } else if (getDiligence() < 0.3f) {
                sb.append("- 虽然被要求干了很多活，但我学会了偷懒...\n");
            }
        }
        
        if (totalCombats > 5) {
            if (getBravery() > 0.7f) {
                sb.append("- 经历了 ").append(totalCombats).append(" 次战斗，我变得更勇敢了\n");
            } else {
                sb.append("- 战斗太可怕了...我还是躲远点吧\n");
            }
        }
        
        if (punishedByPlayerCount > 5) {
            sb.append("- 被打了 ").append(punishedByPlayerCount).append(" 次后，我学会看脸色了...\n");
        }
        
        if (rewardedByPlayerCount > 10) {
            sb.append("- 你经常奖励我，我很喜欢和你在一起！\n");
        }
        
        if (sleepInterruptions > 10) {
            sb.append("- 睡觉老被打断，搞得我脾气都变差了...\n");
        }
        
        return sb.toString();
    }
    
    // ===== 辅助方法 =====
    
    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
    
    public int getTotalExperiences() {
        return totalWorkTasks + totalCombats + chatCount + explorationCount;
    }
    
    private float getBattleWinRate() {
        return totalCombats == 0 ? 0 : (float) wonCombats / totalCombats;
    }
    
    // ===== 持久化 =====
    
    public void save(Path saveDir) {
        File file = saveDir.resolve("dynamic_personality.json").toFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            com.aibot.mod.AiBotMod.LOGGER.error("[DynamicPersonality] Failed to save: {}", e.getMessage());
        }
    }
    
    public static DynamicPersonality load(Path saveDir) {
        File file = saveDir.resolve("dynamic_personality.json").toFile();
        if (!file.exists()) return new DynamicPersonality();
        try (FileReader reader = new FileReader(file)) {
            return GSON.fromJson(reader, DynamicPersonality.class);
        } catch (IOException e) {
            com.aibot.mod.AiBotMod.LOGGER.error("[DynamicPersonality] Failed to load: {}", e.getMessage());
            return new DynamicPersonality();
        }
    }
}
