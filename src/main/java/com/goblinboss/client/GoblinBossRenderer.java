package com.goblinboss.client;

import com.goblinboss.GoblinBossEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class GoblinBossRenderer extends GeoEntityRenderer<GoblinBossEntity> {

    public GoblinBossRenderer(EntityRendererProvider.Context context) {
        super(context, new GoblinBossModel());
        this.shadowRadius = 1.0F;
    }

    /** Không nghiêng xác 90 độ như mob vanilla - animation "death" tự xử lý. */
    @Override
    protected float getDeathMaxRotation(GoblinBossEntity animatable) {
        return 0.0F;
    }

    @Override
    public void preRender(PoseStack poseStack, GoblinBossEntity boss, BakedGeoModel model,
                          @Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, boss, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);

        int stage = boss.getStage();
        hide(model, "armor_left", stage >= 1);
        hide(model, "armor_leg", stage >= 1);
        hide(model, "armor_right", stage >= 2);
        hide(model, "armor_hip", stage >= 2);
        hide(model, "armor_torso", stage >= 3);

        boolean frenzyEyes = boss.hasFrenzyEyes();
        hide(model, "left_eye", frenzyEyes);
        hide(model, "right_eye", frenzyEyes);
        hide(model, "frenzy_eye", !frenzyEyes);
    }

    private static void hide(BakedGeoModel model, String bone, boolean hidden) {
        model.getBone(bone).ifPresent(b -> b.setHidden(hidden));
    }
}
