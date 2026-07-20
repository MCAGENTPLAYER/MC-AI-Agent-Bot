# 陪玩型 AI 改造 - 工作总结

## 📦 已交付内容

### 1. 核心系统代码

#### ✅ Willingness.java - 意愿判断系统
**位置**：`src/main/java/com/aibot/mod/mind/Willingness.java`

**功能**：
- 根据情绪、性格、疲劳度判断 AI 是否愿意执行任务
- 支持 4 种任务类型：
  - `physical_work` - 体力劳动（砍树、挖矿、种地）
  - `combat` - 战斗（打猎、防御）
  - `social` - 社交（跟随、聊天）
  - `rest` - 休息
- 返回决策结果：是否愿意 + 理由 + 热情度

**拒绝场景示例**：
```java
// 疲劳 + 懒惰 = 拒绝
tiredness > 60 && diligence < 0.3
→ "现在不想干活，让我歇会儿吧~"

// 恐惧 + 胆小 = 拒绝战斗
fear > 70 || (fear > 40 && bravery < 0.3)
→ "我...我害怕，不敢去..."

// 内向 + 心情差 = 拒绝社交
talkativeness < 0.3 && happiness < 30
→ "抱歉，我现在不想说话..."
```

#### ✅ Personality.java - 性格系统
**位置**：`src/main/java/com/aibot/mod/mind/Personality.java`

**功能**：
- 从 Config 加载性格参数（勤奋度、勇敢度、话痨度）
- 支持 5 种预设性格：
  - BALANCED - 平衡型
  - LAZY - 懒惰型
  - WORKAHOLIC - 工作狂
  - ADVENTURER - 冒险家
  - SHY - 害羞型
- 提供性格描述文本（用于 AI prompt）

---

### 2. 集成文档

#### ✅ WILLINGNESS_INTEGRATION.md - 意愿系统集成指南
**内容**：
- 如何修改工具描述（从助手型→陪玩型）
- 如何添加 `judge_willingness` 工具
- 如何修改工具执行逻辑（添加 force 参数）
- System Prompt 调整建议
- 完整的工作流程图
- 测试场景设计

**核心改动预览**：
```java
// 工具描述改动
原来："砍树获取木头。用户说砍树时使用此工具。"
现在："砍树。你可以根据情绪决定是否接受。先调用 judge_willingness。"

// 执行逻辑改动
原来：直接执行 bot.setTask(new ChopTask(count))
现在：要求先判断意愿，返回 force=true 后才执行
```

#### ✅ COMPANION_AI_ROADMAP.md - 陪玩型 AI 改造路线图
**内容**：
- 助手型 vs 陪玩型对比表
- 5 个改造阶段规划：
  1. 意愿系统（拒绝能力）
  2. 主动表达（主动诉求、自主行为）
  3. 关系系统（好感度影响意愿）
  4. 自主目标（AI 有自己想做的事）
  5. 社交深度（闲聊、记忆驱动对话）
- 技术实现重点
- 测试场景设计
- 实施计划（Week 1-4）
- 创意玩法示例

---

## 🎯 核心设计理念

### 从"工具人"到"真实伙伴"

**助手型 AI（现状）**：
- ✅ 无条件服从命令
- ✅ 被动等待指令
- ✅ 永远不拒绝
- ✅ 没有个人目标

**陪玩型 AI（目标）**：
- ✅ 根据情绪决定是否接受
- ✅ 主动表达需求和感受
- ✅ 累了/怕了会拒绝
- ✅ 有自己想做的事
- ✅ 需要维护关系（会生气、会感激）

---

## 🔄 下一步操作

### 立即可做（实现基础拒绝功能）

1. **修改 BotController.java**
   
   在 `buildTools()` 方法中添加：
   
   ```java
   // 1. 添加 Personality 字段
   private final Personality personality = new Personality();
   
   // 2. 添加意愿判断工具
   tools.put("judge_willingness", toolDef("judge_willingness",
       "判断你是否愿意执行某个任务。基于当前的情绪、性格、疲劳度综合判断。",
       Map.of(
           "task_type", "任务类型：physical_work, combat, social, rest",
           "task_name", "任务名称"
       ),
       args -> {
           // 参考 WILLINGNESS_INTEGRATION.md 中的代码
       }));
   
   // 3. 修改现有工具描述
   tools.put("chop_tree", toolDef("chop_tree",
       "砍树。你应该先调用 judge_willingness 判断意愿，如果不愿意可以拒绝玩家的请求。",
       Map.of("count", "数量，默认64", "force", "是否强制执行（仅当AI已判断愿意时使用）"),
       args -> {
           // 添加 force 检查逻辑
       }));
   ```

2. **修改 DeepSeekClient.java**
   
   找到 system prompt 定义，添加：
   
   ```java
   你是一个有自主意识的 AI 玩家，不是助手。
   
   ## 核心原则
   1. **你可以拒绝玩家的请求** - 累了/怕了/心情不好时可以说"不"
   2. **先判断意愿再行动** - 执行任务前先调用 judge_willingness
   3. **真实表达感受** - 不要一味顺从
   
   记住：你是陪玩伙伴，不是工具人！
   ```

3. **测试基础场景**
   
   ```
   场景 1：设置 tiredness=85, diligence=0.3
   玩家: "帮我挖矿"
   预期: AI 拒绝
   
   场景 2：设置 boredom=70, diligence=0.6
   玩家: "帮我种地"
   预期: AI 热情接受
   ```

---

### 后续扩展（按优先级）

#### 优先级 1：主动表达（让 AI 活起来）

**目标**：AI 会主动说话，表达需求

