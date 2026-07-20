# AI Bot Mod - 更新日志

## v1.0.4 - ActionCoordinator 总控 & 架构统一

### 新增
- **ActionCoordinator（统一动作总控）**：新建 `entity/actions/ActionCoordinator.java`，替代两套动作系统互相打架的局面
- **标准 BotAction 生命周期**：`start() → tick() → 完成/失败/stop()`，每 tick 返回 boolean 表示完成

### 变更
- **BotAction 接口改造**：`tick()` 改为返回 `boolean`（true=已完成），新增 `start(AiBotEntity)` 和 `stop(AiBotEntity)`
- **6 个 BotAction 实现全部适配**：ChopTreeAction、MineTunnelAction、FarmAction、SleepAction、FollowAction、GotoAction
- **AiBotEntity 集成 coordinator**：字段、注册表（`initCoordinator()`）、绑定到 AiBotActionGoal
- **processCommand 迁移**：砍树/挖矿/种地/睡觉/跟随/回到家命令走 coordinator
- **executeTask 迁移**：chop/mine/farm/sleep 任务通过 coordinator 执行
- **clearActionState()** 增加 `coordinator.stop()`，防止状态残留
- **AgentBrain 集成 coordinator**：`executeStep()` 改用 `receiveCommand()` 走 coordinator 统一调度，移除废弃的 `createAction()` 方法

### 修复
- **isOreBlock 补充深板岩变种**：增加 DEEPSLATE_* 系列（8个）、NETHER_GOLD_ORE、NETHER_QUARTZ_ORE
- **AiBotActionGoal 精简**：移除已迁移的 CHOP_TREE/MINE/FARM/SLEEP/GOTO/FOLLOW 6 个 case
- **coordinator 完成后 Goal 空转**：`AiBotActionGoal.tick()` 中 coordinator 动作完成且无 taskList 时清空 `currentAction`
- **isIdle() 忽略 coordinator**：增加 `coordinator.isIdle()` 检查，防止 AgentBrain 在动作运行时做出冲突决策

### 修改的文件
```
新增:
├── entity/actions/ActionCoordinator.java
修改:
├── entity/actions/BotAction.java        # 接口改造
├── entity/actions/ChopTreeAction.java   # 适配新接口
├── entity/actions/MineTunnelAction.java # 适配新接口
├── entity/actions/FarmAction.java       # 适配新接口
├── entity/actions/SleepAction.java      # 适配新接口
├── entity/actions/FollowAction.java     # 适配新接口
├── entity/actions/GotoAction.java       # 适配新接口
├── entity/AiBotEntity.java             # 集成coordinator + 注册表 + 修复Bug
├── mind/AgentBrain.java                # 集成coordinator，移除 createAction
└── CHANGELOG.md
```

## v1.0.3 - 心智系统重构 & 计划系统

### 新增功能

#### 心智系统（mind/）
- **AgentBrain**：心智系统核心，负责高层决策、目标管理、事件处理
- **Goal System**：多层目标体系（生存本能/长期目标/个性目标/社交目标）
- **Personality**：性格系统（勤奋度、勇敢度、话痨度等参数，支持预设）
- **Emotion**：情绪系统（饥饿、疲劳、快乐、恐惧、无聊，随时间变化）
- **EpisodicMemory**：事件记忆系统（记录重要经历并影响决策）

#### 策略模式重构（entity/actions/）
- **BotAction** 接口：定义动作标准
- **ChopTreeAction**：砍树（含偷懒行为）
- **MineTunnelAction**：挖矿（自动扫矿优先挖掘）
- **FarmAction**：种地（自动收割/种植）
- **SleepAction**：睡觉（夜间自动找床）
- **FollowAction**：跟随玩家
- **GotoAction**：移动到指定坐标

#### LLM 计划系统
- **askStandalone**：独立LLM调用，不污染对话历史
- **planRequestContext**：AgentBrain 空闲时自动触发计划生成
- **背包感知**：计划上下文包含背包物品信息，避免过度工作
- **ClientTickEvent 轮询**：BotController 每10秒检查计划请求

#### 配置文件扩展
- `personality_preset`：性格预设（balanced/lazy/workaholic/adventurer/shy）
- `human_like_level`：人性化程度（0-100%）
- 性格参数配置（diligence/bravery/talkativeness）

### 变更
- **移除自动收割 & 自动打猎**：保留命令触发功能，移除自动化循环逻辑
- **保留自动存箱**：背包快满时自动存入附近箱子
- **LLM 计划系统成为唯一自动化方式**

### 修复
- **主动聊天频率过高**：增加检查间隔（60tick）、延长冷却（30秒）、降低概率（2.5%）
- **白天睡觉Bug**：Split 睡觉/休息条件，白天不会尝试睡觉
- **自动模式移除**：不再支持开关，AI默认自动运行
- **挖错方向删除**：移除 MineTunnelAction 的"挖错方向"逻辑
- **无用字段清理**：删除 ChopTreeAction 的 unused humanErrorChance 字段

### 修改的文件
```
src/main/java/com/aibot/mod/
├── mind/
│   ├── AgentBrain.java        # 新增
│   ├── Goal.java              # 新增
│   ├── Personality.java       # 新增
│   ├── Emotion.java           # 新增
│   └── EpisodicMemory.java    # 新增
├── entity/actions/
│   ├── BotAction.java         # 新增
│   ├── ChopTreeAction.java    # 新增
│   ├── MineTunnelAction.java  # 新增
│   ├── FarmAction.java        # 新增
│   ├── SleepAction.java       # 新增
│   ├── FollowAction.java      # 新增
│   └── GotoAction.java        # 新增
├── entity/
│   └── AiBotEntity.java       # 重构：集成AgentBrain + BotAction
├── Config.java                # 新增性格/人性化配置
├── BotController.java         # 新增LLM计划生成
├── DeepSeekClient.java        # 新增 askStandalone
├── AiBotMod.java              # 注册BotController事件
└── client/
    └── AiBotSettingsScreen.java # 移除自动模式开关
```

## v1.0.2 - 基础功能完善

### 功能
- 聊天对话（DeepSeek LLM 驱动）
- 基础工具调用（砍树、挖矿、种地、睡觉、跟随）
- 全量虚空合成（读取 Minecraft RecipeManager 所有配方）
- 烹饪系统（支持锅/炖锅/砧板，自动找炉灶）
- 配方扫描（RecipeScanner）
- 知识库（KnowledgeBase）
- 物品翻译（TranslationDictionary）
- 教学录制/回放
- 脚本系统（ScriptEngine）
- HudOverlay（显示Bot状态）
- 设置界面（AiBotSettingsScreen）

### 架构
- 客户端-服务端分离
- BotController 消息队列处理
- 状态机驱动动作执行
