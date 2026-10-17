package io.github.izakyl.folkways.core.engine;

import io.github.izakyl.folkways.FolkwaysConfig;

public final class EngineMode {

    private static volatile FolkwaysConfig.Mode running = FolkwaysConfig.Mode.DEFAULT;

    private EngineMode() {
    }

    public static FolkwaysConfig.Mode begin() {
        running = FolkwaysConfig.mode();
        return running;
    }

    public static boolean singleThread() {
        return running == FolkwaysConfig.Mode.SINGLE_THREAD;
    }
}
