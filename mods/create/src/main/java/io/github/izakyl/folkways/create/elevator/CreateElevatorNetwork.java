package io.github.izakyl.folkways.create.elevator;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.create.transport.ContraptionSeats;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.Vec3;

/** The only adapter which knows about Create's pulley, contact and contraption implementation. */
public final class CreateElevatorNetwork implements ElevatorNetwork {
    private static final double STILL = 1.0D / 1024.0D;

    private record Lift(ServerLevel level, ElevatorPulleyBlockEntity pulley,
                        AbstractContraptionEntity vehicle, ElevatorContraption contraption, ElevatorColumn column) {
        double contactY() {
            return vehicle.getY() + contraption.getContactYOffset();
        }

        boolean stopped() {
            return contraption.arrived && vehicle.position().distanceToSqr(vehicle.getPrevAnchorVec()) < STILL * STILL
                && Math.abs(contactY() - Math.rint(contactY())) < STILL;
        }
    }

    private Optional<Lift> lift(MinecraftServer server, ElevatorRef ref) {
        ServerLevel level = server.getLevel(ref.dimension());
        if (level == null || !level.hasChunkAt(ref.pulley())
                || !(level.getBlockEntity(ref.pulley()) instanceof ElevatorPulleyBlockEntity pulley)) {
            return Optional.empty();
        }
        AbstractContraptionEntity vehicle = pulley.getAttachedContraption();
        if (vehicle == null || !vehicle.isAlive() || vehicle.isStalled()
                || !(vehicle.getContraption() instanceof ElevatorContraption contraption)) {
            return Optional.empty();
        }
        ElevatorColumn column = ElevatorColumn.get(level, contraption.getGlobalColumn());
        return column == null ? Optional.empty() : Optional.of(new Lift(level, pulley, vehicle, contraption, column));
    }

    @Override
    public Optional<View> inspect(MinecraftServer server, ElevatorRef ref) {
        return lift(server, ref).map(lift -> {
            List<Integer> seats = IntStream.range(0, lift.contraption().getSeats().size()).boxed().toList();
            List<Integer> free = seats.stream()
                .filter(seat -> ContraptionSeats.occupant(lift.vehicle(), seat).isEmpty()).toList();
            Set<BlockPos> cabin = cabinFootings(lift);
            List<Floor> floors = new ArrayList<>();
            for (BlockPos contact : lift.column().getContacts()) {
                if (!validContact(lift, contact)) {
                    continue;
                }
                Set<WorldPos> platform = platforms(lift, contact.getY(), cabin);
                if (!platform.isEmpty()) {
                    floors.add(new Floor(contact.getY(), new Stances.Cells(platform)));
                }
            }
            floors.sort(java.util.Comparator.comparingInt(Floor::y));
            OptionalInt stopped = lift.stopped() ? OptionalInt.of((int) Math.rint(lift.contactY())) : OptionalInt.empty();
            // Create accelerates toward this cap; do not use the instantaneous speed (zero while waiting).
            double speed = Math.min(1.99D, Math.abs(lift.pulley().getSpeed()) / 256.0D);
            return new View(lift.vehicle().getUUID(), speed > 0 && !lift.pulley().isOverStressed(), lift.contactY(),
                speed, stopped, seats, free, floors);
        });
    }

    private boolean validContact(Lift lift, BlockPos contact) {
        return lift.level().hasChunkAt(contact) && !lift.contraption().isTargetUnreachable(contact.getY())
            && lift.level().getBlockState(contact).getBlock() instanceof ElevatorContactBlock
            && lift.contraption().getGlobalColumn().equals(ElevatorContactBlock.getColumnCoords(lift.level(), contact));
    }

