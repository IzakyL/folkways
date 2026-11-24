package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.block.AircraftCatapultBlock;
import com.atsuishio.superbwarfare.block.CatapultControllerBlock;
import com.atsuishio.superbwarfare.entity.misc.CatapultShuttleEntity;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** The native rail integrator in a platform frame; the native spring still supplies all towing force. */
public final class FrameCatapultMotion {
    private static final Map<CatapultShuttleEntity, FrameCatapultMotion> MOTIONS = new WeakHashMap<>();
    private final LandingSites.Catapult rail;
    private Vec3 local;
    private double speed;

    private FrameCatapultMotion(CatapultShuttleEntity shuttle, LandingSites.Catapult rail, WorldSpaces.Frame frame) {
        this.rail = rail;
        Vec3 axis = Vec3.directionFromRotation(0, rail.yaw());
        Vec3 start = Vec3.atBottomCenterOf(rail.start().cell());
        double along = Math.max(0, frame.toLocal(shuttle.position()).subtract(start).dot(axis));
        local = start.add(axis.scale(along));
    }

    public static void bind(CatapultShuttleEntity shuttle, LandingSites.Catapult rail) {
        if (rail.start().realm() instanceof Realm.Frame address) {
            WorldSpaces.frame(shuttle.level(), address.structure()).ifPresent(frame ->
                MOTIONS.computeIfAbsent(shuttle, ignored -> new FrameCatapultMotion(shuttle, rail, frame)));
        }
    }

    /** Bind before the first native move, even while the airport is still surveying the deck. */
    private static void bindAtRail(CatapultShuttleEntity shuttle) {
        var address = WorldSpaces.at(shuttle);
        if (!(address.realm() instanceof Realm.Frame frameAddress)) {
            return;
        }
        var level = shuttle.level();
        var frame = WorldSpaces.frame(level, frameAddress.structure()).orElse(null);
        if (frame == null) {
            return;
        }
        // A rotated frame can round an exact rail-bottom position into the cell below the rail.
        BlockPos start = frame.storage(BlockPos.containing(frame.toLocal(shuttle.position()).add(0, 1.0E-6, 0)));
        var state = level.getBlockState(start);
        // Deck collision can lift a newly spawned shuttle onto the top of the rail before its first tick.
        if (!(state.getBlock() instanceof AircraftCatapultBlock)) {
            start = start.below();
            state = level.getBlockState(start);
        }
        if (!(state.getBlock() instanceof AircraftCatapultBlock)) {
            return;
        }
        bindAtRail(shuttle, start);
    }

    /** Rail contact supplies a storage address even when Sable has not started tracking the shuttle. */
    public static boolean bindAtRail(CatapultShuttleEntity shuttle, BlockPos start) {
        if (shuttle.level().isClientSide()
            || !(WorldSpaces.at(shuttle.level(), start).realm() instanceof Realm.Frame)) {
            return false;
        }
        if (MOTIONS.containsKey(shuttle)) {
            return true;
        }
        var level = shuttle.level();
        var state = level.getBlockState(start);
        if (!(state.getBlock() instanceof AircraftCatapultBlock)) {
            return false;
        }
        var forward = state.getValue(AircraftCatapultBlock.FACING);
        for (int n = 0; n < 512; n++) {
            var previous = level.getBlockState(start.relative(forward.getOpposite()));
            if (!(previous.getBlock() instanceof AircraftCatapultBlock)
                || previous.getValue(AircraftCatapultBlock.FACING) != forward) {
                break;
            }
            start = start.relative(forward.getOpposite());
        }
        BlockPos controller = start.relative(forward.getOpposite());
        var control = level.getBlockState(controller);
        if (!(control.getBlock() instanceof CatapultControllerBlock)
            || control.getValue(CatapultControllerBlock.FACING) != forward) {
            return false;
        }
        int length = 0;
        while (length < 512) {
            var rail = level.getBlockState(start.relative(forward, length));
            if (!(rail.getBlock() instanceof AircraftCatapultBlock)
                || rail.getValue(AircraftCatapultBlock.FACING) != forward) {
                break;
            }
            length++;
        }
        int power = control.getValue(CatapultControllerBlock.LAUNCH_POWER);
        bind(shuttle, new LandingSites.Catapult(WorldSpaces.at(level, start), WorldSpaces.at(level, controller),
            forward.toYRot(), length, power, VehicleCatapults.exitSpeed(length, power)));
        return MOTIONS.containsKey(shuttle);
    }

