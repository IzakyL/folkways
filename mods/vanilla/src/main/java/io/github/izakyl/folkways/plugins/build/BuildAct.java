package io.github.izakyl.folkways.plugins.build;

import java.util.Optional;
import java.util.UUID;

final class BuildAct {

    private static final String CANCEL = "cancel:";
    private static final String STOP = "stop:";

    private BuildAct() {
    }

    static String cancelling(UUID order) {
        return CANCEL + order;
    }

    static Optional<UUID> cancelled(String key) {
        return idAfter(CANCEL, key);
    }

    static String stopping(UUID growing) {
        return STOP + growing;
    }

    static Optional<UUID> stopped(String key) {
        return idAfter(STOP, key);
    }

    private static Optional<UUID> idAfter(String prefix, String key) {
        if (key == null || !key.startsWith(prefix)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(key.substring(prefix.length())));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }
}
