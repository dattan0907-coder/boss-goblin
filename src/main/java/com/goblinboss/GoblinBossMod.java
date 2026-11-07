package com.goblinboss;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(GoblinBossMod.MODID)
public class GoblinBossMod {
    public static final String MODID = "goblin_boss";

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    // Hitbox giống bone "hitbox" trong model: 24 x 42 px = 1.5 x 2.625 block
    public static final DeferredHolder<EntityType<?>, EntityType<GoblinBossEntity>> GOBLIN_BOSS =
            ENTITIES.register("goblin_boss", () -> EntityType.Builder.of(GoblinBossEntity::new, MobCategory.MONSTER)
                    .sized(1.5F, 2.625F)
                    .eyeHeight(2.3F)
                    .clientTrackingRange(10)
                    .build("goblin_boss"));

    public static final DeferredItem<Item> GOBLIN_BOSS_SPAWN_EGG =
            ITEMS.register("goblin_boss_spawn_egg",
                    () -> new SpawnEggItem(GOBLIN_BOSS.get(), 0x2F8F2E, 0xB0203A, new Item.Properties()));

    public GoblinBossMod(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(this::onAttributes);
        modBus.addListener(this::onCreativeTab);
    }

    private void onAttributes(EntityAttributeCreationEvent event) {
        event.put(GOBLIN_BOSS.get(), GoblinBossEntity.createAttributes().build());
    }

    private void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(GOBLIN_BOSS_SPAWN_EGG.get());
        }
    }
}
