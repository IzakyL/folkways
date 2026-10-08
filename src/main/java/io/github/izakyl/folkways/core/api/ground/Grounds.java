package io.github.izakyl.folkways.core.api.ground;

import java.util.Collection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class Grounds {

    public interface Watch extends AutoCloseable {

        Ground now(Collection<Mob> walkers);

        @Override
        void close();
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
