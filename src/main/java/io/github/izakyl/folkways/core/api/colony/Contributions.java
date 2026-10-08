package io.github.izakyl.folkways.core.api.colony;

import io.github.izakyl.folkways.core.api.Ledger;
import io.github.izakyl.folkways.core.api.work.UrgeSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

public final class Contributions {
    public record Entry(ResourceLocation owner, Consumer<ColonyContext> setup) { }
    private record Key(String kind, ResourceLocation owner) { }
    private static final Map<Key, Entry> ENTRIES = new LinkedHashMap<>();

    private Contributions() { }

    public static void colony(ResourceLocation owner, Consumer<ColonyContext> setup) {
        register("colony", owner, setup);
    }

    public static void urges(ResourceLocation owner, UrgeSource source) {
        Objects.requireNonNull(source);
        register("urges", owner, scope -> scope.urges((who, view) -> source.urges(scope.colony(), who, view)));
    }

    private static void register(String kind, ResourceLocation owner, Consumer<ColonyContext> setup) {
        Ledger.writing(kind + " " + owner);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(setup);
        if (ENTRIES.putIfAbsent(new Key(kind, owner), new Entry(owner, setup)) != null) {
            throw new IllegalStateException(owner + " already declares " + kind);
        }
    }

    public static List<Entry> all() {
        return List.copyOf(ENTRIES.values());
    }

    public static List<ResourceLocation> ids() {
        return ENTRIES.values().stream().map(Entry::owner).distinct().toList();
    }
}
