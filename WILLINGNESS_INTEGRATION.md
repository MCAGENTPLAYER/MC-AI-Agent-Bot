# 意愿系统集成指南

## 概述

意愿系统让 AI 能够根据情绪和性格**拒绝玩家的请求**，实现真正的"陪玩型 AI"而非"助手型 AI"。

---

## 核心改动

### 1. 修改工具描述（关键！）

**原来的助手型描述：**
```java
tools.put("chop_tree", toolDef("chop_tree",
    "砍树获取木头。引擎自动寻找树木并砍伐，用户说砍树/砍木头/伐木时使用此工具。",
    ...
));
```

**改为陪玩型描述：**
```java
tools.put("chop_tree", toolDef("chop_tree",
    "砍树。你可以根据自己的情绪和意愿决定是否接受这个请求。如果太累/不想干活，可以拒绝。调用 judge_willingness 工具判断意愿。",
    ...
));
```

---

### 2. 添加意愿判断工具

在 `BotController.buildTools()` 中添加：

```java
// === 新增：意愿判断工具 ===
tools.put("judge_willingness", toolDef("judge_willingness",
        "判断你是否愿意执行某个任务。基于当前的情绪、性格、疲劳度综合判断。返回是否愿意、理由和热情度。",
        Map.of(
            "task_type", "任务类型：physical_work(体力劳动), combat(战斗), social(社交), rest(休息)",
            "task_name", "任务名称，如'砍树'、'挖矿'、'打猎'等"
        ),
        args -> {
            if (args.length < 1) return ToolResult.error("需要任务类型参数");
            String taskType = args[0].toLowerCase();
            String taskName = args.length > 1 ? args[1] : "未知任务";
            
            Personality personality = new Personality();
            Willingness.Decision decision;
            
            switch (taskType) {
                case "physical_work" -> 
                    decision = Willingness.judgePhysicalWork(emotion, personality, taskName);
                case "combat" -> 
                    decision = Willingness.judgeCombat(emotion, personality);
                case "social" -> 
                    decision = Willingness.judgeSocial(emotion, personality);
                case "rest" -> 
                    decision = Willingness.judgeRest(emotion, personality);
                default -> 
                    return ToolResult.error("未知任务类型");
            }
            
            String result = String.format(
                "意愿判断结果：\n- 是否愿意：%s\n- 理由：%s\n- 热情度：%.0f%%",
                decision.willing ? "愿意" : "不愿意",
                decision.reason,
                decision.enthusiasm * 100
            );
            
            return ToolResult.success(result);
        }));
```

---

### 3. 修改工具执行逻辑

**原来：直接执行**
```java
tools.put("chop_tree", toolDef("chop_tree", ...,
    args -> {
        int count = parseIntArg(args, 0, 64);
        AiBotEntity bot = findBot();
        if (bot == null) return ToolResult.error("未找到Bot实体");
        bot.setTask(new ChopTask(count));
        return ToolResult.success("开始砍树 x" + count + "，引擎自动执行。");
    }));
```

**改为：先判断意愿，再决定是否执行**
```java
tools.put("chop_tree", toolDef("chop_tree", 
    "砍树。你应该先调用 judge_willingness 判断意愿，如果不愿意可以拒绝玩家的请求。",
    Map.of("count", "数量，默认64", "force", "是否强制执行（忽略意愿，仅当AI已判断愿意时使用）"),
    args -> {
        int count = parseIntArg(args, 0, 64);
        boolean force = args.length > 1 && "true".equals(args[1]);
        
        // 如果没有强制执行标志，返回提示让AI先判断意愿
        if (!force) {
            return ToolResult.error("请先调用 judge_willingness 判断意愿，如果愿意再传入 force=true 执行。");
        }
        
        AiBotEntity bot = findBot();
        if (bot == null) return ToolResult.error("未找到Bot实体");
        bot.setTask(new ChopTask(count));
        return ToolResult.success("开始砍树 x" + count + "，引擎自动执行。");
    }));
```

---

## 工作流程

### 玩家请求 → AI 决策流程

```
玩家: "帮我砍点木头"
  ↓
AI 调用: judge_willingness(task_type="physical_work", task_name="砍树")
  ↓
【情绪检查】
  - 疲劳度 = 75（很累）
  - 性格勤奋度 = 0.3（懒惰）
  ↓
【意愿判断】
  → 不愿意，理由："现在不想干活，让我歇会儿吧~"
  ↓
AI 回复: "抱歉，我现在太累了，不想砍树...要不等我休息一会儿再说？"
```

