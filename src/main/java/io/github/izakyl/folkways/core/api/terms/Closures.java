package io.github.izakyl.folkways.core.api.terms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Ground the ways are closed over while whoever closed it sees to it, such as a site going up. No way is laid into
 * or through it: a way to anywhere in it ends at one of its doors, the open ground standing just outside it, and is
 * walked from there; a way out of it starts at the door nearest. Bodies are not kept out, only the ways.
 */
public final class Closures {

    // The open ground around a closure is looked over this far below and above it for somewhere to stand.
    private static final int DOOR_DROP = 4;

    private static final int MOST_DOORS = 256;

    public record Closed(ResourceKey<Level> dimension, BoundingBox box, List<WorldPos> doors) {

        public Closed {
            doors = List.copyOf(doors);
        }

        public boolean holds(WorldPos cell) {
            return cell.realm() instanceof Realm.Dimension at && at.id().equals(dimension)
                && box.isInside(cell.cell());
        }

        public boolean holds(BlockPos cell) {
            return box.isInside(cell);
        }
    }

    private record Key(MinecraftServer server, ResourceKey<Level> dimension) {
    }

    private static final Map<Key, List<Closed>> CLOSED = new ConcurrentHashMap<>();

    private Closures() {
    }

    /** Closes the ways over {@code box} until the answer is run, which opens them again; running it twice is once. */
    public static Runnable close(ServerLevel level, BoundingBox box) {
        Key key = new Key(level.getServer(), level.dimension());
        Closed closed = new Closed(level.dimension(), box, doors(level, box));
        CLOSED.compute(key, (at, had) -> {
            List<Closed> now = new ArrayList<>(had == null ? List.of() : had);
            now.add(closed);
            return List.copyOf(now);
        });
        return () -> CLOSED.computeIfPresent(key, (at, had) -> {
            List<Closed> now = new ArrayList<>(had);
            now.removeIf(one -> one == closed);
            return now.isEmpty() ? null : List.copyOf(now);
        });
    }

    /** What is closed in the level now; the list stays as it is, and is a new one whenever anything closes or opens. */
    public static List<Closed> in(ServerLevel level) {
        return CLOSED.getOrDefault(new Key(level.getServer(), level.dimension()), List.of());
    }

    public static void forgetServer(MinecraftServer server) {
        CLOSED.keySet().removeIf(key -> key.server() == server);
    }

    // Every cell a body could stand on in the ring just outside the box: floored, with room overhead.
    private static List<WorldPos> doors(ServerLevel level, BoundingBox box) {
        List<BlockPos> ring = new ArrayList<>();
        for (int x = box.minX() - 1; x <= box.maxX() + 1; x++) {
            for (int z = box.minZ() - 1; z <= box.maxZ() + 1; z++) {
                boolean outside = x < box.minX() || x > box.maxX() || z < box.minZ() || z > box.maxZ();
                if (!outside) {
                    continue;
                }
                for (int y = box.maxY() + 1; y >= box.minY() - DOOR_DROP; y--) {
                    BlockPos feet = new BlockPos(x, y, z);
                    if (standsAt(level, feet)) {
                        ring.add(feet);
                    }
                }
            }
        }
        int every = Math.max(1, (ring.size() + MOST_DOORS - 1) / MOST_DOORS);
        List<WorldPos> doors = new ArrayList<>();
        for (int at = 0; at < ring.size(); at += every) {
            doors.add(WorldPos.of(level, ring.get(at)));
        }
        return doors;
    }

    private static boolean standsAt(ServerLevel level, BlockPos feet) {
        BlockPos floor = feet.below();
        return level.hasChunkAt(feet)
            && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet).getFluidState().isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
            && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }
}
