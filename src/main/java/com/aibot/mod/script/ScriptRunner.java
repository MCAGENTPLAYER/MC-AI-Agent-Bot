package com.aibot.mod.script;

import com.aibot.mod.AiBotMod;
import com.aibot.mod.actions.MinecraftAPIBridge;
import com.aibot.mod.agent.Tool;
import com.aibot.mod.agent.ToolResult;
import com.aibot.mod.script.ScriptEngine.ScriptAction;
import com.aibot.mod.script.ScriptEngine.ScriptStep;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionHand;

import java.util.HashMap;
import java.util.Map;

public class ScriptRunner implements Tool {
    private final ScriptAction script;

    public ScriptRunner(ScriptAction script) {
        this.script = script;
    }

    @Override
    public String getName() {
        return script.name();
    }

    @Override
    public String getDescription() {
        return "执行脚本: " + script.name();
    }

    @Override
    public Map<String, String> getParameters() {
        return new HashMap<>();
    }

    @Override
    public ToolResult execute(String[] args) {
        AiBotMod.LOGGER.info("[ScriptRunner] Running script: {}", script.name());

        new Thread(() -> {
            var mc = Minecraft.getInstance();
            var player = mc.player;
            if (player == null) return;

            for (int i = 0; i < script.steps().size(); i++) {
                ScriptStep step = script.steps().get(i);
                try {
                    executeStep(step, mc);
                } catch (Exception e) {
                    chatStatus("步骤 " + (i + 1) + " 出错: " + e.getMessage());
                    break;
                }
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            }

            chatStatus("脚本 " + script.name() + " 执行完成");
        }).start();

        return ToolResult.success("脚本 " + script.name() + " 开始执行");
    }

    private void executeStep(ScriptStep step, Minecraft mc) throws Exception {
        switch (step.type()) {
            case "say" -> {
                if (step.message() != null) chatMsg(step.message());
            }
            case "goto" -> {
                if (step.x() != null && step.y() != null && step.z() != null) {
                    MinecraftAPIBridge.gotoBlock(new BlockPos(
                        step.x().intValue(), step.y().intValue(), step.z().intValue()));
                }
            }
            case "rightclick" -> {
                var player = mc.player;
                if (player == null) return;
                var hitResult = mc.hitResult;
                if (hitResult != null && hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    var blockHit = (BlockHitResult) hitResult;
                    mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, blockHit);
                }
            }
            case "break" -> {
                var player = mc.player;
                if (player == null) return;
                var hitResult = mc.hitResult;
                if (hitResult != null && hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    var blockHit = (BlockHitResult) hitResult;
                    mc.gameMode.destroyBlock(blockHit.getBlockPos());
                }
            }
            case "wait" -> {
                int ms = step.count() != null ? step.count() * 1000 : 1000;
                Thread.sleep(ms);
            }
            default -> chatStatus("未知步骤类型: " + step.type());
        }
    }

    private void chatMsg(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.connection.sendCommand("say " + message);
        }
    }

    private void chatStatus(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.connection.sendCommand("say [AI Bot] " + message);
        }
    }
}
