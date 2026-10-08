package io.github.izakyl.folkways.plugins.person;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ResidentSkinRenderer extends HumanoidMobRenderer<ResidentEntity, PlayerModel<ResidentEntity>> {
    private final PlayerModel<ResidentEntity> wide;
    private final PlayerModel<ResidentEntity> slim;

    public ResidentSkinRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wide = this.model;
        this.slim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
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
}
