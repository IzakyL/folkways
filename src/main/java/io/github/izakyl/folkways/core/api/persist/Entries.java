package io.github.izakyl.folkways.core.api.persist;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Entries {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-persist");

    private Entries() {
    }

    public static <T> List<T> of(Reader reader, String key, Function<Reader, Optional<T>> parse) {
        List<Reader> children = reader.children(key);
        List<T> entries = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            try {
                parse.apply(children.get(i)).ifPresent(entries::add);
            } catch (RuntimeException exception) {
                LOGGER.error("Dropping unreadable entry {} of '{}'", i, key, exception);
            }
        }
        return entries;
    }
}
