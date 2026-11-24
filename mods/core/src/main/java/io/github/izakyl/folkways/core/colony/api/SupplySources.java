package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

public final class SupplySources {

    public record Source(String kind, UUID id, Optional<WorldPos> at, long count, Optional<String> reason,
                         long captureTick) {
    }

    private static final List<BiFunction<Colony, ItemSpec, List<Source>>> READERS = new ArrayList<>();

    private SupplySources() {
    }

    public static void install(BiFunction<Colony, ItemSpec, List<Source>> reader) {
        READERS.add(reader);
    }

    public static List<Source> of(Colony colony, ItemSpec item) {
        return READERS.stream().flatMap(reader -> reader.apply(colony, item).stream()).toList();
    }
}
