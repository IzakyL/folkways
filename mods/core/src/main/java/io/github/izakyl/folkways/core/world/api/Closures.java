package io.github.izakyl.folkways.core.world.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Public ways stay outside closed work areas; bodies navigate those areas with their own locomotion. */
public final class Closures {

    public record Closed(Realm realm, BoundingBox box, boolean impassable) {
        public Closed(ResourceKey<Level> dimension, BoundingBox box) {
            this(new Realm.Dimension(dimension), box, false);
        }

        public boolean holds(WorldPos cell) {
            return realm.equals(cell.realm()) && box.isInside(cell.cell());
        }

        public boolean holds(BlockPos cell) {
            return realm instanceof Realm.Dimension && !impassable && box.isInside(cell);
        }
    }

    private record Key(MinecraftServer server, ResourceKey<Level> dimension) {
    }

    private record Entry(Object source, Closed closed) {
    }

    private record Snapshot(List<Entry> entries, List<Closed> closed) {

        private Snapshot(List<Entry> entries) {
            this(List.copyOf(entries), entries.stream().map(Entry::closed).toList());
        }
    }

    private static final Map<Key, Snapshot> CLOSED = new ConcurrentHashMap<>();

    private Closures() {
    }

    /** Closes the ways over {@code box} until the answer is run, which opens them again; running it twice is once. */
    public static Runnable close(ServerLevel level, BoundingBox box) {
        return close(level, new Object(), box);
    }

    public static Runnable close(ServerLevel level, Object source, BoundingBox box) {
        return close(level, source, new Realm.Dimension(level.dimension()), box, false);
    }

    public static Runnable close(ServerLevel level, Object source, Realm realm, BoundingBox box, boolean impassable) {
        Key key = new Key(level.getServer(), level.dimension());
        Entry entry = new Entry(Objects.requireNonNull(source), new Closed(realm, box, impassable));
        CLOSED.compute(key, (at, had) -> {
            List<Entry> now = new ArrayList<>(had == null ? List.of() : had.entries());
            now.add(entry);
            return new Snapshot(now);
        });
        return () -> remove(key, one -> one == entry);
    }

    public static void forget(ServerLevel level, Object source) {
        remove(new Key(level.getServer(), level.dimension()), entry -> entry.source().equals(source));
    }

    private static void remove(Key key, java.util.function.Predicate<Entry> matches) {
        CLOSED.computeIfPresent(key, (at, had) -> {
            List<Entry> now = had.entries().stream().filter(matches.negate()).toList();
            if (now.size() == had.entries().size()) {
                return had;
            }
            return now.isEmpty() ? null : new Snapshot(now);
        });
    }

    /** What is closed in the level now; the list stays as it is, and is a new one whenever anything closes or opens. */
    public static List<Closed> in(ServerLevel level) {
        Snapshot closed = CLOSED.get(new Key(level.getServer(), level.dimension()));
        return closed == null ? List.of() : closed.closed();
    }

    public static void forgetServer(MinecraftServer server) {
        CLOSED.keySet().removeIf(key -> key.server() == server);
    }

}
