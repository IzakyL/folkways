package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.block.AircraftCatapultBlock;
import com.atsuishio.superbwarfare.block.CatapultControllerBlock;
import com.atsuishio.superbwarfare.config.server.VehicleConfig;
import com.atsuishio.superbwarfare.entity.misc.CatapultShuttleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Native SW rails and towing; no launch impulse is applied to the aircraft here. */
public final class VehicleCatapults {
    private static final Map<ServerLevel, Map<LandingSites.Site, Cached>> SURVEYS = new WeakHashMap<>();

    private record Cached(long tick, List<LandingSites.Catapult> catapults) {
    }

    private VehicleCatapults() {
    }

    static List<LandingSites.Catapult> scan(ServerLevel level, LandingSites.Site runway) {
        var surveys = SURVEYS.computeIfAbsent(level, ignored -> new HashMap<>());
        var cached = surveys.get(runway);
        if (cached != null && level.getGameTime() - cached.tick() < 40) {
            return cached.catapults();
        }
        if (surveys.size() >= 512) {
            surveys.clear();
        }
        List<LandingSites.Catapult> found = new ArrayList<>();
        Direction forward = Direction.fromYRot(runway.yaw());
        Direction side = forward.getClockWise();
        BlockPos threshold = runway.position().block(level).below();
        for (int along = 0; along < runway.length(); along++) {
            for (int across = -(int) (runway.width() / 2); across <= runway.width() / 2; across++) {
                BlockPos start = threshold.relative(forward, along).relative(side, across);
                if (!level.hasChunkAt(start) || !WorldSpaces.at(level, start).sameRealm(runway.position())) {
                    continue;
                }
                var state = level.getBlockState(start);
                BlockPos controller = start.relative(forward.getOpposite());
                var control = level.getBlockState(controller);
                if (!(state.getBlock() instanceof AircraftCatapultBlock)
                    || state.getValue(AircraftCatapultBlock.FACING) != forward
                    || !(control.getBlock() instanceof CatapultControllerBlock)
                    || control.getValue(CatapultControllerBlock.FACING) != forward) {
                    continue;
                }
                int length = 0;
                while (along + length < runway.length()) {
                    BlockPos at = start.relative(forward, length);
                    var rail = level.getBlockState(at);
                    if (!(rail.getBlock() instanceof AircraftCatapultBlock)
                        || rail.getValue(AircraftCatapultBlock.FACING) != forward
                        || !WorldSpaces.at(level, at).sameRealm(runway.position())) {
                        break;
                    }
                    length++;
                }
                int power = control.getValue(CatapultControllerBlock.LAUNCH_POWER);
                found.add(new LandingSites.Catapult(WorldSpaces.at(level, start),
                    WorldSpaces.at(level, controller), forward.toYRot(), length, power, exitSpeed(length, power)));
            }
        }
        var result = List.copyOf(found.stream().limit(64).toList());
        for (var catapult : result) {
            var shuttle = shuttle(level, catapult);
            if (shuttle != null) {
                FrameCatapultMotion.bind(shuttle, catapult);
            }
        }
        surveys.put(runway, new Cached(level.getGameTime(), result));
        return result;
    }

    static double exitSpeed(int length, int power) {
        double speed = 0;
        double position = .5;
        if (power <= 0 || length <= 0) {
            return 0;
        }
        // tick: multiply horizontal velocity by .8, move, then entityInside adds power/50.
        for (int tick = 0; tick < 10000; tick++) {
            speed *= .8;
            position += speed;
            if (position >= length) {
                return speed;
            }
            if (speed < .6 * power) {
                speed += power / 50.0;
            }
        }
        return 0;
    }

    public static double slack(VehicleEntity vehicle) {
        return VehicleMotionUtils.calculateLongestSide(vehicle) + VehicleConfig.TOW_BAR_EXTRA_LENGTH.get() + 1.5;
    }

    static double aircraftExitSpeed(VehicleEntity vehicle, LandingSites.Catapult catapult) {
        return DirectVehicleMotion.runway(vehicle).orElseThrow().launchSpeed(catapult);
    }

    public static double requiredLength(VehicleEntity vehicle, LandingSites.Site site, LandingSites.Catapult catapult) {
        var needs = DirectVehicleMotion.runway(vehicle).orElseThrow();
        double offset = catapult.start().cell().getCenter().subtract(site.position().cell().getCenter())
            .dot(Vec3.directionFromRotation(0, site.yaw()));
        return offset + catapult.length() + needs.takeoff(aircraftExitSpeed(vehicle, catapult));
    }

    static CatapultShuttleEntity shuttle(ServerLevel level, LandingSites.Catapult catapult) {
        Vec3 start = WorldSpaces.world(level, catapult.start()).orElse(null);
        if (start == null) {
            return null;
        }
        var bounds = new AABB(start, start).inflate(catapult.length() + 2);
        return level.getEntitiesOfClass(CatapultShuttleEntity.class, bounds).stream()
            .filter(entity -> {
                Vec3 relative = entity.position().subtract(start);
                Vec3 forward = Vec3.directionFromRotation(0, catapult.yaw());
                if (catapult.start().realm() instanceof Realm.Frame frame) {
                    var space = WorldSpaces.frame(level, frame.structure()).orElseThrow();
                    relative = space.toLocal(entity.position()).subtract(Vec3.atBottomCenterOf(catapult.start().cell()));
                }
                double along = relative.dot(forward);
                return entity.getTowingEntity() == null && Math.abs(relative.y) < 2
                    && relative.subtract(forward.scale(along)).horizontalDistance() < 2;
            }).findFirst().orElse(null);
    }

    public static boolean available(ServerLevel level, LandingSites.Catapult catapult) {
        if (catapult.power() <= 0 || catapult.shuttleExitSpeed() <= 0 || shuttle(level, catapult) == null) {
            return false;
        }
        BlockPos controller = catapult.controller().block(level);
        for (Direction side : Direction.values()) {
            if (level.getBlockState(controller.relative(side)).getBlock() instanceof LeverBlock) {
                return true;
            }
        }
        return false;
    }

    static boolean powered(ServerLevel level, LandingSites.Catapult catapult) {
        var at = WorldSpaces.storage(level, catapult.controller());
        if (at.isEmpty()) {
            return false;
        }
        var controller = level.getBlockState(at.get());
        if (controller.hasProperty(CatapultControllerBlock.POWERED)
            && controller.getValue(CatapultControllerBlock.POWERED)) {
            return true;
        }
        for (Direction side : Direction.values()) {
            var state = level.getBlockState(at.get().relative(side));
            if (state.getBlock() instanceof LeverBlock && state.getValue(BlockStateProperties.POWERED)) {
                return true;
            }
        }
        return false;
    }

    public static boolean trigger(ServerLevel level, LandingSites.Catapult catapult, boolean powered) {
        var resolved = WorldSpaces.storage(level, catapult.controller());
        if (resolved.isEmpty()) {
            return false;
        }
        BlockPos controller = resolved.get();
        boolean found = false;
        // Operate an existing adjacent lever: the native controller observes an actual redstone transition.
        for (Direction side : Direction.values()) {
            BlockPos at = controller.relative(side);
            var state = level.getBlockState(at);
            if (state.getBlock() instanceof LeverBlock) {
                level.setBlockAndUpdate(at, state.setValue(BlockStateProperties.POWERED, powered));
                level.updateNeighborsAt(at, state.getBlock());
                level.updateNeighborsAt(controller, state.getBlock());
                found = true;
            }
        }
        return found;
    }
}
