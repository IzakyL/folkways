package io.github.izakyl.folkways.core.api.terms;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

// Ground nobody sets foot on however open it looks, such as the line a train runs along. Paths go around it, and
// nobody is asked to stand in it.
public final class Keepouts {

    public interface Keepout {
        boolean forbids(Level level, int x, int y, int z);

        // Dynamic sources refresh their geometry here, then call changed() if their answers changed.
        default void refresh(Level level) {
        }

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

    private static final List<Keepout> SOURCES = new CopyOnWriteArrayList<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private Keepouts() {
    }

    public static Runnable install(Keepout source) {
        SOURCES.add(source);
        REVISION.incrementAndGet();
        return () -> {
            if (SOURCES.remove(source)) {
                REVISION.incrementAndGet();
            }
        };
    }

    public static long revision() {
        return REVISION.get();
    }

    public static void changed() {
        REVISION.incrementAndGet();
    }

    public static void refresh(Level level) {
        for (Keepout source : SOURCES) {
            source.refresh(level);
        }
    }

    public static boolean forbidden(Level level, int x, int y, int z) {
        return forbidden(level, level, x, y, z);
    }

    public static boolean forbidden(Level level, BlockGetter context, int x, int y, int z) {
        for (Keepout source : SOURCES) {
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
            .map(source -> source.blockingEscapeFrom(level, context, origin)).toList();
        return (host, x, y, z) -> remaining.stream().anyMatch(source -> source.forbids(host, x, y, z));
    }
}
