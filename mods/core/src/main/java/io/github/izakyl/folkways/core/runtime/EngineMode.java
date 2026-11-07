package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.TickBudget;

public final class EngineMode {

    private static volatile FolkwaysConfig.Mode running = FolkwaysConfig.Mode.DEFAULT;

    private EngineMode() {
    }

    public static FolkwaysConfig.Mode begin() {
        running = FolkwaysConfig.mode();
        ColonyWays.singleThread(singleThread());
        TickBudget.singleThread(singleThread());
        return running;
    }

    public static boolean singleThread() {
        return running == FolkwaysConfig.Mode.SINGLE_THREAD;
    }
}