    // Find the deck connected to a seat, rather than interpreting contact height as passenger feet height.
    private Set<BlockPos> cabinFootings(Lift lift) {
        BlockGetter cabin = lift.contraption().getContraptionWorld();
        Set<BlockPos> seats = new HashSet<>(lift.contraption().getSeats());
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos support : lift.contraption().getBlocks().keySet()) {
            BlockPos feet = support.above();
            if (Footing.holdsAbove(cabin, support) && entrancePassable(cabin, feet.above())
                    && (seats.contains(feet) || entrancePassable(cabin, feet))) {
                candidates.add(feet);
            }
        }
        Set<BlockPos> connected = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos seat : seats) {
            if (candidates.contains(seat) && connected.add(seat)) {
                queue.add(seat);
            }
        }
        while (!queue.isEmpty()) {
            BlockPos cell = queue.remove();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(direction);
                if (candidates.contains(next) && connected.add(next)) {
                    queue.add(next);
                }
            }
        }
        return connected;
    }

    private Set<WorldPos> platforms(Lift lift, int floor, Set<BlockPos> cabin) {
        Set<WorldPos> result = new LinkedHashSet<>();
        BlockGetter blocks = lift.contraption().getContraptionWorld();
        for (BlockPos feet : cabin) {
            // Seat blocks themselves can be exits when the seat occupies the edge of a small cabin.
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos outside = feet.relative(direction);
                if (cabin.contains(outside) || !entrancePassable(blocks, outside)
                        || !entrancePassable(blocks, outside.above())
                        || lift.contraption().getBlocks().containsKey(outside.below())) {
                    continue;
                }
                Vec3 current = lift.vehicle().toGlobalVector(Vec3.atLowerCornerOf(outside), 1.0F);
                BlockPos world = BlockPos.containing(current.x + 0.01D,
                    floor + outside.getY() - lift.contraption().getContactYOffset() + 0.01D, current.z + 0.01D);
                BlockPos inner = world.relative(direction.getOpposite());
                if (!lift.level().hasChunkAt(world) || !lift.level().hasChunkAt(inner)
                        || !entrancePassable(lift.level(), inner) || !entrancePassable(lift.level(), inner.above())) {
                    continue;
                }
                // A fixed landing door may be closed while the cabin serves another floor. Wait outside it.
                if (isEntrance(lift.level().getBlockState(world))) {
                    if (!Footing.holdsAbove(lift.level(), world.below())
                            || !entrancePassable(lift.level(), world.above())) {
                        continue;
                    }
                    world = world.relative(direction);
                }
                if (lift.level().hasChunkAt(world) && Footing.withFeetAt(lift.level(), world)) {
                    result.add(WorldPos.of(lift.level(), world));
                }
            }
        }
        return result;
    }

    // Closed automatic doors are still entrances: their current animation must not remove a floor from routing.
    private boolean entrancePassable(BlockGetter blocks, BlockPos pos) {
        BlockState state = blocks.getBlockState(pos);
        return isEntrance(state) || Footing.isPassable(blocks, pos);
    }

    private boolean isEntrance(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof FenceGateBlock;
    }

    @Override
    public boolean target(MinecraftServer server, ElevatorRef ref, int floor) {
        Optional<Lift> found = lift(server, ref);
        if (found.isEmpty()) {
            return false;
        }
        Lift lift = found.get();
        BlockPos contact = lift.column().contactAt(floor);
        if (!validContact(lift, contact) || lift.pulley().getSpeed() == 0 || lift.pulley().isOverStressed()) {
            return false;
        }
        // Same state changes as callToContactAndUpdate, visiting only loaded contacts: calling must not load chunks.
        for (BlockPos other : lift.column().getContacts()) {
            if (!lift.level().hasChunkAt(other)) {
                continue;
            }
            BlockState state = lift.level().getBlockState(other);
            if (state.getBlock() instanceof ElevatorContactBlock block) {
                lift.level().setBlock(other, state.setValue(ElevatorContactBlock.CALLING, other.equals(contact)), 2);
                if (!other.equals(contact)) {
                    block.scheduleActivation(lift.level(), other);
                }
            }
        }
        lift.level().updateNeighborsAt(contact, lift.level().getBlockState(contact).getBlock());
        lift.column().target(floor);
        lift.column().markDirty();
        return true;
    }

    @Override
    public Optional<UUID> occupant(MinecraftServer server, ElevatorRef ref, int seat) {
        return lift(server, ref).flatMap(lift -> ContraptionSeats.occupant(lift.vehicle(), seat));
    }

    @Override
    public boolean board(MinecraftServer server, ElevatorRef ref, int seat, Mob body) {
        return lift(server, ref).filter(Lift::stopped).filter(lift -> body.level() == lift.level())
            .filter(lift -> platforms(lift, (int) Math.rint(lift.contactY()), cabinFootings(lift)).stream()
                .anyMatch(platform -> Vec3.atBottomCenterOf(platform.cell()).distanceToSqr(body.position()) <= 4.0D))
            .filter(lift -> Math.abs(body.getY() - (lift.vehicle().getY()
                + seatHeight(lift, seat))) < 1.0D)
            .map(lift -> ContraptionSeats.board(lift.vehicle(), seat, body)).orElse(false);
    }

    private double seatHeight(Lift lift, int seat) {
        return seat >= 0 && seat < lift.contraption().getSeats().size()
            ? lift.contraption().getSeats().get(seat).getY() : Double.NaN;
    }

    @Override
    public Optional<Integer> seatOf(MinecraftServer server, ElevatorRef ref, Mob body) {
        return lift(server, ref).filter(lift -> body.getVehicle() == lift.vehicle())
            .map(lift -> lift.contraption().getSeatMapping().get(body.getUUID()));
    }

    @Override
    public void disembark(MinecraftServer server, ElevatorRef ref, int seat, Mob body) {
        lift(server, ref).filter(Lift::stopped).filter(lift -> body.getVehicle() == lift.vehicle())
            .ifPresent(lift -> ContraptionSeats.disembark(body));
    }
}
