package io.github.izakyl.folkways.create.elevator;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import net.minecraft.world.entity.LivingEntity;
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
            Set<BlockPos> cabin = cabinFootings(lift);
            List<Integer> places = java.util.stream.IntStream.range(0, standingPlaces(lift, cabin).size()).boxed().toList();
            List<Integer> free = places.stream().filter(place -> occupant(lift, place, cabin).isEmpty()).toList();
            List<Floor> floors = new ArrayList<>();
            for (BlockPos contact : lift.column().getContacts()) {
                if (!validContact(lift, contact)) {
                    continue;
                }
                Set<WorldPos> platform = platforms(lift, contact.getY(), cabin);
                if (!platform.isEmpty()) {
                    floors.add(new Floor(contact.getY(), new Stances.Cells(platform),
                        new Stances.Cells(waiting(lift, contact.getY(), cabin, platform))));
                }
            }
            floors.sort(java.util.Comparator.comparingInt(Floor::y));
            OptionalInt stopped = lift.stopped() ? OptionalInt.of((int) Math.rint(lift.contactY())) : OptionalInt.empty();
            // Create accelerates toward this cap; do not use the instantaneous speed (zero while waiting).
            double speed = Math.min(1.99D, Math.abs(lift.pulley().getSpeed()) / 256.0D);
            return new View(lift.vehicle().getUUID(), speed > 0 && !lift.pulley().isOverStressed(), lift.contactY(),
                speed, stopped, places, free, floors);
        });
    }

    private boolean validContact(Lift lift, BlockPos contact) {
        return lift.level().hasChunkAt(contact) && !lift.contraption().isTargetUnreachable(contact.getY())
            && lift.level().getBlockState(contact).getBlock() instanceof ElevatorContactBlock
            && lift.contraption().getGlobalColumn().equals(ElevatorContactBlock.getColumnCoords(lift.level(), contact));
    }

    // The lowest connected deck is the passenger floor; roof tops are not another passenger deck.
    private Set<BlockPos> cabinFootings(Lift lift) {
        BlockGetter blocks = lift.contraption().getContraptionWorld();
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos support : lift.contraption().getBlocks().keySet()) {
            BlockPos feet = support.above();
            if (Footing.withFeetAt(blocks, feet)) candidates.add(feet);
        }
        if (candidates.isEmpty()) return Set.of();
        BlockPos first = candidates.stream().min(Comparator.<BlockPos>comparingInt(cell -> cell.getY())
            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).orElseThrow();
        Set<BlockPos> connected = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        connected.add(first); queue.add(first);
        while (!queue.isEmpty()) {
            BlockPos cell = queue.remove();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(side);
                if (candidates.contains(next) && connected.add(next)) queue.add(next);
            }
        }
        return Set.copyOf(connected);
    }

    private List<BlockPos> standingPlaces(Lift lift, Set<BlockPos> cabin) {
        List<BlockPos> interior = cabin.stream().filter(cell -> java.util.stream.StreamSupport
            .stream(Direction.Plane.HORIZONTAL.spliterator(), false).allMatch(side -> cabin.contains(cell.relative(side))))
            .sorted(Comparator.<BlockPos>comparingInt(cell -> cell.getX()).thenComparingInt(BlockPos::getZ)).toList();
        if (interior.isEmpty()) {
            // A small cabin needs no permanent passing aisle when its only passenger is inside.
            // Still require a real landing entrance rather than assigning capacity to an isolated slab.
            boolean entrance = lift.column().getContacts().stream().filter(contact -> validContact(lift, contact))
                .anyMatch(contact -> !platforms(lift, contact.getY(), cabin).isEmpty());
            return entrance ? cabin.stream().sorted(Comparator.<BlockPos>comparingInt(cell -> cell.getX())
                .thenComparingInt(BlockPos::getZ)).limit(1).toList() : List.of();
        }
        List<BlockPos> places = new ArrayList<>();
        // Keep edge aisles free, and leave a full cell between standing places where possible.
        for (BlockPos cell : interior) {
            if (places.stream().noneMatch(other -> other.distManhattan(cell) < 2)) places.add(cell);
        }
        return List.copyOf(places);
    }

    private Vec3 world(Lift lift, BlockPos local) {
        return lift.vehicle().toGlobalVector(Vec3.atBottomCenterOf(local), 1.0F);
    }

    private boolean aboard(Lift lift, LivingEntity body, Set<BlockPos> cabin) {
        if (body.level() != lift.level() || body.isPassenger() || !body.isAlive()) return false;
        Vec3 local = lift.vehicle().toLocalVector(body.position(), 1.0F);
        double half = body.getBbWidth() / 2.0D;
        for (double x : new double[] {-half + 0.01D, half - 0.01D}) {
            for (double z : new double[] {-half + 0.01D, half - 0.01D}) {
                BlockPos feet = BlockPos.containing(local.x + x, local.y + 0.2D, local.z + z);
                if (!cabin.contains(feet) || Math.abs(local.y - feet.getY()) > 0.35D) return false;
            }
        }
        return body.onGround() || lift.vehicle().collidingEntities.containsKey(body);
    }

    private boolean clearOfDeck(Lift lift, LivingEntity body, Set<BlockPos> cabin) {
        Vec3 local = lift.vehicle().toLocalVector(body.position(), 1.0F);
        double half = body.getBbWidth() / 2.0D + 0.05D;
        return cabin.stream().noneMatch(cell -> local.x + half > cell.getX() && local.x - half < cell.getX() + 1
            && local.z + half > cell.getZ() && local.z - half < cell.getZ() + 1
            && Math.abs(local.y - cell.getY()) < 1.0D);
    }

    private Optional<Integer> placeOf(Lift lift, LivingEntity body, Set<BlockPos> cabin) {
        if (!aboard(lift, body, cabin)) return Optional.empty();
        List<BlockPos> places = standingPlaces(lift, cabin);
        return java.util.stream.IntStream.range(0, places.size()).boxed()
            .min(Comparator.comparingDouble(place -> world(lift, places.get(place)).distanceToSqr(body.position())));
    }

    private Optional<UUID> occupant(Lift lift, int place, Set<BlockPos> cabin) {
        return lift.level().getEntitiesOfClass(LivingEntity.class, lift.vehicle().getBoundingBox().inflate(1.0D)).stream()
            .filter(body -> placeOf(lift, body, cabin).filter(index -> index == place).isPresent())
            .map(LivingEntity::getUUID).sorted().findFirst();
    }

    private Set<WorldPos> platforms(Lift lift, int floor, Set<BlockPos> cabin) {
        Set<WorldPos> result = new LinkedHashSet<>();
        BlockGetter blocks = lift.contraption().getContraptionWorld();
        for (BlockPos feet : cabin) {
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

    private Set<WorldPos> waiting(Lift lift, int floor, Set<BlockPos> cabin, Set<WorldPos> platform) {
        Set<BlockPos> projected = new HashSet<>();
        for (BlockPos local : cabin) {
            Vec3 current = lift.vehicle().toGlobalVector(Vec3.atLowerCornerOf(local), 1.0F);
            projected.add(BlockPos.containing(current.x + 0.01D,
                floor + local.getY() - lift.contraption().getContactYOffset() + 0.01D, current.z + 0.01D));
        }
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (WorldPos at : platform) {
            if (!projected.contains(at.cell()) && seen.add(at.cell())) queue.add(at.cell());
        }
        Set<WorldPos> result = new LinkedHashSet<>();
        // Expand real same-height floor cells only. Reproject the deck for this landing, even
        // while the car is elsewhere, so waiting never uses the empty shaft or its entrance.
        for (int distance = 0; distance <= 3 && !queue.isEmpty(); distance++) {
            int count = queue.size();
            for (int i = 0; i < count; i++) {
                BlockPos cell = queue.remove();
                if (projected.stream().allMatch(deck -> deck.distManhattan(cell) >= 3)) {
                    result.add(WorldPos.of(lift.level(), cell));
                }
                if (distance == 3) continue;
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos next = cell.relative(side);
                    if (!projected.contains(next) && !seen.contains(next) && lift.level().hasChunkAt(next)
                            && Footing.withFeetAt(lift.level(), next)) {
                        seen.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return result.isEmpty() ? platform : Set.copyOf(result);
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
    public boolean readyToDepart(MinecraftServer server, ElevatorRef ref) {
        Optional<Lift> found = lift(server, ref).filter(Lift::stopped);
        if (found.isEmpty()) return false;
        Lift lift = found.get();
        Set<BlockPos> cabin = cabinFootings(lift);
        if (cabin.isEmpty()) return false;
        int deckY = cabin.iterator().next().getY();
        for (LivingEntity body : lift.level().getEntitiesOfClass(LivingEntity.class,
                lift.vehicle().getBoundingBox().inflate(4.0D))) {
            if (!body.isAlive()) continue;
            Vec3 feet = lift.vehicle().toLocalVector(body.position(), 1.0F);
            if (Math.abs(feet.y - deckY) > 0.5D) continue;
            double half = body.getBbWidth() / 2.0D + 0.02D;
            boolean inside = false;
            boolean outside = false;
            for (int x = net.minecraft.util.Mth.floor(feet.x - half);
                    x < net.minecraft.util.Mth.ceil(feet.x + half); x++) {
                for (int z = net.minecraft.util.Mth.floor(feet.z - half);
                        z < net.minecraft.util.Mth.ceil(feet.z + half); z++) {
                    if (cabin.contains(new BlockPos(x, deckY, z))) inside = true;
                    else outside = true;
                }
            }
            if (inside && outside) return false;
        }
        return true;
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
    public Optional<UUID> occupant(MinecraftServer server, ElevatorRef ref, int place) {
        return lift(server, ref).flatMap(lift -> occupant(lift, place, cabinFootings(lift)));
    }

    @Override
    public boolean board(MinecraftServer server, ElevatorRef ref, int place, Mob body) {
        Optional<Lift> found = lift(server, ref).filter(Lift::stopped).filter(lift -> body.level() == lift.level());
        if (found.isEmpty() || body.isPassenger()) return false;
        Lift lift = found.get();
        Set<BlockPos> cabin = cabinFootings(lift);
        List<BlockPos> places = standingPlaces(lift, cabin);
        if (place < 0 || place >= places.size() || occupant(lift, place, cabin).filter(id -> !id.equals(body.getUUID())).isPresent()) return false;
        Vec3 target = world(lift, places.get(place));
        if (aboard(lift, body, cabin) && target.subtract(body.position()).horizontalDistanceSqr() <= 0.20D) {
            CabinNavigation.stop(body);
            return body.getDeltaMovement().horizontalDistanceSqr() <= 0.0025D;
        }
        CabinNavigation.walk(lift.level(), lift.vehicle(), body, Set.of(BlockPos.containing(target)));
        return false;
    }

    @Override
    public Optional<Integer> placeOf(MinecraftServer server, ElevatorRef ref, Mob body) {
        return lift(server, ref).flatMap(lift -> placeOf(lift, body, cabinFootings(lift)));
    }

    @Override
    public boolean disembark(MinecraftServer server, ElevatorRef ref, int place, Mob body) {
        return disembark(server, ref, place, body, Set.of());
    }

    @Override
    public boolean disembark(MinecraftServer server, ElevatorRef ref, int place, Mob body, Set<WorldPos> exits) {
        Optional<Lift> found = lift(server, ref).filter(Lift::stopped).filter(lift -> body.level() == lift.level());
        if (found.isEmpty() || body.isPassenger()) return false;
        Lift lift = found.get();
        Set<BlockPos> cabin = cabinFootings(lift);
        Set<WorldPos> available = platforms(lift, (int) Math.rint(lift.contactY()), cabin);
        Set<WorldPos> platforms = exits.isEmpty() ? available : available.stream().filter(exits::contains)
            .collect(java.util.stream.Collectors.toSet());
        if (platforms.isEmpty()) {
            CabinNavigation.stop(body);
            return false;
        }
        if (clearOfDeck(lift, body, cabin) && platforms.stream().anyMatch(at -> at.cell().equals(body.blockPosition()))
                && body.onGround() && Footing.withFeetAt(lift.level(), body.blockPosition())) {
            CabinNavigation.stop(body);
            return true;
        }
        CabinNavigation.walk(lift.level(), lift.vehicle(), body, platforms.stream().map(WorldPos::cell).collect(java.util.stream.Collectors.toSet()));
        return false;
    }
}
