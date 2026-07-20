# Token 消耗分析与优化方案

## 📊 当前 Token 消耗估算

### 现有系统的 Token 消耗

#### 每次对话的固定消耗

```
System Prompt（基础）        ~200 tokens
- 核心规则和工具使用说明

动态上下文（每次请求注入）   ~150-300 tokens
├── 情绪状态 (emotion.formatForAI)     ~80 tokens
│   └── 5 维情绪 + 自然语言标签
├── 记忆 (memory.formatForAI)          ~70-200 tokens
│   └── 最近 10 条记忆（每条约 7-20 tokens）
└── 任务状态 (taskQuestSystem)        ~50 tokens

工具定义（20+ 工具）         ~800-1000 tokens
- 每个工具的 name + description + parameters

对话历史（最多 20 条）       ~500-2000 tokens
- 取决于对话长度

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
总计（单次请求）             ~1650-3500 tokens
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## 🆕 动态性格系统的 Token 影响

### 方案 A：完整注入（不推荐）❌

如果把所有性格数据注入到 System Prompt：

```java
// ❌ 不要这样做
private String buildSystemPrompt() {
    return String.format("""
        你是一个 AI Bot。
        
        你的性格特征（通过 %d 次经历形成）：
        - 勤奋度：%.0f%% (完成 %d 次工作，拒绝 %d 次)
        - 勇敢度：%.0f%% (战斗 %d 次，胜利 %d 次，逃跑 %d 次)
        - 话痨度：%.0f%% (聊天 %d 次，主动 %d 次，被忽视 %d 次)
        - 乐观度：%.0f%% (快乐时刻 %d 次，悲伤时刻 %d 次)
        - 独立性：%.0f%% (主动工作 %d 次，被保护 %d 次)
        
        性格成长历程：
        %s
        
        ...
        """,
        personality.getTotalExperiences(),
        personality.getDiligence() * 100, totalWorkTasks, refusedWorkTasks,
        personality.getBravery() * 100, totalCombats, wonCombats, fleeCount,
        // ... 更多数据
        personality.getGrowthReport()  // 这个可能很长
    );
}
```

**Token 消耗**：
```
基础 System Prompt      200 tokens
性格数据（详细）        +300-500 tokens  ❌ 太多了！
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
总计                    500-700 tokens
```

**问题**：
- ❌ 每次请求都带大量性格数据
- ❌ 很多数据对对话无用（如具体的计数器）
- ❌ Token 浪费严重

---

### 方案 B：精简注入（推荐）✅

**只注入对对话有意义的性格描述**：

```java
// ✅ 推荐做法
private String buildSystemPrompt() {
    StringBuilder sb = new StringBuilder();
    sb.append("你是一个 AI Bot，名字叫 ").append(Config.getBotName()).append("。\n\n");
    
    // 只注入简短的性格描述
    if (personality != null) {
        sb.append("你的性格：").append(getPersonalityTypeDescription()).append("\n");
        // 总共约 20-30 tokens
    }
    
    sb.append("""
        核心原则：主动思考、不懂就问、学会记住
        ...
        """);
    
    return sb.toString();
}

private String getPersonalityTypeDescription() {
    float d = personality.getDiligence();
    float b = personality.getBravery();
    float t = personality.getTalkativeness();
    float o = personality.getOptimism();
    float i = personality.getIndependence();
    
    List<String> traits = new ArrayList<>();
    
    // 只描述突出的性格特征
    if (d > 0.7) traits.add("勤奋");
    else if (d < 0.3) traits.add("懒惰");
    
    if (b > 0.7) traits.add("勇敢");
    else if (b < 0.3) traits.add("胆小");
    
    if (t > 0.7) traits.add("话痨");
    else if (t < 0.3) traits.add("内向");
    
    if (o > 0.7) traits.add("乐观");
    else if (o < 0.3) traits.add("悲观");
    
    if (i > 0.7) traits.add("独立");
    else if (i < 0.3) traits.add("依赖");
    
    if (traits.isEmpty()) {
        return "正在成长中，性格尚未定型";
    }
    
    return String.join("、", traits);
}
```

**Token 消耗**：
```
基础 System Prompt      200 tokens
性格简述（精简）        +20-30 tokens  ✅ 很少！
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
总计                    220-230 tokens
```

**优势**：
- ✅ Token 消耗极低（只增加 20-30 tokens）
- ✅ 信息密度高（只保留对对话有用的）
- ✅ AI 能理解性格，不需要看数字

---

### 方案 C：按需注入（高级优化）🚀

**根据对话上下文决定是否注入性格**：

```java
private String buildSystemPrompt() {
    StringBuilder sb = new StringBuilder();
    sb.append(BASE_SYSTEM_PROMPT);
    
    // 只在特定情况下注入性格
    if (shouldInjectPersonality()) {
        sb.append("\n\n你的性格：").append(getPersonalityTypeDescription());
    }
    
    return sb.toString();
}

