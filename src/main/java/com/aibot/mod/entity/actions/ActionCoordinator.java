package com.aibot.mod.entity.actions;

import com.aibot.mod.AiBotMod;
import com.aibot.mod.entity.AiBotEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 统一动作调度器（总控）。
 * 
 * 所有动作都注册到这里，由 coordinator 统一调度，消除过去多套动作系统打架的问题。
 * 
 * 调度流程：
 *   1. 外部调用 start(name, args, bot) → 查注册表 → 创建 BotAction → 调 start()
 *   2. 每 tick 调 tick(bot) → 执行当前动作
 *   3. 动作完成(tick返回true) 或 失败 → 自动清理，通知调用方
 *   4. 外部调 stop() → 强制中断当前动作
 * 
 * 使用示例：
 *   coordinator.register("chop", (bot, args) -> new ChopTreeAction(bot, 64));
 *   coordinator.start("chop", new String[]{"64"}, bot);
 */
public class ActionCoordinator {

    /** 动作注册表：名称 → 工厂函数 (bot, args[] → BotAction) */
    private final Map<String, BiFunction<AiBotEntity, String[], BotAction>> registry = new HashMap<>();

    /** 当前正在执行的动作 */
    private BotAction currentAction = null;

    /** 当前动作的名称 */
    private String currentActionName = "";

    // ========== 注册 ==========

    /**
     * 注册一个动作类型。
     * @param name   动作名称（小写，如 "chop"、"mine_stone"）
     * @param factory 工厂函数，接收 (bot, 参数数组) 返回 BotAction 实例
     */
    public void register(String name, BiFunction<AiBotEntity, String[], BotAction> factory) {
        registry.put(name.toLowerCase(), factory);
    }

    // ========== 启动 ==========

    /**
     * 启动一个动作。会自动停止当前正在执行的动作。
     * @param name 动作名称（已在注册表中注册）
     * @param args 字符串参数数组（与 processCommand 的 args 格式一致，可为空数组）
     * @param bot  执行的 Bot 实体
     * @return true 启动成功，false 动作不存在或启动失败
     */
    public boolean start(String name, String[] args, AiBotEntity bot) {
        // 停止当前动作
        stop(bot);

        // 查注册表
        BiFunction<AiBotEntity, String[], BotAction> factory = registry.get(name.toLowerCase());
        if (factory == null) {
            AiBotMod.LOGGER.warn("[Coordinator] Action '{}' not registered, available: {}", name, registry.keySet());
            return false;
        }

        // 创建实例
        BotAction action = factory.apply(bot, args != null ? args : new String[0]);
        if (action == null) return false;

        // 启动
        if (!action.start(bot)) {
            return false;
        }

        currentAction = action;
        currentActionName = action.getName();
        AiBotMod.LOGGER.info("[Coordinator] Started action: {}", name);
        return true;
    }

    // ========== 执行 ==========

    /**
     * 每 tick 执行当前动作。
     * @param bot 执行的 Bot 实体
     * @return true 表示这一 tick 动作刚完成（可用于触发后续逻辑）
     */
    public boolean tick(AiBotEntity bot) {
        if (currentAction == null) {
            AiBotMod.LOGGER.info("[Coordinator] tick - no current action (idle)");
            return false;
        }

        AiBotMod.LOGGER.info("[Coordinator] tick - action: {} (failed={}, completed={})",
            currentActionName, currentAction.isFailed(), currentAction.isCompleted());

        // 如果已经完成/失败，清理并返回
        if (currentAction.isFailed()) {
            currentAction.stop(bot);
            currentAction = null;
            currentActionName = "";
            return true;
        }
        if (currentAction.isCompleted()) {
            currentAction.stop(bot);
            currentAction = null;
            currentActionName = "";
            return true;
        }

        // 执行一 tick
        boolean done = currentAction.tick(bot);
        AiBotMod.LOGGER.info("[Coordinator] tick done, action done={}", done);
        if (done) {
            currentAction.stop(bot);
            currentAction = null;
            currentActionName = "";
            return true;
        }

        return false;
    }

    // ========== 停止 ==========

    /**
     * 强制停止当前动作。
     */
    public void stop(AiBotEntity bot) {
        if (currentAction != null) {
            currentAction.stop(bot);
            currentAction = null;
            currentActionName = "";
        }
    }

    // ========== 状态查询 ==========

    /** 当前是否空闲（没有在执行任何动作） */
    public boolean isIdle() {
        return currentAction == null;
    }

    /** 当前动作的名称（用于状态显示） */
    public String getCurrentActionName() {
        return currentActionName;
    }

    /** 当前动作实例（外部一般不直接使用） */
    public BotAction getCurrentAction() {
        return currentAction;
    }
}
