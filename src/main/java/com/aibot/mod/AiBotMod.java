package com.aibot.mod;

import com.aibot.mod.entity.AiBotEntity;
import com.aibot.mod.entity.AiBotRenderer;
import com.aibot.mod.entity.AiBotScreen;
import com.aibot.mod.entity.ModEntities;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("ai_bot")
public class AiBotMod {
    public static final String MODID = "ai_bot";
    public static final Logger LOGGER = LogManager.getLogger();

    private static BotController botController;

    public AiBotMod() {
        ModEntities.ENTITIES.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModEntities.ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModEntities.MENUS.register(FMLJavaModLoadingContext.get().getModEventBus());

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onEntityAttributeCreation);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onEntityRenderers);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[AI Bot] Common setup completed");
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        try {
            Config.reload();
            ConversationLogger.init(net.minecraft.client.Minecraft.getInstance().gameDirectory.getAbsolutePath());
            botController = new BotController();
            ChatHandler chatHandler = new ChatHandler(botController);
            botController.setChatHandler(chatHandler);
            MinecraftForge.EVENT_BUS.register(chatHandler);
            MinecraftForge.EVENT_BUS.register(botController);
            MinecraftForge.EVENT_BUS.register(new GameControlHandler());
            MinecraftForge.EVENT_BUS.register(new HudOverlay());
            
            net.minecraft.client.gui.screens.MenuScreens.register(
                    ModEntities.AI_BOT_MENU.get(), AiBotScreen::new);
            
            LOGGER.info("[AI Bot] Mod initialized. Bot name: {}", Config.getBotName());
        } catch (Throwable t) {
            LOGGER.error("[AI Bot] Failed to initialize: {}", t.getMessage(), t);
        }
    }

    public void onEntityRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.AI_BOT_ENTITY.get(), AiBotRenderer::new);
    }

    @SubscribeEvent
    public void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.AI_BOT_ENTITY.get(), AiBotEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        if (botController != null) {
            botController.shutdown();
        }
    }

    public static BotController getBotController() {
        return botController;
    }
}