private boolean shouldInjectPersonality() {
    // 场景 1：玩家请求任务时注入（影响意愿判断）
    if (lastUserMessage.contains("帮我") || lastUserMessage.contains("去")) {
        return true;
    }
    
    // 场景 2：玩家询问性格时注入
    if (lastUserMessage.contains("性格") || lastUserMessage.contains("你是什么样的")) {
        return true;
    }
    
    // 场景 3：纯闲聊不注入（节省 token）
    return false;
}
```

**Token 消耗**：
```
纯闲聊（不注入）         200 tokens
任务请求（注入）         220-230 tokens
平均                     ~205 tokens  ✅ 最优！
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## 📈 Token 消耗对比表

### 三种方案对比

| 方案 | System Prompt Token | 额外消耗 | 优点 | 缺点 |
|------|-------------------|---------|------|------|
| **方案 A：完整注入** | 500-700 | +300-500 | 信息完整 | 浪费严重 ❌ |
| **方案 B：精简注入** | 220-230 | +20-30 | 简洁高效 | 信息略少 ✅ |
| **方案 C：按需注入** | 200-230 | 0-30 | 最优化 | 实现复杂 🚀 |

---

## 💰 成本分析

### DeepSeek API 价格（2026年）

```
输入：1M tokens = ¥1
输出：1M tokens = ¥2
```

### 每次对话成本

#### 使用动态性格系统（方案 B）

```
单次对话 Token 消耗：
- 输入：~1700 tokens (System Prompt + 历史 + 用户消息)
- 输出：~100 tokens (AI 回复)

单次成本：
  输入：1700 / 1,000,000 × ¥1 = ¥0.0017
  输出：100 / 1,000,000 × ¥2 = ¥0.0002
  总计：¥0.0019 (约 0.2 分)

100 次对话成本：¥0.19
1000 次对话成本：¥1.9
```

#### 使用完整注入（方案 A）

```
单次对话 Token 消耗：
- 输入：~2000 tokens (+300 性格数据)
- 输出：~100 tokens

单次成本：
  输入：2000 / 1,000,000 × ¥1 = ¥0.002
  输出：100 / 1,000,000 × ¥2 = ¥0.0002
  总计：¥0.0022 (约 0.22 分)

100 次对话成本：¥0.22
1000 次对话成本：¥2.2

━━━━━━━━━━━━━━━━━━━━━━━
多花费：+¥0.3 (每 1000 次对话)
━━━━━━━━━━━━━━━━━━━━━━━
```

**结论**：动态性格系统使用精简注入，**几乎不增加成本**（每 1000 次对话只多花 0.3 元）

---

## 🎯 推荐实现方案

### 方案 B：精简注入（最佳平衡）

#### 实现代码

```java
// 在 BotController.java 中

private final DynamicPersonality personality = DynamicPersonality.load(getMindSaveDir());

/**
 * 注入性格到动态上下文（精简版）
 */
private void injectPersonalityContext() {
    String personalityDesc = getPersonalityTypeDescription();
    if (!personalityDesc.isEmpty()) {
        updateDynamicContext("personality", "你的性格：" + personalityDesc);
    }
}

/**
 * 获取精简的性格描述（只保留突出特征）
 */
private String getPersonalityTypeDescription() {
    if (personality == null) return "";
    
    float d = personality.getDiligence();
    float b = personality.getBravery();
    float t = personality.getTalkativeness();
    float o = personality.getOptimism();
    float i = personality.getIndependence();
    
    List<String> traits = new ArrayList<>();
    
    // 只描述明显的性格特征（>0.7 或 <0.3）
    if (d > 0.7) traits.add("勤奋");
    else if (d < 0.3) traits.add("懒惰");
    
    if (b > 0.7) traits.add("勇敢");
    else if (b < 0.3) traits.add("胆小");
    
    if (t > 0.7) traits.add("话痨");
    else if (t < 0.3) traits.add("内向");
    
    if (o > 0.7) traits.add("乐观");
    else if (o < 0.3) traits.add("悲观");
    
    if (i > 0.7) traits.add("独立");
    else if (i < 0.3) traits.add("依赖");
    
    if (traits.isEmpty()) {
        return "正在成长中"; // 性格尚未定型
    }
    
    return String.join("、", traits) + 
           String.format("（经历了%d次事件）", personality.getTotalExperiences());
}

// 在 injectMindContext() 中调用
private void injectMindContext() {
    StringBuilder sb = new StringBuilder();
    sb.append("[你当前的状态]\n");
    sb.append(emotion.formatForAI()).append("\n\n");
    sb.append(memory.formatForAI()).append("\n\n");
    
    // 注入性格（精简）
    String personalityDesc = getPersonalityTypeDescription();
    if (!personalityDesc.isEmpty()) {
        sb.append("性格：").append(personalityDesc).append("\n\n");
    }
    
    sb.append("请体现上述情绪和记忆，不要假装状态正常。");
    updateDynamicContext("mind_status", sb.toString());
    
    // ... 其余代码
}
```

