package io.github.izakyl.folkways.vanilla.compost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.item.Item;

/** Optional integrations can register interchangeable planting materials without a base-mod dependency. */
public final class SeedReserves {
    private static final Map<Item, List<Item>> GROUPS = new LinkedHashMap<>();

    private SeedReserves() {
    }

    /** The first alternative is the stable configuration key and the preferred material to retain. */
    public static synchronized void register(List<Item> alternatives) {
        List<Item> group = List.copyOf(new LinkedHashSet<>(alternatives));
        if (group.isEmpty()) throw new IllegalArgumentException("empty seed reserve group");
        for (Item item : group) {
            List<Item> previous = GROUPS.get(item);
            if (previous != null && !previous.equals(group)) {
                throw new IllegalArgumentException("overlapping seed reserve groups");
            }
        }
        group.forEach(item -> GROUPS.put(item, group));
    }

    public static synchronized List<Item> alternatives(Item item) {
        Objects.requireNonNull(item);
        return GROUPS.getOrDefault(item, List.of(item));
    }

    public static Item canonical(Item item) {
        return alternatives(item).getFirst();
    }

    public static List<List<Item>> purposes(Set<Item> seeds) {
        List<List<Item>> purposes = new ArrayList<>();
        Set<Item> remaining = new LinkedHashSet<>(seeds);
        for (Item seed : seeds) {
            if (!remaining.contains(seed)) continue;
            List<Item> group = alternatives(seed).stream().filter(seeds::contains).toList();
            purposes.add(group);
            remaining.removeAll(group);
        }
        return List.copyOf(purposes);
    }
}
