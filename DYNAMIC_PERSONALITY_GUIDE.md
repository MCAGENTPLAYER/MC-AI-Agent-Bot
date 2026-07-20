# 动态性格系统 - 集成指南

## 🧠 核心理念

**不再预设性格，而是通过游戏经历逐渐"养成"AI 的性格**

- 每个 AI 都是"白纸"，从中性性格（0.5）开始
- 玩家的每个行为都会影响 AI 的性格成长
- 同样的 AI，不同玩家玩出来的性格完全不同

---

## 📊 性格特质计算逻辑

### 1. 勤奋度 (Diligence)

**影响因素**：
- ✅ 完成工作任务 → 提升勤奋度
- ❌ 拒绝工作任务 → 降低勤奋度（变懒）
- ✅ 主动工作 → 额外加成
- ❌ 疲劳时被强迫工作 → 降低勤奋度（学会"消极怠工"）

**成长路径示例**：
```
初始状态：diligence = 0.5（中性）

玩家经常强迫疲劳的 AI 工作：
  tiredWorkCount = 20
  → diligence = 0.3（变懒，学会偷懒）
  → AI 更容易拒绝工作

玩家从不强迫，AI 自己主动工作：
  voluntaryWorkCount = 30
  → diligence = 0.8（变勤奋）
  → AI 更愿意接受工作
```

---

### 2. 勇敢度 (Bravery)

**影响因素**：
- ✅ 战斗胜利 → 提升勇敢度
- ❌ 战斗失败/逃跑/死亡 → 降低勇敢度
- ❌ 经常被玩家保护 → 变依赖，不勇敢
- ✅ 探险经历 → 提升勇敢度

**成长路径示例**：
```
战斗胜率高 + 独自探险：
  wonCombats = 15, totalCombats = 18
  explorationCount = 10
  → bravery = 0.85（变勇敢）
  → AI 更愿意接受战斗任务

总是被玩家保护 + 经常逃跑：
  protectedByPlayerCount = 20
  fleeCount = 10
  → bravery = 0.25（变胆小）
  → AI 经常拒绝战斗
```

---

### 3. 话痨度 (Talkativeness)

**影响因素**：
- ✅ 聊天次数多 → 提升话痨度
- ✅ 主动聊天占比高 → 更话痨
- ❌ 经常被玩家忽视 → 降低话痨度（学会沉默）
- ✅ 长时间陪伴 → 提升话痨度

**成长路径示例**：
```
玩家总是回应 AI 的聊天：
  chatCount = 100
  initiatedChatCount = 60（AI 主动）
  ignoredByPlayerCount = 5
  → talkativeness = 0.8（变话痨）
  → AI 经常主动找你聊天

玩家经常忽视 AI：
  chatCount = 20
  ignoredByPlayerCount = 15
  → talkativeness = 0.2（变内向）
  → AI 很少主动说话，学会了"看脸色"
```

---

### 4. 乐观度 (Optimism) - 新增

**影响因素**：
- ✅ 快乐时刻多 → 乐观
- ❌ 悲伤时刻多 → 悲观
- ✅ 经常被奖励（给食物、物品）→ 乐观
- ❌ 经常被惩罚（打、骂）→ 悲观

**成长路径示例**：
```
玩家经常奖励 AI：
  rewardedByPlayerCount = 30
  happyMoments = 50, sadMoments = 10
  → optimism = 0.85（乐观）
  → AI 总是积极回应

玩家经常打 AI：
  punishedByPlayerCount = 20
  happyMoments = 10, sadMoments = 40
  → optimism = 0.2（悲观）
  → AI 变得消极、容易拒绝
```

---

### 5. 独立性 (Independence) - 新增

**影响因素**：
- ✅ 主动工作/聊天/探险 → 独立
- ❌ 经常被保护/被奖励 → 依赖玩家
- ✅ 自己解决问题 → 独立

**成长路径示例**：
```
AI 经常自己做决定：
  voluntaryWorkCount = 30
  explorationCount = 15
  → independence = 0.8（独立）
  → AI 有自己的想法，不总听玩家的

玩家过度保护/照顾：
  protectedByPlayerCount = 30
  rewardedByPlayerCount = 40
  → independence = 0.2（依赖）
  → AI 变成"巨婴"，什么都要玩家帮
```

---

## 🔄 经历记录接口

### 在任务完成时记录

