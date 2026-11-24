package io.github.izakyl.folkways.core.shell.cbc;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.shell.cbc.mixin.CannonMountAccess;
import io.github.izakyl.folkways.core.shell.create.actuator.mixin.RotationPropagatorAccess;
import io.github.izakyl.folkways.core.shell.create.kinetic.CreateCranks;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlock;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountInterfaceBlockEntity;

/** Aiming a mounted cannon by turning Create hand cranks and letting CBC apply the shaft rotation. */
final class CbcCrankAim {

    private static final double TOLERANCE = 0.02;
    private static final int MAX_SHAFT_BLOCKS = 64;
    private static final int MAX_TURN_TICKS = 1200;
    private static final Map<CannonMountBlockEntity, Job> JOBS = new WeakHashMap<>();

    private CbcCrankAim() {
    }

    static Value observed(CannonMountBlockEntity mount) {
        var angles = (CannonMountAccess) mount;
        Job job = JOBS.get(mount);
        // CBC keeps its own signed representation of the turn. Report the equivalent angle nearest the command.
        double yaw = job == null ? angles.folkwaysYaw() : job.target().values().get(0) - yawError(mount, job.target());
        return Value.of(yaw, angles.folkwaysPitch());
    }

    static Optional<Value> reached(CannonMountBlockEntity mount) {
        Job job = JOBS.get(mount);
        return job != null && mount.getYawSpeed() == 0 && mount.getPitchSpeed() == 0
            && Math.abs(yawError(mount, job.target())) <= TOLERANCE
            && Math.abs(pitchError(mount, job.target())) <= TOLERANCE
            ? Optional.of(job.target()) : Optional.empty();
    }

    static Signal.Write drive(ServerLevel level, CannonMountBlockEntity mount, Value.Vector target) {
        var state = mount.getBlockState();
        if (!(state.getBlock() instanceof CannonMountBlock block)) {
            return Signal.Write.REFUSED;
        }
        Direction yawFace = state.getValue(CannonMountBlock.VERTICAL_DIRECTION);
        Direction pitchFace = Direction.fromAxisAndDirection(block.getRotationAxis(state),
            Direction.AxisDirection.POSITIVE);
        var yaw = shaft(level, mount, yawFace);
        var pitch = shaft(level, mount, pitchFace).or(() -> shaft(level, mount, pitchFace.getOpposite()));
        var direction = mount.getContraption().getInitialOrientation();
        boolean positivePitch = (direction.getAxisDirection() == Direction.AxisDirection.POSITIVE)
            == (direction.getAxis() == Direction.Axis.X);
        var horizontal = new Axis((CannonMountInterfaceBlockEntity) mount.getYawInterface(), yaw.orElse(null),
            yawError(mount, target), -1);
        var vertical = new Axis((CannonMountInterfaceBlockEntity) mount.getPitchInterface(), pitch.orElse(null),
            pitchError(mount, target), positivePitch ? 1 : -1);
        Job previous = JOBS.get(mount);
        if (horizontal.busy(level, previous == null ? null : previous.yaw())
                || vertical.busy(level, previous == null ? null : previous.pitch())) {
            return Signal.Write.BUSY;
        }
        if (horizontal.missingDrive() || vertical.missingDrive()
                || horizontal.ticks(mount) > MAX_TURN_TICKS || vertical.ticks(mount) > MAX_TURN_TICKS
                || horizontal.input().isOverStressed() || vertical.input().isOverStressed()
                || yaw.isPresent() && pitch.isPresent() && yaw.get().crank() == pitch.get().crank()) {
            return Signal.Write.REFUSED;
        }
        JOBS.put(mount, new Job(target, horizontal.turn(mount), vertical.turn(mount)));
        return Signal.Write.WRITTEN;
    }

    private static double yawError(CannonMountBlockEntity mount, Value.Vector target) {
        return Mth.wrapDegrees(target.values().get(0) - ((CannonMountAccess) mount).folkwaysYaw());
    }

    private static double pitchError(CannonMountBlockEntity mount, Value.Vector target) {
        return target.values().get(1) - ((CannonMountAccess) mount).folkwaysPitch();
    }

