package com.aibot.mod.task;

import com.aibot.mod.AiBotMod;
import com.aibot.mod.ConversationLogger;
import com.aibot.mod.entity.AiBotEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务链执行引擎 — 按顺序执行任务链中的每一步。
 * <p>
 * 执行模式：
 * - 普通步骤（CRAFT/MINE/CHOP 等）：创建对应的 BaseTask 交由 Bot 执行
 * - 特殊步骤（TELEPORT/GIVE/WAIT）：引擎直接执行
 * <p>
 * 可靠性设计：
 * - 每步超时：超时后标记失败，可重试
 * - 每步重试：最多重试 2 次
 * - 持久化：每步完成后自动保存
 * - 崩溃恢复：启动时自动加载未完成的链
 */
public class TaskChainExecutor {

    private final Path saveDir;
    private TaskChain chain;
    private boolean isInitializingStep = false;  // 正在初始化步骤
    private int waitTickCounter = 0;             // WAIT 步骤计时
    private long lastSavedAt = 0;
    private static final long SAVE_INTERVAL_MS = 5000; // 5秒自动保存

    public TaskChainExecutor(Path saveDir) {
        this.saveDir = saveDir;
        // 启动时尝试恢复未完成的链
        this.chain = TaskChain.load(saveDir);
        if (this.chain != null && !this.chain.isDone() && this.chain.getStatus() == TaskChain.ChainStatus.RUNNING) {
            AiBotMod.LOGGER.info("[TaskChain] 恢复未完成的任务链: {} (Step {}/{})",
                chain.getName(), chain.getCurrentStepIndex() + 1, chain.getSteps().size());
            // 当前步骤需要重新激活
            TaskChain.Step current = chain.getCurrentStep();
            if (current != null && current.status == TaskChain.StepStatus.RUNNING) {
                // 可能正在执行中，重置为 PENDING 让 tick 重新启动
                current.status = TaskChain.StepStatus.PENDING;
            }
        } else {
            this.chain = null;
        }
    }

