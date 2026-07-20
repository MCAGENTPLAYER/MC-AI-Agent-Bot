package com.aibot.mod.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.RegistryObject;

public class AiBotSpawnEgg extends Item {
    private final RegistryObject<EntityType<AiBotEntity>> entityType;

    public AiBotSpawnEgg(RegistryObject<EntityType<AiBotEntity>> entityType, Item.Properties properties) {
        super(properties);
        this.entityType = entityType;
    }

    @Override
    public net.minecraft.world.InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (!level.isClientSide && player != null) {
            if (entityType.isPresent()) {
                AiBotEntity bot = entityType.get().create(level);
                if (bot != null) {
                    bot.moveTo(context.getClickedPos().getX() + 0.5, 
                            context.getClickedPos().getY() + 1, 
                            context.getClickedPos().getZ() + 0.5, 
                            level.random.nextFloat() * 360.0F, 0.0F);
                    level.addFreshEntity(bot);
                    if (!player.getAbilities().instabuild) {
                        context.getItemInHand().shrink(1);
                    }
                    return net.minecraft.world.InteractionResult.SUCCESS;
                }
            }
        }
        return net.minecraft.world.InteractionResult.PASS;
    }
}