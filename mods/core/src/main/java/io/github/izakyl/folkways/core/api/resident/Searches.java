package io.github.izakyl.folkways.core.api.resident;

import net.minecraft.world.level.Level;

// A gait's pathfinding tells the core how much searching it does, so the load it reports counts every body.
public final class Searches {

    public interface Meter {

        void starting(Level level);

        void expanded();
    }

    private static final Meter NONE = new Meter() {
        @Override
        public void starting(Level level) {
        }

        @Override
        public void expanded() {
        }
    };

    private static volatile Meter meter = NONE;

    private Searches() {
    }

    public static void install(Meter reading) {
        meter = reading;
    }

    public static void starting(Level level) {
        meter.starting(level);
    }

    public static void expanded() {
        meter.expanded();
    }
}
