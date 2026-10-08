package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import software.bernie.geckolib.animatable.GeoReplacedEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.GeoReplacedEntityRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

@OnlyIn(Dist.CLIENT)
public final class ResidentGeoBackend extends GeoReplacedEntityRenderer<ResidentEntity, ResidentGeoBackend.Crew> {

    private ResidentGeoBackend(EntityRendererProvider.Context context) {
        super(context, new Rig(), Crew.INSTANCE);
    }

    public static EntityRenderer<ResidentEntity> create(EntityRendererProvider.Context context) {
        return new ResidentGeoBackend(context);
    }

    @Override
    public ResourceLocation getTextureLocation(ResidentEntity resident) {
        ResidentLook.Appearance appearance = ResidentLooks.of(resident).appearance();
        return appearance instanceof ResidentLook.Model model ? model.texture() : appearance.skin().texture();
    }

    private static Optional<ResidentLook.Model> modelOf(ResidentEntity resident) {
        return ResidentLooks.of(resident).appearance() instanceof ResidentLook.Model model
            ? Optional.of(model)
            : Optional.empty();
    }

    private static ResidentLook.Model firstModelInPool() {
        List<ResidentLook.Model> models = ResidentLooks.modelsInPool(Minecraft.getInstance().level);
        if (models.isEmpty()) {
            throw new IllegalStateException(
                "the pool has no model entry, so nothing dispatches to ResidentGeoBackend");
        }
        return models.getFirst();
    }

    public static final class Crew implements GeoReplacedEntity {
        static final Crew INSTANCE = new Crew();

        private static final Map<String, RawAnimation> LOOPS = new ConcurrentHashMap<>();
        private static final Map<String, RawAnimation> ONCE = new ConcurrentHashMap<>();
        // A swing's first ticks; later in the same swing a short work motion that already finished stays done.
        private static final int FRESH_SWING_TICKS = 1;

        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

        private Crew() {
        }

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            controllers.add(new AnimationController<>(this, "move", 4, Crew::chooseLoop));
            controllers.add(new AnimationController<>(this, "work", 0, Crew::chooseWork));
        }

        private static PlayState chooseLoop(AnimationState<Crew> state) {
            Entity entity = state.getData(DataTickets.ENTITY);
            if (!(entity instanceof ResidentEntity resident)) {
                return PlayState.STOP;
            }
            return modelOf(resident)
                .map(model -> state.setAndContinue(
                    loop(state.isMoving() ? model.walkAnimation() : model.idleAnimation())))
                .orElse(PlayState.STOP);
        }

        // A swing the server sends is one piece of work, so it plays the work motion through once: started
        // while the swing is fresh, and left to finish even after the shorter vanilla swing has ended.
        private static PlayState chooseWork(AnimationState<Crew> state) {
            Entity entity = state.getData(DataTickets.ENTITY);
            if (!(entity instanceof ResidentEntity resident)) {
                return PlayState.STOP;
            }
            Optional<String> work = modelOf(resident).flatMap(ResidentLook.Model::workAnimation);
            if (work.isEmpty()) {
                return PlayState.STOP;
            }
            AnimationController<Crew> controller = state.getController();
            boolean playing = controller.getCurrentRawAnimation() != null && !controller.hasAnimationFinished();
            if (!playing && resident.swinging && resident.swingTime <= FRESH_SWING_TICKS) {
                controller.forceAnimationReset();
                return state.setAndContinue(once(work.get()));
            }
            return playing ? PlayState.CONTINUE : PlayState.STOP;
        }

        private static RawAnimation loop(String name) {
            return LOOPS.computeIfAbsent(name, key -> RawAnimation.begin().thenLoop(key));
        }

        private static RawAnimation once(String name) {
            return ONCE.computeIfAbsent(name, key -> RawAnimation.begin().thenPlay(key));
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() {
            return cache;
        }

        @Override
        public EntityType<?> getReplacingEntityType() {
            return PersonBody.RESIDENT.get();
        }
    }

    private static final class Rig extends GeoModel<Crew> {

        private static final String HEAD_BONE = "head";

        @Override
        public ResourceLocation getModelResource(Crew crew) {
            return firstModelInPool().geometry();
        }

        @Override
        public ResourceLocation getTextureResource(Crew crew) {
            return firstModelInPool().texture();
        }

        @Override
        public ResourceLocation getAnimationResource(Crew crew) {
            return firstModelInPool().animations();
        }

        @Override
        public ResourceLocation[] getAnimationResourceFallbacks(Crew crew) {
            return ResidentLooks.modelsInPool(Minecraft.getInstance().level).stream()
                .map(ResidentLook.Model::animations)
                .distinct()
                .toArray(ResourceLocation[]::new);
        }

        @Override
        public void setCustomAnimations(Crew crew, long instanceId, AnimationState<Crew> state) {
            super.setCustomAnimations(crew, instanceId, state);
            EntityModelData look = state.getData(DataTickets.ENTITY_MODEL_DATA);
            if (look == null) {
                return;
            }
            headBone().ifPresent(head -> {
                head.setRotX(look.headPitch() * Mth.DEG_TO_RAD);
                head.setRotY(look.netHeadYaw() * Mth.DEG_TO_RAD);
            });
        }

        private Optional<GeoBone> headBone() {
            return getBone(HEAD_BONE).or(() -> getAnimationProcessor().getRegisteredBones().stream()
                .filter(bone -> bone.getName().equalsIgnoreCase(HEAD_BONE))
                .findFirst());
        }

        @Override
        public ResourceLocation getModelResource(Crew crew, GeoRenderer<Crew> renderer) {
            return forCurrentEntity(renderer, ResidentLook.Model::geometry);
        }

        @Override
        public ResourceLocation getTextureResource(Crew crew, GeoRenderer<Crew> renderer) {
            return forCurrentEntity(renderer, ResidentLook.Model::texture);
        }

        private static ResourceLocation forCurrentEntity(
                GeoRenderer<Crew> renderer, Function<ResidentLook.Model, ResourceLocation> field) {
            Entity current = renderer instanceof GeoReplacedEntityRenderer<?, ?> replaced
                ? replaced.getCurrentEntity()
                : null;
            ResidentLook.Model model = current instanceof ResidentEntity resident
                ? modelOf(resident).orElseGet(ResidentGeoBackend::firstModelInPool)
                : firstModelInPool();
            return field.apply(model);
        }
    }
}
