package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

// A colony store as residents may use it: only the slots its rules leave open, and only goods they admit.
public final class Stores {

    private static final Map<UUID, Map<WorldPos, List<StoreRule>>> RULED = new ConcurrentHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private Stores() {
    }

    public static Optional<Container> at(ServerLevel level, WorldPos where) {
        return at(level, where, false);
    }

    // Orders bypass incoming goods filters while retaining slot and native container restrictions.
    public static Optional<Container> at(ServerLevel level, WorldPos where, boolean directed) {
        return WorldSpaces.resolve(level.getServer(), where)
            .filter(block -> block.level() == level)
            .flatMap(block -> Containers.at(block.level(), block.cell()))
            .flatMap(container -> kept(container, rulesAt(level, where), directed))
            .map(container -> new Watched(container, () -> InventoryFeedback.store(level, where)));
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

    public static List<StoreRule> rulesAt(Collection<WorldPos> aliases) {
        Set<StoreRule> rules = new LinkedHashSet<>();
        aliases.forEach(alias -> rules.addAll(rulesAt(alias)));
        return List.copyOf(rules);
    }

    public static List<StoreRule> rulesAt(ServerLevel level, WorldPos where) {
        BlockPos pos = WorldSpaces.storage(level, where).orElse(null);
        if (pos == null || !level.isLoaded(pos)) {
            return rulesAt(where);
        }
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            WorldPos other = WorldPos.of(level, pos.relative(ChestBlock.getConnectedDirection(state)));
            return rulesAt(List.of(where, other));
        }
        return rulesAt(where);
    }

    // The container itself, beneath whatever view of it the rules gave.
    public static Container inner(Container container) {
        return switch (container) {
            case Kept kept -> inner(kept.inner());
            case Watched watched -> inner(watched.inner());
            default -> container;
        };
    }

    public static int accessibleSlot(List<StoreRule> rules, int size, int physicalSlot) {
        int from = 0;
        int to = size;
        for (StoreRule rule : rules) {
            if (rule instanceof StoreRule.Slots slots) {
                from = Math.max(from, slots.from());
                to = Math.min(to, slots.to());
            }
        }
        return physicalSlot >= from && physicalSlot < to ? physicalSlot - from : -1;
    }

    static Optional<Container> kept(Container container, List<StoreRule> rules) {
        return kept(container, rules, false);
    }

    private static Optional<Container> kept(Container container, List<StoreRule> rules, boolean directed) {
        if (directed) {
            rules = rules.stream().filter(rule -> !(rule instanceof StoreRule.Admit)).toList();
        }
        if (rules.stream().allMatch(rule -> rule instanceof StoreRule.Reserve || rule instanceof StoreRule.Prefer)) {
            return Optional.of(container);
        }
        int from = 0;
        int to = container.getContainerSize();
        for (StoreRule rule : rules) {
            if (rule instanceof StoreRule.Slots slots) {
                from = Math.max(from, slots.from());
                to = Math.min(to, slots.to());
            }
        }
        return from >= to ? Optional.empty() : Optional.of(new Kept(container, rules, from, to));
    }

    private record Watched(Container inner, Runnable changed) implements Container {

        @Override
        public int getContainerSize() {
            return inner.getContainerSize();
        }

        @Override
        public boolean isEmpty() {
            return inner.isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return inner.getItem(slot);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            ItemStack taken = inner.removeItem(slot, count);
            changed.run();
            return taken;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            ItemStack taken = inner.removeItemNoUpdate(slot);
            changed.run();
            return taken;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inner.setItem(slot, stack);
            changed.run();
        }

        @Override
        public int getMaxStackSize() {
            return inner.getMaxStackSize();
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return inner.getMaxStackSize(stack);
        }

        @Override
        public void setChanged() {
            inner.setChanged();
            changed.run();
        }

        @Override
        public boolean stillValid(Player player) {
            return inner.stillValid(player);
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return Containers.accepts(inner, slot, stack);
        }

        @Override
        public void clearContent() {
            inner.clearContent();
            changed.run();
        }
    }

    private record Kept(Container inner, List<StoreRule> rules, int from, int to) implements Container {

        @Override
        public int getContainerSize() {
            return to - from;
        }

        @Override
        public boolean isEmpty() {
            for (int slot = from; slot < to; slot++) {
                if (!inner.getItem(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            return inner.getItem(from + slot);
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            return inner.removeItem(from + slot, count);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return inner.removeItemNoUpdate(from + slot);
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            inner.setItem(from + slot, stack);
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
            return StoreRule.admits(rules, stack) && Containers.accepts(inner, from + slot, stack);
        }

        @Override
        public void clearContent() {
            for (int slot = from; slot < to; slot++) {
                inner.setItem(slot, ItemStack.EMPTY);
            }
        }
    }
}
