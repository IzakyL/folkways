package io.github.izakyl.folkways.core.api.ground;

import java.util.Collection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class Grounds {

    public interface Watch extends AutoCloseable {

        Ground now(Collection<Mob> walkers);

        // Survey one body's dimensions and locomotion, including a player who may need a way out of a work site.
        Ground now(LivingEntity walker);

        // Changes whenever watched terrain or loaded-space availability changes. An earlier access proof
        // must not be retained after the revision changes, including while its search is unfinished.
        default long revision() {
            return 0;
        }

        // Force one live environment check for a batch of body views, especially before committing work.
        // Ordinary now()/revision() calls reuse that check for the rest of this server tick.
        default long refresh() {
            return revision();
        }

        default Stats stats() {
            return new Stats(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        @Override
        void close();
    }

    // Cumulative counters and current bounded-cache sizes, suitable for profiling without debug chat.
    public record Stats(long accessRequests, long accessHits, long sectionBuilds, long work,
                        long invalidations, long environmentScans, int accessEntries, int geometryEntries,
                        int cachedCells) {
    }

    public interface Source {

        Watch watch(ServerLevel level, BoundingBox box);
    }

    private static final Source NONE = (level, box) -> {
        throw new IllegalStateException("nothing installed a way to watch the ground");
    };

    private static Source held = NONE;

    private Grounds() {
    }

    public static void install(Source source) {
        held = source;
    }

    public static Watch watch(ServerLevel level, BoundingBox box) {
        return held.watch(level, box);
    }
}
