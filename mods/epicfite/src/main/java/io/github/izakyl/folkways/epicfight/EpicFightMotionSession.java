package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.motion.CombatMotions;
import io.github.izakyl.folkways.plugins.combat.weapon.ResidentShields;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import yesman.epicfight.api.animation.AnimationPlayer;
import yesman.epicfight.api.animation.property.MoveCoordFunctions;
import yesman.epicfight.api.animation.types.ActionAnimation;
import yesman.epicfight.api.animation.types.DodgeAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.gameasset.Animations;

/** Bounded NPC decisions around EF's existing server root motion, not a player skill or a navigation goal. */
final class EpicFightMotionSession implements CombatMotions.Session {
    static final int DODGE_COOLDOWN = 40;
    static final int DODGE_LIMIT = 20;
    static final int SPRINT_LIMIT = 20;
    private static final double MAX_STEP = 3;
    private static final double SAMPLE = .15;

    private final Worker worker;
    private final ResidentEntity resident;
    private final ResidentEpicFightPatch patch;
    private final CombatMotions.Request request;
    private final Optional<UUID> colony;
    private final WorldPos realm;
    private final Vec3 origin;
    private final AABB standingBox;
    private final boolean wasSprinting;
    private final long started;
    private AssetAccessor<? extends DodgeAnimation> animation;
    private boolean initializing;
    private boolean closed;

    private EpicFightMotionSession(Worker worker, ResidentEntity resident, ResidentEpicFightPatch patch,
            CombatMotions.Request request) {
        this.worker = worker;
        this.resident = resident;
        this.patch = patch;
        this.request = request;
        colony = resident.colonyId();
        realm = WorldSpaces.at(resident);
        origin = resident.position();
        standingBox = resident.getBoundingBox();
        wasSprinting = resident.isSprinting();
        started = resident.level().getGameTime();
    }

