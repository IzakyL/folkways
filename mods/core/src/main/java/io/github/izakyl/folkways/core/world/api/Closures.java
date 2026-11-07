package io.github.izakyl.folkways.core.world.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Public ways stay outside closed work areas; bodies navigate those areas with their own locomotion. */
public final class Closures {

    public record Closed(ResourceKey<Level> dimension, BoundingBox box) {
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
        Closed closed = new Closed(level.dimension(), box);
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

}
