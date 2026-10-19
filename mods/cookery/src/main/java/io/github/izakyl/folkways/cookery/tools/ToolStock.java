package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.cookery.racks.KitchenRacks;
import io.github.izakyl.folkways.plugins.orders.Orders;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

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
    private final Map<UUID, List<ItemStack>> remembered = new LinkedHashMap<>();
    private final Map<UUID, WorldPos> lastBody = new LinkedHashMap<>();
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
        boolean complete = true;
        Map<UUID, Source> sources = new LinkedHashMap<>();
        Set<WorldPos> nearby = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (WorldPos at : colony.storesIn(level.dimension())) {
                if (!at.in(level) || !level.isLoaded(at.block(level))) {
                    complete = false;
                    continue;
                }
                Optional<Container> held = colony.store(level, at);
                if (held.isEmpty()) {
                    continue;
                }
                nearby.add(at);
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
            if (body.isEmpty()) {
                complete = false;
            }
            if (body.isPresent()) {
                lastBody.put(resident.id(), WorldPos.of(body.get().mob().level(), body.get().mob().blockPosition()));
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
        List<ItemStack> known = new ArrayList<>(lastCarried.values().stream().flatMap(List::stream)
            .map(KitchenTools.Tool::stack).toList());
        remembered.values().forEach(known::addAll);
        nearby.addAll(lastBody.values());
        for (WorldPos at : nearby) {
            Optional<ServerLevel> observed = at.level(server);
            if (observed.isEmpty() || !observed.get().isLoaded(at.block(observed.get()))) {
                complete = false;
                continue;
            }
            ServerLevel level = observed.get();
            WorldSpaces.world(level, at).ifPresent(center -> {
                for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(8),
                        entity -> entity.isAlive() && Goods.matches(entity.getItem(), family)
                            && known.stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, entity.getItem())))) {
                    ItemStack stack = drop.getItem();
                    UUID id = physical.computeIfAbsent(stack, ignored -> UUID.randomUUID());
                    tools.add(new KitchenTools.Tool(id, stack.copyWithCount(1), KitchenTools.Whereabouts.IN_TRANSIT));
                }
            });
        }
        return new Snapshot(new KitchenTools.Inventory(tools, Orders.commitments(colony, family), complete), Map.copyOf(sources));
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

    void remember(KitchenTools.Lease lease, ItemStack stack) {
        List<ItemStack> previous = new ArrayList<>(remembered.getOrDefault(lease.resident(), List.of()));
        previous.removeIf(held -> Goods.matches(held, lease.request().tools()));
        previous.add(stack.copyWithCount(1));
        remembered.put(lease.resident(), List.copyOf(previous));
        List<KitchenTools.Tool> carried = new ArrayList<>(lastCarried.getOrDefault(lease.resident(), List.of()));
        carried.removeIf(tool -> tool.id().equals(lease.tool()));
        carried.add(new KitchenTools.Tool(lease.tool(), stack.copyWithCount(1), KitchenTools.Whereabouts.BORROWED));
        lastCarried.put(lease.resident(), List.copyOf(carried));
        body(lease.resident()).ifPresent(body ->
            lastBody.put(lease.resident(), WorldPos.of(body.mob().level(), body.mob().blockPosition())));
    }

    Optional<Body> body(UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            var entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved()) {
                return Bodies.of(entity);
            }
        }
        return Optional.empty();
    }

    Optional<Container> store(ServerLevel level, WorldPos at) {
        return colony.store(level, at);
    }

    static long roomCount(Container into, ItemStack stack) {
        long room = 0;
        int cap = Math.min(into.getMaxStackSize(), stack.getMaxStackSize());
        for (int slot = 0; slot < into.getContainerSize(); slot++) {
            ItemStack held = into.getItem(slot);
            if (Containers.accepts(into, slot, stack) && (held.isEmpty()
                    || ItemStack.isSameItemSameComponents(held, stack) && held.getCount() < cap)) {
                room += held.isEmpty() ? cap : cap - held.getCount();
            }
        }
        return room;
    }

    static boolean room(Container into, ItemStack stack) {
        return roomCount(into, stack) > 0;
    }
}
