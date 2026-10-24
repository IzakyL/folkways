package io.github.izakyl.folkways.core.engine.travel.graph;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Recent places the ways serve, counting overlapping storeys separately. */
final class TravelExtent {

    static final long WINDOW_TICKS = 6_000L;

    private static final int STOREY_HEIGHT = 8;

    private Map<Integer, Footprint> current = new LinkedHashMap<>();
    private Map<Integer, Footprint> previous = Map.of();
    private LongOpenHashSet wanted = new LongOpenHashSet();
    private int wantedBefore;
    private long windowFrom = -1L;

    void roll(long now) {
        if (windowFrom < 0L) {
            windowFrom = now;
        } else if (now - windowFrom >= WINDOW_TICKS) {
            boolean skippedWindow = now - windowFrom >= WINDOW_TICKS * 2;
            previous = skippedWindow ? Map.of() : current;
            wantedBefore = skippedWindow ? 0 : wanted.size();
            current = new LinkedHashMap<>();
            wanted = new LongOpenHashSet();
            windowFrom = now;
        }
    }

    void covers(BlockPos at) {
        current.merge(Math.floorDiv(at.getY(), STOREY_HEIGHT), Footprint.at(at), Footprint::with);
    }

    void wants(BlockPos at) {
        wanted.add(at.asLong());
    }

    int wanted() {
        return Math.max(wanted.size(), wantedBefore);
    }

    int layers() {
        return footprints().size();
    }

    long ground() {
        return footprints().values().stream().mapToLong(Footprint::area).sum();
    }

    private Map<Integer, Footprint> footprints() {
        Map<Integer, Footprint> all = new LinkedHashMap<>(previous);
        current.forEach((layer, footprint) -> all.merge(layer, footprint, Footprint::with));
        return all;
    }

    private record Footprint(int west, int east, int north, int south) {
        static Footprint at(BlockPos at) {
            return new Footprint(at.getX(), at.getX(), at.getZ(), at.getZ());
        }

        Footprint with(Footprint other) {
            return new Footprint(Math.min(west, other.west), Math.max(east, other.east),
                Math.min(north, other.north), Math.max(south, other.south));
        }

        long area() {
            return ((long) east - west + 1) * ((long) south - north + 1);
        }
    }
}