    /** 向所有玩家广播消息 */
    private void broadcast(AiBotEntity bot, String msg) {
        if (bot.getServer() != null) {
            bot.getServer().getPlayerList().broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal(msg), false);
        }
    }

    /** 当前是否有正在执行的链 */
    public boolean hasActiveChain() {
        return chain != null && !chain.isDone();
    }

    /** 获取当前链（可能为 null） */
    public TaskChain getChain() { return chain; }

    /** 获取链状态的简短文本 */
    public String getChainStatusText() {
        if (chain == null) return "";
        return chain.formatShortStatus();
    }

    /** 获取链进度的完整文本 */
    public String getChainProgressText() {
        if (chain == null) return "";
        return chain.formatProgressForAI();
    }

    // ==================== 链管理 ====================

    /** 开始执行一个新的任务链 */
    public void startChain(TaskChain newChain, AiBotEntity bot) {
        // 如果有正在执行的链，先取消
        if (hasActiveChain()) {
            chain.cancel();
        }
        this.chain = newChain;
        chain.start();
        chain.save(saveDir);
        isInitializingStep = false;
        AiBotMod.LOGGER.info("[TaskChain] 开始执行任务链: {} ({} 步)", chain.getName(), chain.getSteps().size());
        ConversationLogger.logBotCommand("TaskChain 启动: " + chain.getName() + " [" + chain.getSteps().size() + "步]");
        broadcast(bot, "§e[Bot]§f 开始执行任务: " + chain.getName() + "（共" + chain.getSteps().size() + "步）");
    }

    /** 取消当前链 */
    public void cancelChain(AiBotEntity bot) {
        if (chain == null) return;
        chain.cancel();
        chain.save(saveDir);
        AiBotMod.LOGGER.info("[TaskChain] 取消任务链: {}", chain.getName());
        ConversationLogger.logBotCommand("TaskChain 取消: " + chain.getName());
        broadcast(bot, "§e[Bot]§f 任务已取消: " + chain.getName());
        // 停止当前任务
        bot.setTask(null);
        bot.coordinator.stop(bot);
    }

    /** 跳过当前步骤 */
    public void skipCurrentStep(AiBotEntity bot) {
        if (chain == null) return;
        chain.skipCurrentStep();
        // 停止当前任务
        bot.setTask(null);
        bot.coordinator.stop(bot);
        // 前进到下一步
        if (!chain.advance()) {
            // 链完成
            onChainComplete();
        }
        chain.save(saveDir);
        isInitializingStep = false;
        AiBotMod.LOGGER.info("[TaskChain] 跳过步骤: {}", chain.getCurrentStepIndex());
    }

    // ==================== 核心 tick ====================

    /**
     * 每 tick 调用，驱动任务链执行。
     * 返回 true 表示有步骤完成了（供外部触发回调）。
     */
    public boolean tick(AiBotEntity bot) {
        if (!hasActiveChain()) return false;

        // 定期自动保存
        long now = System.currentTimeMillis();
        if (now - lastSavedAt > SAVE_INTERVAL_MS) {
            chain.save(saveDir);
            lastSavedAt = now;
        }

        TaskChain.Step current = chain.getCurrentStep();
        if (current == null) return false;

        switch (current.status) {
            case PENDING -> {
                // 开始执行当前步骤
                return startStep(bot, current);
            }
            case RUNNING -> {
                // 执行中，检查完成状态
                return checkStepCompletion(bot, current);
            }
            case COMPLETED -> {
                // 步骤已完成，前进
                if (chain.advance()) {
                    isInitializingStep = false;
                    AiBotMod.LOGGER.info("[TaskChain] Step {}/{} 完成，前进到下一步",
                        chain.getCurrentStepIndex(), chain.getSteps().size());
                    chain.save(saveDir);
                } else {
                    // 链全部完成
                    onChainComplete();
                    chain.save(saveDir);
                    return true;
                }
                return false;
            }
            case FAILED -> {
                // 步骤重试耗尽 — 跳过并通知玩家
                if (chain.getStatus() != TaskChain.ChainStatus.FAILED) {
                    // 如果链还没标记失败（比如之前已通过 stepFailed 标记），通知玩家
                    broadcast(bot, "§c[Bot]§f 步骤 " + (chain.getCurrentStepIndex() + 1) + "/" 
                        + chain.getSteps().size() + " 失败: " + current.result);
                }
                AiBotMod.LOGGER.warn("[TaskChain] Step {} 失败，跳过: {}", chain.getCurrentStepIndex(), current.result);
                if (chain.advance()) {
                    isInitializingStep = false;
                    chain.save(saveDir);
                } else {
                    // 最后一个步骤也失败了 — 链已由 stepFailed 标记失败，无需再 call
                    chain.save(saveDir);
                    return true;
                }
                return false;
            }
            case SKIPPED -> {
                if (chain.advance()) {
                    isInitializingStep = false;
                    chain.save(saveDir);
                } else {
                    onChainComplete();
                    chain.save(saveDir);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /** 开始执行当前步骤 */
    private boolean startStep(AiBotEntity bot, TaskChain.Step current) {
        if (isInitializingStep) return false;
        isInitializingStep = true;

        current.status = TaskChain.StepStatus.RUNNING;
        current.startedAt = System.currentTimeMillis();

        AiBotMod.LOGGER.info("[TaskChain] 开始 Step {}/{}: {}",
            chain.getCurrentStepIndex() + 1, chain.getSteps().size(), current.getDisplayName());
        ConversationLogger.logBotCommand("TaskChain Step "
            + (chain.getCurrentStepIndex() + 1) + "/" + chain.getSteps().size()
            + ": " + current.getDisplayName());

        broadcast(bot, "§e[Bot]§f Step " + (chain.getCurrentStepIndex() + 1)
            + "/" + chain.getSteps().size() + ": " + current.getDisplayName());

        chain.save(saveDir);

        switch (current.type) {
            case CRAFT -> {
                String item = current.getParam("item", "");
                int count = current.getIntParam("count", 1);
                if (item.isEmpty()) {
                    stepFailed(bot, current, "未指定物品");
                    return true;
                }
                bot.setTask(new CraftTask(item, count));
                return false;
            }
            case MINE -> {
                int count = current.getIntParam("count", 1);
                String mode = current.getParam("mode", "mine");
                bot.setTask(new MineTask(count, mode));
                return false;
            }
            case CHOP -> {
                int count = current.getIntParam("count", 1);
                bot.setTask(new ChopTask(count));
                return false;
            }
            case FARM -> {
                bot.setTask(new FarmTask());
                return false;
            }
            case HUNT -> {
                bot.setTask(new HuntTask());
                return false;
            }
            case GOTO -> {
                double x = current.getDoubleParam("x", 0);
                double y = current.getDoubleParam("y", 64);
                double z = current.getDoubleParam("z", 0);
                bot.setTask(new GotoTask(x, y, z));
                return false;
            }
            case FOLLOW -> {
                bot.setTask(new FollowTask());
                return false;
            }
            case SLEEP -> {
                bot.setTask(new SleepTask());
                return false;
            }
            case EAT -> {
                bot.setTask(new EatTask());
                return false;
            }
            case INTERACT -> {
                String target = current.getParam("target", "");
                String item = current.getParam("item", null);
                if (target.isEmpty()) {
                    stepFailed(bot, current, "未指定交互目标");
                    return true;
                }
                bot.setTask(new InteractTask(target, item));
                return false;
            }
            case COOK -> {
                // 烹饪需要完整参数，暂不支持从链中执行
                stepFailed(bot, current, "烹饪暂不支持任务链，请直接使用 cook 工具");
                return true;
            }
            case TELEPORT -> {
                // 跨维度传送 - 直接执行
                boolean success = executeTeleport(bot);
                if (success) {
                    stepCompleted(bot, current, "传送成功");
                } else {
                    stepFailed(bot, current, "传送失败（找不到玩家）");
                }
                return true;
            }
            case GIVE -> {
                // 给予物品 - 直接执行
                String item = current.getParam("item", "");
                int count = current.getIntParam("count", 1);
                boolean success = executeGive(bot, item, count);
                if (success) {
                    stepCompleted(bot, current, "给予 " + item + " x" + count);
                } else {
                    stepFailed(bot, current, "给予失败（背包中没有 " + item + "）");
                }
                return true;
            }
            case WAIT -> {
                waitTickCounter = 0;
                return false;
            }
            default -> {
                stepFailed(bot, current, "未知步骤类型: " + current.type);
                return true;
            }
        }
    }

    /** 检查步骤是否完成 */
    private boolean checkStepCompletion(AiBotEntity bot, TaskChain.Step current) {
        switch (current.type) {
            case CRAFT, MINE, CHOP, FARM, HUNT, GOTO, FOLLOW, SLEEP, EAT, INTERACT -> {
                // 这些类型通过 BaseTask 执行，检查 bot 当前 task 状态
                BaseTask currentTask = bot.getCurrentTask();
                if (currentTask == null || currentTask.isDone()) {
                    if (currentTask != null && currentTask.isSuccess()) {
                        stepCompleted(bot, current, currentTask.getName() + " 完成");
                    } else if (currentTask != null) {
                        // 任务失败，尝试重试
                        boolean canRetry = chain.failCurrentStep(currentTask.getFailReason());
                        if (canRetry) {
                            AiBotMod.LOGGER.info("[TaskChain] Step {} 失败但可重试: {}",
                                chain.getCurrentStepIndex(), currentTask.getFailReason());
                            isInitializingStep = false;
                            chain.save(saveDir);
                            return true; // 下一 tick 会重新开始
                        } else {
                            stepFailed(bot, current, currentTask.getFailReason());
                        }
                    } else {
                        // 任务被意外清除了
                        boolean canRetry = chain.failCurrentStep("任务意外中断");
                        if (canRetry) {
                            isInitializingStep = false;
                            chain.save(saveDir);
                            return true;
                        } else {
                            stepFailed(bot, current, "任务意外中断");
                        }
                    }
                    return true;
                }
                // 检查超时
                if (current.startedAt > 0) {
                    long elapsed = System.currentTimeMillis() - current.startedAt;
                    long timeoutMs = current.timeoutTicks * 50L; // tick → ms
                    if (elapsed > timeoutMs) {
                        boolean canRetry = chain.failCurrentStep("执行超时（" + (timeoutMs / 1000) + "秒）");
                        if (canRetry) {
                            AiBotMod.LOGGER.info("[TaskChain] Step {} 超时可重试", chain.getCurrentStepIndex());
                            isInitializingStep = false;
                            bot.setTask(null);
                            bot.coordinator.stop(bot);
                            chain.save(saveDir);
                            return true;
                        } else {
                            stepFailed(bot, current, "执行超时（" + (timeoutMs / 1000) + "秒）");
                            bot.setTask(null);
                            bot.coordinator.stop(bot);
                        }
                        return true;
                    }
                }
                return false;
            }
            case WAIT -> {
                waitTickCounter++;
                if (waitTickCounter >= current.getIntParam("ticks", 20)) {
                    stepCompleted(bot, current, "等待完成");
                    return true;
                }
                return false;
            }
            case TELEPORT, GIVE -> {
                // 这些是即时完成的，停留在 COMPLETED 状态
                return false;
            }
            default -> {
                stepCompleted(bot, current, "完成");
                return true;
            }
        }
    }

    // ==================== 特殊步骤执行 ====================

    /** 执行跨维度传送 */
    private boolean executeTeleport(AiBotEntity bot) {
        try {
            LivingEntity target = bot.findNearestPlayer();
            if (target == null) return false;

            ResourceKey<net.minecraft.world.level.Level> currentDim = bot.level().dimension();
            ResourceKey<net.minecraft.world.level.Level> targetDim = target.level().dimension();

            double tx = target.getX();
            double ty = target.getY();
            double tz = target.getZ();

            // 下界↔主世界坐标转换
            if (currentDim == net.minecraft.world.level.Level.NETHER && targetDim == net.minecraft.world.level.Level.OVERWORLD) {
                tx *= 8;
                tz *= 8;
            } else if (currentDim == net.minecraft.world.level.Level.OVERWORLD && targetDim == net.minecraft.world.level.Level.NETHER) {
                tx /= 8;
                tz /= 8;
            }

            if (currentDim != targetDim && bot.getServer() != null) {
                ServerLevel targetLevel = bot.getServer().getLevel(targetDim);
                if (targetLevel != null) {
                    bot.teleportTo(targetLevel, tx, ty, tz, java.util.Set.of(), bot.getYRot(), bot.getXRot());
                } else {
                    return false;
                }
            } else {
                bot.teleportTo(tx, ty, tz);
            }

            broadcast(bot, "§e[Bot]§f 传送到了你身边！");
            return true;
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[TaskChain] 传送失败: {}", e.getMessage());
            return false;
        }
    }

    /** 执行给予物品 */
    private boolean executeGive(AiBotEntity bot, String itemId, int count) {
        try {
            if (itemId.isEmpty()) return false;

            // 解析物品 ID
            String resolvedId = itemId;
            if (!resolvedId.contains(":")) resolvedId = "minecraft:" + resolvedId;

            ResourceLocation rl = ResourceLocation.tryParse(resolvedId);
            if (rl == null) return false;

            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item == null) return false;

            // 查找背包中的物品并投掷给玩家
            int remaining = count;
            LivingEntity player = bot.findNearestPlayer();
            if (player == null) return false;

            for (int i = 0; i < bot.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack stack = bot.getInventory().getItem(i);
                if (stack.is(item)) {
                    int giveCount = Math.min(stack.getCount(), remaining);
                    ItemStack giveStack = stack.split(giveCount);
                    if (stack.isEmpty()) bot.getInventory().setItem(i, ItemStack.EMPTY);
                    remaining -= giveCount;

                    // 投掷到玩家位置
                    ItemStack dropStack = giveStack.copy();
                    bot.spawnAtLocation(dropStack, 1.0f);
                }
            }

            return remaining < count; // 至少给了部分
        } catch (Exception e) {
            AiBotMod.LOGGER.error("[TaskChain] 给予物品失败: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 状态管理 ====================

    private void stepCompleted(AiBotEntity bot, TaskChain.Step step, String result) {
        step.result = result;
        step.completedAt = System.currentTimeMillis();
        step.status = TaskChain.StepStatus.COMPLETED;
        isInitializingStep = false;
        ConversationLogger.logBotCommand("TaskChain Step ✅: " + step.getDisplayName() + " — " + result);
        AiBotMod.LOGGER.info("[TaskChain] Step {} 完成: {} — {}", chain.getCurrentStepIndex(), step.getDisplayName(), result);
        chain.save(saveDir);
    }

    private void stepFailed(AiBotEntity bot, TaskChain.Step step, String reason) {
        boolean canRetry = chain.failCurrentStep(reason);
        if (canRetry) {
            AiBotMod.LOGGER.info("[TaskChain] Step {} 失败可重试: {}", chain.getCurrentStepIndex(), reason);
            int remaining = step.maxRetries - step.retryCount;
            broadcast(bot, "§e[Bot]§f Step " + (chain.getCurrentStepIndex() + 1) + "/" 
                + chain.getSteps().size() + " 失败，准备第 " + (step.retryCount + 1) + " 次重试: " + reason);
            isInitializingStep = false;
            chain.save(saveDir);
        } else {
            step.status = TaskChain.StepStatus.FAILED;
            step.result = reason;
            isInitializingStep = false;
            ConversationLogger.logBotCommand("TaskChain Step ❌: " + step.getDisplayName() + " — " + reason);
            broadcast(bot, "§c[Bot]§f Step " + (chain.getCurrentStepIndex() + 1) + "/" 
                + chain.getSteps().size() + " 失败: " + step.getDisplayName() + " — " + reason);

            // 检查是否还有后续步骤
            if (chain.getCurrentStepIndex() < chain.getSteps().size() - 1) {
                broadcast(bot, "§e[Bot]§f 将跳过此步骤继续执行...");
            }

            // 检查整个链是否结束
            if (chain.getStatus() != TaskChain.ChainStatus.FAILED) {
                chain.failChain("步骤 " + step.getDisplayName() + " 失败: " + reason);
                onChainFailed(bot);
            }
            chain.save(saveDir);
        }
    }

    private void onChainComplete() {
        if (chain == null) return;
        AiBotMod.LOGGER.info("[TaskChain] 任务链完成: {} ({} 步)", chain.getName(), chain.getSteps().size());
        ConversationLogger.logBotCommand("TaskChain ✅ 完成: " + chain.getName());
        // 注入 AI 上下文由下一条消息触发时自动注入
        chain.markNotified();
        chain.save(saveDir);
    }

    private void onChainFailed(AiBotEntity bot) {
        if (chain == null) return;
        AiBotMod.LOGGER.warn("[TaskChain] 任务链失败: {} — {}", chain.getName(), chain.getFailReason());
        ConversationLogger.logBotCommand("TaskChain ❌ 失败: " + chain.getName() + " — " + chain.getFailReason());
        broadcast(bot, "§c[Bot]§f 任务链 [" + chain.getName() + "] 执行失败: " + chain.getFailReason());
        broadcast(bot, "§e[Bot]§f 你可以用 \"重试\" 或 \"继续\" 让我重新尝试失败的步骤");
        chain.markNotified();
        chain.save(saveDir);
    }

    /** 从失败步骤恢复执行（重置重试计数） */
    public void resumeChain(AiBotEntity bot) {
        if (chain == null || chain.getStatus() != TaskChain.ChainStatus.FAILED) {
            broadcast(bot, "§e[Bot]§f 当前没有失败的任务链需要恢复");
            return;
        }
        chain.resume();
        isInitializingStep = false;
        AiBotMod.LOGGER.info("[TaskChain] 恢复任务链: {}", chain.getName());
        broadcast(bot, "§e[Bot]§f 正在重试任务链: " + chain.getName());
        chain.save(saveDir);
    }

    /** 获取链完成通知文本（用于 AI 主动汇报/处理失败后决策） */
    public String getCompletionNotification() {
        if (chain == null || !chain.isDone() || chain.isNotifiedCompletion()) return null;
        chain.markNotified();
        if (chain.getStatus() == TaskChain.ChainStatus.COMPLETED) {
            return "任务链 \"" + chain.getName() + "\" 全部完成！🎉（共" + chain.getSteps().size() + "步）";
        } else if (chain.getStatus() == TaskChain.ChainStatus.FAILED) {
            return "任务链 \"" + chain.getName() + "\" 执行失败了：" + chain.getFailReason() 
                + "。你可以告诉玩家失败原因，或者让玩家用 \"重试\" 命令让我重新尝试。";
        }
        return null;
    }

    /** 清除已完成/失败的链 */
    public void clearCompleted(AiBotEntity bot) {
        if (chain != null && chain.isDone()) {
            TaskChain.deleteSave(saveDir);
            chain = null;
            AiBotMod.LOGGER.info("[TaskChain] 清除已完成链");
        }
    }

    /** 保存当前链 */
    public void save() {
        if (chain != null) {
            chain.save(saveDir);
        }
    }
}
