package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// A colony store as residents may use it: all its slots, and only goods its rules admit.
public final class Stores {

    private static final Map<UUID, Map<WorldPos, List<StoreRule>>> RULED = new ConcurrentHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private Stores() {
    }

    public static Optional<Container> at(ServerLevel level, WorldPos where) {
        return WorldSpaces.resolve(level.getServer(), where)
            .filter(block -> block.level() == level)
            .flatMap(block -> Containers.at(block.level(), block.cell()))
            .flatMap(container -> kept(container, rulesAt(where)));
    }

    public static void ruled(UUID colony, Map<WorldPos, List<StoreRule>> rules) {
        Map<WorldPos, List<StoreRule>> had = rules.isEmpty()
            ? RULED.remove(colony) : RULED.put(colony, Map.copyOf(rules));
        if (!rules.equals(had == null ? Map.of() : had)) {
            REVISION.incrementAndGet();
        }
    }

    // Moves whenever any store's rules change, so whoever read stores through them knows to read again.
    public static long revision() {
        return REVISION.get();
    }

    public static List<StoreRule> rulesAt(WorldPos where) {
        List<StoreRule> found = new ArrayList<>();
        for (Map<WorldPos, List<StoreRule>> colony : RULED.values()) {
            found.addAll(colony.getOrDefault(where, List.of()));
        }
        return List.copyOf(found);
    }

    // The container itself, beneath whatever view of it the rules gave.
    public static Container inner(Container container) {
        return container instanceof Kept kept ? kept.inner() : container;
    }

    static Optional<Container> kept(Container container, List<StoreRule> rules) {
        if (rules.stream().allMatch(rule -> rule instanceof StoreRule.Reserve)) {
            return Optional.of(container);
        }
        return Optional.of(new Kept(container, rules));
    }

    private record Kept(Container inner, List<StoreRule> rules) implements Container {

        @Override
        public int getContainerSize() {
            return inner.getContainerSize();
        }

        @Override
        public boolean isEmpty() {
            for (int slot = 0; slot < inner.getContainerSize(); slot++) {
                if (!inner.getItem(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return inner.getItem(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return inner.removeItem(slot, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return inner.removeItemNoUpdate(slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inner.setItem(slot, stack);
        }

        @Override
        public int getMaxStackSize() {
            return inner.getMaxStackSize();
        }

        @Override
        public void setChanged() {
            inner.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return inner.stillValid(player);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return StoreRule.admits(rules, stack) && Containers.accepts(inner, slot, stack);
        }

        @Override
        public void clearContent() {
            for (int slot = 0; slot < inner.getContainerSize(); slot++) {
                inner.setItem(slot, ItemStack.EMPTY);
            }
        }
    }
}