    private static Optional<Drive> shaft(ServerLevel level, CannonMountBlockEntity mount, Direction face) {
        var at = mount.getBlockPos().relative(face);
        if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof KineticBlockEntity first)
                || first.isRemoved() || !hasShaft(level, first, face.getOpposite())) {
            return Optional.empty();
        }
        var checked = new HashSet<BlockPos>();
        checked.add(mount.getBlockPos());
        checked.add(at);
        var pending = new ArrayDeque<Shaft>();
        pending.add(new Shaft(first, 1));
        while (!pending.isEmpty() && checked.size() <= MAX_SHAFT_BLOCKS) {
            Shaft next = pending.removeFirst();
            if (next.block() instanceof HandCrankBlockEntity crank && !(crank instanceof ValveHandleBlockEntity)) {
                return Optional.of(new Drive(crank, next.ratio()));
            }
            for (BlockPos neighbor : RotationPropagatorAccess.folkways$neighbours(next.block())) {
                if (checked.contains(neighbor) || !level.isLoaded(neighbor)
                        || !(level.getBlockEntity(neighbor) instanceof KineticBlockEntity other)
                        || other instanceof CannonMountBlockEntity || other.isRemoved()) {
                    continue;
                }
                float ratio = RotationPropagatorAccess.folkways$modifier(other, next.block());
                if (ratio != 0) {
                    checked.add(neighbor);
                    pending.addLast(new Shaft(other, next.ratio() * ratio));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean hasShaft(ServerLevel level, KineticBlockEntity block, Direction face) {
        var state = block.getBlockState();
        return state.getBlock() instanceof IRotate rotation
            && rotation.hasShaftTowards(level, block.getBlockPos(), state, face);
    }

    private record Shaft(KineticBlockEntity block, float ratio) {
    }

    private record Drive(HandCrankBlockEntity crank, float ratio) {
    }

    private record Job(Value.Vector target, BlockPos yaw, BlockPos pitch) {
    }

    private record Axis(CannonMountInterfaceBlockEntity input, Drive drive, double error, int direction) {

        boolean missingDrive() {
            return drive == null && Math.abs(error) > TOLERANCE;
        }

        boolean busy(ServerLevel level, BlockPos previous) {
            if (input.getTheoreticalSpeed() != 0 && !poweredByCrank(level)) {
                return true;
            }
            return drive != null && drive.crank().inUse > 0 && !drive.crank().getBlockPos().equals(previous);
        }

        private boolean poweredByCrank(ServerLevel level) {
            if (drive == null) {
                return false;
            }
            BlockPos at = input.source;
            for (int distance = 0; at != null && distance < MAX_SHAFT_BLOCKS; distance++) {
                if (at.equals(drive.crank().getBlockPos())) {
                    return true;
                }
                if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof KineticBlockEntity block)) {
                    return false;
                }
                at = block.source;
            }
            return false;
        }

        int ticks(CannonMountBlockEntity mount) {
            if (drive == null || Math.abs(error) <= TOLERANCE) {
                return 0;
            }
            double speed = Math.abs(mount.getAngularSpeed(CreateCranks.forwardSpeed(drive.crank()) * drive.ratio(), 0));
            double ticks = Math.ceil(Math.abs(error) / speed) + 4;
            return (int) Math.min(Integer.MAX_VALUE, ticks);
        }

        BlockPos turn(CannonMountBlockEntity mount) {
            if (drive == null) {
                return null;
            }
            HandCrankBlockEntity crank = drive.crank();
            if (Math.abs(error) <= TOLERANCE) {
                return crank.inUse > 0 ? crank.getBlockPos() : null;
            }
            boolean reverse = (CreateCranks.forwardSpeed(crank) * drive.ratio() * direction < 0) != (error < 0);
            crank.turn(reverse);
            crank.inUse = Math.max(crank.inUse, ticks(mount));
            // CBC consumes this limit while integrating the actual kinetic input, including a partial final tick.
            input.setSequencedAngleLimit(Math.abs(error));
            return crank.getBlockPos();
        }
    }
}
