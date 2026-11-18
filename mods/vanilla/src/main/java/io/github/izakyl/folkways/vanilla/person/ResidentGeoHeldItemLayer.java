package io.github.izakyl.folkways.vanilla.person;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Locale;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

@OnlyIn(Dist.CLIENT)
final class ResidentGeoHeldItemLayer extends GeoRenderLayer<ResidentGeoBackend.Crew> {

    private final ResidentGeoBackend backend;
    private final ItemInHandRenderer items;
    private boolean drawn;
    private GeoBone hand;
    private float gripOffset;

    ResidentGeoHeldItemLayer(ResidentGeoBackend backend, EntityRendererProvider.Context context) {
        super(backend);
        this.backend = backend;
        items = context.getItemInHandRenderer();
    }

    @Override
    public void preRender(PoseStack pose, ResidentGeoBackend.Crew crew, BakedGeoModel baked, RenderType type,
        MultiBufferSource buffers, VertexConsumer buffer, float partialTick, int light, int overlay) {
        drawn = false;
        hand = null;
        int best = Integer.MAX_VALUE;
        ResidentEntity body = backend.getCurrentEntity();
        boolean weapon = body != null && ResidentWeaponPoses.of(body).isPresent();
        for (GeoBone bone : backend.getGeoModel().getAnimationProcessor().getRegisteredBones()) {
            String name = bone.getName().replace("_", "").toLowerCase(Locale.ROOT);
            int rank = switch (name) {
                case "righthandlocator", "rightitem", "itemright" -> weapon ? 4 : 0;
                case "righthand", "handright" -> 1;
                case "rightforearm", "forearmright" -> 2;
                case "rightarm", "armright", "rarm" -> weapon ? 0 : 3;
                default -> Integer.MAX_VALUE;
            };
            if (!bone.isHidden() && rank < best) {
                hand = bone;
                best = rank;
                gripOffset = switch (name) {
                    case "rightarm", "armright", "rarm" -> 10;
                    case "rightforearm", "forearmright" -> 5;
                    default -> 0;
                };
            }
        }
    }

    @Override
    public void renderForBone(PoseStack pose, ResidentGeoBackend.Crew crew, GeoBone bone, RenderType type,
        MultiBufferSource buffers, VertexConsumer buffer, float partialTick, int light, int overlay) {
        ResidentEntity body = backend.getCurrentEntity();
        if (drawn
            || body == null
            || bone.isHidden()
            || body.getMainHandItem().isEmpty()
            || bone != hand) {
            return;
        }
        drawn = true;
        pose.pushPose();
        try {
            pose.translate(bone.getPivotX() / 16F, (bone.getPivotY() - gripOffset) / 16F, bone.getPivotZ() / 16F);
            pose.scale(-1, -1, 1);
            pose.mulPose(Axis.XP.rotationDegrees(-90));
            pose.mulPose(Axis.YP.rotationDegrees(180));
            items.renderItem(
                body,
                body.getMainHandItem(),
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                false,
                pose,
                buffers,
                light);
        } finally {
            pose.popPose();
        }
        if (type != null) {
            buffers.getBuffer(type);
        }
    }
}