    static Optional<EpicFightMotionSession> open(Worker worker, CombatMotions.Request request) {
        if (!(worker.body() instanceof ResidentEntity resident) || !(resident.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread() || worker.pack() != resident.pack()
                || !worker.resident().id().equals(resident.id()) || request == null || request.kind() == null
                || request.direction() == null || !finite(request.direction())
                || request.direction().horizontalDistanceSqr() < .0001 || request.authorized() == null
                || request.within() == null || !request.authorized().getAsBoolean()
                || !resident.isAlive() || resident.isRemoved() || resident.isPassenger()
                || resident.isSleeping() || !resident.onGround() || resident.isInWaterOrBubble()
                || resident.onClimbable() || resident.hasWeaponSession()) { return Optional.empty(); }
        ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(resident);
        if (patch == null || patch.uninitialized() || patch.hasMotion() || patch.hasStrike() || patch.blocksLabor()
                || patch.actionOwnsPose()) { return Optional.empty(); }
        if (request.kind() == CombatMotions.Kind.DODGE && level.getGameTime() < patch.nextDodgeAt()) return Optional.empty();
        if (request.kind() == CombatMotions.Kind.SPRINT && resident.getNavigation().isDone()) return Optional.empty();
        EpicFightMotionSession session = new EpicFightMotionSession(worker, resident, patch, request);
        if (!session.safe(resident.position())) return Optional.empty();
        if (request.kind() == CombatMotions.Kind.DODGE && !session.chooseStep()) return Optional.empty();
        patch.claimMotion(session);
        try {
            if (request.kind() == CombatMotions.Kind.DODGE) {
                patch.nextDodgeAt(level.getGameTime() + DODGE_COOLDOWN);
                resident.getNavigation().stop();
                resident.setSprinting(false);
                resident.setDeltaMovement(0, resident.getDeltaMovement().y, 0);
                resident.xxa = 0; resident.zza = 0;
                ResidentShields.lower(resident);
                session.initializing = true;
                patch.playAnimationSynchronized(session.animation, 0);
                session.initializing = false;
            } else {
                resident.setSprinting(true);
            }
            return Optional.of(session);
        } catch (RuntimeException | LinkageError failure) {
            session.initializing = false;
            session.close();
            throw failure;
        }
    }

    private boolean chooseStep() {
        Vec3 wanted = request.direction().multiply(1, 0, 1).normalize();
        double best = .7;
        for (var candidate : List.of(Animations.BIPED_STEP_FORWARD, Animations.BIPED_STEP_BACKWARD,
                Animations.BIPED_STEP_LEFT, Animations.BIPED_STEP_RIGHT)) {
            DodgeAnimation step = candidate.get();
            step.getAnimationClip();
            Vec3 distance = step.getExpectedMovement(patch, step.getTotalTime());
            if (!finite(distance) || distance.horizontalDistanceSqr() < .01 || distance.lengthSqr() > MAX_STEP * MAX_STEP) continue;
            double score = distance.multiply(1, 0, 1).normalize().dot(wanted);
            if (score <= best) continue;
            boolean safe = true;
            int samples = Math.max(8, (int) Math.ceil(step.getTotalTime() / .025));
            Vec3 previous = origin;
            for (int index = 1; index <= samples; index++) {
                Vec3 next = origin.add(step.getExpectedMovement(patch, step.getTotalTime() * index / samples));
                if (!safeSegment(previous, next)) { safe = false; break; }
                previous = next;
            }
            if (safe) { animation = candidate; best = score; }
        }
        return animation != null;
    }

    @Override
    public boolean tick() {
        beforeAnimationTick();
        return !closed;
    }

    void beforeAnimationTick() {
        if (closed) return;
        if (!valid() || !safe(resident.position())) { close(); return; }
        long elapsed = resident.level().getGameTime() - started;
        if (request.kind() == CombatMotions.Kind.SPRINT) {
            Vec3 ahead = resident.position().add(request.direction().multiply(1, 0, 1).normalize().scale(.5));
            if (elapsed >= SPRINT_LIMIT || resident.getNavigation().isDone() || !safeSegment(resident.position(), ahead)) close();
        } else if (elapsed >= DODGE_LIMIT || elapsed > 0 && !playing()) {
            close();
        }
    }

    /** Called by ActionAnimation immediately before its own server-side move, including native link frames. */
    boolean allowsRootMotion(ActionAnimation action) {
        if (closed || animation == null || action != animation.get() || !valid()) { close(); return false; }
        if (initializing) return true;
        AnimationPlayer player = patch.getAnimator().getPlayerFor(animation);
        Vec3 delta = MoveCoordFunctions.MODEL_COORD.get(player.getAnimation().get(), patch,
            patch.getAnimator().getVariables().getOrDefaultSharedVariable(ActionAnimation.ACTION_ANIMATION_COORD),
            player.getPrevElapsedTime(), player.getElapsedTime()).toDoubleVector();
        if (!finite(delta) || delta.lengthSqr() > MAX_STEP * MAX_STEP
                || !safeSegment(resident.position(), resident.position().add(delta))) {
            close(); return false;
        }
        return true;
    }

    private boolean valid() {
        return !closed && resident.level() instanceof ServerLevel level && level.getServer().isSameThread()
            && resident.isAlive() && !resident.isRemoved() && !resident.isPassenger() && !resident.isSleeping()
            && !resident.isInWaterOrBubble() && !resident.onClimbable() && !resident.hasWeaponSession()
            && !patch.isStunned() && patch.ownsMotion(this) && resident.colonyId().equals(colony)
            && realm.sameRealm(WorldSpaces.at(resident)) && worker.body() == resident
            && worker.pack() == resident.pack() && worker.resident().id().equals(resident.id())
            && request.authorized().getAsBoolean();
    }

    private boolean safeSegment(Vec3 from, Vec3 to) {
        if (!finite(from) || !finite(to)) return false;
        int samples = Math.max(1, (int) Math.ceil(from.distanceTo(to) / SAMPLE));
        if (samples > 32) return false;
        for (int index = 0; index <= samples; index++) {
            if (!safe(from.lerp(to, (double) index / samples))) return false;
        }
        return true;
    }

    private boolean safe(Vec3 point) {
        if (!finite(point) || !request.within().test(point) || !(resident.level() instanceof ServerLevel level)
                || request.kind() == CombatMotions.Kind.DODGE && origin.distanceToSqr(point) > MAX_STEP * MAX_STEP) return false;
        AABB box = standingBox.move(point.subtract(origin)).deflate(.001);
        if (!level.hasChunkAt(BlockPos.containing(point)) || !level.noCollision(resident, box)) return false;
        double radius = resident.getBbWidth() * .4;
        for (double x : new double[] {-radius, radius}) for (double z : new double[] {-radius, radius}) {
            BlockPos feet = BlockPos.containing(point.x + x, point.y + .01, point.z + z);
            BlockPos floor = BlockPos.containing(point.x + x, point.y - .05, point.z + z);
            if (!level.hasChunkAt(feet) || !level.hasChunkAt(floor) || !level.getWorldBorder().isWithinBounds(feet)) return false;
            var ground = level.getBlockState(floor);
            var occupying = level.getBlockState(feet);
            if (!ground.isFaceSturdy(level, floor, Direction.UP) || !ground.getFluidState().isEmpty()
                    || !occupying.getFluidState().isEmpty() || ground.is(Blocks.MAGMA_BLOCK) || occupying.is(BlockTags.FIRE)
                    || occupying.is(Blocks.CACTUS) || occupying.is(Blocks.SWEET_BERRY_BUSH)) return false;
        }
        return true;
    }

    boolean playing() { return animation != null && patch.getAnimator().isPlaying(animation); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        boolean owns = patch.ownsMotion(this);
        patch.releaseMotion(this);
        if (owns && animation != null && playing()) patch.playAnimationSynchronized(Animations.BIPED_IDLE, 0);
        if (owns) {
            resident.setSprinting(wasSprinting);
            if (animation != null) resident.refreshDimensions();
        }
    }

    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}
