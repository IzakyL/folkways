package io.github.izakyl.folkways.plugins.person;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Locale;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.ItemInHandRenderer;
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
    ResidentGeoHeldItemLayer(ResidentGeoBackend backend, EntityRendererProvider.Context context) {
        super(backend); this.backend = backend; items = context.getItemInHandRenderer();
    }
    @Override public void preRender(PoseStack pose, ResidentGeoBackend.Crew crew, BakedGeoModel baked,
            RenderType type, MultiBufferSource buffers, VertexConsumer buffer, float partialTick, int light, int overlay) { drawn = false; }
    @Override public void renderForBone(PoseStack pose, ResidentGeoBackend.Crew crew, GeoBone bone,
            RenderType type, MultiBufferSource buffers, VertexConsumer buffer, float partialTick, int light, int overlay) {
        ResidentEntity body = backend.getCurrentEntity();
        String name = bone.getName().replace("_", "").toLowerCase(Locale.ROOT);
        if (drawn || body == null || bone.isHidden() || body.getMainHandItem().isEmpty()
                || ResidentWeaponPoses.of(body).isEmpty() || !(name.equals("rightarm") || name.equals("armright"))) { return; }
        drawn = true;
        pose.pushPose();
        try {
            pose.translate(bone.getPivotX() / 16F, (bone.getPivotY() - 10) / 16F, bone.getPivotZ() / 16F);
            pose.scale(-1, -1, 1);
            pose.mulPose(Axis.XP.rotationDegrees(-90));
            pose.mulPose(Axis.YP.rotationDegrees(180));
            items.renderItem(body, body.getMainHandItem(), ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false, pose, buffers, light);
        } finally { pose.popPose(); }
        if (type != null) { buffers.getBuffer(type); }
    }
}
