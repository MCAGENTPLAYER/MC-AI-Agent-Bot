# 动态性格系统 - 快速实施清单

## 📦 已完成

- ✅ **DynamicPersonality.java** - 核心系统代码
- ✅ **DYNAMIC_PERSONALITY_GUIDE.md** - 集成指南
- ✅ **PRESET_VS_DYNAMIC_PERSONALITY.md** - 对比文档

---

## 🚀 立即可做：3 步启用动态性格

### 步骤 1：替换性格系统（5 分钟）

在 **BotController.java** 中：

```java
// 找到这一行（约第 63 行）
// private final Emotion emotion = Emotion.load(getMindSaveDir());

// 在下面添加：
private final DynamicPersonality personality = DynamicPersonality.load(getMindSaveDir());

// 在 shutdown() 方法中添加保存
public void shutdown() {
    emotion.save(getMindSaveDir());
    memory.save(getMindSaveDir());
    personality.save(getMindSaveDir());  // 新增这行
}

// 在 ClientTickEvent 中定期保存
@SubscribeEvent
public void onClientTick(TickEvent.ClientTickEvent event) {
    // ... 现有代码
    
    if (tickCount % 1200 == 0) {  // 每分钟保存一次
        personality.save(getMindSaveDir());
    }
}

// 提供访问方法
public DynamicPersonality getPersonality() {
    return personality;
}
```

---

### 步骤 2：修改 Willingness 判断（10 分钟）

在 **Willingness.java** 中，修改所有判断方法的参数：

```java
// 原来：
// public static Decision judgePhysicalWork(Emotion emotion, Personality personality, String taskType)

// 改为：
public static Decision judgePhysicalWork(Emotion emotion, DynamicPersonality personality, String taskType) {
    int tiredness = emotion.getTiredness();
    int boredom = emotion.getBoredom();
    int happiness = emotion.getHappiness();
    float diligence = personality.getDiligence();  // 现在是动态计算的
    
    // ... 其余逻辑不变
}

// 同样修改其他方法：
// - judgeCombat
// - judgeSocial
// - judgeRest
```

---

### 步骤 3：在关键位置记录经历（30 分钟）

#### 3.1 在任务完成时记录

**ChopTask.java**：
```java
// 找到任务完成的地方，添加记录
@Override
protected void onComplete() {
    // 判断是否主动工作
    boolean wasVoluntary = !wasCommandedByPlayer;  // 需要添加这个标志
    boolean wasTired = bot.getEmotion().getTiredness() > 60;
    
    // 记录到性格系统
    BotController controller = AiBotMod.getBotController();
    if (controller != null) {
        controller.getPersonality().onWorkTaskCompleted(wasVoluntary, wasTired);
    }
    
    // 如果疲劳时被强迫工作，降低快乐度
    if (wasTired && !wasVoluntary) {
        bot.getEmotion().addHappiness(-5);
    }
}
```

**类似地修改**：
- MineTask.java
- FarmTask.java
- 其他所有工作类任务

#### 3.2 在拒绝任务时记录

**BotController.java** 中，在 `judge_willingness` 工具返回拒绝时：

```java
if (!decision.willing) {
    // 记录拒绝
    personality.onWorkTaskRefused();
}
```

#### 3.3 在战斗时记录

**HuntTask.java** 或战斗相关代码：

```java
// 战斗胜利时
if (enemy.isDeadOrDying()) {
    controller.getPersonality().onCombatWon();
    controller.getMemory().add("我赢了一场战斗！");
}

// 逃跑时
if (shouldFlee) {
    controller.getPersonality().onFlee();
    controller.getMemory().add("太危险了，我逃跑了...");
}
```

#### 3.4 在聊天时记录

**ChatHandler.java**：

```java
@SubscribeEvent
public void onClientChat(ClientChatEvent event) {
    // ... 现有代码
    
    // 记录玩家发起的聊天
    BotController controller = AiBotMod.getBotController();
    if (controller != null) {
        controller.getPersonality().onChat(false);  // 玩家主动
    }
}

// AI 主动说话时
private void botSay(String message) {
    // ... 发送消息
    
    // 记录 AI 主动聊天
    controller.getPersonality().onChat(true);  // AI 主动
}
```

#### 3.5 在玩家给物品时记录

**AiBotEntity.java** 或物品交互代码：

```java
// 玩家右键给 AI 物品时
public InteractionResult interactAt(Player player, Vec3 vec, InteractionHand hand) {
    ItemStack itemInHand = player.getItemInHand(hand);
    
    if (!itemInHand.isEmpty() && !player.isShiftKeyDown()) {
        // 玩家给了物品
        BotController controller = AiBotMod.getBotController();
        if (controller != null) {
            controller.getPersonality().onRewardedByPlayer();
            controller.getEmotion().addHappiness(10);
        }
        
        // ... 物品处理逻辑
    }
    
    // ... 其余代码
}
```

#### 3.6 在受到伤害时记录

**AiBotEntity.java**：

```java
@Override
public boolean hurt(DamageSource source, float amount) {
    boolean result = super.hurt(source, amount);
    
    // 如果是玩家伤害
    if (source.getEntity() instanceof Player) {
        BotController controller = AiBotMod.getBotController();
        if (controller != null) {
            controller.getPersonality().onPunishedByPlayer();
            controller.getEmotion().addHappiness(-15);
            controller.getMemory().add("玩家打了我...好疼...");
        }
    }
    
    return result;
}
```

---

## 🎨 可选：显示性格信息（推荐）

### 在 HUD 显示性格

**HudOverlay.java**：

