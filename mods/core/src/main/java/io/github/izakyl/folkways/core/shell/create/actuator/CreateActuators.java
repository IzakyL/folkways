package io.github.izakyl.folkways.core.shell.create.actuator;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock;
import com.simibubi.create.content.contraptions.gantry.GantryContraption;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlock;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.contraptions.piston.PistonExtensionPoleBlock;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.control.Motion;
import io.github.izakyl.folkways.core.shell.create.actuator.mixin.LinearActuatorRead;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CreateActuators {

    private CreateActuators() {
    }

    public static void install() {
        Controls.registerMotion((level, at) -> {
            var block = level.getBlockEntity(at.block(level));
            if (block instanceof MechanicalPistonBlockEntity piston) {
                return Optional.of(new Linear(at, piston));
            }
            if (block instanceof PulleyBlockEntity pulley && !(pulley instanceof ElevatorPulleyBlockEntity)
                && pulley.getMirrorParent() == null) {
                return Optional.of(new Linear(at, pulley));
            }
            var pos = at.block(level);
            if (level.getBlockState(pos).getBlock() instanceof GantryShaftBlock) {
                return gantry(level, at, pos);
            }
            return Optional.empty();
        });
    }

    // Use a fixed shaft as the address, since Create removes the carriage block during travel.
    private static Optional<Motion> gantry(ServerLevel level, WorldPos anchor, BlockPos shaft) {
        Direction direction = level.getBlockState(shaft).getValue(GantryShaftBlock.FACING);
        BlockPos first = end(level, shaft, direction.getOpposite(), direction);
        BlockPos last = end(level, shaft, direction, direction);
        Vec3 start = Vec3.atLowerCornerOf(first);
        Vec3 axis = Vec3.atLowerCornerOf(direction.getNormal());
        double maximum = Vec3.atLowerCornerOf(last).subtract(start).dot(axis);
        var bounds = new AABB(start, Vec3.atLowerCornerOf(last)).inflate(2);
        var moving = level.getEntitiesOfClass(GantryContraptionEntity.class, bounds, entity -> {
            if (!(entity.getContraption() instanceof GantryContraption contraption)) {
                return false;
            }
            Vec3 pinion = entity.position().subtract(Vec3.atLowerCornerOf(contraption.getFacing().getNormal()));
            Vec3 delta = pinion.subtract(start);
            double offset = delta.dot(axis);
            return offset >= 0 && offset <= maximum && delta.subtract(axis.scale(offset)).lengthSqr() < 1e-6;
        });
        Gantry reading = null;
        for (var entity : moving) {
            if (reading != null) {
                return Optional.empty();
            }
            reading = new Gantry(anchor, entity.position().subtract(start).dot(axis), maximum,
                entity.position(), entity);
        }
        for (BlockPos rail : BlockPos.betweenClosed(first, last)) {
            for (Direction facing : Direction.values()) {
                if (facing.getAxis() == direction.getAxis()) {
                    continue;
                }
                var cell = rail.relative(facing);
                var state = level.getBlockState(cell);
                if (state.getBlock() instanceof GantryCarriageBlock
                    && state.getValue(GantryCarriageBlock.FACING) == facing
                    && GantryCarriageBlock.isValidGantryShaftAxis(state, level.getBlockState(rail))) {
                    if (reading != null) {
                        return Optional.empty();
                    }
                    reading = new Gantry(anchor, Vec3.atLowerCornerOf(rail).subtract(start).dot(axis),
                        maximum, Vec3.atLowerCornerOf(cell), null);
                }
            }
        }
        return Optional.ofNullable(reading);
    }

    private static BlockPos end(ServerLevel level, BlockPos start, Direction step, Direction facing) {
        BlockPos current = start;
        while (true) {
            BlockPos next = current.relative(step);
            if (!level.hasChunkAt(next)) {
                return current;
            }
            var state = level.getBlockState(next);
            if (!(state.getBlock() instanceof GantryShaftBlock)
                || state.getValue(GantryShaftBlock.FACING) != facing) {
                return current;
            }
            current = next;
        }
    }

    private record Gantry(WorldPos anchor, double position, double maximum, Vec3 carriage,
                          GantryContraptionEntity entity) implements Motion {

        @Override
        public String part() {
            return "motion";
        }

        @Override
        public boolean moving() {
            return entity != null && entity.getDeltaMovement().lengthSqr() > 0 && !entity.isStalled();
        }

        @Override
        public Optional<Vec3> contraption() {
            return Optional.of(carriage);
        }

        @Override
        public Optional<AABB> sweep() {
            return moving() ? Optional.of(entity.getBoundingBox().expandTowards(entity.getDeltaMovement()))
                : Optional.empty();
        }
    }

    private record Linear(WorldPos anchor, LinearActuatorBlockEntity actuator) implements Motion {

        @Override
        public String part() {
            return "motion";
        }

        @Override
        public boolean valid(ServerLevel level) {
            return Motion.super.valid(level) && level.getBlockEntity(anchor.block(level)) == actuator;
        }

        @Override
        public double position() {
            if (!actuator.running && actuator instanceof MechanicalPistonBlockEntity piston) {
                return pistonPoles(piston, false);
            }
            return actuator.offset;
        }

        @Override
        public double maximum() {
            if (!actuator.running && actuator instanceof MechanicalPistonBlockEntity piston) {
                return pistonPoles(piston, true);
            }
            return ((LinearActuatorRead) actuator).folkways$extensionRange();
        }

        @Override
        public boolean moving() {
            return actuator.running && actuator.getMovementSpeed() != 0
                && (!actuator.needsContraption || actuator.movedContraption != null)
                && (actuator.movedContraption == null || !actuator.movedContraption.isStalled());
        }

        @Override
        public Optional<Vec3> contraption() {
            return Optional.ofNullable(actuator.movedContraption).filter(AbstractContraptionEntity::isAlive)
                .map(AbstractContraptionEntity::position);
        }

        @Override
        public Optional<AABB> sweep() {
            return moving() && actuator.movedContraption != null
                ? Optional.of(actuator.movedContraption.getBoundingBox().expandTowards(actuator.getMotionVector()))
                : Optional.empty();
        }
    }

    private static int pistonPoles(MechanicalPistonBlockEntity piston, boolean countRear) {
        int poles = 0;
        Direction facing = piston.getBlockState().getValue(BlockStateProperties.FACING);
        boolean extended = piston.getBlockState().getValue(MechanicalPistonBlock.STATE)
            == MechanicalPistonBlock.PistonState.EXTENDED;
        for (Direction direction : countRear ? List.of(facing, facing.getOpposite()) : List.of(facing)) {
            if (direction == facing && !extended) {
                continue;
            }
            for (int distance = 1; distance <= MechanicalPistonBlock.maxAllowedPistonPoles(); distance++) {
                var pos = piston.getBlockPos().relative(direction, distance);
                if (!piston.getLevel().hasChunkAt(pos)) {
                    break;
                }
                var state = piston.getLevel().getBlockState(pos);
                if (direction == facing && MechanicalPistonBlock.isPistonHead(state)
                    && state.getValue(BlockStateProperties.FACING) == facing) {
                    poles++;
                    break;
                }
                if (!(state.getBlock() instanceof PistonExtensionPoleBlock)
                    || state.getValue(BlockStateProperties.FACING).getAxis() != direction.getAxis()) {
                    break;
                }
                poles++;
            }
        }
        return poles;
    }
}
