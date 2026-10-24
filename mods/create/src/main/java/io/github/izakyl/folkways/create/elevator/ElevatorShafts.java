package io.github.izakyl.folkways.create.elevator;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Immutable main-thread snapshots keep planner queries from enumerating live world entities. */
final class ElevatorShafts implements Keepouts.Keepout {
    static final ElevatorShafts INSTANCE = new ElevatorShafts();
    private record Shaft(UUID vehicle, Set<Long> columns, int bottom, int top) {
        boolean contains(int x, int y, int z) {
            return y >= bottom && y <= top && columns.contains(ChunkPos.asLong(x, z));
        }
    }
    private static final Map<ServerLevel, Map<Long, List<Shaft>>> SNAPSHOTS = new ConcurrentHashMap<>();

    private ElevatorShafts() { }

    static void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) refresh(level);
    }

    static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var removed = SNAPSHOTS.remove(level);
            if (removed != null && !removed.isEmpty()) Keepouts.changed();
        }
    }

    @Override
    public void refresh(Level level) {
        if (level instanceof ServerLevel server && server.getServer().isSameThread()) refresh(server);
    }

    static void refresh(ServerLevel level) {
        if (!level.getServer().isSameThread()) return;
        Map<Long, List<Shaft>> byChunk = new HashMap<>();
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof AbstractContraptionEntity vehicle) || !vehicle.isAlive()
                    || !(vehicle.getContraption() instanceof ElevatorContraption contraption)) continue;
            ElevatorColumn column = ElevatorColumn.get(level, contraption.getGlobalColumn());
            if (column == null) continue;
            List<BlockPos> contacts = column.getContacts().stream()
                .filter(at -> level.hasChunkAt(at) && !contraption.isTargetUnreachable(at.getY())
                    && level.getBlockState(at).getBlock() instanceof ElevatorContactBlock
                    && contraption.getGlobalColumn().equals(ElevatorContactBlock.getColumnCoords(level, at))).toList();
            if (contacts.isEmpty()) continue;
            var blocks = contraption.getBlocks().keySet();
            if (blocks.isEmpty()) continue;
            int minBlock = blocks.stream().mapToInt(BlockPos::getY).min().orElseThrow();
            int maxBlock = blocks.stream().mapToInt(BlockPos::getY).max().orElseThrow();
            int minFloor = contacts.stream().mapToInt(BlockPos::getY).min().orElseThrow();
            int maxFloor = contacts.stream().mapToInt(BlockPos::getY).max().orElseThrow();
            Set<Long> columns = new HashSet<>();
            Set<Long> chunks = new HashSet<>();
            for (BlockPos local : blocks) {
                Vec3 point = vehicle.toGlobalVector(Vec3.atLowerCornerOf(local), 1.0F);
                int x = net.minecraft.util.Mth.floor(point.x + 0.001D);
                int z = net.minecraft.util.Mth.floor(point.z + 0.001D);
                columns.add(ChunkPos.asLong(x, z));
                chunks.add(ChunkPos.asLong(x >> 4, z >> 4));
            }
            Shaft shaft = new Shaft(vehicle.getUUID(), Set.copyOf(columns),
                minFloor + minBlock - contraption.getContactYOffset(),
                maxFloor + maxBlock + 1 - contraption.getContactYOffset());
            for (long chunk : chunks) byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(shaft);
        }
        byChunk.replaceAll((chunk, shafts) -> {
            shafts.sort(Comparator.comparing(Shaft::vehicle));
            return List.copyOf(shafts);
        });
        Map<Long, List<Shaft>> next = Map.copyOf(byChunk);
        var previous = SNAPSHOTS.get(level);
        if (previous == null ? next.isEmpty() : previous.equals(next)) return;
        // Publish before advancing the version, so a fresh certificate sees this geometry.
        SNAPSHOTS.put(level, next);
        Keepouts.changed();
    }

    @Override
    public boolean forbids(Level level, int x, int y, int z) {
        return forbids(level, level, x, y, z);
    }

    @Override
    public boolean forbids(Level level, BlockGetter context, int x, int y, int z) {
        if (!(level instanceof ServerLevel server)) return false;
        var chunks = SNAPSHOTS.get(server);
        if (chunks == null) return false;
        return forbidden(chunks, context, x, y, z, Set.of());
    }

    @Override
    public Keepouts.Keepout blockingEscapeFrom(Level level, BlockGetter context, BlockPos origin) {
        if (!(level instanceof ServerLevel server)) return (host, x, y, z) -> false;
        var snapshot = SNAPSHOTS.get(server);
        if (snapshot == null) return (host, x, y, z) -> false;
        Set<UUID> occupied = new HashSet<>();
        for (Shaft shaft : snapshot.getOrDefault(ChunkPos.asLong(origin.getX() >> 4, origin.getZ() >> 4), List.of())) {
            if (shaft.contains(origin.getX(), origin.getY(), origin.getZ())) occupied.add(shaft.vehicle());
        }
        Set<UUID> exempt = Set.copyOf(occupied);
        return (host, x, y, z) -> host == level && forbidden(snapshot, context, x, y, z, exempt);
    }

    private static boolean forbidden(Map<Long, List<Shaft>> chunks, BlockGetter context,
                                     int x, int y, int z, Set<UUID> exempt) {
        for (Shaft shaft : chunks.getOrDefault(ChunkPos.asLong(x >> 4, z >> 4), List.of())) {
            if (!exempt.contains(shaft.vehicle()) && shaft.contains(x, y, z)
                    && !(context instanceof CabinPathRegion cabin && cabin.permits(shaft.vehicle(), x, y, z))) {
                return true;
            }
        }
        return false;
    }
}
