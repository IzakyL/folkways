package io.github.izakyl.folkways.core.api.library;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

// What a server reads from outside the mod: each kind of file sits on a shelf, filled by data packs and by a folder.
public final class Shelves {

    // The folder's files load under this namespace, so a data pack's own never clash with them.
    public static final String NAMESPACE = "local";

    private static final String ROOT = "folkways";

    private static final Map<String, Shelf> shelves = new LinkedHashMap<>();
    private static final Map<String, Stock> stocks = new ConcurrentHashMap<>();

    private Shelves() {
    }

    // A plugin puts up its shelves as it installs, before any pack is gathered.
    public static synchronized void register(Shelf... added) {
        for (Shelf shelf : added) {
            if (shelves.putIfAbsent(shelf.name(), shelf) != null) {
                throw new IllegalStateException("the shelf '" + shelf.name() + "' is registered twice");
            }
        }
    }

    public static synchronized List<Shelf> all() {
        return List.copyOf(shelves.values());
    }

    public static Path root() {
        return FMLPaths.GAMEDIR.get().resolve(ROOT);
    }

    public static Stock stock(Shelf shelf) {
        return stocks.getOrDefault(shelf.name(), Stock.EMPTY);
    }

    public static void forgetServer() {
        stocks.clear();
    }

    public record Shelf(String name) {

        // Where a data pack keeps this shelf's files, under data/<namespace>/.
        public String folder() {
            return ROOT + "/" + name;
        }

        // Where the server's own folder keeps them.
        public Path path() {
            return root().resolve(name);
        }

        public void report(Stock stock) {
            stocks.put(name, stock);
        }
    }

    // What one reload put on a shelf: each entry and the pack it came from, and every file turned away and why.
    public record Stock(Map<ResourceLocation, String> entries, List<String> rejected) {

        public static final Stock EMPTY = new Stock(Map.of(), List.of());

        public Stock {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            rejected = List.copyOf(rejected);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private final Map<ResourceLocation, String> entries = new LinkedHashMap<>();
            private final List<String> rejected = new ArrayList<>();

            public Builder entry(ResourceLocation id, String pack) {
                entries.put(id, pack);
                return this;
            }

            public Builder rejected(String why) {
                rejected.add(why);
                return this;
            }

            public Builder rejected(List<String> why) {
                rejected.addAll(why);
                return this;
            }

            public Stock build() {
                return new Stock(entries, rejected);
            }
        }
    }
}
