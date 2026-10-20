package io.github.izakyl.folkways.plugins.person;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ResidentSkinRenderer extends HumanoidMobRenderer<ResidentEntity, PlayerModel<ResidentEntity>> {
    private final PlayerModel<ResidentEntity> wide;
    private final PlayerModel<ResidentEntity> slim;

    public ResidentSkinRenderer(EntityRendererProvider.Context context) {
        super(context, new LeisureModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wide = this.model;
        this.slim = new LeisureModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        addLayer(new HumanoidArmorLayer<>(this,
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
    }

    @Override
    public void render(ResidentEntity resident, float yaw, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light) {
        this.model = skinOf(resident).arms() == ResidentLook.Arms.SLIM ? slim : wide;
        super.render(resident, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(ResidentEntity resident) {
        return skinOf(resident).texture();
    }

    private static ResidentLook.Skin skinOf(ResidentEntity resident) {
        return ResidentLooks.of(resident).appearance().skin();
    }
    private static final class LeisureModel extends PlayerModel<ResidentEntity> {
        LeisureModel(ModelPart root, boolean slim) { super(root, slim); }

        @Override
        public void setupAnim(ResidentEntity resident, float stride, float amount, float age, float yaw, float pitch) {
            crouching = resident.leisureMotion().equals("petting");
            super.setupAnim(resident, stride, amount, age, yaw, pitch);
            float wave = Mth.sin(age * 0.09F);
            switch (resident.leisureMotion()) {
                case "dining" -> {
                    rightArm.xRot = -1.0F + wave * 0.18F;
                    leftArm.xRot = -0.65F;
                    head.xRot += 0.15F;
                }
                case "chatting" -> {
                    boolean speaking = ((int) (resident.level().getGameTime() / 60) + resident.leisurePhase()) % 2 == 0;
                    if (speaking) { rightArm.xRot = -0.65F + wave * 0.15F; rightArm.zRot = 0.20F; }
                    else { head.xRot += Mth.sin(age * 0.15F) * 0.08F; }
                }
                case "petting" -> {
                    body.xRot = 0.5F;
                    rightArm.xRot = -0.9F + wave * 0.12F;
                    head.xRot = 0.5F;
                    leftLeg.xRot = -0.15F;
                    rightLeg.xRot = -0.15F;
                }
                case "relaxing", "strolling" -> {
                    if (amount < 0.02F) { head.yRot += wave * 0.25F; leftArm.zRot = -0.05F; rightArm.zRot = 0.05F; }
                }
                default -> { }
            }
            hat.copyFrom(head); jacket.copyFrom(body);
            rightSleeve.copyFrom(rightArm); leftSleeve.copyFrom(leftArm);
            rightPants.copyFrom(rightLeg); leftPants.copyFrom(leftLeg);
        }
    }

}
