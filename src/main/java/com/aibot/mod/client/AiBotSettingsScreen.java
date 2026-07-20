package com.aibot.mod.client;

import com.aibot.mod.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AiBotSettingsScreen extends Screen {
    private static final int WIDGET_W = 140;
    private static final int WIDGET_H = 20;
    private static final int VIEWPORT_TOP = 25;
    private static final int SCROLL_SPEED = 20;

    // Toggle buttons
    private Button homeModeBtn, autoStoreBtn;

    // Home pos inputs
    private EditBox homeXInput, homeYInput, homeZInput;

    // Radius
    private Button radiusValBtn, radiusIncBtn, radiusDecBtn;

    // Halo controls
    private Button haloEnabledBtn, haloGlowBtn, angleValBtn, angleDecBtn, angleIncBtn;
    private Button offsetValBtn, offsetDecBtn, offsetIncBtn;
    private Button sizeValBtn, sizeDecBtn, sizeIncBtn;
    private Button heightValBtn, heightDecBtn, heightIncBtn;

    // API settings
    private EditBox modelInput, apiUrlInput, serverUrlInput;

    // Skin
    private Button skinBtn;

    // Layout tracking
    private int left, right;
    private int totalContentHeight = 0;

    // Scrolling
    private int scrollOffset = 0;

    public AiBotSettingsScreen() {
        super(Component.literal("AI Bot 设置"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        left = cx - 155;
        right = cx + 15;
        int y = 30 - scrollOffset;

        // Row 1: 住家模式
        homeModeBtn = addRenderableWidget(Button.builder(
            toggleLabel("住家模式", Config.isHomeMode()),
            btn -> toggleHomeMode()
        ).bounds(left, y, WIDGET_W, WIDGET_H).build());
        y += 26;

        // Row 2: 自动存箱
        autoStoreBtn = addRenderableWidget(Button.builder(
            toggleLabel("自动存箱", Config.isAutoStore()),
            btn -> { Config.setAutoStore(!Config.isAutoStore()); refreshToggles(); }
        ).bounds(left, y, 100, WIDGET_H).build());
        y += 30;

        // Row 3: 家坐标输入
        homeXInput = new EditBox(font, left, y, 70, WIDGET_H, Component.literal("X"));
        homeXInput.setValue(String.valueOf(Config.getHomeX()));
        homeXInput.setFilter(s -> s.matches("-?\\d*"));
        addRenderableWidget(homeXInput);

        homeYInput = new EditBox(font, left + 75, y, 70, WIDGET_H, Component.literal("Y"));
        homeYInput.setValue(String.valueOf(Config.getHomeY()));
        homeYInput.setFilter(s -> s.matches("-?\\d*"));
        addRenderableWidget(homeYInput);

        homeZInput = new EditBox(font, left + 150, y, 70, WIDGET_H, Component.literal("Z"));
        homeZInput.setValue(String.valueOf(Config.getHomeZ()));
        homeZInput.setFilter(s -> s.matches("-?\\d*"));
        addRenderableWidget(homeZInput);

        addRenderableWidget(Button.builder(
            Component.literal("当前位置"),
            btn -> {
                var player = Minecraft.getInstance().player;
                if (player != null) {
                    homeXInput.setValue(String.valueOf((int) player.getX()));
                    homeYInput.setValue(String.valueOf((int) player.getY()));
                    homeZInput.setValue(String.valueOf((int) player.getZ()));
                }
            }
        ).bounds(left + 225, y, 90, WIDGET_H).build());
        y += 26;

        // Row 4: 活动半径
        radiusDecBtn = addRenderableWidget(Button.builder(
            Component.literal("-"),
            btn -> { Config.setHomeRadius(Config.getHomeRadius() - 8); refreshRadius(); }
        ).bounds(left, y, 30, WIDGET_H).build());

        radiusValBtn = addRenderableWidget(Button.builder(
            Component.literal(Config.getHomeRadius() + " 格"),
            btn -> {}
        ).bounds(left + 35, y, 80, WIDGET_H).build());

        radiusIncBtn = addRenderableWidget(Button.builder(
            Component.literal("+"),
            btn -> { Config.setHomeRadius(Config.getHomeRadius() + 8); refreshRadius(); }
        ).bounds(left + 120, y, 30, WIDGET_H).build());
        y += 30;

        // ====== 光环设置 ======
        // Row 5: 光环开关
        haloEnabledBtn = addRenderableWidget(Button.builder(
            toggleLabel("光环", Config.isHaloEnabled()),
            btn -> { Config.setHaloEnabled(!Config.isHaloEnabled()); refreshHalo(); }
        ).bounds(left, y, 140, WIDGET_H).build());
        y += 26;

        // Row 6: 发光开关
        haloGlowBtn = addRenderableWidget(Button.builder(
            toggleLabel("光环发光", Config.isHaloGlow()),
            btn -> { Config.setHaloGlow(!Config.isHaloGlow()); refreshHalo(); }
        ).bounds(left, y, 140, WIDGET_H).build());
        y += 26;

        // Row 6: 光环角度
        angleDecBtn = addRenderableWidget(Button.builder(
            Component.literal("-"),
            btn -> { Config.setHaloAngle(Config.getHaloAngle() - 5); refreshHalo(); }
        ).bounds(left, y, 30, WIDGET_H).build());

        angleValBtn = addRenderableWidget(Button.builder(
            Component.literal(Config.getHaloAngle() + "°"),
            btn -> {}
        ).bounds(left + 35, y, 80, WIDGET_H).build());

        angleIncBtn = addRenderableWidget(Button.builder(
            Component.literal("+"),
            btn -> { Config.setHaloAngle(Config.getHaloAngle() + 5); refreshHalo(); }
        ).bounds(left + 120, y, 30, WIDGET_H).build());
        y += 26;

        // Row 7: 光环前后偏移
        offsetDecBtn = addRenderableWidget(Button.builder(
            Component.literal("-"),
            btn -> { Config.setHaloOffsetZ(Config.getHaloOffsetZ() - 0.1F); refreshHalo(); }
        ).bounds(left, y, 30, WIDGET_H).build());

        offsetValBtn = addRenderableWidget(Button.builder(
            Component.literal(String.format("%.1f 格", Config.getHaloOffsetZ())),
            btn -> {}
        ).bounds(left + 35, y, 80, WIDGET_H).build());

        offsetIncBtn = addRenderableWidget(Button.builder(
            Component.literal("+"),
            btn -> { Config.setHaloOffsetZ(Config.getHaloOffsetZ() + 0.1F); refreshHalo(); }
        ).bounds(left + 120, y, 30, WIDGET_H).build());
        y += 26;

        // Row 8: 光环大小
        sizeDecBtn = addRenderableWidget(Button.builder(
            Component.literal("-"),
            btn -> { Config.setHaloSize(Config.getHaloSize() - 0.05F); refreshHalo(); }
        ).bounds(left, y, 30, WIDGET_H).build());

        sizeValBtn = addRenderableWidget(Button.builder(
            Component.literal(String.format("%.2f", Config.getHaloSize())),
            btn -> {}
        ).bounds(left + 35, y, 80, WIDGET_H).build());

        sizeIncBtn = addRenderableWidget(Button.builder(
            Component.literal("+"),
            btn -> { Config.setHaloSize(Config.getHaloSize() + 0.05F); refreshHalo(); }
        ).bounds(left + 120, y, 30, WIDGET_H).build());
        y += 26;

        // Row 9: 光环高度
        heightDecBtn = addRenderableWidget(Button.builder(
            Component.literal("-"),
            btn -> { Config.setHaloHeight(Config.getHaloHeight() - 0.1F); refreshHalo(); }
        ).bounds(left, y, 30, WIDGET_H).build());

        heightValBtn = addRenderableWidget(Button.builder(
            Component.literal(String.format("%.1f", Config.getHaloHeight())),
            btn -> {}
        ).bounds(left + 35, y, 80, WIDGET_H).build());

        heightIncBtn = addRenderableWidget(Button.builder(
            Component.literal("+"),
            btn -> { Config.setHaloHeight(Config.getHaloHeight() + 0.1F); refreshHalo(); }
        ).bounds(left + 120, y, 30, WIDGET_H).build());
        y += 30;

        // Row 10: 皮肤切换
        skinBtn = addRenderableWidget(Button.builder(
            Component.literal("皮肤: skin" + Config.getSkin()),
            btn -> toggleSkin()
        ).bounds(left, y, 140, WIDGET_H).build());
        y += 26;

        // Row 11: 模型名称
        modelInput = new EditBox(font, left, y, 250, WIDGET_H, Component.literal("Model"));
        modelInput.setValue(Config.getModel());
        addRenderableWidget(modelInput);
        y += 26;

        // Row 12: API 地址
        apiUrlInput = new EditBox(font, left, y, 250, WIDGET_H, Component.literal("API URL"));
        apiUrlInput.setValue(Config.getApiUrl());
        addRenderableWidget(apiUrlInput);
        y += 26;

        // Row 13: 服务器地址
        serverUrlInput = new EditBox(font, left, y, 250, WIDGET_H, Component.literal("Server URL"));
        serverUrlInput.setValue(Config.getServerUrl());
        addRenderableWidget(serverUrlInput);
        y += 30;

        // Row 13: 保存按钮
        addRenderableWidget(Button.builder(
            Component.literal("保存并关闭"),
            btn -> saveAndClose()
        ).bounds(cx - 75, y, 150, WIDGET_H).build());
        y += 30;

        totalContentHeight = y + scrollOffset - 30; // 恢复成绝对总高度
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // 背景
        gfx.fill(0, 0, this.width, this.height, 0xCC111111);

        // 裁剪视口（标题 + 内容区）
        int vpBottom = this.height - 10;
        gfx.enableScissor(0, VIEWPORT_TOP, this.width, vpBottom);
        super.render(gfx, mouseX, mouseY, partialTick);
        gfx.disableScissor();

        // 标题（始终可见）
        gfx.drawString(this.font, this.title,
            (this.width - this.font.width(this.title)) / 2, 10, 0xFFFFFF, false);

        // 标签（跟随滚动）
        int ly = 30 + 26 + 30 - scrollOffset; // "家坐标"标签
        gfx.drawString(this.font, "家坐标", left, ly - 12, 0xAAAAAA, false);
        ly += 26;
        gfx.drawString(this.font, "活动半径", left, ly - 12, 0xAAAAAA, false);
        ly += 30;
        gfx.drawString(this.font, "§e=== 光环设置 ===", left, ly - 12, 0xFFFFAA, false);
        ly += 26*7 + 30;
        gfx.drawString(this.font, "皮肤", left, ly - 12, 0xAAAAAA, false);
        ly += 26;
        gfx.drawString(this.font, "模型", left, ly - 12, 0xAAAAAA, false);
        ly += 26;
        gfx.drawString(this.font, "API", left, ly - 12, 0xAAAAAA, false);

        ly += 26;
        gfx.drawString(this.font, "服务器", left, ly - 12, 0xAAAAAA, false);

        // 滚动指示器
        int maxScroll = Math.max(0, totalContentHeight - (vpBottom - VIEWPORT_TOP));
        if (maxScroll > 0) {
            float progress = (float) scrollOffset / maxScroll;
            int barH = Math.max(20, (int)((float)(vpBottom - VIEWPORT_TOP) / totalContentHeight * (vpBottom - VIEWPORT_TOP)));
            int barY = VIEWPORT_TOP + (int)(progress * (vpBottom - VIEWPORT_TOP - barH));
            // 滚动条轨道
            gfx.fill(this.width - 6, VIEWPORT_TOP, this.width - 2, vpBottom, 0x33FFFFFF);
            // 滚动条滑块
            gfx.fill(this.width - 6, barY, this.width - 2, barY + barH, 0xAAFFFFFF);
        }
    }

    // === 按钮逻辑 ===

    private void toggleHomeMode() {
        Config.setHomeMode(!Config.isHomeMode());
        applyHomePos();
        sendCmd("homemode");
        refreshToggles();
    }

    private void applyHomePos() {
        try {
            int x = Integer.parseInt(homeXInput.getValue());
            int y = Integer.parseInt(homeYInput.getValue());
            int z = Integer.parseInt(homeZInput.getValue());
            Config.setHomePos(x, y, z);
            sendCmd("sethome " + x + " " + y + " " + z);
        } catch (NumberFormatException e) { /* ignore */ }
    }

    private void refreshToggles() {
        homeModeBtn.setMessage(toggleLabel("住家模式", Config.isHomeMode()));
        autoStoreBtn.setMessage(toggleLabel("自动存箱", Config.isAutoStore()));
    }

    private void refreshRadius() {
        radiusValBtn.setMessage(Component.literal(Config.getHomeRadius() + " 格"));
    }

    private void refreshHalo() {
        haloEnabledBtn.setMessage(toggleLabel("光环", Config.isHaloEnabled()));
        haloGlowBtn.setMessage(toggleLabel("光环发光", Config.isHaloGlow()));
        angleValBtn.setMessage(Component.literal(Config.getHaloAngle() + "°"));
        offsetValBtn.setMessage(Component.literal(String.format("%.1f 格", Config.getHaloOffsetZ())));
        sizeValBtn.setMessage(Component.literal(String.format("%.2f", Config.getHaloSize())));
        heightValBtn.setMessage(Component.literal(String.format("%.1f", Config.getHaloHeight())));
    }

    private void toggleSkin() {
        int cur = Config.getSkin();
        Config.setSkin(cur == 1 ? 2 : 1);
        skinBtn.setMessage(Component.literal("皮肤: skin" + Config.getSkin()));
    }

    private void saveAndClose() {
        applyHomePos();
        Config.setModel(modelInput.getValue());
        Config.setApiUrl(apiUrlInput.getValue());
        Config.setServerUrl(serverUrlInput.getValue());
        this.onClose();
    }

    private static Component toggleLabel(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§a✔ ON" : "§7✘ OFF"));
    }

    private void sendCmd(String cmd) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.connection != null) {
            player.connection.sendCommand("aibot " + cmd);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int vpBottom = this.height - 10;
        int maxScroll = Math.max(0, totalContentHeight - (vpBottom - VIEWPORT_TOP));
        int oldScroll = scrollOffset;
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int)(delta * SCROLL_SPEED)));
        if (scrollOffset != oldScroll) {
            // 保存输入框值
            String[] saved = new String[]{
                modelInput.getValue(), apiUrlInput.getValue(), serverUrlInput.getValue(),
                homeXInput.getValue(), homeYInput.getValue(), homeZInput.getValue()
            };
            this.clearWidgets();
            init();
            modelInput.setValue(saved[0]);
            apiUrlInput.setValue(saved[1]);
            serverUrlInput.setValue(saved[2]);
            homeXInput.setValue(saved[3]);
            homeYInput.setValue(saved[4]);
            homeZInput.setValue(saved[5]);
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
