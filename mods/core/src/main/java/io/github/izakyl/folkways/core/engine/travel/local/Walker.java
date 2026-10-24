package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.resident.Locomotion;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.pathfinder.PathType;

record Walker(Kind kind, Locomotion locomotion, Mob body, LivingEntity source) {

    boolean current() {
        if (source.isRemoved() || source.level() != body.level() || !kind.matches(body)) {
            return false;
        }
        if (source == body) {
            return true;
        }
        EntityDimensions dimensions = source.getPose() == Pose.SLEEPING ? source.getDimensions(Pose.STANDING)
            : EntityDimensions.scalable(source.getBbWidth(), source.getBbHeight());
        return kind.width() == Float.floatToIntBits(dimensions.width())
            && kind.height() == Float.floatToIntBits(dimensions.height())
            && kind.onRail() == Kind.onRail(source)
            && (kind.railOrigin() == null || kind.railOrigin().equals(source.blockPosition()))
            && (kind.keepoutOrigin() == null || kind.keepoutOrigin().equals(source.blockPosition()))
            && kind.inKeepout() == Keepouts.forbidden(source.level(), source.blockPosition());
    }

    // Evaluators read more than the entity's class or size. In particular rail/keepout permissions depend
    // on the origin, while step height, falls, fire immunity and maluses may differ between two villagers.
    record Kind(ResourceLocation locomotion, EntityType<?> type, int width, int height, int step, int fall,
                boolean fireImmune, boolean onRail, @Nullable BlockPos railOrigin, boolean inKeepout,
                @Nullable BlockPos keepoutOrigin, List<Integer> maluses, @Nullable UUID individual) {

        private static final PathType[] PATH_TYPES = PathType.values();

        static Kind of(Locomotion locomotion, Mob body) {
            List<Integer> maluses = new ArrayList<>();
            for (PathType type : PATH_TYPES) {
                maluses.add(Float.floatToIntBits(body.getPathfindingMalus(type)));
            }
            boolean rail = onRail(body);
            boolean keepout = Keepouts.forbidden(body.level(), body.blockPosition());
            return new Kind(locomotion.id(), body.getType(), Float.floatToIntBits(body.getBbWidth()),
                Float.floatToIntBits(body.getBbHeight()), Float.floatToIntBits(body.maxUpStep()),
                body.getMaxFallDistance(), body.fireImmune(), rail, rail ? body.blockPosition().immutable() : null,
                keepout, keepout ? body.blockPosition().immutable() : null, List.copyOf(maluses),
                locomotion.id().equals(Locomotions.WALKING) ? null : body.getUUID());
        }

        boolean matches(Mob body) {
            if (type != body.getType() || width != Float.floatToIntBits(body.getBbWidth())
                    || height != Float.floatToIntBits(body.getBbHeight())
                    || step != Float.floatToIntBits(body.maxUpStep()) || fall != body.getMaxFallDistance()
                    || fireImmune != body.fireImmune() || onRail != onRail(body)
                    || railOrigin != null && !railOrigin.equals(body.blockPosition())
                    || keepoutOrigin != null && !keepoutOrigin.equals(body.blockPosition())
                    || individual != null && !individual.equals(body.getUUID())
                    || inKeepout != Keepouts.forbidden(body.level(), body.blockPosition())) {
                return false;
            }
            for (int i = 0; i < PATH_TYPES.length; i++) {
                if (maluses.get(i) != Float.floatToIntBits(body.getPathfindingMalus(PATH_TYPES[i]))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean onRail(LivingEntity body) {
            return body.level().getBlockState(body.blockPosition()).is(BlockTags.RAILS)
                || body.level().getBlockState(body.blockPosition().below()).is(BlockTags.RAILS);
        }
    }

    // A body the colony has no kind for is surveyed as if it walked, when anything walks at all.
    static Optional<Walker> of(Mob body) {
        return Bodies.of(body).map(Body::kind).map(kind -> kind.locomotion())
            .or(() -> Locomotions.of(Locomotions.WALKING))
            .map(locomotion -> new Walker(Kind.of(locomotion, body), locomotion, body, body));
    }

    static Optional<Walker> of(LivingEntity body) {
        if (body instanceof Mob mob) {
            return of(mob).map(walker -> {
                if (body.getPose() != Pose.SLEEPING || !walker.locomotion().id().equals(Locomotions.WALKING)) {
                    return walker;
                }
                Mob sizing = new SizingBody(body);
                return new Walker(Kind.of(walker.locomotion(), sizing), walker.locomotion(), sizing, body);
            });
        }
        return Locomotions.of(Locomotions.WALKING).map(locomotion -> {
            Mob sizing = new SizingBody(body);
            return new Walker(Kind.of(locomotion, sizing), locomotion, sizing, body);
        });
    }

    // NodeEvaluator requires a Mob. This unspawned body supplies a non-mob's actual dimensions; it never moves
    // the original entity or joins the world. Players are conservatively surveyed as ordinary walkers.
    private static final class SizingBody extends PathfinderMob {

        private EntityDimensions size;

        SizingBody(LivingEntity original) {
            super(EntityType.VILLAGER, original.level());
            // Sleep is not a way to walk out. Protect the space needed after waking, without waking or
            // resizing the real body while surveying a placement.
            size = original.getPose() == Pose.SLEEPING ? original.getDimensions(Pose.STANDING)
                : EntityDimensions.scalable(original.getBbWidth(), original.getBbHeight());
            refreshDimensions();
            setPos(original.position());
        }

        @Override
        protected EntityDimensions getDefaultDimensions(Pose pose) {
            return size == null ? super.getDefaultDimensions(pose) : size;
        }
    }
}
