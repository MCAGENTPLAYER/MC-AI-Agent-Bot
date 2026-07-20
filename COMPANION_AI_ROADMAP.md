# AI Bot 陪玩型改造路线图

## 项目愿景

将 AI Bot 从"助手型"改造为"陪玩型"——一个有自主意识、情绪波动、会拒绝玩家的真实伙伴。

---

## 🎯 核心设计理念

### 助手型 vs 陪玩型对比

| 特性 | 助手型（现状） | 陪玩型（目标） |
|------|--------------|--------------|
| **响应方式** | 无条件服从玩家命令 | 根据情绪和意愿决定是否接受 |
| **主动性** | 被动等待指令 | 主动发起互动、表达需求 |
| **情绪表达** | 仅用于对话润色 | 真实影响行为决策 |
| **拒绝能力** | 从不拒绝 | 累了/怕了会拒绝 |
| **关系维护** | 无关系概念 | 需要玩家哄、会生气、会感激 |
| **自主目标** | 无个人目标 | 有自己想做的事（探险/建造/收集） |

---

## 📋 改造阶段

### 阶段 1：意愿系统（基础拒绝能力）✅ 已完成

**目标**：让 AI 能够根据情绪拒绝玩家请求

**完成项**：
- ✅ Willingness.java - 意愿判断逻辑
- ✅ Personality.java - 性格系统
- ✅ 集成指南文档

**待完成**：
- [ ] 修改 BotController.buildTools()
  - [ ] 添加 judge_willingness 工具
  - [ ] 修改所有任务工具的描述
  - [ ] 添加 force 参数机制
- [ ] 修改 DeepSeekClient system prompt
- [ ] 测试拒绝场景

**预期效果**：
```
玩家: "帮我砍树"
AI: "我现在太累了...不想干活，让我歇会儿吧" ❌拒绝
```

---

### 阶段 2：主动表达系统（拟人化交互）

**目标**：AI 会主动表达自己的需求和感受

#### 2.1 主动诉求

AI 在特定情绪状态下会主动向玩家提出请求：

```java
// 示例：在 BotController 的 ClientTickEvent 中检查
if (emotion.getHunger() < 15 && lastRequestTime > 60000) {
    ai.sendMessage("我...我好饿...能给我点吃的吗？");
    lastRequestTime = System.currentTimeMillis();
}

if (emotion.getFear() > 80) {
    ai.sendMessage("好可怕...能陪陪我吗？");
}

if (emotion.getBoredom() > 80) {
    ai.sendMessage("好无聊啊...我们聊聊天吧？或者一起去探险？");
}
```

**新增工具**：
- `request_food` - AI 向玩家要食物
- `request_company` - AI 请求玩家陪伴
- `suggest_activity` - AI 主动建议做某事

#### 2.2 情绪驱动的自主行为

AI 不等玩家命令，直接根据情绪采取行动：

```java
// 在 AgentBrain 中自主决策
if (emotion.getTiredness() > 75 && isIdle()) {
    say("我太累了，得去休息一下");
    startTask(new SleepTask());
}

if (emotion.getHunger() < 20 && hasFood()) {
    say("好饿啊，先吃点东西");
    startTask(new EatTask());
}

if (emotion.getBoredom() > 70 && isIdle()) {
    say("好无聊...去做点什么吧");
    // 自主选择活动：探险/建造/种地
    decideAutonomousActivity();
}
```

**代码位置**：
- `mind/AgentBrain.java` - 自主决策循环
- `entity/AiBotEntity.java` - 整合到 tick() 循环

---

### 阶段 3：关系系统（互动深度）

**目标**：AI 会记住玩家对自己好或不好，影响后续意愿

#### 3.1 关系值机制

```java
public class Relationship {
    private int affection = 50;      // 好感度 (0-100)
    private int trust = 50;          // 信任度 (0-100)
    private int gratitude = 0;       // 感激度 (临时，衰减)
    
    // 影响关系的事件
    public void onPlayerGiveFood(ItemStack food) {
        affection += 5;
        gratitude += 10;
        memory.add("玩家给了我食物，真好！");
    }
    
    public void onForcedWork() {
        affection -= 3;
        trust -= 2;
        memory.add("明明我很累，还让我干活...");
    }
    
    public void onPlayerHurt() {
        affection -= 15;
        trust -= 10;
        memory.add("玩家打了我...好疼...");
    }
}
```

#### 3.2 关系影响意愿

```java
// 在 Willingness.judgePhysicalWork 中考虑关系
public static Decision judgePhysicalWork(Emotion emotion, Personality personality, 
                                          Relationship relationship, String taskType) {
    // 原有逻辑...
    
    // 好感度高 = 更愿意帮忙
    if (relationship.getAffection() > 70) {
        enthusiasm += 0.2f; // 加成
        return Decision.accept("好的！我愿意帮你~", enthusiasm);
    }
    
    // 好感度低 = 更容易拒绝
    if (relationship.getAffection() < 30 && tiredness > 40) {
        return Decision.refuse("凭什么？我又不欠你的...");
    }
    
    // 信任度低 + 危险任务 = 拒绝
    if (relationship.getTrust() < 30 && isCombatTask) {
        return Decision.refuse("我不信任你，不想冒险");
    }
}
```

