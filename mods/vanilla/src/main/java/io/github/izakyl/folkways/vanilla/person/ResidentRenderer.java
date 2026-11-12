package io.github.izakyl.folkways.vanilla.person;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.vanilla.person.look.ResidentLook;
import io.github.izakyl.folkways.vanilla.person.look.ResidentLooks;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * Chooses a backend per resident. It is a {@link LivingEntityRenderer} only so that worn-gear renderers which
 * follow "the entity's renderer model" (Curios' and Artifacts' followBodyRotations, rotateIfSneaking) find the
 * humanoid pose of the backend drawing this resident; its own layer list is never drawn.
 */
@OnlyIn(Dist.CLIENT)
public final class ResidentRenderer extends LivingEntityRenderer<ResidentEntity, HumanoidModel<ResidentEntity>> {
    private final ResidentSkinRenderer skinBackend;
    private final EntityRenderer<ResidentEntity> modelBackend;
    private EntityRenderer<ResidentEntity> drawing;

    public ResidentRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        skinBackend = new ResidentSkinRenderer(context);
        modelBackend = ModList.get().isLoaded("geckolib") ? ResidentGeoBackend.create(context) : null;
        drawing = skinBackend;
    }

    @Override
    public void render(ResidentEntity resident, float yaw, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light) {
        drawing = backendFor(resident);
        drawing.render(resident, yaw, partialTick, pose, buffers, light);
        CastLineRenderer.render(resident, partialTick, pose, buffers, light, entityRenderDispatcher);
    }

    @Override
    public HumanoidModel<ResidentEntity> getModel() {
        return drawing == skinBackend ? skinBackend.getModel() : ResidentGeoBackend.gearModel(drawing);
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
        boolean drawsModel = modelBackend != null
            && ResidentLooks.of(resident).appearance() instanceof ResidentLook.Model model
            && ResidentGeoBackend.loaded(model);
        return drawsModel ? modelBackend : skinBackend;
    }
}