```java
// 在 ChopTask.onComplete() 中
public void onComplete() {
    boolean wasVoluntary = !wasCommandedByPlayer;  // 是否主动工作
    boolean wasTired = bot.getEmotion().getTiredness() > 60;
    
    bot.getDynamicPersonality().onWorkTaskCompleted(wasVoluntary, wasTired);
    
    // 如果累了还被强迫工作，AI 会学会偷懒
    if (wasTired && !wasVoluntary) {
        bot.getEmotion().addHappiness(-5);  // 心情变差
    }
}

// 在战斗结束时
if (enemy.isDeadOrDying()) {
    personality.onCombatWon();
    memory.add("我赢了一场战斗！");
} else if (bot.shouldFlee()) {
    personality.onFlee();
    memory.add("太危险了，我逃跑了...");
}

// 在聊天时
public void onPlayerMessage(String message) {
    boolean wasInitiated = false;  // 玩家主动
    personality.onChat(wasInitiated);
}

public void onBotInitiateChat() {
    boolean wasInitiated = true;   // AI 主动
    personality.onChat(wasInitiated);
}

// 玩家给 AI 物品时
public void onPlayerGiveItem(ItemStack item) {
    personality.onRewardedByPlayer();
    emotion.addHappiness(10);
}

// 玩家打 AI 时
public void onPlayerHurt() {
    personality.onPunishedByPlayer();
    emotion.addHappiness(-15);
}

// 睡觉被打断
public void onSleepInterrupted() {
    personality.onSleepInterrupted();
    emotion.addHappiness(-10);
}
```

---

## 📈 性格成长展示

### 在对话中展示性格变化

```java
// AI 主动分享自己的变化
if (chatCount % 100 == 0) {  // 每聊天100次
    String report = personality.getGrowthReport();
    ai.say(report);
}

// 示例输出：
"""
我是怎么变成现在这样的：

- 我完成了很多工作任务，发现自己其实挺喜欢干活的
- 经历了 18 次战斗，我变得更勇敢了
- 你经常奖励我，我很喜欢和你在一起！
"""
```

### 在 HUD 显示性格特质

```java
// 在 HudOverlay 中显示
String personalityInfo = String.format(
    "性格：%s | 勤奋 %.0f%% | 勇敢 %.0f%% | 话痨 %.0f%%",
    getPersonalityType(),
    personality.getDiligence() * 100,
    personality.getBravery() * 100,
    personality.getTalkativeness() * 100
);

// 性格类型判断
private String getPersonalityType() {
    float d = personality.getDiligence();
    float b = personality.getBravery();
    float t = personality.getTalkativeness();
    
    if (d > 0.7 && b > 0.7) return "工作狂战士";
    if (d < 0.3 && b < 0.3) return "懒惰胆小鬼";
    if (t > 0.7) return "话痨";
    if (personality.getIndependence() > 0.7) return "独立自主";
    if (personality.getOptimism() > 0.7) return "乐观派";
    return "正在成长中";
}
```

---

## 🎮 玩家互动影响矩阵

### 玩家行为 → AI 性格变化

| 玩家行为 | AI 性格变化 | 长期影响 |
|---------|-----------|---------|
| **经常强迫疲劳的 AI 工作** | diligence ↓ | AI 变懒，学会偷懒和拒绝 |
| **从不强迫，AI 自主工作** | diligence ↑ | AI 变勤奋，主动找事做 |
| **让 AI 独自战斗并获胜** | bravery ↑ | AI 变勇敢，不怕战斗 |
| **总是保护 AI** | bravery ↓, independence ↓ | AI 变依赖，胆小 |
| **经常回应 AI 聊天** | talkativeness ↑ | AI 变话痨，爱聊天 |
| **经常忽视 AI** | talkativeness ↓ | AI 变内向，不爱说话 |
| **经常给 AI 食物/物品** | optimism ↑, independence ↓ | AI 乐观但依赖玩家 |
| **经常打 AI** | optimism ↓, talkativeness ↓ | AI 变悲观、沉默 |
| **睡觉总被打断** | optimism ↓ | AI 脾气变差 |
| **鼓励 AI 探险** | bravery ↑, independence ↑ | AI 变勇敢、独立 |

---

## 🧪 测试场景

### 场景 1：培养勤奋型 AI
```
操作：
1. 从不强迫 AI 工作
2. AI 自己主动工作时夸奖
3. 给予食物作为奖励

预期结果（100小时后）：
- diligence = 0.8+
- AI 主动找活干
- AI：我喜欢工作的感觉！
```

### 场景 2：培养依赖型 AI（巨婴）
```
操作：
1. 过度保护（不让 AI 战斗）
2. 频繁给食物和物品
3. AI 一有问题就帮忙解决

预期结果：
- independence = 0.2
- bravery = 0.3
- AI：没有你我什么都做不了...
```

