package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.persist.Entries;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

public final class BlueprintBook {

    private static final String TAG_BLUEPRINTS = "blueprints";
    private static final String TAG_BUILD_ORDERS = "buildOrders";

    static int maxCaptureBlocks() {
        return FolkwaysConfig.maxBuildCells();
    }

    static final int MAX_FILED_BLUEPRINTS = 64;

    static final int MAX_BUILD_ORDERS = 64;

    private final Map<UUID, Blueprint> blueprints = new LinkedHashMap<>();
    private final Map<UUID, BlueprintBuildOrder> orders = new LinkedHashMap<>();
    private Runnable dirty = () -> {
    };

    void reportChangesTo(Runnable listener) {
        this.dirty = listener;
    }

    void add(Blueprint blueprint) {
        file(blueprint);
    }

    private void file(Blueprint blueprint) {
        blueprints.put(blueprint.id(), blueprint);
        forgetOldestUnused();
        dirty.run();
    }

    private void forgetOldestUnused() {
        if (blueprints.size() <= MAX_FILED_BLUEPRINTS) {
            return;
        }
        Set<UUID> building = new HashSet<>();
        for (BlueprintBuildOrder order : orders.values()) {
            building.add(order.blueprintId());
        }
        List<Blueprint> droppable = blueprints.values().stream()
            .filter(blueprint -> !building.contains(blueprint.id()))
            .sorted(Comparator.comparingLong(Blueprint::createdGameTime))
            .toList();
        for (Blueprint oldest : droppable) {
            if (blueprints.size() <= MAX_FILED_BLUEPRINTS) {
                return;
            }
            blueprints.remove(oldest.id());
        }
    }

    Optional<BlueprintBuildOrder> place(ServerLevel level, UUID blueprintId, String name, BlockPos anchor,
            Optional<UUID> playerId, String playerName, long createdGameTime) {
        Blueprint chosen = blueprints.get(blueprintId);
        if (chosen == null || orders.size() >= MAX_BUILD_ORDERS) {
            return Optional.empty();
        }
        return Optional.of(record(BlueprintBuildOrder.create(chosen.id(), name,
            WorldPos.of(level, anchor), playerId, playerName, createdGameTime)));
    }

    private BlueprintBuildOrder record(BlueprintBuildOrder order) {
        orders.put(order.id(), order);
        dirty.run();
        return order;
    }

    Optional<Blueprint> blueprint(UUID id) {
        return Optional.ofNullable(blueprints.get(id));
    }

    List<BlueprintBuildOrder> allBuildOrders() {
        return List.copyOf(orders.values());
    }

    void rename(UUID orderId, String name) {
        orders.computeIfPresent(orderId, (id, order) -> order.named(name));
        dirty.run();
    }

    boolean removeBuildOrder(UUID orderId) {
        boolean removed = orders.remove(orderId) != null;
        if (removed) {
            dirty.run();
        }
        return removed;
    }

    CompoundTag save(HolderLookup.Provider provider) {
        return Writer.of()
            .children(TAG_BLUEPRINTS, blueprints.values(), blueprint -> blueprint.save(provider))
            .children(TAG_BUILD_ORDERS, orders.values(), order -> order.save(provider))
            .tag();
    }

    static BlueprintBook load(Reader reader, HolderLookup.Provider provider) {
        BlueprintBook book = new BlueprintBook();
        for (Blueprint blueprint
            : Entries.of(reader, TAG_BLUEPRINTS, entry -> Blueprint.load(entry, provider))) {
            book.blueprints.put(blueprint.id(), blueprint);
        }
        for (BlueprintBuildOrder order
            : Entries.of(reader, TAG_BUILD_ORDERS, entry -> BlueprintBuildOrder.load(entry, provider))) {
            if (book.blueprints.containsKey(order.blueprintId())) {
                book.orders.put(order.id(), order);
            }
        }
        return book;
    }

    void loadInto(Reader reader, HolderLookup.Provider provider) {
        BlueprintBook read = load(reader, provider);
        blueprints.putAll(read.blueprints);
        orders.putAll(read.orders);
    }
}
