package com.aibot.mod;

import com.aibot.mod.agent.Tool;
import com.aibot.mod.agent.ToolResult;
import com.aibot.mod.script.ScriptEngine;
import com.aibot.mod.script.ScriptRunner;
import com.aibot.mod.mind.Emotion;
import com.aibot.mod.mind.EpisodicMemory;
import com.aibot.mod.mind.DynamicPersonality;
import com.aibot.mod.ai.AIProvider;
import com.aibot.mod.ai.LocalAIProvider;
import com.aibot.mod.ai.RemoteAIProvider;
import com.aibot.mod.network.AiServerClient;
import com.aibot.mod.network.AiServerClient.ServerAction;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.Logger;

import com.aibot.mod.task.BaseTask;
import com.aibot.mod.task.ChopTask;
import com.aibot.mod.task.CookTask;
import com.aibot.mod.task.CraftTask;
import com.aibot.mod.task.EatTask;
import com.aibot.mod.task.FarmTask;
import com.aibot.mod.task.FollowTask;
import com.aibot.mod.task.GotoTask;
import com.aibot.mod.task.HuntTask;
import com.aibot.mod.task.InteractTask;
import com.aibot.mod.task.MineTask;
import com.aibot.mod.task.SleepTask;
import com.aibot.mod.task.TaskChain;
import com.aibot.mod.task.TaskChainExecutor;
import com.aibot.mod.task.TaskQuestSystem;
import com.aibot.mod.entity.AiBotEntity;
import com.aibot.mod.entity.Task;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Path;
import java.util.function.Function;

public class BotController {
    private static final Logger LOGGER = AiBotMod.LOGGER;
    private static final long AI_TIMEOUT_MS = 30000;
    private static final int MAX_QUEUE_SIZE = 20;
    /** Agent 循环最大迭代次数，防止无限工具调用递归 */
    private static final int MAX_AGENT_ITERATIONS = 8;

    /** 消息队列链式处理：保证每条消息的完整 agent 循环结束后才处理下一条 */
    private CompletableFuture<Void> processingChain = CompletableFuture.completedFuture(null);

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final BlockingQueue<ChatMessage> messageQueue = new LinkedBlockingQueue<>();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AI-Bot-Processor");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean isProcessing = false;
    private ChatHandler chatHandler;

    private final Emotion emotion = Emotion.load(getMindSaveDir());
    private final EpisodicMemory memory = EpisodicMemory.load(getMindSaveDir());
    private final DynamicPersonality personality = DynamicPersonality.load(getMindSaveDir());
    /** 自动情绪检查冷却计时器（tick 计数） */
    private int autoEmotionTick = 0;
    /** 自动情绪全局冷却：最近 30 秒内已触发过主动说话就不再触发 */
    private long lastAutoEmotionTime = 0;
    private static final long AUTO_EMOTION_COOLDOWN_MS = 30000;
    
    /** 性格自动保存计时器（每分钟保存一次） */
    private int personalitySaveTick = 0;
    private static final int PERSONALITY_SAVE_INTERVAL = 1200; // 60秒 = 1200 ticks

    /** 任务系统 */
    private final TaskQuestSystem taskQuestSystem = new TaskQuestSystem();

    /** 任务链执行引擎 */
    private final TaskChainExecutor chainExecutor = new TaskChainExecutor(getMindSaveDir());

    /** AI 提供器（支持内置和外接两种模式） */
    private AIProvider aiProvider;
    private LocalAIProvider localAIProvider;
    private RemoteAIProvider remoteAIProvider;
    
    /** 连接重试相关 */
    private int connectionRetryTicks = 0;
    private static final int RETRY_INTERVAL_TICKS = 100; // 5秒 = 100 ticks
    private boolean hasShownConnectionError = false;

    public BotController() {
        TranslationDictionary.init();
        buildTools();
        taskQuestSystem.load();
        initAIProviders();
        switchAIMode(Config.getAiMode());
    }

    private void initAIProviders() {
        localAIProvider = new LocalAIProvider(new ArrayList<>(tools.values()));
        
        remoteAIProvider = new RemoteAIProvider();
    }

    private void switchAIMode(String mode) {
        if ("remote".equalsIgnoreCase(mode)) {
            if (aiProvider != null) {
                aiProvider.disconnect();
            }
            aiProvider = remoteAIProvider;
            remoteAIProvider.connect();
            LOGGER.info("[AI Bot] 已切换到外接模式，正在连接后端服务器: {}", Config.getServerUrl());
        } else {
            if (aiProvider != null) {
                aiProvider.disconnect();
            }
            aiProvider = localAIProvider;
            LOGGER.info("[AI Bot] 已切换到内置模式，直接调用DeepSeek API");
        }
    }

    /**
     * 客户端 tick 事件驱动任务链执行。
     * 每 tick 调用一次 TaskChainExecutor，驱动链中的步骤按顺序执行。
     */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (chainExecutor.hasActiveChain()) {
            AiBotEntity bot = findBot();
            if (bot != null) {
                chainExecutor.tick(bot);
            }
        }
        
