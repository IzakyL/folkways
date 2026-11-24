package io.github.izakyl.folkways.core.world.api;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

// Ground nobody sets foot on however open it looks, such as the line a train runs along. Paths go around it, and
// nobody is asked to stand in it.
public final class Keepouts {

    public interface Keepout {
        boolean forbids(Level level, int x, int y, int z);

        default boolean forbids(Level level, BlockGetter context, int x, int y, int z) {
            return forbids(level, x, y, z);
        }

        default Keepout blockingEscapeFrom(Level level, BlockGetter context, BlockPos origin) {
            if (forbids(level, context, origin.getX(), origin.getY(), origin.getZ())) {
                return (host, x, y, z) -> false;
            }
            return (host, x, y, z) -> forbids(host, context, x, y, z);
        }
    }

    private record Entry(Level level, Object source, Keepout value) {
    }

    private static final List<Entry> SOURCES = new CopyOnWriteArrayList<>();

    private Keepouts() {
    }

    public static Runnable install(Keepout value) {
        return install(new Object(), value);
    }

    public static Runnable install(Object source, Keepout value) {
        return install(null, source, value);
    }

    public static Runnable install(Level level, Object source, Keepout value) {
        Entry entry = new Entry(level, Objects.requireNonNull(source), Objects.requireNonNull(value));
        SOURCES.add(entry);
        return () -> SOURCES.removeIf(one -> one == entry);
    }

    public static void forget(Object source) {
        SOURCES.removeIf(entry -> entry.source().equals(source));
    }

    public static boolean forbidden(Level level, int x, int y, int z) {
        return forbidden(level, level, x, y, z);
    }

    public static boolean forbidden(Level level, BlockGetter context, int x, int y, int z) {
        for (Entry entry : SOURCES) {
            if (entry.level() != null && entry.level() != level) {
                continue;
            }
            Keepout source = entry.value();
            if (source.forbids(level, context, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public static boolean forbidden(Level level, BlockGetter context, BlockPos cell) {
        return forbidden(level, context, cell.getX(), cell.getY(), cell.getZ());
    }

    public static boolean forbidden(Level level, BlockPos cell) {
        return forbidden(level, cell.getX(), cell.getY(), cell.getZ());
    }

    // A misplaced body may escape the sources covering its origin, but every other
    // source remains a wall even when the forbidden areas touch one another.
    public static Keepout blockingEscapeFrom(Level level, BlockGetter context, BlockPos origin) {
        List<Keepout> remaining = SOURCES.stream()
            .filter(entry -> entry.level() == null || entry.level() == level).map(Entry::value)
            .map(source -> source.blockingEscapeFrom(level, context, origin)).toList();
        return (host, x, y, z) -> remaining.stream().anyMatch(source -> source.forbids(host, x, y, z));
    }
}
