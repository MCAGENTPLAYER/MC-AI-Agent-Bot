package com.aibot.mod.entity;

import com.aibot.mod.AiBotMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AiBotMod.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AiBotMod.MODID);

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, AiBotMod.MODID);

    public static final RegistryObject<EntityType<AiBotEntity>> AI_BOT_ENTITY = ENTITIES.register("ai_bot",
            () -> EntityType.Builder.of(AiBotEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(8)
                    .build("ai_bot"));

    public static final RegistryObject<AiBotSpawnEgg> AI_BOT_SPAWN_EGG = ITEMS.register("ai_bot_spawn_egg",
            () -> new AiBotSpawnEgg(AI_BOT_ENTITY, new Item.Properties().stacksTo(64)));

    public static final RegistryObject<MenuType<AiBotMenu>> AI_BOT_MENU = MENUS.register("ai_bot_menu",
            () -> net.minecraftforge.common.extensions.IForgeMenuType.create(AiBotMenu::new));
}