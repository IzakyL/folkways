package io.github.izakyl.folkways.plugins.cookery.tools;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.cookery.racks.KitchenRacks;
import io.github.izakyl.folkways.plugins.orders.Orders;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

final class ToolStock {

    record Source(UUID id, WorldPos at, int slot, ItemStack expected, Stances stances) {
        Source {
            expected = expected.copyWithCount(1);
        }
    }

    record Destination(WorldPos at, Stances stances, int priority) {
    }

    record Snapshot(KitchenTools.Inventory inventory, Map<UUID, Source> sources) {
    }

    private final Colony colony;
    private final MinecraftServer server;
    private final KitchenTools pool;
    private final Function<KitchenTools.Lease, Optional<ItemStack>> borrowed;
    private final Map<ItemStack, UUID> physical = new WeakHashMap<>();
    private final Map<UUID, List<KitchenTools.Tool>> lastCarried = new LinkedHashMap<>();

    ToolStock(Colony colony, MinecraftServer server, KitchenTools pool,
              Function<KitchenTools.Lease, Optional<ItemStack>> borrowed) {
        this.colony = colony;
        this.server = server;
        this.pool = pool;
        this.borrowed = borrowed;
    }

    Optional<ItemSpec> allowed(ItemSpec family) {
        List<ItemSpec> choices = Goods.members(family).stream().filter(item -> KitRules.allows(colony, item))
            .map(item -> Goods.specOf(item.getDefaultInstance())).toList();
        return choices.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(choices));
    }

    Snapshot read(ServerLevel where, ItemSpec family) {
        List<KitchenTools.Tool> tools = new ArrayList<>();
        Map<UUID, Source> sources = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (WorldPos at : colony.storesIn(level.dimension())) {
                if (!at.in(level) || !level.isLoaded(at.block(level))) {
                    continue;
                }
                Optional<Container> held = colony.store(level, at);
                if (held.isEmpty()) {
                    continue;
                }
                Container store = held.get();
                Optional<Stances> approach = Stances.of(level, Reach.workableCells(level, at.block(level)));
                for (int slot = 0; slot < store.getContainerSize(); slot++) {
                    ItemStack stack = store.getItem(slot);
                    if (!Goods.matches(stack, family)) {
                        continue;
                    }
                    UUID id = physical.computeIfAbsent(stack, ignored -> UUID.randomUUID());
                    boolean reachable = level == where && approach.isPresent();
                    tools.add(new KitchenTools.Tool(id, stack.copyWithCount(1), reachable
                        ? KitchenTools.Whereabouts.AVAILABLE : KitchenTools.Whereabouts.UNREACHABLE));
                    if (reachable) {
                        sources.put(id, new Source(id, at, slot, stack, approach.orElseThrow()));
                    }
                }
            }
        }
        for (var resident : colony.residents()) {
            Optional<Body> body = body(resident.id());
            if (body.isPresent()) {
                List<KitchenTools.Tool> carried = new ArrayList<>();
                List<KitchenTools.Lease> loans = pool.leases(colony.id()).stream()
                    .filter(lease -> lease.resident().equals(resident.id())).toList();
                List<UUID> seenLoans = new ArrayList<>();
                for (int slot = 0; slot < body.get().pack().getContainerSize(); slot++) {
                    ItemStack stack = body.get().pack().getItem(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    Optional<KitchenTools.Lease> loan = loans.stream()
                        .filter(lease -> !seenLoans.contains(lease.id()) && borrowed.apply(lease)
                            .filter(expected -> ItemStack.isSameItemSameComponents(stack, expected)).isPresent())
                        .findFirst();
                    UUID id = loan.map(KitchenTools.Lease::tool)
                        .orElseGet(() -> physical.computeIfAbsent(stack, ignored -> UUID.randomUUID()));
                    loan.ifPresent(lease -> seenLoans.add(lease.id()));
                    physical.put(stack, id);
                    carried.add(new KitchenTools.Tool(id, stack.copyWithCount(1), loan.isPresent()
                        && loan.get().phase() == KitchenTools.Phase.BORROWED
                        ? KitchenTools.Whereabouts.BORROWED : KitchenTools.Whereabouts.IN_TRANSIT));
                }
                lastCarried.put(resident.id(), List.copyOf(carried));
            }
            lastCarried.getOrDefault(resident.id(), List.of()).stream()
                .filter(tool -> Goods.matches(tool.stack(), family)).forEach(tools::add);
        }
        lastCarried.keySet().removeIf(id -> colony.residents().stream().noneMatch(resident -> resident.id().equals(id)));
        return new Snapshot(new KitchenTools.Inventory(tools, Orders.commitments(colony, family)), Map.copyOf(sources));
    }

    List<Destination> destinations(ServerLevel level, ItemStack stack) {
        List<Destination> targets = new ArrayList<>();
        for (WorldPos at : colony.storesIn(level.dimension())) {
            if (!at.in(level) || !level.isLoaded(at.block(level))) {
                continue;
            }
            Optional<Container> store = colony.store(level, at);
            if (store.isEmpty() || !room(store.get(), stack)) {
                continue;
            }
            Stances.of(level, Reach.workableCells(level, at.block(level))).ifPresent(stances ->
                targets.add(new Destination(at, stances, KitchenRacks.matches(level.getBlockState(at.block(level))) ? 100 : 0)));
        }
        targets.sort(java.util.Comparator.comparingInt(Destination::priority).reversed());
        return List.copyOf(targets);
    }

    Optional<Body> body(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            var entity = level.getEntity(id);
            if (entity != null) {
                return Bodies.of(entity);
            }
        }
        return Optional.empty();
    }

    Optional<Container> store(ServerLevel level, WorldPos at) {
        return colony.store(level, at);
    }

    static boolean room(Container into, ItemStack stack) {
        int cap = Math.min(into.getMaxStackSize(), stack.getMaxStackSize());
        for (int slot = 0; slot < into.getContainerSize(); slot++) {
            ItemStack held = into.getItem(slot);
            if (Containers.accepts(into, slot, stack) && (held.isEmpty()
                    || ItemStack.isSameItemSameComponents(held, stack) && held.getCount() < cap)) {
                return true;
            }
        }
        return false;
    }
}