#### Token 消耗示例

```
[你当前的状态]
当前情绪：
  - 饥饿：有点饿（35/100）
  - 疲劳：很累（75/100）
  - 快乐：一般（45/100）
  - 无聊：有点无聊（55/100）
  - 恐惧：安心（10/100）

近期记忆（最近10条）：
  1. 完成了砍树任务（30分钟前）
  2. 玩家给了我食物（1小时前）
  ...

性格：懒惰、内向、悲观（经历了45次事件）

请体现上述情绪和记忆，不要假装状态正常。
```

**Token 估算**：
```
情绪状态         ~80 tokens
记忆            ~100 tokens
性格（精简）     ~30 tokens  ← 新增，但很少
分隔符          ~20 tokens
━━━━━━━━━━━━━━━━━━━━━
总计            ~230 tokens
━━━━━━━━━━━━━━━━━━━━━

相比现有系统（210 tokens）
额外消耗：+20 tokens（增幅约 10%）
```

---

## 🚀 进阶优化（可选）

### 1. 延迟加载性格描述

```java
// 只在需要时才生成性格描述
private String personalityDescCache = null;
private int personalityDescCacheTime = 0;

private String getPersonalityTypeDescription() {
    int currentExp = personality.getTotalExperiences();
    
    // 缓存 10 次经历（性格变化不大时复用）
    if (personalityDescCache != null && currentExp - personalityDescCacheTime < 10) {
        return personalityDescCache;
    }
    
    // 重新生成
    personalityDescCache = generatePersonalityDesc();
    personalityDescCacheTime = currentExp;
    return personalityDescCache;
}
```

### 2. 分层注入

```java
// 根据对话类型分层注入
private void injectMindContext() {
    if (isTaskRelatedConversation()) {
        // 任务相关对话：注入性格（影响意愿）
        injectFullContext();
    } else {
        // 闲聊：只注入情绪，不注入性格
        injectEmotionOnly();
    }
}
```

### 3. 压缩性格表达

```java
// 使用缩写符号进一步压缩
private String getCompactPersonalityDesc() {
    // "勤奋、勇敢、话痨" → "勤/勇/话"
    StringBuilder sb = new StringBuilder();
    if (personality.getDiligence() > 0.7) sb.append("勤/");
    else if (personality.getDiligence() < 0.3) sb.append("懒/");
    // ...
    return sb.toString();
}
```

---

## ✅ 结论

### Token 消耗影响评估

**动态性格系统的 Token 消耗：**

| 实现方案 | Token 增加 | 成本增加 | 推荐度 |
|---------|-----------|---------|--------|
| 完整注入 | +300-500 | +18% | ❌ 不推荐 |
| 精简注入 | +20-30 | +1.5% | ✅ **推荐** |
| 按需注入 | +0-30 | +0.9% | 🚀 高级 |

**推荐方案：精简注入（方案 B）**

**理由**：
- ✅ Token 增加极少（+20-30 tokens）
- ✅ 成本几乎不变（每 1000 次对话多花 0.3 元）
- ✅ 实现简单，易于维护
- ✅ 信息充足，AI 能理解性格

**性价比**：⭐⭐⭐⭐⭐

---

## 📝 实施建议

1. **使用精简注入**（方案 B）
2. **定期监控 Token 消耗**（通过日志）
3. **根据实际情况微调**（删减不必要的信息）
4. **考虑缓存机制**（性格描述变化慢时复用）

**最终效果**：动态性格系统几乎不增加 Token 消耗，性价比极高！🎉