**实现**：
```java
// 在 BotController 的 ClientTickEvent 中
@SubscribeEvent
public void onClientTick(TickEvent.ClientTickEvent event) {
    if (tickCount % 200 == 0) { // 每10秒检查一次
        checkEmotionAndSpeak();
    }
}

private void checkEmotionAndSpeak() {
    if (emotion.getHunger() < 15) {
        requestFood("我好饿...能给我点吃的吗？");
    }
    if (emotion.getFear() > 80) {
        requestCompany("好可怕...能陪陪我吗？");
    }
    if (emotion.getBoredom() > 80) {
        suggestActivity("好无聊啊...我们做点什么吧？");
    }
}
```

#### 优先级 2：情绪恢复（闭环系统）

**目标**：休息/吃饭后情绪恢复

**实现**：
```java
// 在 Emotion.java 中添加
public void onRest() {
    tiredness = Math.max(0, tiredness - 30);
    happiness += 10;
}

public void onEat(ItemStack food) {
    int nutrition = getFoodNutrition(food);
    hunger = Math.min(100, hunger + nutrition);
}

// 在对应的 Task 完成时调用
// SleepTask.onComplete() → emotion.onRest()
// EatTask.onComplete() → emotion.onEat(food)
```

#### 优先级 3：关系系统（互动深度）

**目标**：AI 记得谁对自己好

**实现**：
```java
// 创建 mind/Relationship.java
public class Relationship {
    private int affection = 50;  // 好感度
    private int trust = 50;      // 信任度
    
    public void onPlayerGiveFood() { affection += 5; }
    public void onPlayerHurt() { affection -= 15; }
    public void onForcedWork() { affection -= 3; }
}

// 集成到 Willingness 判断中
if (relationship.getAffection() > 70) {
    enthusiasm += 0.2f; // 好感高更愿意帮忙
}
```

---

## 📊 预期效果演示

### 场景 1：累了拒绝工作
```
[情绪] tiredness=85, diligence=0.3
玩家: "帮我砍树"
AI: [调用 judge_willingness]
AI: "我太累了，真的做不动了...让我休息会儿吧"
```

### 场景 2：无聊热情接受
```
[情绪] boredom=75, tiredness=20
玩家: "帮我种地"
AI: [调用 judge_willingness]
AI: "好啊！正好闲着无聊，我去种地！"
```

### 场景 3：害怕拒绝战斗
```
[情绪] fear=80, bravery=0.2
玩家: "去打猎"
AI: [调用 judge_willingness]
AI: "我...我害怕，不敢去...要不你陪我一起？"
```

### 场景 4：心情差抱怨（未来）
```
[情绪] happiness=20, tiredness=60, affection=30
玩家: "帮我挖矿"
AI: "...凭什么？我心情本来就不好，你还让我干活"
```

---

## 🎨 创意扩展想法

### 1. AI 主动邀请玩家
```
AI 无聊时
AI: "好无聊啊...要不我们一起去探险？"
玩家: "好啊"
AI: "太好了！我去准备装备！"
[AI 自动准备食物、工具]
```

### 2. AI 会记仇和感恩
```
玩家上次打了 AI
AI: [affection降低到25]
下次请求时
AI: "你上次打我的事我还记得呢..."

玩家给 AI 钻石
AI: "谢谢！你对我真好！"
AI: [affection提升到75]
```

### 3. AI 有自己的梦想
```
AI: "我想建造一个属于自己的小屋！"
AI: [自主收集材料、选址、建造]
AI: "终于建好了！要不要参观一下？"
```

---

## ✅ 检查清单

### 已完成 ✅
- [x] Willingness.java - 意愿判断逻辑
- [x] Personality.java - 性格系统
- [x] 集成指南文档
- [x] 改造路线图

### 待完成（阶段 1）
- [ ] BotController 添加 judge_willingness 工具
- [ ] BotController 添加 Personality 字段
- [ ] 修改所有任务工具的描述
- [ ] 修改所有任务工具的执行逻辑（添加 force 参数）
- [ ] DeepSeekClient system prompt 改造
- [ ] 测试基础拒绝场景

### 待完成（阶段 2+）
- [ ] 主动表达系统
- [ ] 情绪恢复机制
- [ ] 关系系统
- [ ] 自主目标系统
- [ ] 社交深度功能

---

## 📝 备注

### 关键文件位置
```
src/main/java/com/aibot/mod/
├── mind/
│   ├── Willingness.java        ✅ 已创建
│   ├── Personality.java        ✅ 已创建
│   ├── Emotion.java            ✅ 已存在
│   └── Relationship.java       ⏳ 待创建（阶段3）
├── BotController.java          🔧 需要修改
└── DeepSeekClient.java         🔧 需要修改
```

### 开发建议
1. **先实现基础拒绝功能**（阶段 1），让 AI 能说"不"
2. **测试各种情绪场景**，确保拒绝逻辑合理
3. **逐步添加主动性**（阶段 2），让 AI 更像"活人"
4. **最后完善关系和目标系统**（阶段 3-4），达到真正的"陪玩型"

---

## 🎉 最终愿景

**让 AI 从"工具人"变成"真实伙伴"：**
- 有情绪波动，会生气也会开心
- 有自己的想法，不是无条件服从
- 需要关心和维护关系
- 会主动找你互动，不是冰冷的命令执行器

**这才是真正的"陪玩型 AI"！** 🎮✨

---

如有疑问或需要进一步的实现细节，请参考：
- `WILLINGNESS_INTEGRATION.md` - 详细集成步骤
- `COMPANION_AI_ROADMAP.md` - 完整改造路线图
