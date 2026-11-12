package io.github.izakyl.folkways.vanilla.person;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.izakyl.folkways.core.equipment.api.WornGearLayers;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Draws the same worn-gear layers a skinned resident gets (backpacks, Curios, Accessories) on a Geo model. The
 * frameworks' renderers place gear on a humanoid model, so they receive a stand-in humanoid whose body sits at
 * the top of the model's own torso: the body bone's animation and size carry the gear, and the stand-in keeps
 * the head look.
 * A geometry without a conventional body bone shows no gear, as it shows no armor.
 */
@OnlyIn(Dist.CLIENT)
final class ResidentGeoGearLayer extends GeoRenderLayer<ResidentGeoBackend.Crew> {
    private static final float BODY_WIDTH = 8.0F;
    private static final float BODY_HEIGHT = 12.0F;
    private static final float BODY_DEPTH = 4.0F;

    private final ResidentGeoBackend backend;
    private final HumanoidModel<ResidentEntity> standIn;
    private final List<RenderLayer<ResidentEntity, HumanoidModel<ResidentEntity>>> layers;
    private boolean drawn;

    ResidentGeoGearLayer(ResidentGeoBackend backend, EntityRendererProvider.Context context) {
        super(backend);
        this.backend = backend;
        standIn = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER));
        layers = WornGearLayers.of(new RenderLayerParent<>() {
            @Override
            public HumanoidModel<ResidentEntity> getModel() {
                return standIn;
            }

            @Override
            public ResourceLocation getTextureLocation(ResidentEntity resident) {
                return backend.getTextureLocation(resident);
            }
        });
    }

    HumanoidModel<ResidentEntity> model() {
        return standIn;
    }

    @Override
    public void preRender(PoseStack pose, ResidentGeoBackend.Crew crew, BakedGeoModel baked,
            RenderType renderType, MultiBufferSource buffers, VertexConsumer buffer, float partialTick,
            int light, int overlay) {
        drawn = false;
    }

    @Override
    public void renderForBone(PoseStack pose, ResidentGeoBackend.Crew crew, GeoBone bone,
            RenderType renderType, MultiBufferSource buffers, VertexConsumer buffer, float partialTick,
            int light, int overlay) {
        ResidentEntity resident = backend.getCurrentEntity();
        if (layers.isEmpty() || drawn || resident == null || bone.isHidden() || !isBody(bone.getName())) {
            return;
        }
        drawn = true;
        float age = resident.tickCount + partialTick;
        float bodyYaw = Mth.rotLerp(partialTick, resident.yBodyRotO, resident.yBodyRot);
        float headYaw = Mth.rotLerp(partialTick, resident.yHeadRotO, resident.yHeadRot) - bodyYaw;
        float headPitch = Mth.lerp(partialTick, resident.xRotO, resident.getXRot());
        float limbSwing = resident.walkAnimation.position(partialTick);
        float limbAmount = Math.min(resident.walkAnimation.speed(partialTick), 1.0F);
        posture(resident, limbSwing, limbAmount, age, headYaw, headPitch);
        GeoCube torso = ResidentGeoArmorLayer.mainCube(bone);
        if (torso == null) {
            return;
        }
        float[] box = ResidentGeoArmorLayer.bounds(torso);
        pose.pushPose();
        try {
            // Inside the bone's animated transform: the humanoid body's origin is the top centre of the torso
            // (the neck), its Y points down, and it is 8 x 12 x 4 pixels. Fit the model's own torso to that.
            pose.translate((box[0] + box[1]) / 2, box[3], (box[4] + box[5]) / 2);
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.scale((float) torso.size().x / BODY_WIDTH, (float) torso.size().y / BODY_HEIGHT,
                (float) torso.size().z / BODY_DEPTH);
            for (var layer : layers) {
                layer.render(pose, buffers, light, resident, limbSwing, limbAmount, partialTick, age, headYaw,
                    headPitch);
            }
        } finally {
            pose.popPose();
        }
        // Layer buffers can invalidate the bone renderer's active consumer.
        if (renderType != null) {
            buffers.getBuffer(renderType);
        }
    }

    // The body bone already carries the model's own lean and stride, so the stand-in body stays upright at the
    // origin; the head keeps the resident's look for gear that follows it.
    private void posture(ResidentEntity resident, float limbSwing, float limbAmount, float age, float headYaw,
            float headPitch) {
        standIn.young = resident.isBaby();
        standIn.riding = resident.isPassenger();
        standIn.crouching = false;
        standIn.attackTime = 0.0F;
        standIn.setupAnim(resident, limbSwing, limbAmount, age, headYaw, headPitch);
        standIn.body.resetPose();
        standIn.hat.copyFrom(standIn.head);
    }

    private static boolean isBody(String name) {
        String key = name.toLowerCase(Locale.ROOT).replace("_", "");
        return key.equals("body") || key.equals("upperbody") || key.equals("upbody");
    }
}
