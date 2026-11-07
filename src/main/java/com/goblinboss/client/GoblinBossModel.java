package com.goblinboss.client;

import com.goblinboss.GoblinBossEntity;
import com.goblinboss.GoblinBossMod;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class GoblinBossModel extends GeoModel<GoblinBossEntity> {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath(GoblinBossMod.MODID, "geo/goblin_boss.geo.json");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(GoblinBossMod.MODID, "textures/entity/goblin_boss.png");
    private static final ResourceLocation ANIMATION = ResourceLocation.fromNamespaceAndPath(GoblinBossMod.MODID, "animations/goblin_boss.animation.json");

    @Override
    public ResourceLocation getModelResource(GoblinBossEntity animatable) { return MODEL; }

    @Override
    public ResourceLocation getTextureResource(GoblinBossEntity animatable) { return TEXTURE; }

    @Override
    public ResourceLocation getAnimationResource(GoblinBossEntity animatable) { return ANIMATION; }
}