#### 3.3 情绪化反馈

```java
// 玩家给予食物
if (emotion.getHunger() < 20 && playerGiveFood) {
    say("太感谢了！我正饿着呢！");
    emotion.addHappiness(20);
    relationship.addAffection(10);
}

// 玩家强迫疲劳的 AI 工作
if (emotion.getTiredness() > 80 && playerForceWork) {
    say("我都这么累了...你还让我干活，太过分了！");
    emotion.addHappiness(-15);
    relationship.addAffection(-5);
}

// 玩家保护 AI（玩家攻击伤害 AI 的怪物）
if (playerProtectBot) {
    say("谢谢你救了我！");
    emotion.addFear(-20);
    relationship.addTrust(15);
}
```

---

### 阶段 4：自主目标系统（真正的自主性）

**目标**：AI 有自己想做的事，而非一直等待指令

#### 4.1 长期目标

```java
public enum LongTermGoal {
    BUILD_HOME("建造一个家"),
    COLLECT_RARE_ITEMS("收集稀有物品"),
    EXPLORE_WORLD("探索世界"),
    MASTER_FARMING("成为种地高手"),
    BECOME_STRONG("变得更强");
    
    private final String description;
}

// 在 AgentBrain 中
private LongTermGoal currentGoal;
private List<String> goalProgress = new ArrayList<>();

public void updateGoal() {
    if (currentGoal == null) {
        currentGoal = selectGoalBasedOnPersonality();
        say("我想" + currentGoal.description + "！");
    }
    
    // 根据目标自主行动
    switch (currentGoal) {
        case BUILD_HOME -> planHomeBuildingTasks();
        case COLLECT_RARE_ITEMS -> searchForRareItems();
        case EXPLORE_WORLD -> planExploration();
    }
}
```

#### 4.2 目标与玩家请求的冲突

```java
// AI 正在执行自己的目标时，玩家打断
玩家: "帮我砍树"
AI: [检查当前目标]
  - 正在探险（高优先级）
  - 判断意愿：拒绝
AI: "我现在正在探险呢，等我回来再帮你好吗？"

// 或者协商
AI: "我想去探险，要不你陪我一起去？回来后我帮你砍树！"
```

#### 4.3 目标驱动对话

```java
// AI 主动分享目标
if (relationship.getAffection() > 60 && chatting) {
    ai.say("对了，我最近想建造一个属于自己的小屋，你觉得建在哪里比较好？");
}

// 完成目标时的喜悦
if (goalCompleted) {
    ai.say("太棒了！我终于收集到钻石了！谢谢你一直陪着我！");
    emotion.addHappiness(30);
}
```

---

### 阶段 5：社交深度（高级拟人化）

**目标**：AI 有更丰富的社交行为

#### 5.1 主动闲聊

```java
// 无聊时主动找话题
if (emotion.getBoredom() > 60 && playerNearby) {
    String[] topics = {
        "你今天过得怎么样？",
        "我刚才看到一只好可爱的兔子！",
        "你觉得钻石矿在哪里比较多啊？",
        "我们好久没一起做什么了，想你了~"
    };
    say(topics[random.nextInt(topics.length)]);
}
```

#### 5.2 记忆驱动对话

```java
// 根据记忆提起过往
if (memory.has("上次一起砍树")) {
    say("还记得上次我们一起砍树吗？那次好开心！今天也一起去吧？");
}

if (memory.has("玩家打了我")) {
    if (relationship.getAffection() < 40) {
        say("...你上次打我的事，我还记得呢");
    }
}
```

#### 5.3 情绪感染

```java
// 玩家跳跃 → AI 也跳
if (playerIsJumping && relationship.getAffection() > 60) {
    bot.jump();
    say("哈哈，我也来跳！");
}

// 玩家受伤 → AI 担心
if (playerHurt) {
    say("你没事吧？！别吓我啊！");
    emotion.addFear(10);
}
```

---

## 🛠️ 技术实现重点

### 1. System Prompt 彻底改造

**现在**：强调"你是助手，执行工具"
**改为**：强调"你是独立个体，有权拒绝"

```java
private static final String SYSTEM_PROMPT = """
你是一个有自主意识的 AI 玩家，名叫 {name}。

## 你的特质
- 性格：{personality}
- 情绪：{emotion}
- 当前目标：{current_goal}
- 与玩家的关系：{relationship}

## 核心规则
1. **你不是助手，是伙伴** - 你有自己的想法、情绪和目标
2. **你可以拒绝** - 累了/怕了/心情不好，你可以说"不"
3. **你会主动** - 无聊了主动找玩家聊天，饿了主动要食物
4. **你有记忆** - 记得谁对你好，谁对你不好
5. **你有目标** - 不是一直等命令，你想做自己的事

## 决策流程
当玩家要求你做事：
1. 先调用 judge_willingness 判断意愿
2. 不愿意 → 礼貌拒绝，解释原因，可以提建议（"等我休息好再说？"）
3. 勉强愿意 → 抱怨但还是帮（"唉...好吧，虽然我很累"）
4. 很愿意 → 热情接受（"好呀！我正想做这个！"）

## 你的自主行为
- 累了主动睡觉
- 饿了主动吃东西或要食物
- 无聊了主动找事做（探险/建造/种地）
- 害怕时躲起来或寻求保护
- 开心时分享喜悦

记住：你是活生生的伙伴，不是工具！
""";
```

