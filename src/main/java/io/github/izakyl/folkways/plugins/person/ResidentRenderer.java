package io.github.izakyl.folkways.plugins.person;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

@OnlyIn(Dist.CLIENT)
public final class ResidentRenderer extends EntityRenderer<ResidentEntity> {
    private final EntityRenderer<ResidentEntity> skinBackend;
    private final EntityRenderer<ResidentEntity> modelBackend;

    public ResidentRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0.5F;
        skinBackend = new ResidentSkinRenderer(context);
        modelBackend = ModList.get().isLoaded("geckolib") ? ResidentGeoBackend.create(context) : null;
    }

    @Override
    public void render(ResidentEntity resident, float yaw, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light) {
        backendFor(resident).render(resident, yaw, partialTick, pose, buffers, light);
        CastLineRenderer.render(resident, partialTick, pose, buffers, light, entityRenderDispatcher);
    }

    @Override
    public boolean shouldRender(ResidentEntity resident, Frustum frustum, double x, double y, double z) {
        return backendFor(resident).shouldRender(resident, frustum, x, y, z);
    }

    @Override
    public Vec3 getRenderOffset(ResidentEntity resident, float partialTick) {
        return backendFor(resident).getRenderOffset(resident, partialTick);
    }

    @Override
    public ResourceLocation getTextureLocation(ResidentEntity resident) {
        return backendFor(resident).getTextureLocation(resident);
    }

    private EntityRenderer<ResidentEntity> backendFor(ResidentEntity resident) {
        boolean wantsModel = ResidentLooks.of(resident).appearance() instanceof ResidentLook.Model;
        return wantsModel && modelBackend != null ? modelBackend : skinBackend;
    }
}