    public static boolean tick(CatapultShuttleEntity shuttle, Runnable tow) {
        if (!shuttle.level().isClientSide() && !MOTIONS.containsKey(shuttle)) {
            bindAtRail(shuttle);
        }
        var motion = MOTIONS.get(shuttle);
        if (motion == null) {
            return false;
        }
        var address = (Realm.Frame) motion.rail.start().realm();
        var frame = WorldSpaces.frame(shuttle.level(), address.structure()).orElse(null);
        if (frame == null) {
            MOTIONS.remove(shuttle);
            shuttle.clearTowingInfo();
            return false;
        }
        boolean intact = true;
        var forwardDirection = net.minecraft.core.Direction.fromYRot(motion.rail.yaw());
        for (int index = 0; index < motion.rail.length(); index++) {
            var state = shuttle.level().getBlockState(motion.rail.start().block(shuttle.level())
                .relative(forwardDirection, index));
            if (!(state.getBlock() instanceof AircraftCatapultBlock)
                || state.getValue(AircraftCatapultBlock.FACING) != forwardDirection) {
                intact = false;
                break;
            }
        }
        var controller = shuttle.level().getBlockState(motion.rail.controller().block(shuttle.level()));
        if (!(controller.getBlock() instanceof CatapultControllerBlock)
            || !intact) {
            MOTIONS.remove(shuttle);
            shuttle.clearTowingInfo();
            return false;
        }
        Vec3 axis = Vec3.directionFromRotation(0, motion.rail.yaw());
        Vec3 start = Vec3.atBottomCenterOf(motion.rail.start().cell());
        motion.speed *= .8;
        double along = motion.local.subtract(start).dot(axis) + motion.speed;
        // Keep the returning shuttle on its rail instead of allowing it to coast into world space.
        if (along < 0) {
            along = 0;
            motion.speed = 0;
        }
        var control = shuttle.level().getBlockState(motion.rail.controller().block(shuttle.level()));
        if (along >= motion.rail.length() && shuttle.getTowingEntity() == null
            && control.hasProperty(CatapultControllerBlock.POWERED)
            && !control.getValue(CatapultControllerBlock.POWERED)) {
            along = motion.rail.length() - 1;
            motion.speed = 0;
        }
        motion.local = start.add(axis.scale(along));
        Vec3 world = frame.toWorld(motion.local);
        Vec3 forward = frame.toWorld(motion.local.add(axis)).subtract(world).normalize();
        shuttle.setPos(world);
        shuttle.setYRot((float) Math.toDegrees(Math.atan2(-forward.x, forward.z)));
        shuttle.setXRot((float) -Math.toDegrees(Math.asin(Math.clamp(forward.y, -1, 1))));
        var state = shuttle.level().getBlockState(frame.storage(BlockPos.containing(motion.local)));
        if (state.getBlock() instanceof AircraftCatapultBlock) {
            int power = state.getValue(AircraftCatapultBlock.LAUNCH_POWER);
            double direction = state.getValue(AircraftCatapultBlock.REVERSED) ? -1 : 1;
            if (motion.speed * direction < .6 * power) {
                motion.speed += direction * power / 50.0;
            }
        }
        Vec3 platform = frame.velocity(motion.local);
        var plane = shuttle.getTowingEntity();
        Vec3 planePlatform = plane == null ? Vec3.ZERO : frame.velocity(frame.toLocal(plane.position()));
        if (plane != null) {
            plane.setDeltaMovement(plane.getDeltaMovement().subtract(planePlatform));
        }
        shuttle.setDeltaMovement(forward.scale(motion.speed));
        tow.run();
        if (plane != null) {
            plane.setDeltaMovement(plane.getDeltaMovement().add(planePlatform));
        }
        shuttle.setDeltaMovement(forward.scale(motion.speed).add(platform));
        return true;
    }
}