        // 外接模式：处理服务器连接和重连
        if ("remote".equalsIgnoreCase(Config.getAiMode()) && remoteAIProvider != null) {
            remoteAIProvider.tick();
            
            if (!remoteAIProvider.isConnected()) {
                connectionRetryTicks++;
                if (connectionRetryTicks >= RETRY_INTERVAL_TICKS) {
                    connectionRetryTicks = 0;
                    if (!hasShownConnectionError) {
                        sayToPublic("§c[AI Bot] 连接后端失败，5秒后自动重试...");
                        hasShownConnectionError = true;
                    }
                    remoteAIProvider.connect();
                    LOGGER.warn("[AI Bot] 尝试重新连接后端服务器...");
                }
            } else {
                if (hasShownConnectionError) {
                    sayToPublic("§a[AI Bot] 已成功连接到后端服务器！");
                    hasShownConnectionError = false;
                }
                connectionRetryTicks = 0;
            }
        }
    }

    private Path getMindSaveDir() {
        return Path.of(net.minecraft.client.Minecraft.getInstance().gameDirectory.getAbsolutePath(), "ai_bot", "mind");
    }

    private void buildTools() {
        // === 引擎任务（统一通过 Task 系统执行） ===
        tools.put("chop_tree", toolDef("chop_tree",
                "砍树获取木头。引擎自动寻找树木并砍伐，用户说砍树/砍木头/伐木时使用此工具。",
                Map.of("count", "数量，默认64"),
                args -> {
                    int count = parseIntArg(args, 0, 64);
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    bot.setTask(new ChopTask(count));
                    return ToolResult.success("开始砍树 x" + count + "，引擎自动执行。");
                }));

        tools.put("mine_block", toolDef("mine_block",
                "挖掘矿石/石头等方块。引擎自动执行，用户说挖矿/挖掘时使用此工具。",
                Map.of("count", "数量，默认64"),
                args -> {
                    int count = parseIntArg(args, 0, 64);
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    bot.setTask(new MineTask(count, "mine"));
                    return ToolResult.success("开始挖掘 x" + count + "，引擎自动执行。");
                }));

        tools.put("follow", toolDef("follow",
                "让AI Bot跟随你或移动到玩家身边。引擎自动执行，玩家说'过来'/'来'/'跟'等指令时使用此工具。",
                Map.of(),
                args -> {
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    bot.setTask(new FollowTask());
                    return ToolResult.success("开始跟随玩家，引擎自动执行。");
                }));

        tools.put("goto", toolDef("goto",
                "让AI Bot移动到指定坐标。",
                Map.of("x", "X坐标", "y", "Y坐标", "z", "Z坐标"),
                args -> {
                    if (args.length < 3) return ToolResult.error("需要 x y z 三个坐标参数");
                    try {
                        double x = Double.parseDouble(args[0]);
                        double y = Double.parseDouble(args[1]);
                        double z = Double.parseDouble(args[2]);
                        AiBotEntity bot = findBot();
                        if (bot == null) return ToolResult.error("未找到Bot实体");
                        bot.setTask(new GotoTask(x, y, z));
                        return ToolResult.success("开始移动到目标位置，引擎自动执行。");
                    } catch (NumberFormatException e) {
                        return ToolResult.error("坐标参数无效");
                    }
                }));

        tools.put("stop", toolDef("stop",
                "停止AI Bot当前正在执行的所有动作。用户说停止/停下时使用此工具。",
                Map.of(),
                args -> {
                    sendBotCmd("stop");
                    return ToolResult.success("已向AI Bot发送停止命令");
                }));

        tools.put("teleport_to_player", toolDef("teleport_to_player",
                "让AI Bot直接传送到你身边。支持跨维度传送（主世界/下界/末地）。用户说传送/过来/到我身边时使用此工具。",
                Map.of(),
                args -> {
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    
                    net.minecraft.world.entity.LivingEntity target = bot.findNearestPlayer();
                    if (target == null) return ToolResult.error("附近没有玩家");
                    
                    // 跨维度传送
                    var currentDim = bot.level().dimension();
                    var targetDim = target.level().dimension();
                    
                    if (currentDim != targetDim) {
                        // 下界↔主世界坐标转换
                        double tx = target.getX();
                        double tz = target.getZ();
                        if (currentDim == net.minecraft.world.level.Level.NETHER && 
                            targetDim == net.minecraft.world.level.Level.OVERWORLD) {
                            tx *= 8; tz *= 8;
                        } else if (currentDim == net.minecraft.world.level.Level.OVERWORLD && 
                                   targetDim == net.minecraft.world.level.Level.NETHER) {
                            tx /= 8; tz /= 8;
                        }
                        if (bot.getServer() != null) {
                            var targetLevel = bot.getServer().getLevel(targetDim);
                            if (targetLevel != null) {
                                 bot.teleportTo(targetLevel, tx, target.getY(), tz, java.util.Set.of(), bot.getYRot(), bot.getXRot());
                                 return ToolResult.success("已跨维度传送至玩家身边");
                            }
                        }
                    }
                    
                    bot.teleportTo(target.getX(), target.getY(), target.getZ());
                    return ToolResult.success("已传送至玩家身边");
                }));

        // === 意愿判断工具（动态性格系统） ===
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
                    
                    com.aibot.mod.mind.Willingness.Decision decision;
                    
                    switch (taskType) {
                        case "physical_work":
                            decision = com.aibot.mod.mind.Willingness.judgePhysicalWork(emotion, personality, taskName);
                            break;
                        case "combat":
                            decision = com.aibot.mod.mind.Willingness.judgeCombat(emotion, personality);
                            break;
                        case "social":
                            decision = com.aibot.mod.mind.Willingness.judgeSocial(emotion, personality);
                            break;
                        case "rest":
                            decision = com.aibot.mod.mind.Willingness.judgeRest(emotion, personality);
                            break;
                        default:
                            return ToolResult.error("未知任务类型，可用：physical_work, combat, social, rest");
                    }
                    
                    String result = String.format(
                        "意愿判断结果：\n- 是否愿意：%s\n- 理由：%s\n- 热情度：%.0f%%",
                        decision.willing ? "愿意" : "不愿意",
                        decision.reason,
                        decision.enthusiasm * 100
                    );
                    
                    return ToolResult.success(result);
                }));

        // === 任务链系统（核心） ===
        tools.put("plan_tasks", toolDef("plan_tasks",
                "【核心】创建并执行一个多步骤任务链。用于需要按顺序做多件事的场景，如：合成多个物品后传送到玩家身边、先挖矿后合成装备、\n"
                + "多步合成（先合成木板，再合成木棍，再合成木镐）等。\n"
                + "参数格式：name=任务名称;steps=[步骤JSON数组]\n"
                + "步骤类型说明：\n"
                + "  CRAFT: 合成物品 {\"type\":\"CRAFT\",\"params\":{\"item\":\"minecraft:iron_pickaxe\",\"count\":1}}\n"
                + "  MINE: 挖掘 {\"type\":\"MINE\",\"params\":{\"mode\":\"mine\",\"count\":64}}\n"
                + "  CHOP: 砍树 {\"type\":\"CHOP\",\"params\":{\"count\":16}}\n"
                + "  GOTO: 移动 {\"type\":\"GOTO\",\"params\":{\"x\":\"0\",\"y\":\"64\",\"z\":\"0\"}}\n"
                + "  TELEPORT: 传送到玩家身边 {\"type\":\"TELEPORT\",\"params\":{}}\n"
                + "  GIVE: 给玩家物品 {\"type\":\"GIVE\",\"params\":{\"item\":\"minecraft:iron_ingot\",\"count\":3}}\n"
                + "  FOLLOW: 跟随 {\"type\":\"FOLLOW\",\"params\":{}}\n"
                + "  SLEEP: 睡觉 {\"type\":\"SLEEP\",\"params\":{}}\n"
                + "  EAT: 进食 {\"type\":\"EAT\",\"params\":{}}\n"
                + "  WAIT: 等待 {\"type\":\"WAIT\",\"params\":{\"ticks\":\"20\"}}\n\n"
                + "示例：\"name=给我物品;steps=[{\\\"type\\\":\\\"CRAFT\\\",\\\"params\\\":{\\\"item\\\":\\\"minecraft:iron_pickaxe\\\",\\\"count\\\":1}},"
                + "{\\\"type\\\":\\\"TELEPORT\\\",\\\"params\\\":{}},"
                + "{\\\"type\\\":\\\"GIVE\\\",\\\"params\\\":{\\\"item\\\":\\\"minecraft:iron_pickaxe\\\",\\\"count\\\":1}}]\"",
                Map.of(
                    "name", "任务名称，如\"给我铁镐和铁甲\"",
                    "steps", "JSON数组，定义每个步骤"
                ),
                args -> {
                    if (args.length < 2) return ToolResult.error("需要 name 和 steps 参数");
                    String name = args[0];
                    String stepsJson = args[1];
                    
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    
                    try {
                        var stepsArray = com.google.gson.JsonParser.parseString(stepsJson).getAsJsonArray();
                        TaskChain chain = new TaskChain().setName(name);
                        
                        for (var element : stepsArray) {
                            var obj = element.getAsJsonObject();
                            String typeStr = obj.get("type").getAsString();
                            var paramsObj = obj.getAsJsonObject("params");
                            java.util.Map<String, String> params = new java.util.HashMap<>();
                            for (var key : paramsObj.keySet()) {
                                params.put(key, paramsObj.get(key).getAsString());
                            }
                            TaskChain.StepType type = TaskChain.StepType.valueOf(typeStr);
                            chain.addStep(new TaskChain.Step(type, params));
                        }
                        
                        chainExecutor.startChain(chain, bot);
                        return ToolResult.success("已创建任务链: " + name + "（共" + chain.getSteps().size() + "步）");
                    } catch (Exception e) {
                        AiBotMod.LOGGER.error("[BotController] plan_tasks 解析失败: {}", e.getMessage());
                        return ToolResult.error("任务链格式错误: " + e.getMessage());
                    }
                }));

        tools.put("check_chain", toolDef("check_chain",
                "查看当前任务链的执行进度和状态。",
                Map.of(),
                args -> {
                    if (!chainExecutor.hasActiveChain()) return ToolResult.success("当前没有正在执行的任务链");
                    String progress = chainExecutor.getChainProgressText();
                    return ToolResult.success(progress);
                }));

        tools.put("cancel_chain", toolDef("cancel_chain",
                "取消当前正在执行的任务链。AI 主动放弃任务链时或者用户说取消任务时使用。",
                Map.of(),
                args -> {
                    if (!chainExecutor.hasActiveChain()) return ToolResult.success("当前没有任务链需要取消");
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    chainExecutor.cancelChain(bot);
                    return ToolResult.success("已取消当前任务链");
                }));

        tools.put("skip_step", toolDef("skip_step",
                "跳过任务链中的当前步骤。如果某步骤失败或不需要继续执行时使用。",
                Map.of(),
                args -> {
                    if (!chainExecutor.hasActiveChain()) return ToolResult.success("当前没有正在执行的任务链");
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    chainExecutor.skipCurrentStep(bot);
                    return ToolResult.success("已跳过当前步骤");
                }));

        tools.put("resume_chain", toolDef("resume_chain",
                "恢复执行已失败的任务链，从失败步骤重新尝试。当任务链因某步骤失败而中断后，玩家说重试/继续时使用此工具。",
                Map.of(),
                args -> {
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    chainExecutor.resumeChain(bot);
                    return ToolResult.success("已恢复任务链执行");
                }));

        tools.put("check_connection", toolDef("check_connection",
                "检查AI服务连接状态。",
                Map.of(),
                args -> {
                    boolean connected = aiProvider != null && aiProvider.isConnected();
                    String mode = "remote".equalsIgnoreCase(Config.getAiMode()) ? "外接模式" : "内置模式";
                    return ToolResult.success("连接状态: " + (connected ? "已连接" : "未连接") + " | 模式: " + mode);
                }));

        tools.put("farm", toolDef("farm",
                "种地/ farming。引擎自动寻找附近32格内的耕地进行种植或收割。",
                Map.of(),
                args -> {
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    bot.setTask(new FarmTask());
                    return ToolResult.success("开始种地，引擎自动执行。");
                }));

        tools.put("sleep", toolDef("sleep",
                "睡觉。引擎自动寻找附近32格内的床并躺下睡觉。",
                Map.of(),
                args -> {
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    bot.setTask(new SleepTask());
                    return ToolResult.success("开始睡觉，引擎自动执行。");
                }));

        tools.put("plan", toolDef("plan",
                "任务规划。直接输出JSON任务列表让Bot按顺序自动执行多个任务。适用于需要精确指定任务内容的场景。AI自主生成JSON格式的任务列表。",
                Map.of("json", "JSON格式的任务列表数组，如[{\"type\":\"chop\",\"count\":16,\"desc\":\"砍16个木头\"}]"),
                args -> {
                    if (args.length < 1) return ToolResult.error("需要JSON参数");
                    String json = String.join(" ", args);
                    sendBotCmd("plan " + json);
                    return ToolResult.success("已向AI Bot发送任务规划");
                }));

        tools.put("auto_plan", toolDef("auto_plan",
                "自动生成任务计划。引擎会根据Bot当前的生存状态（时间、位置等）自动生成一个合理的任务列表并执行。玩家说'做个计划'/'规划一下'/'想想干什么'时使用此工具。会清除当前正在执行的任务。",
                Map.of(),
                args -> {
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    sendBotCmd("plan_now"); // 先清除当前任务
                    generatePlanAsync();      // 异步生成并执行新计划
                    return ToolResult.success("正在生成计划...");
                }));

        tools.put("plan_now", toolDef("plan_now",
                "清除Bot当前所有任务，回到空闲状态。玩家说'停止'/'停下'时也可用。注意：这个工具只是清除任务，不会生成新计划。需要新计划请用 auto_plan。",
                Map.of(),
                args -> {
                    sendBotCmd("plan_now");
                    return ToolResult.success("已清除所有任务");
                }));

        tools.put("eat", toolDef("eat",
                "吃东西。从背包中找到食物并吃掉回血。",
                Map.of(),
                args -> {
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    bot.setTask(new EatTask());
                    return ToolResult.success("开始吃东西，引擎自动执行。");
                }));

        // === 合成引擎（CraftTask，自动解析配方+收集材料+执行合成） ===
        tools.put("craft_item", toolDef("craft_item",
                "合成物品。引擎会自动检查背包和附近箱子找材料、解析配方树逐级合成。用户说合成/做/制造时使用此工具。\n注意：item 参数请使用英文注册名，如 minecraft:stone_pickaxe 或 stone_pickaxe，不要用中文名。",
                Map.of("item", "物品注册名，如 minecraft:stone_pickaxe", "count", "数量，默认1"),
                args -> {
                    if (args.length < 1) return ToolResult.error("需要指定物品名称");
                    String item = args[0];
                    int count = parseIntArg(args, 1, 1);
                    if (!item.contains(":")) item = "minecraft:" + item;
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    bot.setTask(new CraftTask(item, count));
                    return ToolResult.success("开始合成 " + item + " x" + count + "，引擎自动执行。");
                }));

        // === 交互（对任意方块/实体执行右键操作） ===
        tools.put("interact_block", toolDef("interact_block",
                "对指定的方块或实体执行右键操作。比如给花瓶插花、把物品放盔甲架上、按按钮、拉拉杆、喂动物等。\n注意：target 和 item 参数请使用英文注册名。",
                Map.of("target", "目标方块注册名或实体名，如 flower_pot", "item", "手持物品注册名（可选），如 dandelion"),
                args -> {
                    if (args.length < 1) return ToolResult.error("需要指定目标");
                    String target = args[0];
                    String item = args.length >= 2 ? args[1] : null;
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    bot.setTask(new InteractTask(target, item));
                    return ToolResult.success("开始交互 " + target + (item != null ? " + " + item : "") + "，引擎自动执行。");
                }));

        // === 家 & 设置（仍走命令转发） ===
        tools.put("set_home", toolDef("set_home",
                "设置AI Bot的家坐标。用户说设家/设置家/回家时使用。",
                Map.of("x", "X坐标（不填则设为当前位置）", "y", "Y坐标", "z", "Z坐标"),
                args -> {
                    if (args.length >= 3) {
                        sendBotCmd("sethome " + args[0] + " " + args[1] + " " + args[2]);
                    } else {
                        sendBotCmd("sethome");
                    }
                    return ToolResult.success("已设置Bot的家坐标");
                }));

        tools.put("home_mode", toolDef("home_mode",
                "切换AI Bot的住家模式开关。开启后Bot不会离开家的48格范围。用户说住家模式/在家时使用。",
                Map.of(),
                args -> {
                    sendBotCmd("homemode");
                    return ToolResult.success("已切换Bot住家模式");
                }));

        tools.put("hunt", toolDef("hunt",
                "打猎。引擎自动寻找附近24格内的动物（牛/猪/羊/鸡/兔）并攻击获取食物。用户说打猎/打动物时使用此工具。",
                Map.of(),
                args -> {
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");
                    bot.setTask(new HuntTask());
                    return ToolResult.success("开始打猎，引擎自动执行。");
                }));

        // === 本地执行的工具 ===
        tools.put("say", toolDef("say",
                "在聊天框说话。",
                Map.of("message", "要说的内容"),
                args -> {
                    if (args.length < 1) return ToolResult.error("需要消息内容");
                    String message = String.join(" ", args);
                    sayToPublic(message);
                    return ToolResult.success("已发送消息");
                }));

        tools.put("check_inventory", toolDef("check_inventory",
                "检查AI Bot自己的背包物品。仅当用户明确问背包/物品时使用。",
                Map.of(),
                args -> {
                    var level = Minecraft.getInstance().level;
                    if (level == null) return ToolResult.error("世界未加载");
                    // 查找世界中的 Bot 实体
                    AiBotEntity bot = null;
                    for (Entity e : level.entitiesForRendering()) {
                        if (e instanceof AiBotEntity) {
                            bot = (AiBotEntity) e;
                            break;
                        }
                    }
                    if (bot == null) return ToolResult.error("Bot未找到，请先生成Bot");

                    // 读取背包数据：优先从集成服务端读取真实数据（单机模式），
                    // 因为客户端的 SimpleContainer 不会自动同步服务端的变更
                    SimpleContainer inv;
                    try {
                        var mc = Minecraft.getInstance();
                        var server = mc.getSingleplayerServer();
                        if (server != null) {
                            var serverLevel = server.getLevel(level.dimension());
                            if (serverLevel != null) {
                                var serverEntity = serverLevel.getEntity(bot.getId());
                                if (serverEntity instanceof AiBotEntity serverBot) {
                                    inv = serverBot.getInventory();
                                } else {
                                    inv = bot.getInventory();
                                }
                            } else {
                                inv = bot.getInventory();
                            }
                        } else {
                            inv = bot.getInventory();
                        }
                    } catch (Exception e) {
                        inv = bot.getInventory();
                    }

                    int empty = 0;
                    StringBuilder hotbar = new StringBuilder();
                    StringBuilder storage = new StringBuilder();
                    int size = inv.getContainerSize();
                    for (int i = 0; i < size; i++) {
                        ItemStack stack = inv.getItem(i);
                        if (stack.isEmpty()) { empty++; }
                        else {
                            String name = stack.getHoverName().getString();
                            String entry = name + "x" + stack.getCount();
                            if (i < 9) {
                                if (hotbar.length() > 0) hotbar.append(",");
                                hotbar.append(entry);
                            } else {
                                if (storage.length() > 0) storage.append(",");
                                storage.append(entry);
                            }
                        }
                    }
                    Map<String, Object> data = new HashMap<>();
                    data.put("total_slots", size);
                    data.put("empty_slots", empty);
                    String desc = empty + "/" + size + "空";
                    if (hotbar.length() > 0) desc += " 快捷栏:" + hotbar;
                    if (storage.length() > 0) desc += " | 背包:" + storage;
                    return ToolResult.success(desc, data);
                }));

        tools.put("check_status", toolDef("check_status",
                "检查生命值和饥饿值。仅当用户明确问血量/状态时使用。",
                Map.of(),
                args -> {
                    var player = Minecraft.getInstance().player;
                    if (player == null) return ToolResult.error("玩家未就绪");
                    Map<String, Object> data = new HashMap<>();
                    data.put("health", (int) player.getHealth());
                    data.put("max_health", (int) player.getMaxHealth());
                    data.put("hunger", player.getFoodData().getFoodLevel());
                    data.put("saturation", (int) player.getFoodData().getSaturationLevel());
                    data.put("x", (int) player.getX());
                    data.put("y", (int) player.getY());
                    data.put("z", (int) player.getZ());
                    data.put("dimension", player.level().dimension().location().toString());
                    return ToolResult.success("生命值：" + (int) player.getHealth() + "/" + (int) player.getMaxHealth()
                            + " 饥饿值：" + player.getFoodData().getFoodLevel(), data);
                }));

        tools.put("get_position", toolDef("get_position",
                "获取AI Bot当前的坐标位置（X，Y，Z）和所在维度。用户问'你在哪'或需要知道坐标时使用。",
                Map.of(),
                args -> {
                    var level = Minecraft.getInstance().level;
                    if (level == null) return ToolResult.error("世界未加载");
                    AiBotEntity bot = null;
                    for (Entity e : level.entitiesForRendering()) {
                        if (e instanceof AiBotEntity) {
                            bot = (AiBotEntity) e;
                            break;
                        }
                    }
                    if (bot == null) return ToolResult.error("Bot未找到，请先生成Bot");
                    var pos = bot.blockPosition();
                    String dim = level.dimension().location().toString();
                    String desc = String.format("维度: %s 坐标: [%d, %d, %d]", dim, pos.getX(), pos.getY(), pos.getZ());
                    Map<String, Object> data = new HashMap<>();
                    data.put("x", pos.getX());
                    data.put("y", pos.getY());
                    data.put("z", pos.getZ());
                    data.put("dimension", dim);
                    return ToolResult.success(desc, data);
                }));

        tools.put("translate", toolDef("translate",
                "查询物品/方块的中英文名称。用户问'xxx的英文是什么'或'怎么用英文说xxx'时使用。也可用于验证名称是否正确。",
                Map.of("name", "中文名称或英文注册名，如'花盆'或'flower_pot'"),
                args -> {
                    if (args.length < 1) return ToolResult.error("需要指定名称");
                    String query = String.join(" ", args);
                    String blockId = TranslationDictionary.translateBlock(query);
                    String itemId = TranslationDictionary.translateItem(query);
                    String blockCN = TranslationDictionary.getBlockCN(query);
                    String itemCN = TranslationDictionary.getItemCN(query);

                    StringBuilder result = new StringBuilder();
                    if (blockId != null) {
                        result.append("方块: ").append(blockId).append("\n");
                    }
                    if (itemId != null) {
                        result.append("物品: ").append(itemId).append("\n");
                    }
                    if (blockCN != null) {
                        result.append("中文名: ").append(blockCN).append(" (").append(query).append(")\n");
                    }
                    if (itemCN != null) {
                        result.append("中文名: ").append(itemCN).append(" (").append(query).append(")\n");
                    }
                    if (result.length() == 0) {
                        List<String> suggestions = TranslationDictionary.suggest(query);
                        if (!suggestions.isEmpty()) {
                            result.append("未找到精确匹配，以下是相似结果:\n");
                            for (String s : suggestions) {
                                result.append("  - ").append(s).append("\n");
                            }
                        } else {
                            return ToolResult.success("未找到匹配的物品或方块：" + query);
                        }
                    }
                    return ToolResult.success(result.toString());
                }));

        // === 知识 & 学习 ===
        tools.put("ask", toolDef("ask",
                "向玩家提出问题，等待玩家回答。当遇到不了解的方块、不确定怎么做、需要玩家指导时使用。",
                Map.of("question", "要问的问题"),
                args -> {
                    if (args.length < 1) return ToolResult.error("需要问题内容");
                    String question = String.join(" ", args);
                    sayToPublic("❓ " + question + " （请告诉我答案~）");
                    return ToolResult.success("已向玩家提问，等待回答");
                }));

        tools.put("need", toolDef("need",
                "向玩家请求物品。当需要某个物品但没有时，走到玩家面前告诉玩家需要什么。",
                Map.of("item", "需要的物品名称"),
                args -> {
                    if (args.length < 1) return ToolResult.error("需要物品名");
                    String item = String.join(" ", args);
                    sayToPublic("我需要 " + item + "，能丢给我吗？");
                    sendBotCmd("need");
                    return ToolResult.success("已向玩家请求物品: " + item);
                }));

        tools.put("identify", toolDef("identify",
                "识别玩家视线正对着的方块/物品。查看玩家在看什么，返回方块名称、注册名等信息。",
                Map.of(),
                args -> {
                    var mc = Minecraft.getInstance();
                    var player = mc.player;
                    if (player == null) return ToolResult.error("玩家未就绪");

                    var hitResult = mc.hitResult;
                    if (hitResult == null) return ToolResult.error("玩家没有看着任何方块");

                    if (hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                        var blockHit = (net.minecraft.world.phys.BlockHitResult) hitResult;
                        var pos = blockHit.getBlockPos();
                        var state = player.level().getBlockState(pos);
                        var block = state.getBlock();
                        var registryName = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
                        var displayName = block.getName().getString();

                        StringBuilder info = new StringBuilder();
                        info.append("方块: ").append(displayName);
                        info.append(" (").append(registryName).append(")");
                        info.append("\n坐标: ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ());
                        return ToolResult.success(info.toString());
                    } else {
                        return ToolResult.error("玩家没有看着方块");
                    }
                }));

        // === 模组配方扫描 ===
        tools.put("scan_recipes", toolDef("scan_recipes",
                "扫描已安装模组的配方（森罗物语等），将配方加入知识库。用户说扫描配方/看看有什么菜谱时使用。",
                Map.of("mod", "模组ID，如 kaleidoscope_cookery，不填则扫描全部已知模组"),
                args -> {
                    String modFilter = args.length > 0 ? args[0] : "";
                    List<RecipeScanner.ScannedRecipe> allRecipes = RecipeScanner.scanAll();
                    List<RecipeScanner.ScannedRecipe> filtered;
                    if (modFilter != null && !modFilter.isEmpty()) {
                        String f = modFilter;
                        filtered = new ArrayList<>();
                        for (var r : allRecipes) {
                            if (r.id.startsWith(f + ":")) filtered.add(r);
                        }
                    } else {
                        filtered = allRecipes;
                    }
                    if (filtered.isEmpty()) {
                        return ToolResult.success("没有扫描到模组配方。" + (modFilter.isEmpty() ? "" : " 模组: " + modFilter));
                    }
                    // 导入到知识库
                    String knowledge = RecipeScanner.toKnowledgeText(filtered);
                    // 也缓存到文件
                    Path logDir = Path.of(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "ai_bot", "logs");
                    RecipeScanner.saveCache(filtered, logDir.resolve("scanned_recipes.json"));
                    return ToolResult.success("扫描到 " + filtered.size() + " 个配方，已加入知识库。类型: " +
                            filtered.stream().map(r -> r.type).distinct().toList());
                }));

        // === 任务书扫描 + 任务系统分析 ===
        tools.put("scan_quests", toolDef("scan_quests",
                "扫描 FTB Quests 任务书并进行可行性分析，获取所有任务的详细信息和需求。用户说任务书/任务/看任务/扫描任务时使用。返回可做/需条件/受阻任务分类。",
                Map.of(),
                args -> {
                    String result = taskQuestSystem.scanAndAnalyze();
                    if (result.startsWith("未检测到")) {
                        return ToolResult.success(result);
                    }
                    // 更新动态上下文，让 AI 知道任务状态
                    updateDynamicContext("tasks", taskQuestSystem.getContextForAI());
                    return ToolResult.success(result);
                }));

        tools.put("check_quests", toolDef("check_quests",
                "查看已扫描的任务书详情。用户说查看任务/任务列表/当前任务时使用。需要先调用 scan_quests 扫描任务书。",
                Map.of(),
                args -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    var cachePath = java.nio.file.Path.of(mc.gameDirectory.getAbsolutePath(), "ai_bot", "quests_cache.json");
                    var data = QuestScanner.loadCache(cachePath);
                    if (data == null || data.chapters.isEmpty()) {
                        return ToolResult.success("暂无任务数据，请先调用 scan_quests 扫描任务书。");
                    }
                    return ToolResult.success(QuestScanner.formatForAI(data));
                }));

        // === 任务系统（分析+执行+追踪） ===
        tools.put("do_task", toolDef("do_task",
                "开始执行指定任务。用户说做任务/去做/执行任务时使用。需要先调用 scan_quests 扫描任务书。参数 quest 是任务标题，精确匹配。",
                Map.of("quest", "任务标题，如\"收集64个圆石\""),
                args -> {
                    if (args.length < 1) return ToolResult.error("需要指定任务标题");
                    String title = String.join(" ", args).trim();
                    String result = taskQuestSystem.startTask(title);
                    if (result == null) return ToolResult.error("找不到任务: " + title);
                    // 更新动态上下文，让 AI 知道当前执行状态
                    updateDynamicContext("tasks", taskQuestSystem.getContextForAI());
                    return ToolResult.success(result);
                }));

        tools.put("check_tasks", toolDef("check_tasks",
                "查看任务系统状态。用户问任务怎么样了/进度/完成了吗/还有什么任务时使用。返回当前可做任务列表、受阻任务和正在执行的任务进度。",
                Map.of(),
                args -> {
                    String summary = taskQuestSystem.formatSummaryForAI();
                    if (summary.isEmpty()) {
                        return ToolResult.success("暂无任务数据，请先调用 scan_quests 扫描任务书。");
                    }
                    return ToolResult.success(summary);
                }));

        tools.put("get_next_doable", toolDef("get_next_doable",
                "获取当前可做的下一个任务。用户说有什么我能做的/下个任务/推荐任务时使用。返回优先级最高的可做任务和建议。",
                Map.of(),
                args -> {
                    String names = taskQuestSystem.getDoableNames();
                    if (names.isEmpty()) {
                        return ToolResult.success("当前没有可做的任务。可能有任务受阻或等待完成。使用 check_tasks 查看详情。");
                    }
                    int count = taskQuestSystem.getDoableCount();
                    String first = names.split("[、，]")[0];
                    return ToolResult.success("有 " + count + " 个任务可以做。推荐: " + first + "。执行 do_task 命令并指定任务标题即可。可做任务: " + names);
                }));

        // === 环境扫描 ===
        tools.put("scan_environment", toolDef("scan_environment",
                "扫描 Bot 周围的地表环境。用户问周围环境/附近有什么/周围有什么/当前在哪时使用。返回群系、时间、天气、树木、裸露矿石、作物、建筑、动物、怪物等信息。",
                Map.of("radius", "扫描半径（可选，默认20，范围5-50）"),
                args -> {
                    AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("Bot 不存在，请先召唤 Bot");
                    int radius = EnvironmentScanner.DEFAULT_RADIUS;
                    if (args.length > 0 && !args[0].isEmpty()) {
                        try {
                            radius = Math.max(5, Math.min(50, Integer.parseInt(args[0])));
                        } catch (NumberFormatException ignored) {}
                    }
                    var result = EnvironmentScanner.scan(bot.level(), bot.blockPosition(), radius);
                    updateDynamicContext("environment", EnvironmentScanner.formatForAI(result));
                    return ToolResult.success("环境已更新，我已了解当前周围的情况。");
                }));

        // === 烹饪引擎 ===
        tools.put("cook", toolDef("cook",
                "烹饪食物。用户说做饭/炒菜/烹饪/做菜/煮汤时使用。引擎会自动检查背包和附近箱子找材料、找炉灶、点火、放锅、倒油、下料、翻炒、盛出。如果背包里没有材料，会自动搜索附近的箱子；如果箱子也没有，才会问玩家。所以即使你看不到背包里有材料，也直接调用 cook。",
                Map.of("recipe", "配方名称（如青椒炒肉），或食物名称"),
                args -> {
                    String recipeName = args.length > 0 ? args[0] : "";
                    if (recipeName.isEmpty()) return ToolResult.error("请指定要做的菜名");

                    // 从扫描缓存查找配方
                    Path logDir = Path.of(Minecraft.getInstance().gameDirectory.getAbsolutePath(), "ai_bot", "logs");
                    List<RecipeScanner.ScannedRecipe> scanned = RecipeScanner.loadCache(logDir.resolve("scanned_recipes.json"));

                    // 重新扫描以获得中文名（缓存只保存英文ID，中文名需运行时获取）
                    if (!scanned.isEmpty() && (scanned.get(0).cnOutput == null || scanned.get(0).cnOutput.isEmpty())) {
                        List<RecipeScanner.ScannedRecipe> fresh = RecipeScanner.scanAll();
                        if (!fresh.isEmpty()) {
                            RecipeScanner.saveCache(fresh, logDir.resolve("scanned_recipes.json"));
                            scanned = fresh;
                        }
                    }

                    // 收集所有匹配的配方（所有烹具类型）
                    String query = recipeName.toLowerCase();
                    List<RecipeScanner.ScannedRecipe> allMatches = new ArrayList<>();
                    for (var sr : scanned) {
                        // 只处理我们支持的类型：pot（炒锅）、stockpot（炖锅）、chopping_board（砧板）
                        if (!"pot".equals(sr.type) && !"flex_pot".equals(sr.type) && !"oil_pot".equals(sr.type)
                            && !"stockpot".equals(sr.type) && !"flex_stockpot".equals(sr.type)
                            && !"chopping_board".equals(sr.type))
                            continue;
                        String cn = sr.cnDisplay().toLowerCase();
                        String search = sr.searchText();
                        if (cn.contains(query) || search.contains(query)
                            || sr.id.toLowerCase().contains(query)
                            || sr.shortName.toLowerCase().contains(query)) {
                            allMatches.add(sr);
                        }
                    }

                    // 如果没有全字匹配，尝试部分匹配
                    if (allMatches.isEmpty() && query.length() >= 2) {
                        for (var sr : scanned) {
                            String search = sr.searchText();
                            for (int i = 0; i <= query.length() - 2; i++) {
                                if (search.contains(query.substring(i, i + 2))) {
                                    allMatches.add(sr);
                                    break;
                                }
                            }
                        }
                    }

                    // 从多个匹配中选最优：原料越多越好，避免选到成品组装配方
                    RecipeScanner.ScannedRecipe matched = null;
                    if (!allMatches.isEmpty()) {
                        // 去重（同一配方可能多次匹配）
                        allMatches = new ArrayList<>(new LinkedHashSet<>(allMatches));
                        // 按原料数量降序排列（烹饪配方原料多，组装配方原料少）
                        allMatches.sort((a, b) -> {
                            // 优先：原料多的
                            int cmp = Integer.compare(b.ingredients.size(), a.ingredients.size());
                            if (cmp != 0) return cmp;
                            // 其次：ID短的（去掉饭/面等后缀）
                            return Integer.compare(a.id.length(), b.id.length());
                        });
                        matched = allMatches.get(0);
                        // 如果最优匹配的原料都是成品，跳过
                        if (matched.ingredients.size() <= 2) {
                            boolean allFinished = matched.ingredients.stream().allMatch(id ->
                                id.contains("stir_fried") || id.contains("cooked") || id.contains("roasted")
                                || id.contains("fried") || id.contains("rice_bowl") || id.contains("rice"));
                            if (allFinished && allMatches.size() > 1) {
                                // 尝试下一个配方
                                for (int i = 1; i < allMatches.size(); i++) {
                                    var alt = allMatches.get(i);
                                    if (alt.ingredients.size() > matched.ingredients.size()) {
                                        matched = alt;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (matched == null) {
                        // 列出可用的pot类型配方供参考
                        var potRecipes = scanned.stream()
                            .filter(r -> "pot".equals(r.type))
                            .map(r -> r.cnOutput.isEmpty() ? r.shortName : r.cnOutput)
                            .limit(10).toList();
                        return ToolResult.success("未找到配方: " + recipeName
                            + "。可用的锅配方: " + potRecipes
                            + "。请用 scan_recipes 重新扫描，或指定精确名称。");
                    }

                    List<String> ingredients = new ArrayList<>(matched.ingredients);

                    // 确定烹具类型
                    com.aibot.mod.task.CookTask.CookwareType cookwareType = switch (matched.type) {
                        case "stockpot", "flex_stockpot" -> com.aibot.mod.task.CookTask.CookwareType.STOCKPOT;
                        case "chopping_board" -> com.aibot.mod.task.CookTask.CookwareType.CHOPPING;
                        default -> com.aibot.mod.task.CookTask.CookwareType.POT;
                    };

                    // 创建烹饪任务并分配给 Bot
                    com.aibot.mod.entity.AiBotEntity bot = findBot();
                    if (bot == null) return ToolResult.error("未找到Bot实体");

                    CookTask task = new CookTask(matched.cnOutput.isEmpty() ? recipeName : matched.cnOutput, ingredients,
                        matched.output, matched.carrier, cookwareType);
                    bot.setTask(task);

                    return ToolResult.success("开始烹饪 " + (matched.cnOutput.isEmpty() ? recipeName : matched.cnOutput)
                        + "，材料: " + ingredients + "，引擎自动执行。");
                }));

        tools.put("give_item", toolDef("give_item",
                "把身上的指定物品丢给玩家。当玩家说\"把xxx给我\"、\"给我xxx\"等索要物品时使用。必须先确认背包装了该物品。",
                Map.of("item", "物品ID或中文名，如 minecraft:diamond 或 钻石", "count", "数量，默认1"),
                args -> {
                    if (args.length < 1) return ToolResult.error("需要指定物品名称");
                    String itemName = args[0];
                    int count = parseIntArg(args, 1, 1);

                    // 尝试解析物品ID：如果没带冒号则尝试模糊匹配
                    String itemId = itemName;
                    if (!itemId.contains(":")) {
                        String lower = itemId.toLowerCase();
                        for (var entry : ForgeRegistries.ITEMS.getEntries()) {
                            String key = entry.getKey().location().toString();
                            String path = entry.getKey().location().getPath();
                            if (key.equalsIgnoreCase(itemId) || path.equalsIgnoreCase(itemId)
                                    || path.contains(lower)) {
                                itemId = key;
                                break;
                            }
                        }
                    }

                    // 在客户端检查 bot 是否有该物品（先验证再发命令）
                    var bot = findBot();
                    if (bot == null) return ToolResult.error("未找到附近的AI Bot");

                    // 读取真实背包数据（优先服务端）
                    SimpleContainer inv;
                    try {
                        var mc = Minecraft.getInstance();
                        var server = mc.getSingleplayerServer();
                        if (server != null) {
                            var serverLevel = server.getLevel(mc.level.dimension());
                            if (serverLevel != null) {
                                var serverEntity = serverLevel.getEntity(bot.getId());
                                if (serverEntity instanceof AiBotEntity serverBot) {
                                    inv = serverBot.getInventory();
                                } else {
                                    inv = bot.getInventory();
                                }
                            } else {
                                inv = bot.getInventory();
                            }
                        } else {
                            inv = bot.getInventory();
                        }
                    } catch (Exception e) {
                        inv = bot.getInventory();
                    }

                    int totalCount = 0;
                    String matchedId = itemId;
                    // 先用注册名匹配
                    for (int i = 0; i < inv.getContainerSize(); i++) {
                        ItemStack stack = inv.getItem(i);
                        if (!stack.isEmpty()) {
                            String regName = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                            if (regName.equals(itemId)) {
                                totalCount += stack.getCount();
                            }
                        }
                    }
                    // 如果注册名没匹配到，尝试用物品显示名（中文名）匹配
                    if (totalCount == 0) {
                        for (int i = 0; i < inv.getContainerSize(); i++) {
                            ItemStack stack = inv.getItem(i);
                            if (!stack.isEmpty()) {
                                String displayName = stack.getHoverName().getString();
                                if (displayName.contains(itemName) || itemName.contains(displayName)) {
                                    totalCount += stack.getCount();
                                    if (matchedId.equals(itemId)) {
                                        matchedId = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                                    }
                                }
                            }
                        }
                    }

                    if (totalCount == 0) {
                        return ToolResult.error("我身上没有 " + itemName + " (ID: " + itemId + ")");
                    }

                    int giveCount = Math.min(count, totalCount);
                    sendBotCmd("give " + matchedId + " " + giveCount);
                    return ToolResult.success("已把 " + giveCount + " 个 " + itemName + " 丢给你~");
                }));

        // === 脚本 ===
        ScriptEngine.reload();
        for (var entry : ScriptEngine.getAll().entrySet()) {
            tools.put(entry.getKey().toLowerCase(), new ScriptRunner(entry.getValue()));
        }

        // === 设置页面 ===
        tools.put("!settings", toolDef("!settings", "", Map.of(), args -> {
            Minecraft.getInstance().execute(() ->
                Minecraft.getInstance().setScreen(new com.aibot.mod.client.AiBotSettingsScreen()));
            return ToolResult.success("");
        }));

        tools.put("!reload_dict", toolDef("!reload_dict", "", Map.of(), args -> {
            TranslationDictionary.reload();
            return ToolResult.success("已重新加载物品/方块名称对照表");
        }));

        tools.put("!export_items", toolDef("!export_items", "", Map.of(), args -> {
            String result = TranslationDictionary.exportToJson();
            return ToolResult.success(result);
        }));
        // AI 工具
        tools.put("open_settings", toolDef("open_settings",
            "打开AI Bot的设置页面。用户说设置/设置页面/配置时使用。",
            Map.of(),
            args -> {
                Minecraft.getInstance().execute(() ->
                    Minecraft.getInstance().setScreen(new com.aibot.mod.client.AiBotSettingsScreen()));
                return ToolResult.success("已打开设置页面");
            }));
    }

    private Tool toolDef(String name, String desc, Map<String, String> params, Function<String[], ToolResult> handler) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return desc; }
            @Override public Map<String, String> getParameters() { return params; }
            @Override public ToolResult execute(String[] args) { return handler.apply(args); }
        };
    }

    private int parseIntArg(String[] args, int index, int defaultValue) {
        if (args == null || index >= args.length) return defaultValue;
        try {
            String cleaned = args[index].replaceAll("[^0-9]", "");
            return cleaned.isEmpty() ? defaultValue : Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 通过静态 pendingCommands 向 Bot 发送命令 */
    private void sendBotCmd(String command) {
        AiBotEntity bot = findBot();
        if (bot != null) {
            AiBotEntity.sendCommand(bot.getId(), command);
        }
    }

    // === 消息处理 ===

    public void processMessage(String sender, String message) {
        if (sender == null || message == null || message.isEmpty()) return;
        ConversationLogger.logUser(sender, message);

        // 本地模式（纯工具模式）不注入情感/性格/记忆上下文
        if ("remote".equalsIgnoreCase(Config.getAiMode())) {
            injectMindContext();
        }
        if (messageQueue.size() >= MAX_QUEUE_SIZE) {
            LOGGER.warn("[AI Bot] Queue full, dropping message from {}", sender);
            say("Busy, message dropped");
            return;
        }
        boolean accepted = messageQueue.offer(new ChatMessage(sender, message));
        if (accepted && !isProcessing) {
            startProcessing();
        }
    }

    public void executeCommand(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) return;
        String[] parts = commandLine.trim().split("\\s+");
        if (parts.length == 0) return;
        String actionName = parts[0].toLowerCase();
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        // 先精确匹配（!myfarm 等脚本名字自带 !），再尝试去掉 ! 前缀（!stop → stop）
        Tool tool = tools.get(actionName);
        if (tool == null && actionName.startsWith("!")) {
            tool = tools.get(actionName.substring(1));
        }
        if (tool == null) {
            LOGGER.warn("[AI Bot] Unknown command: {}", actionName);
            say("Unknown command: " + actionName);
            return;
        }

        LOGGER.info("[AI Bot] Executing local command: {}", commandLine);
        ToolResult result = tool.execute(args);
        if (!result.isSuccess()) {
            say(result.getMessage());
        }
    }

    private void startProcessing() {
        isProcessing = true;
        aiExecutor.submit(this::processQueueLoop);
    }

    private void processQueueLoop() {
        try {
            isProcessing = true;
            while (!Thread.currentThread().isInterrupted()) {
                ChatMessage msg = messageQueue.poll(100, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    if (messageQueue.isEmpty()) {
                        isProcessing = false;
                        return;
                    }
                    continue;
                }
                // 链式处理：上一条消息的 agent 循环完全结束后才处理下一条
                final ChatMessage currentMsg = msg;
                processingChain = processingChain
                    .thenCompose(v -> processOne(currentMsg))
                    .exceptionally(e -> {
                        LOGGER.error("[AI Bot] Processing chain error: {}", e.getMessage());
                        return null;
                    });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            isProcessing = false;
        }
    }

    /**
     * 处理单条消息（异步），返回的 CompletableFuture 在完整 agent 循环结束后完成。
     */
    private CompletableFuture<Void> processOne(ChatMessage msg) {
        String userMessage = msg.message;
        ConversationLogger.logSystem("Processing message from " + msg.sender + ": " + userMessage);

        if (aiProvider == null) {
            sayToPublic("§c[AI Bot] AI提供器未初始化");
            LOGGER.warn("[AI Bot] Cannot process message: AI provider not initialized");
            return CompletableFuture.completedFuture(null);
        }

        if (!aiProvider.isConnected()) {
            if ("remote".equalsIgnoreCase(Config.getAiMode())) {
                sayToPublic("§c[AI Bot] 后端服务器未连接，无法处理消息");
                LOGGER.warn("[AI Bot] Cannot process message: backend not connected");
            } else {
                if (Config.getApiKey() == null || Config.getApiKey().isEmpty()) {
                    sayToPublic("§c[AI Bot] 未配置API Key，请在设置中配置");
                    LOGGER.warn("[AI Bot] Cannot process message: API key not configured");
                } else {
                    sayToPublic("§c[AI Bot] AI服务未就绪");
                    LOGGER.warn("[AI Bot] Cannot process message: AI service not ready");
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        try {
            Map<String, Object> gameState = new HashMap<>();
            
            AiBotEntity bot = findBot();
            if (bot != null) {
                gameState.put("x", bot.getX());
                gameState.put("y", bot.getY());
                gameState.put("z", bot.getZ());
                gameState.put("health", bot.getHealth());
                gameState.put("max_health", bot.getMaxHealth());
            }

            Map<String, Object> emotionState = new HashMap<>();
            Map<String, Object> personalityState = new HashMap<>();
            // 外接模式才收集情绪/性格状态
            if ("remote".equalsIgnoreCase(Config.getAiMode())) {
                emotionState.put("hunger", emotion.getHunger());
                emotionState.put("tiredness", emotion.getTiredness());
                emotionState.put("happiness", emotion.getHappiness());
                emotionState.put("stress", emotion.getStress());

                personalityState.put("diligence", personality.getDiligence());
                personalityState.put("bravery", personality.getBravery());
                personalityState.put("talkativeness", personality.getTalkativeness());
            }
            
            ConversationLogger.logSystem("Message sent to AI provider");
            
            return aiProvider.chat(userMessage, gameState, emotionState, personalityState)
                .thenCompose(response -> runAgentLoop(response, 0))
                .exceptionally(e -> {
                    LOGGER.error("[AI Bot] AI request failed: {}", e.getMessage());
                    sayToPublic("§c[AI Bot] AI请求失败: " + e.getMessage());
                    return null;
                });
            
        } catch (Exception e) {
            LOGGER.error("[AI Bot] Failed to send message to AI: {}", e.getMessage());
            sayToPublic("§c[AI Bot] 发送消息到AI失败: " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * ReAct Agent 循环：AI 回复 → 有工具调用 → 执行 → 结果喂回 → 继续
     * 直到 AI 给出纯文本回复或达到最大迭代次数
     */
    private CompletableFuture<Void> runAgentLoop(AIProvider.AIResponse response, int iteration) {
        if (!response.success) {
            LOGGER.error("[AI Bot] AI response error: {}", response.reply);
            sayToPublic("§c[AI Bot] AI错误: " + response.reply);
            return CompletableFuture.completedFuture(null);
        }

        // 有文本就说话
        if (!response.reply.isEmpty()) {
            say(response.reply);
            ConversationLogger.logAIChat(response.reply);
        }

        // 没有工具调用 → 本轮结束
        if (response.actions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // 达到最大迭代次数 → 强制结束
        if (iteration >= MAX_AGENT_ITERATIONS) {
            LOGGER.warn("[AI Bot] Agent loop reached max iterations ({})", MAX_AGENT_ITERATIONS);
            say("我思考了太多次，还是先这样吧。");
            return CompletableFuture.completedFuture(null);
        }

        // 执行所有工具，收集结果
        List<AIProvider.ToolResult> toolResults = new ArrayList<>();
        for (AIProvider.ActionStep action : response.actions) {
            String toolName = action.type;
            Map<String, Object> params = action.params;
            
            Tool tool = tools.get(toolName);
            if (tool == null) {
                LOGGER.warn("[AI Bot] Unknown tool: {}", toolName);
                toolResults.add(new AIProvider.ToolResult(
                    action.toolCallId != null ? action.toolCallId : "unknown",
                    "错误：未找到工具 '" + toolName + "'"
                ));
                continue;
            }

            try {
                List<String> argList = new ArrayList<>();
                for (var entry : tool.getParameters().entrySet()) {
                    Object val = params.get(entry.getKey());
                    argList.add(val != null ? val.toString() : "");
                }
                String[] args = argList.toArray(new String[0]);
                ToolResult result = tool.execute(args);

                // 构建带有 tool_call_id 的结果，用于 continueChat
                String resultMsg = result.isSuccess() ? result.getMessage() : "失败: " + result.getMessage();
                toolResults.add(new AIProvider.ToolResult(
                    action.toolCallId != null ? action.toolCallId : toolName,
                    resultMsg
                ));

                if (!result.isSuccess()) {
                    LOGGER.warn("[AI Bot] Tool {} failed: {}", toolName, result.getMessage());
                }
            } catch (Exception e) {
                LOGGER.error("[AI Bot] Failed to execute tool {}: {}", toolName, e.getMessage());
                toolResults.add(new AIProvider.ToolResult(
                    action.toolCallId != null ? action.toolCallId : toolName,
                    "执行异常: " + e.getMessage()
                ));
            }
        }

        // 将工具执行结果喂回 AI，继续下一轮
        return aiProvider.continueChat(toolResults)
            .thenCompose(nextResponse -> runAgentLoop(nextResponse, iteration + 1));
    }

    /** 将工具调用返回的 JSON 参数转为按工具定义顺序排列的 String[] */
    private String[] jsonToOrderedArgs(String jsonArgs, Map<String, String> paramDefs) {
        if (jsonArgs == null || jsonArgs.isBlank()) return new String[0];
        if (paramDefs.isEmpty()) return new String[0];
        try {
            com.google.gson.JsonObject obj = JsonParser.parseString(jsonArgs).getAsJsonObject();
            List<String> result = new java.util.ArrayList<>();
            // 按 JSON 对象自身的键顺序（AI API 按 schema 定义顺序返回）提取参数，
            // 不依赖 Map.of 的迭代顺序（Java 9+ 不保证顺序）
            for (var entry : obj.entrySet()) {
                if (paramDefs.containsKey(entry.getKey())) {
                    var val = entry.getValue();
                    result.add(val.isJsonNull() ? "" : val.getAsString());
                }
            }
            return result.toArray(new String[0]);
        } catch (Exception e) {
            // 如果不是合法 JSON，回退：整段当 args[0]
            return new String[]{jsonArgs};
        }
    }

    // ========== 情绪/记忆/性格系统 ==========

    /** 将当前情绪、记忆、性格和任务状态注入动态上下文，使 AI 在对话中自然体现状态。 */
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
        
        // 注入任务链状态
        String chainStatus = chainExecutor.getChainStatusText();
        if (!chainStatus.isEmpty()) {
            sb.append("[").append(chainStatus).append("]\n\n");
        }
        
        sb.append("请体现上述情绪和记忆，不要假装状态正常。");
        updateDynamicContext("mind_status", sb.toString());

        // 同时注入任务状态
        String taskCtx = taskQuestSystem.getContextForAI();
        if (!taskCtx.isEmpty()) {
            updateDynamicContext("tasks", taskCtx);
        }
    }
    
    /**
     * 获取精简的性格描述（只保留突出特征）
     * Token 消耗：约 20-30 tokens
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
            int totalExp = personality.getTotalExperiences();
            if (totalExp < 10) {
                return "刚出生，性格尚未形成";
            }
            return "性格正在成长中（经历了" + totalExp + "次事件）";
        }
        
        return String.join("、", traits) + 
               String.format("（经历了%d次事件）", personality.getTotalExperiences());
    }

    /**
     * 自动情绪检测，每 10 秒（200 tick）检查一次。
     * 由外部定时调用（通过 AiBotMod 的 TickEvent 或类似机制）。
     * 返回 true 表示触发了一次主动说话，false 表示无变化。
     * 本地模式直接返回 false（不需要情绪系统）。
     */
    public boolean tickAutoEmotion() {
        if (!"remote".equalsIgnoreCase(Config.getAiMode())) return false;
        autoEmotionTick++;
        if (autoEmotionTick < 200) {
            // 每 tick 都检查是否需要保存性格
            personalitySaveTick++;
            if (personalitySaveTick >= PERSONALITY_SAVE_INTERVAL) {
                personalitySaveTick = 0;
                // 定期保存性格数据（每分钟）
                personality.save(getMindSaveDir());
            }
            return false; // 每 200 tick ≈ 10秒检查一次
        }
        autoEmotionTick = 0;

        // 冷却检查：30 秒内已主动说过话就不再触发
        long now = System.currentTimeMillis();
        if (now - lastAutoEmotionTime < AUTO_EMOTION_COOLDOWN_MS) {
            emotion.syncCheckBaseline();
            return false;
        }

        // 检查情绪变化
        String change = emotion.checkSignificantChange();
        if (change == null) {
            emotion.syncCheckBaseline();
            return false;
        }

        // 一级变化 → 调 AI 说一句话
        lastAutoEmotionTime = now;
        emotion.syncCheckBaseline();
        triggerAutoSpeak(change);
        return true;
    }

    /** 触发 AI 主动说话（异步）。情绪变化剧烈时 Bot 自己说一句相关的话。 */
    private void triggerAutoSpeak(String emotionChange) {
        // 自动说话功能暂时禁用，等后端支持后再启用
        LOGGER.info("[AI Bot] Auto-speak triggered but disabled: {}", emotionChange);
    }

    /** 保存情绪、记忆、性格、任务链和任务数据。Bot 死亡或关闭时调用。 */
    public void saveMindData() {
        Path dir = getMindSaveDir();
        dir.toFile().mkdirs();
        // 外接模式才保存情绪/记忆/性格
        if ("remote".equalsIgnoreCase(Config.getAiMode())) {
            emotion.save(dir);
            memory.save(dir);
            personality.save(dir);
        }
        chainExecutor.save();
        taskQuestSystem.save();
    }

    public Emotion getEmotion() { return emotion; }
    public EpisodicMemory getMemory() { return memory; }
    public DynamicPersonality getPersonality() { return personality; }
    public TaskQuestSystem getTaskQuestSystem() { return taskQuestSystem; }

    // === 消息发送 ===

    public void say(String message) {
        if (!isConnectedToServer()) return;
        String sanitized = sanitizeChat(message);
        if (sanitized.isEmpty()) return;
        if (chatHandler != null) chatHandler.markOwnMessage(sanitized);
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null && player.connection != null) {
                player.connection.sendCommand("say " + sanitized);
            }
        });
        ConversationLogger.logSystem("Say: " + message);
    }

    public void sayToPublic(String message) {
        if (!isConnectedToServer()) return;
        String sanitized = sanitizeChat(message);
        if (sanitized.isEmpty()) return;
        String botName = Config.getBotName();
        String formattedMessage = "[" + botName + "] " + sanitized;
        if (chatHandler != null) chatHandler.markOwnMessage(formattedMessage);
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null && player.connection != null) {
                player.connection.sendCommand("say " + formattedMessage);
            }
        });
    }

    private boolean isConnectedToServer() {
        var mc = Minecraft.getInstance();
        return mc.getConnection() != null && mc.getConnection().getConnection().isConnected();
    }

    private String sanitizeChat(String message) {
        if (message == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : message.toCharArray()) {
            if (c >= 0x20 && c <= 0x7E || 
                Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN ||
                c == '　' ||
                // CJK 符号和标点（。、〃等）
                (c >= 0x3000 && c <= 0x303F) ||
                // 全角 ASCII 变体（，！？；：等）
                (c >= 0xFF00 && c <= 0xFFEF)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String sanitizeForStatus(String message) {
        if (message == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        for (char c : message.toCharArray()) {
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            }
            if (sb.length() >= 64) break;
        }
        return sb.toString();
    }

    /** 类型到动态上下文的映射，每次 setDynamicContext 后合并注入 */
    private final Map<String, String> dynamicContexts = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 更新某类动态上下文（如 environment / quests / status）。
     * 动态上下文会在下次发送到后端时包含。
     */
    public void updateDynamicContext(String type, String data) {
        if (data == null || data.isEmpty()) {
            dynamicContexts.remove(type);
        } else {
            dynamicContexts.put(type, data);
        }
    }

    public void setChatHandler(ChatHandler handler) { this.chatHandler = handler; }

    public void shutdown() {
        saveMindData();
        chainExecutor.save();
        if (aiProvider != null) {
            aiProvider.disconnect();
        }
        aiExecutor.shutdownNow();
    }

    /** 异步生成计划：调 LLM → 解析 JSON → 发送到 Bot 执行 */
    private void generatePlanAsync() {
        var player = Minecraft.getInstance().player;
        if (player == null) return;

        String context = "当前时间: " + (player.level().isNight() ? "夜晚" : "白天") + "\n"
            + "家坐标: " + (Config.getHomeX() + "," + Config.getHomeY() + "," + Config.getHomeZ()) + "\n";

        String systemPrompt = """
            你是一个Minecraft AI Bot的规划系统。根据当前状态，生成一个任务计划。

            可用任务类型：
            - chop: 砍树获取木头，参数 count
            - mine_stone: 露天挖石头/圆石，参数 count
            - mine_ore: 挖裸露在地表的矿石（煤/铁/铜等），参数 count
            - mine: 挖矿道/地下挖掘，参数 count
            - farm: 种地/收割作物，自动执行
            - eat: 吃东西恢复饥饿
            - sleep: 睡觉恢复疲劳
            - craft: 合成物品，参数 item（物品注册名如 stone_pickaxe）和 count

            规则：
            1. 输出纯JSON数组，不要任何markdown标记
            2. 每个任务格式：{"type":"任务类型","count":数量,"item":"物品名(可选)","desc":"描述"}
            3. 一次只规划 2-4 个任务
            4. 根据性格和当前状态合理安排计划
            5. 如果是夜晚，优先安排睡觉
            6. 如果饥饿值低，优先安排吃东西或种地
            7. 白天优先安排资源收集和发展
            8. 如果背包没有食物或工具，先合成基本工具
            9. 需要圆石时用 mine_stone，需要矿石时用 mine_ore，需要挖矿道时用 mine

            示例输出：[{"type":"chop","count":16,"desc":"砍16个木头"},{"type":"mine_stone","count":64,"desc":"挖64个圆石"}]
            """;

        // 计划生成功能暂时禁用，等后端支持后再启用
        LOGGER.info("[AI Bot] Plan generation requested but disabled, waiting for backend support");
    }

    /** 在世界中找到 AiBotEntity */
    private AiBotEntity findBot() {
        return net.minecraft.client.Minecraft.getInstance().level.getEntitiesOfClass(
            AiBotEntity.class,
            new net.minecraft.world.phys.AABB(
                net.minecraft.client.Minecraft.getInstance().player.blockPosition()
            ).inflate(128)
        ).stream().findFirst().orElse(null);
    }

    private record ChatMessage(String sender, String message) {}

    // === 远程 AI 服务器集成 ===

    private void onServerMessage(String message) {
        say(message);
        AiBotMod.LOGGER.info("[RemoteAI] Received message: {}", message);
    }

    private void onServerActions(ServerAction[] actions) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                AiBotMod.LOGGER.info("[RemoteAI] Executing {} actions on server thread", actions.length);
                AiBotEntity bot = findServerBot();
                if (bot == null) {
                    AiBotMod.LOGGER.warn("[RemoteAI] No server bot found to execute actions");
                    return;
                }
                for (ServerAction action : actions) {
                    executeServerAction(bot, action);
                }
            });
        } else {
            AiBotMod.LOGGER.error("[RemoteAI] No integrated server available");
        }
    }

    private AiBotEntity findServerBot() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return null;
        var level = server.getLevel(mc.player.level().dimension());
        if (level == null) return null;
        var pos = mc.player.blockPosition();
        return level.getEntitiesOfClass(
            AiBotEntity.class,
            new net.minecraft.world.phys.AABB(pos).inflate(128)
        ).stream().findFirst().orElse(null);
    }

    private void executeServerAction(AiBotEntity bot, ServerAction action) {
        String type = action.type;
        java.util.Map<String, Object> params = action.params;
        AiBotMod.LOGGER.info("[RemoteAI] Executing action type: {}", type);

        try {
            switch (type) {
                case "say" -> {
                    // 已通过 onServerMessage 处理，避免重复
                }
                case "chop" -> {
                    int count = params != null ? ((Number) params.getOrDefault("count", 10)).intValue() : 10;
                    bot.setTask(new ChopTask(count));
                    AiBotMod.LOGGER.info("[RemoteAI] Action chop set with count={}", count);
                }
                case "mine" -> {
                    int count = params != null ? ((Number) params.getOrDefault("count", 64)).intValue() : 64;
                    String mode = params != null ? String.valueOf(params.getOrDefault("mode", "mine")) : "mine";
                    bot.setTask(new MineTask(count, mode));
                    AiBotMod.LOGGER.info("[RemoteAI] Action mine set with count={}, mode={}", count, mode);
                }
                case "craft" -> {
                    String item = params != null ? String.valueOf(params.getOrDefault("item", "")) : "";
                    int count = params != null ? ((Number) params.getOrDefault("count", 1)).intValue() : 1;
                    if (!item.isEmpty()) {
                        bot.setTask(new CraftTask(item, count));
                        AiBotMod.LOGGER.info("[RemoteAI] Action craft set: {} x{}", item, count);
                    }
                }
                case "farm" -> {
                    bot.setTask(new FarmTask());
                    AiBotMod.LOGGER.info("[RemoteAI] Action farm set");
                }
                case "hunt" -> {
                    bot.setTask(new HuntTask());
                    AiBotMod.LOGGER.info("[RemoteAI] Action hunt set");
                }
                case "follow" -> {
                    bot.setTask(new FollowTask());
                    AiBotMod.LOGGER.info("[RemoteAI] Action follow set");
                }
                case "sleep" -> {
                    bot.setTask(new SleepTask());
                    AiBotMod.LOGGER.info("[RemoteAI] Action sleep set");
                }
                case "eat" -> {
                    bot.setTask(new EatTask());
                    AiBotMod.LOGGER.info("[RemoteAI] Action eat set");
                }
                case "teleport" -> {
                    AiBotMod.LOGGER.info("[RemoteAI] Executing direct teleport on server thread");
                    net.minecraft.world.entity.LivingEntity target = bot.findNearestPlayer();
                    if (target != null) {
                        var currentDim = bot.level().dimension();
                        var targetDim = target.level().dimension();
                        if (currentDim != targetDim) {
                            double tx = target.getX();
                            double tz = target.getZ();
                            if (currentDim == net.minecraft.world.level.Level.NETHER &&
                                targetDim == net.minecraft.world.level.Level.OVERWORLD) {
                                tx *= 8; tz *= 8;
                            } else if (currentDim == net.minecraft.world.level.Level.OVERWORLD &&
                                       targetDim == net.minecraft.world.level.Level.NETHER) {
                                tx /= 8; tz /= 8;
                            }
                            if (bot.getServer() != null) {
                                var targetLevel = bot.getServer().getLevel(targetDim);
                                if (targetLevel != null) {
                                    bot.teleportTo(targetLevel, tx, target.getY(), tz, java.util.Set.of(), bot.getYRot(), bot.getXRot());
                                    AiBotMod.LOGGER.info("[RemoteAI] Direct teleport (cross-dim) completed");
                                }
                            }
                        } else {
                            bot.teleportTo(target.getX(), target.getY(), target.getZ());
                            AiBotMod.LOGGER.info("[RemoteAI] Direct teleport completed");
                        }
                    } else {
                        AiBotMod.LOGGER.warn("[RemoteAI] No nearest player found for teleport");
                    }
                }
                case "give" -> {
                    String item = params != null ? String.valueOf(params.getOrDefault("item", "")) : "";
                    int count = params != null ? ((Number) params.getOrDefault("count", 1)).intValue() : 1;
                    AiBotMod.LOGGER.info("[RemoteAI] Executing give action: {} x{}", item, count);
                    Tool tool = tools.get("give_item");
                    if (tool == null) {
                        AiBotMod.LOGGER.error("[RemoteAI] give_item tool not found!");
                    } else {
                        ToolResult result = tool.execute(new String[]{item, String.valueOf(count)});
                        AiBotMod.LOGGER.info("[RemoteAI] give_item result: {}", result.getMessage());
                    }
                }
                case "goto" -> {
                    double x = params != null ? ((Number) params.getOrDefault("x", 0)).doubleValue() : 0;
                    double y = params != null ? ((Number) params.getOrDefault("y", 64)).doubleValue() : 64;
                    double z = params != null ? ((Number) params.getOrDefault("z", 0)).doubleValue() : 0;
                    bot.setTask(new GotoTask(x, y, z));
                }
                default -> {
                    AiBotMod.LOGGER.warn("[RemoteAI] Unknown action type: {}", type);
                    say("抱歉，我还不会做 " + type + " 这个动作");
                }
            }
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[RemoteAI] Failed to execute action {}: {}", type, e.getMessage());
        }
    }

    /** 发送状态更新到远程服务器 */

    private java.util.Map<String, Object> getGameStateForServer() {
        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return null;

        java.util.Map<String, Object> state = new java.util.HashMap<>();
        state.put("dimension", player.level().dimension().location().toString());
        state.put("x", player.getX());
        state.put("y", player.getY());
        state.put("z", player.getZ());
        state.put("health", player.getHealth());
        state.put("hunger", player.getFoodData().getFoodLevel());

        java.util.List<String> inventory = new java.util.ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                inventory.add(stack.getItem().builtInRegistryHolder().key().location().toString() + "x" + stack.getCount());
            }
        }
        state.put("inventory", inventory);

        return state;
    }

    private java.util.Map<String, Object> getEmotionStateForServer() {
        java.util.Map<String, Object> state = new java.util.HashMap<>();
        state.put("hunger", emotion.getHunger());
        state.put("tiredness", emotion.getTiredness());
        state.put("happiness", emotion.getHappiness());
        state.put("boredom", emotion.getBoredom());
        state.put("fear", emotion.getFear());
        return state;
    }

    private java.util.Map<String, Object> getPersonalityStateForServer() {
        java.util.Map<String, Object> state = new java.util.HashMap<>();
        state.put("diligence", personality.getDiligence());
        state.put("bravery", personality.getBravery());
        state.put("talkativeness", personality.getTalkativeness());
        state.put("optimism", personality.getOptimism());
        state.put("independence", personality.getIndependence());
        return state;
    }

    /** 发送状态更新到服务器 */
    public void sendStateUpdateToServer() {
        if ("remote".equalsIgnoreCase(Config.getAiMode()) && remoteAIProvider != null && remoteAIProvider.isConnected()) {
            var gameState = getGameStateForServer();
            var emotionState = getEmotionStateForServer();
            var personalityState = getPersonalityStateForServer();
            remoteAIProvider.sendStateUpdate(gameState, emotionState, personalityState);
        }
    }
}