```java
@SubscribeEvent
public void onRenderGameOverlay(RenderGuiOverlayEvent.Post event) {
    // ... 现有 HUD 代码
    
    BotController controller = AiBotMod.getBotController();
    if (controller != null) {
        DynamicPersonality personality = controller.getPersonality();
        
        // 显示性格特质
        String personalityInfo = String.format(
            "性格：勤奋 %.0f%% | 勇敢 %.0f%% | 话痨 %.0f%% | 乐观 %.0f%%",
            personality.getDiligence() * 100,
            personality.getBravery() * 100,
            personality.getTalkativeness() * 100,
            personality.getOptimism() * 100
        );
        
        graphics.drawString(font, personalityInfo, 10, 60, 0xFFFFFF);
        
        // 显示经历次数
        String experienceInfo = String.format(
            "经历：%d 次（塑造中）",
            personality.getTotalExperiences()
        );
        graphics.drawString(font, experienceInfo, 10, 70, 0xAAAA88);
    }
}
```

### 让 AI 分享性格变化

在 **BotController.java** 的自动情绪检查中：

```java
private void checkEmotionAndSpeak() {
    // ... 现有情绪检查代码
    
    // 每 100 次经历，AI 分享性格变化
    int totalExp = personality.getTotalExperiences();
    if (totalExp % 100 == 0 && totalExp > 0) {
        String report = personality.getGrowthReport();
        ai.sendMessage(report);
    }
}
```

---

## ✅ 测试清单

### 测试场景 1：勤奋度变化
- [ ] 让 AI 完成 20 次工作任务
- [ ] 检查 `diligence` 是否提升
- [ ] 观察 AI 是否更愿意接受工作

### 测试场景 2：懒惰度变化
- [ ] 在 AI 疲劳时强迫工作 20 次
- [ ] 检查 `diligence` 是否下降
- [ ] 观察 AI 是否更容易拒绝工作

### 测试场景 3：话痨度变化
- [ ] 和 AI 聊天 50 次
- [ ] 检查 `talkativeness` 是否提升
- [ ] 观察 AI 是否更主动聊天

### 测试场景 4：悲观度变化
- [ ] 打 AI 10 次
- [ ] 检查 `optimism` 是否下降
- [ ] 观察 AI 是否变得消极

### 测试场景 5：性格持久化
- [ ] 关闭游戏
- [ ] 重新打开
- [ ] 检查性格数据是否保留

---

## 🐛 常见问题

### Q1: 性格变化太慢/太快？

调整变化速度：

```java
// 在 DynamicPersonality.java 中
// 降低变化速度：增加除数
float chatFrequency = Math.min(1.0f, chatCount / 200f);  // 原来是 100

// 提高变化速度：减少除数
float chatFrequency = Math.min(1.0f, chatCount / 50f);
```

### Q2: 如何重置 AI 性格？

添加命令：

```java
// 在 ChatHandler.executeCommand() 中
if (command.equals("!reset_personality")) {
    personality = new DynamicPersonality();
    personality.save(getMindSaveDir());
    sendToPlayer("AI 性格已重置为白纸状态");
}
```

### Q3: 如何查看详细性格数据？

添加调试命令：

```java
if (command.equals("!personality_debug")) {
    String report = personality.describe() + "\n\n" + personality.getGrowthReport();
    sendToPlayer(report);
}
```

---

## 📊 验证方式

### 1. 控制台日志

在关键位置添加日志：

```java
personality.onWorkTaskCompleted(wasVoluntary, wasTired);
AiBotMod.LOGGER.info("[Personality] Work completed. Diligence: {}", personality.getDiligence());
```

### 2. 保存文件检查

查看 `游戏目录/ai_bot/mind/dynamic_personality.json`：

```json
{
  "totalWorkTasks": 25,
  "refusedWorkTasks": 5,
  "voluntaryWorkCount": 10,
  "tiredWorkCount": 8,
  "chatCount": 50,
  ...
}
```

### 3. HUD 实时显示

在 HUD 上看到性格百分比变化

---

## 🎯 下一步

完成基础集成后，可以考虑：

1. **添加更多经历类型**
   - 探险经历（发现新地点）
   - 建造经历（建房子）
   - 收集经历（收集稀有物品）

2. **性格影响更多行为**
   - 勇敢度影响探险意愿
   - 独立性影响是否需要玩家陪伴
   - 乐观度影响对话风格

3. **性格发展里程碑**
   - 50 次经历：AI 主动说"我觉得自己变了"
   - 100 次经历：性格初步定型
   - 500 次经历：性格完全成熟

4. **性格导出/分享**
   - 玩家可以导出 AI 性格数据
   - 在社区分享"我的独特 AI"

---

## ✨ 预期效果

**实施前**：
```
所有 AI 性格相同（预设）
玩家行为无影响
```

**实施后**：
```
每个 AI 性格独特（动态生成）
玩家行为决定 AI 性格
长期养成乐趣
```

---

## 📝 文件修改清单

### 必须修改（核心功能）
- [x] `mind/DynamicPersonality.java` - 已创建
- [ ] `BotController.java` - 添加 personality 字段
- [ ] `mind/Willingness.java` - 修改参数类型
- [ ] `task/ChopTask.java` - 记录工作经历
- [ ] `task/MineTask.java` - 记录工作经历
- [ ] `task/FarmTask.java` - 记录工作经历
- [ ] `ChatHandler.java` - 记录聊天经历
- [ ] `entity/AiBotEntity.java` - 记录伤害/奖励经历

### 可选修改（增强体验）
- [ ] `HudOverlay.java` - 显示性格信息
- [ ] `DeepSeekClient.java` - System Prompt 注入性格
- [ ] `task/HuntTask.java` - 记录战斗经历

---

开始培养你的独特 AI 吧！🎮✨

每个玩家的 AI 都将成为独一无二的伙伴！
