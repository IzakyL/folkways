package io.github.izakyl.folkways.epicfight.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.izakyl.folkways.epicfight.ResidentEpicFightPatch;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Vector3f;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.mesh.HumanoidMesh;
import yesman.epicfight.client.renderer.patched.entity.PHumanoidRenderer;
import yesman.epicfight.client.renderer.patched.entity.PatchedEntityRenderer;

/** The registered Folkways renderer is a dispatcher, so never cast it to LivingEntityRenderer. */
@OnlyIn(Dist.CLIENT)
public final class ResidentEpicFightRenderer extends PatchedEntityRenderer<ResidentEntity,
        ResidentEpicFightPatch, EntityRenderer<ResidentEntity>, HumanoidMesh> {
    private final SkinRenderer skin;
    private final AnimatedSkinRenderer animated;

    public ResidentEpicFightRenderer(EntityRendererProvider.Context context, EntityType<?> type) {
        skin = new SkinRenderer(context);
        animated = new AnimatedSkinRenderer(context, type);
    }

    @Override
    public void render(ResidentEntity resident, ResidentEpicFightPatch patch, EntityRenderer<ResidentEntity> original,
            MultiBufferSource buffers, PoseStack pose, int light, float partialTick) {
        skin.select(resident);
        float entryScale = pose.last().pose().getScale(new Vector3f()).x;
        animated.render(resident, patch, skin, buffers, pose, light, partialTick);
        ResidentRenderEvidence.drawn(resident, animated.getMeshProvider(patch).registryName(),
            skin.getTextureLocation(resident), original.getClass().getName(), entryScale);
    }

    @Override
    public AssetAccessor<HumanoidMesh> getDefaultMesh() {
        return Meshes.BIPED;
    }

    @Override
    public AssetAccessor<HumanoidMesh> getMeshProvider(ResidentEpicFightPatch patch) {
        return skinMesh(patch.getOriginal());
    }

    private static AssetAccessor<HumanoidMesh> skinMesh(ResidentEntity resident) {
        var converted = ResidentGeoMeshes.mesh(resident);
        if (converted.isPresent()) {
            return converted.get();
        }
        return ResidentLooks.of(resident).appearance().skin().arms() == ResidentLook.Arms.SLIM
            ? Meshes.ALEX : Meshes.BIPED;
    }

    private static final class AnimatedSkinRenderer extends PHumanoidRenderer<ResidentEntity,
            ResidentEpicFightPatch, PlayerModel<ResidentEntity>, SkinRenderer, HumanoidMesh> {
        AnimatedSkinRenderer(EntityRendererProvider.Context context, EntityType<?> type) {
            super(Meshes.BIPED, context, type);
        }

        @Override
        public AssetAccessor<HumanoidMesh> getMeshProvider(ResidentEpicFightPatch patch) {
            return skinMesh(patch.getOriginal());
        }
    }

    private static final class SkinRenderer extends HumanoidMobRenderer<ResidentEntity, PlayerModel<ResidentEntity>> {
        private final PlayerModel<ResidentEntity> wide;
        private final PlayerModel<ResidentEntity> slim;

        SkinRenderer(EntityRendererProvider.Context context) {
            super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
            wide = model;
            slim = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
            addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
            addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        }

        void select(ResidentEntity resident) {
            model = ResidentLooks.of(resident).appearance().skin().arms() == ResidentLook.Arms.SLIM ? slim : wide;
        }

        @Override
        public ResourceLocation getTextureLocation(ResidentEntity resident) {
            ResidentLook.Appearance appearance = ResidentLooks.of(resident).appearance();
            return appearance instanceof ResidentLook.Model model && ResidentGeoMeshes.supports(resident)
                ? model.texture() : appearance.skin().texture();
        }
    }
}