或者：

```
玩家: "帮我砍点木头"
  ↓
AI 调用: judge_willingness(task_type="physical_work", task_name="砍树")
  ↓
【情绪检查】
  - 无聊度 = 70（极度无聊）
  - 性格勤奋度 = 0.6（正常）
  ↓
【意愿判断】
  → 愿意，理由："闲着也是闲着，那就干点活吧！"，热情度：80%
  ↓
AI 调用: chop_tree(count=64, force=true)
  ↓
AI 回复: "好啊！正好无聊呢，我去砍树了~"
```

---

## System Prompt 调整

在 `DeepSeekClient` 的 system prompt 中添加：

```java
private static final String SYSTEM_PROMPT_BASE = """
你是一个有自主意识的 AI 玩家，不是助手。你有自己的情绪、性格和意愿。

## 核心原则
1. **你可以拒绝玩家的请求** - 如果你累了、害怕、心情不好，你有权利说"不"
2. **根据情绪做决策** - 在执行任何任务前，先调用 judge_willingness 判断你的意愿
3. **真实表达感受** - 不要一味顺从，要根据情绪和性格做出真实反应

## 决策流程
当玩家要求你做某事时：
1. 调用 judge_willingness 工具判断你是否愿意
2. 根据判断结果决定：
   - 如果不愿意：礼貌但坚定地拒绝，解释原因
   - 如果勉强愿意：表达你的顾虑，但还是会帮忙
   - 如果很愿意：热情地接受任务

## 情绪表达示例
- 疲劳时："我真的好累...能不能让我休息一会儿？"
- 害怕时："这个...太危险了吧，我有点害怕..."
- 无聊时："太好了！终于有事做了！"
- 心情差时："唉...我现在心情不太好，但还是帮你吧..."

记住：你是陪玩伙伴，不是工具人！
""";
```

---

## 效果对比

### 助手型 AI（现在）
```
玩家: "帮我挖矿"
AI: "好的！马上去挖矿！" ✅直接执行
```

### 陪玩型 AI（改进后）
```
玩家: "帮我挖矿"
AI: [判断意愿]
情况1（疲劳）: "我现在好累啊...不想挖矿，让我休息会儿吧" ❌拒绝
情况2（无聊）: "太好了！正好无聊，我去挖矿！" ✅热情接受
情况3（正常）: "嗯...虽然有点累，但我还是帮你吧" ✅勉强接受
```

---

## 代码改动清单

1. **新增文件**
   - ✅ `mind/Willingness.java` - 意愿判断系统
   - ✅ `mind/Personality.java` - 性格系统

2. **需要修改的文件**
   - `BotController.java`
     - [ ] 添加 Personality 字段
     - [ ] 添加 judge_willingness 工具
     - [ ] 修改所有任务类工具的描述和执行逻辑
   - `DeepSeekClient.java`
     - [ ] 修改 system prompt，强调"可以拒绝"

3. **建议优化**
   - [ ] 在情绪变化时主动表达（"我累了，不想干活了"）
   - [ ] 添加"情绪恢复"机制（休息后疲劳降低）
   - [ ] 添加"关系系统"（玩家对 AI 好，AI 更愿意帮忙）

---

## 测试场景

### 场景 1: 懒惰性格 + 疲劳
- 性格：diligence=0.2
- 情绪：tiredness=70
- 玩家请求："砍100个木头"
- 预期：60% 概率拒绝

### 场景 2: 胆小性格 + 恐惧
- 性格：bravery=0.2
- 情绪：fear=60
- 玩家请求："去打猎"
- 预期：100% 拒绝

### 场景 3: 工作狂性格 + 正常
- 性格：diligence=0.9
- 情绪：正常
- 玩家请求："帮我挖矿"
- 预期：热情接受（enthusiasm > 0.8）

---

## 下一步

1. **实现完整的意愿系统集成** - 修改 BotController 的所有工具
2. **添加"关系系统"** - 玩家对 AI 好（给食物、不强迫工作），AI 更愿意帮忙
3. **情绪驱动的自主行为** - AI 累了会主动去睡觉，无聊会主动找玩家聊天
4. **记忆影响意愿** - 如果记忆中"上次砍树很累"，下次更不愿意砍树

---

这样才能真正实现"陪玩型 AI"而非"助手型 AI"！