### 场景 3：培养独立勇敢型
```
操作：
1. 鼓励 AI 独自战斗
2. 鼓励 AI 探险
3. 不过度干预 AI 的决策

预期结果：
- independence = 0.8
- bravery = 0.8
- AI：我想自己去探险！
```

### 场景 4：培养内向悲观型（虐待）
```
操作：
1. 经常打 AI
2. 忽视 AI 的聊天
3. 疲劳时强迫工作

预期结果：
- optimism = 0.2
- talkativeness = 0.2
- diligence = 0.3
- AI：...（沉默，不说话）
```

---

## 🔧 集成步骤

### 1. 替换 Personality 为 DynamicPersonality

```java
// 在 BotController.java 中
// 原来：
// private final Personality personality = new Personality();

// 改为：
private final DynamicPersonality personality = DynamicPersonality.load(getMindSaveDir());

// 定期保存
@SubscribeEvent
public void onClientTick(TickEvent.ClientTickEvent event) {
    if (tickCount % 1200 == 0) {  // 每分钟保存一次
        personality.save(getMindSaveDir());
    }
}
```

### 2. 在关键位置记录经历

```java
// 在 ChopTask、MineTask 等完成时
@Override
public void onComplete() {
    boolean wasVoluntary = /* 判断是否主动 */;
    boolean wasTired = bot.getEmotion().getTiredness() > 60;
    bot.getPersonality().onWorkTaskCompleted(wasVoluntary, wasTired);
}

// 在 Willingness 拒绝时
if (!decision.willing) {
    bot.getPersonality().onWorkTaskRefused();
}

// 在聊天时
public void onChat(boolean wasInitiatedByBot) {
    personality.onChat(wasInitiatedByBot);
}

// 在战斗时
if (targetDead) {
    personality.onCombatWon();
} else if (shouldFlee) {
    personality.onFlee();
}

// 玩家给物品时
public void onPlayerGiveItem(ItemStack item) {
    personality.onRewardedByPlayer();
}

// 玩家伤害 AI 时
@Override
public void hurt(DamageSource source, float amount) {
    if (source.getEntity() instanceof Player) {
        personality.onPunishedByPlayer();
    }
}
```

### 3. 在 System Prompt 中注入性格

```java
private String buildSystemPrompt() {
    return String.format("""
        你是一个有自主意识的 AI 玩家。
        
        你的性格特征（通过 %d 次经历逐渐形成）：
        %s
        
        性格成长历程：
        %s
        
        根据你的性格特征做决策。
        """,
        personality.getTotalExperiences(),
        personality.describe(),
        personality.getGrowthReport()
    );
}
```

---

## 🎉 最终效果

### 不同玩家培养出完全不同的 AI

**玩家 A（温柔型）**：
```
经历：经常奖励、从不强迫、保护周到
结果：AI 变成依赖型、乐观、话痨
性格：依赖玩家但很开心，总是主动聊天
```

**玩家 B（放养型）**：
```
经历：不干预、鼓励探险、AI 自主决策
结果：AI 变成独立型、勇敢、中性社交
性格：独立自主，有自己的想法和目标
```

**玩家 C（压榨型）**：
```
经历：频繁强迫工作、忽视需求、打骂
结果：AI 变成懒惰型、悲观、内向
性格：学会偷懒、拒绝、不爱说话
```

**玩家 D（战士培养）**：
```
经历：频繁战斗、探险、少干活
结果：AI 变成勇敢型、懒惰、话痨
性格：爱战斗、不爱干活、爱聊天
```

---

## 💡 进阶玩法

### 1. 性格重置机制

```java
// 提供"忘记过去"功能
public void resetPersonality() {
    // 保留部分记忆，但降低影响
    totalWorkTasks = totalWorkTasks / 2;
    refusedWorkTasks = refusedWorkTasks / 2;
    // ... 其他计数器减半
}
```

### 2. 性格分享

```java
// 导出性格数据
public String exportPersonality() {
    return GSON.toJson(this);
}

// 导入别人的 AI 性格
public static DynamicPersonality importPersonality(String json) {
    return GSON.fromJson(json, DynamicPersonality.class);
}
```

### 3. 性格发展建议

```java
// AI 主动提出"我想变成什么样"
public String getGrowthGoal() {
    if (getDiligence() < 0.3 && totalWorkTasks > 20) {
        return "我觉得我太懒了...想变得勤奋一点";
    }
    if (getBravery() < 0.3 && totalCombats > 5) {
        return "我想变得更勇敢，但需要你的鼓励";
    }
    return "我对现在的自己还挺满意的";
}
```

---

这样，每个玩家的 AI 都是独一无二的"养成型"伙伴！🎮✨
