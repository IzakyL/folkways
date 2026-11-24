package io.github.izakyl.folkways.core.shell.create.elevator;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import io.github.izakyl.folkways.core.facility.api.Contribution;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.world.api.Keepouts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Immutable main-thread snapshots keep planner queries from enumerating live world entities. */
public final class ElevatorShafts {
    private record Shaft(UUID vehicle, Set<Long> columns, int bottom, int top) {
        boolean contains(int x, int y, int z) {
            return y >= bottom && y <= top && columns.contains(ChunkPos.asLong(x, z));
        }
    }

    private static final Map<ServerLevel, ShaftKeepout> AREAS = new HashMap<>();

    // Track all contraptions: their elevator data may be assigned after the join event.
    private static final Map<ServerLevel, Set<AbstractContraptionEntity>> VEHICLES = new HashMap<>();

    private ElevatorShafts() {
    }

    public static void install() {
        var events = NeoForge.EVENT_BUS;
        events.addListener(ElevatorShafts::joined);
        events.addListener(ElevatorShafts::left);
        events.addListener(ElevatorShafts::tick);
        events.addListener(ElevatorShafts::unload);
    }

    static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof AbstractContraptionEntity vehicle) {
            VEHICLES.computeIfAbsent(level, ignored -> new HashSet<>()).add(vehicle);
        }
    }

    static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var vehicles = VEHICLES.get(level);
            if (vehicles != null) {
                vehicles.remove(event.getEntity());
                if (vehicles.isEmpty()) {
                    VEHICLES.remove(level);
                }
            }
        }
    }

    static void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            refresh(level);
        }
    }

    static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            VEHICLES.remove(level);
            AREAS.remove(level);
        }
    }

    static void refresh(ServerLevel level) {
        var vehicles = VEHICLES.get(level);
        if (vehicles == null || vehicles.isEmpty()) {
            publish(level, Map.of());
            return;
        }
        Map<Long, List<Shaft>> byChunk = new HashMap<>();
        for (var vehicle : vehicles) {
            if (!vehicle.isAlive()
                    || !(vehicle.getContraption() instanceof ElevatorContraption contraption)) {
                continue;
            }
            ElevatorColumn column = ElevatorColumn.get(level, contraption.getGlobalColumn());
            if (column == null) {
                continue;
            }
            List<BlockPos> contacts = column.getContacts().stream()
                .filter(at -> level.hasChunkAt(at) && !contraption.isTargetUnreachable(at.getY())
                    && level.getBlockState(at).getBlock() instanceof ElevatorContactBlock
                    && contraption.getGlobalColumn().equals(ElevatorContactBlock.getColumnCoords(level, at))).toList();
            if (contacts.isEmpty()) {
                continue;
            }
            var blocks = contraption.getBlocks().keySet();
            if (blocks.isEmpty()) {
                continue;
            }
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
            for (long chunk : chunks) {
                byChunk.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(shaft);
            }
        }
        byChunk.replaceAll((chunk, shafts) -> List.copyOf(shafts));
        publish(level, Map.copyOf(byChunk));
    }

    private static void publish(ServerLevel level, Map<Long, List<Shaft>> snapshot) {
        ShaftKeepout area = AREAS.get(level);
        if (area != null) {
            area.chunks = snapshot;
        } else if (!snapshot.isEmpty()) {
            area = new ShaftKeepout(snapshot);
            Facilities.projectSpatial(level, ElevatorShafts.class, List.of(new Contribution.Keepout(area)));
            AREAS.put(level, area);
        }
    }

    private static final class ShaftKeepout implements Keepouts.Keepout {

        private volatile Map<Long, List<Shaft>> chunks;

        private ShaftKeepout(Map<Long, List<Shaft>> chunks) {
            this.chunks = chunks;
        }

        @Override
        public boolean forbids(Level level, int x, int y, int z) {
            return forbids(level, level, x, y, z);
        }

        @Override
        public boolean forbids(Level level, BlockGetter context, int x, int y, int z) {
            return forbidden(chunks, context, x, y, z, Set.of());
        }

        @Override
        public Keepouts.Keepout blockingEscapeFrom(Level level, BlockGetter context, BlockPos origin) {
            Map<Long, List<Shaft>> snapshot = chunks;
            Set<UUID> occupied = new HashSet<>();
            long chunk = ChunkPos.asLong(origin.getX() >> 4, origin.getZ() >> 4);
            for (Shaft shaft : snapshot.getOrDefault(chunk, List.of())) {
                if (shaft.contains(origin.getX(), origin.getY(), origin.getZ())) {
                    occupied.add(shaft.vehicle());
                }
            }
            Set<UUID> exempt = Set.copyOf(occupied);
            return (host, x, y, z) -> host == level && forbidden(snapshot, context, x, y, z, exempt);
        }
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