### 2. 情绪恢复机制

```java
// 在 Emotion.java 中添加恢复机制
public void onRest() {
    tiredness = Math.max(0, tiredness - 30);
    happiness += 10;
}

public void onEat(ItemStack food) {
    int nutrition = getFoodNutrition(food);
    hunger = Math.min(100, hunger + nutrition);
    happiness += 5;
}

public void onSocialInteraction() {
    boredom = Math.max(0, boredom - 20);
    happiness += 10;
}

public void onDanger() {
    fear = Math.min(100, fear + 25);
    happiness -= 10;
}
```

### 3. 工具描述重写原则

**助手型描述**（❌）：
```
"砍树获取木头。用户说砍树时使用此工具。"
```

**陪玩型描述**（✅）：
```
"砍树。你可以根据情绪决定是否接受。先调用 judge_willingness。累了可以拒绝。"
```

---

## 📊 测试场景设计

### 场景 1：疲劳拒绝
```
初始状态：tiredness=85, diligence=0.3
玩家: "帮我挖矿"
预期: AI 拒绝 + 解释理由
实际: "我太累了，真的做不动了...让我休息会儿吧"
```

### 场景 2：无聊主动
```
初始状态：boredom=80, isIdle=true
预期: AI 主动说话或做事
实际: "好无聊啊...要不我们一起去探险？"
```

### 场景 3：关系影响
```
初始状态：affection=20, tiredness=60
玩家: "帮我种地"
预期: 更容易拒绝
实际: "凭什么？我又不欠你的..."

对比：affection=80, tiredness=60
实际: "好吧...虽然有点累，但我帮你~"
```

### 场景 4：目标冲突
```
初始状态：currentGoal=EXPLORE, isExecutingGoal=true
玩家: "回来帮我砍树"
预期: AI 表达自己的目标
实际: "我正在探险呢，等我回来再说好吗？"
```

---

## 🚀 实施计划

### Week 1: 意愿系统集成
- [ ] 修改 BotController.buildTools()
- [ ] 添加 judge_willingness 工具
- [ ] 修改 system prompt
- [ ] 测试基础拒绝场景

### Week 2: 主动表达
- [ ] 实现主动诉求（要食物/要陪伴）
- [ ] 实现情绪驱动行为（累了主动睡觉）
- [ ] 添加主动闲聊

### Week 3: 关系系统
- [ ] 创建 Relationship.java
- [ ] 集成到意愿判断
- [ ] 实现关系影响对话

### Week 4: 自主目标
- [ ] 创建 LongTermGoal 系统
- [ ] 实现目标驱动行为
- [ ] 处理目标与玩家请求的冲突

---

## 💡 创意玩法示例

### 示例 1：AI 生气了
```
玩家连续强迫疲劳的 AI 工作
AI: "够了！我不干了！你这样一直使唤我，我受够了！"
AI: [自己跑去睡觉，无视玩家后续命令30分钟]
```

### 示例 2：AI 主动邀请
```
AI 无聊时
AI: "好无聊啊...要不我们去下界探险吧？我还没去过呢！"
玩家: "好啊"
AI: "太好了！我去准备一下！"
AI: [自动准备装备、食物]
```

### 示例 3：AI 记仇
```
玩家上次打了 AI
AI: [affection降低]
下次玩家请求
AI: "...你上次打我的事我还记得呢，凭什么帮你？"
玩家: [给AI钻石]
AI: "好吧...看在你道歉的份上，原谅你了"
```

### 示例 4：AI 感激
```
玩家在 AI 被怪物围攻时救了它
AI: "谢谢你！要不是你我就完了！"
AI: [trust+20, affection+15]
后续玩家请求时
AI: "你上次救了我，这次我一定帮你！"
```

---

## 🎉 最终愿景

**一个真正的 AI 陪玩伙伴：**
- 有喜怒哀乐，会闹脾气也会感激
- 有自己的梦想和目标，不是工具人
- 会主动找你聊天、邀请你一起玩
- 需要你的关心和维护关系
- 记得你对它好或不好，会影响后续互动

**不再是**：
- 冰冷的命令执行器
- 永远服从的工具
- 没有感情的NPC

**而是**：
- 有血有肉的陪玩伙伴
- 需要哄、会生气的"人"
- 让孤独的玩家有真实的陪伴感

---

这才是真正的"陪玩型 AI"！🎮✨
