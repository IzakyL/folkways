package io.github.izakyl.folkways.cookery.tools;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.plugins.orders.Orders;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

final class ToolStock {

    record Source(UUID id, WorldPos at, int slot, ItemStack expected, Stances stances,
                  InventoryFeedback.StoreSlot physical, long incarnation, long slotIncarnation,
                  InventorySnapshot.Version version) {
        Source {
            expected = expected.copyWithCount(1);
        }
    }

    record Destination(WorldPos at, Stances stances, int priority, long room, InventorySnapshot.Version version) {
    }

    record Snapshot(KitchenTools.Inventory inventory, Map<UUID, Source> sources) {
    }

    record StoreToken(Stash stash, int slot, long incarnation, UUID epoch, long slotIncarnation) {
    }

    record ResidentToken(UUID resident, int slot, long incarnation, UUID epoch) {
    }

    private final Colony colony;
    private final MinecraftServer server;
    private final KitchenTools pool;
    private final Function<KitchenTools.Lease, Optional<ItemStack>> borrowed;
    private final Map<UUID, Integer> carried = new LinkedHashMap<>();
    private final Map<UUID, EquipmentSlot> worn = new LinkedHashMap<>();
    private final Map<UUID, Long> incarnations = new LinkedHashMap<>();
    private final Map<UUID, Long> residents = new LinkedHashMap<>();
    private final Map<UUID, ItemStack> live = new LinkedHashMap<>();
    private final Map<UUID, WorldPos> lastBody = new LinkedHashMap<>();
    private final Map<UUID, List<KitchenTools.Tool>> drops = new LinkedHashMap<>();

    ToolStock(Colony colony, MinecraftServer server, KitchenTools pool,
              Function<KitchenTools.Lease, Optional<ItemStack>> borrowed) {
        this.colony = colony;
        this.server = server;
        this.pool = pool;
        this.borrowed = borrowed;
    }

    Optional<ItemSpec> allowed(Optional<Vocation> vocation, ItemSpec family) {
        List<ItemSpec> choices = new ArrayList<>();
        for (ItemSpec alternative : family.anyOf().isEmpty() ? List.of(family) : family.anyOf()) {
            for (Item item : Goods.members(alternative)) {
                if (KitRules.allows(colony, vocation, item)) {
                    choices.add(
                        new ItemSpec(
                        Optional.of(BuiltInRegistries.ITEM.getKey(item)),
                        Optional.empty(),
                        Set.of(),
                        alternative.components()));
                }
            }
        }
        return choices.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(choices));
    }

    Snapshot read(ServerLevel where, ItemSpec family) {
        InventorySnapshot observed = colony.inventory().snapshot();
        if (!observed.coverage().complete()) {
            colony.inventory().ensureObserved();
        }
        List<KitchenTools.Tool> tools = new ArrayList<>();
        Map<UUID, Source> sources = new LinkedHashMap<>();
        for (InventorySnapshot.Store store : observed.stores().values()) {
            Optional<Stances> approach = approach(store);
            for (SlotSnapshot slot : store.slots()) {
                ItemStack stack = slot.stackCopy();
                if (slot.accessibleSlot().isEmpty() || !Goods.matches(stack, family)) {
                    continue;
                }
                UUID id = token(new StoreToken(store.stash(), slot.physicalSlot(), store.incarnation(),
                    store.version().epoch(), slot.incarnation()));
                boolean reachable = store.dimension().equals(where.dimension()) && store.state()
                    == InventorySnapshot.State.OBSERVED && StoreRule.takes(store.rules()) && approach.isPresent();
                tools.add(new KitchenTools.Tool(id, stack.copyWithCount(1), reachable
                    ? KitchenTools.Whereabouts.AVAILABLE : KitchenTools.Whereabouts.UNREACHABLE));
                if (reachable) {
                    sources.put(id, new Source(id, store.stash().pos(), slot.accessibleSlot().getAsInt(), stack,
                        approach.orElseThrow(), new InventoryFeedback.StoreSlot(store.stash(), slot.physicalSlot()),
                        store.incarnation(), slot.incarnation(), store.version()));
                }
            }
        }
        Set<UUID> accounted = new LinkedHashSet<>();
        List<KitchenTools.Lease> loans = pool.leases(colony.id());
        for (InventorySnapshot.Resident resident : observed.residents().values()) {
            if (resident.at() != null) {
                lastBody.put(resident.resident(), resident.at());
            }
            for (SlotSnapshot slot : resident.pack()) {
                ItemStack stack = slot.stackCopy();
                if (stack.isEmpty()) {
                    continue;
                }
                Optional<KitchenTools.Lease> loan = loans.stream().filter(lease ->
                    lease.resident().equals(resident.resident()) && !accounted.contains(lease.id())
                        && carried.getOrDefault(lease.id(), -1) == slot.physicalSlot()
                        && matchesSlot(slot, lease)
                        && borrowed.apply(lease).filter(expected -> LoanClothing.same(stack, expected)).isPresent())
                    .findFirst();
                UUID id = loan.map(KitchenTools.Lease::tool).orElseGet(() -> token(new ResidentToken(
                    resident.resident(), slot.physicalSlot(), slot.incarnation(), resident.version().epoch())));
                loan.ifPresent(lease -> accounted.add(lease.id()));
                if (Goods.matches(stack, family)) {
                    tools.add(new KitchenTools.Tool(id, stack.copyWithCount(1), loan.isPresent()
                        && loan.get().phase() == KitchenTools.Phase.BORROWED
                        ? KitchenTools.Whereabouts.BORROWED : KitchenTools.Whereabouts.IN_TRANSIT));
                }
            }
            for (KitchenTools.Lease lease : loans) {
                ItemStack stack = held(observed, lease);
                if (!stack.isEmpty()) {
                    accounted.add(lease.id());
                    if (worn.containsKey(lease.id()) && Goods.matches(stack, family)) {
                        tools.add(new KitchenTools.Tool(lease.tool(), stack.copyWithCount(1),
                            lease.phase() == KitchenTools.Phase.BORROWED
                            ? KitchenTools.Whereabouts.BORROWED : KitchenTools.Whereabouts.IN_TRANSIT));
                    }
                }
            }
        }
        observeDrops(observed, loans, accounted, false);
        drops.values().stream().flatMap(List::stream).filter(tool -> Goods.matches(tool.stack(), family))
            .forEach(tools::add);
        return new Snapshot(new KitchenTools.Inventory(tools, Orders.commitments(colony, family),
            observed.coverage().complete()), Map.copyOf(sources));
    }

    private static UUID token(Object location) {
        return UUID.nameUUIDFromBytes(location.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<Stances> approach(InventorySnapshot.Store store) {
        return store.approach().state() == InventorySnapshot.State.OBSERVED
            ? Stances.of(store.approach().stands().stream().map(Stand::cell).toList()) : Optional.empty();
    }

    private void observeDrops(InventorySnapshot observed, List<KitchenTools.Lease> loans, Set<UUID> accounted,
                              boolean execution) {
        accounted.forEach(drops::remove);
        drops.replaceAll((loan, tools) -> tools.stream().filter(tool -> {
            for (ServerLevel level : server.getAllLevels()) {
                var entity = level.getEntity(tool.id());
                if (entity instanceof ItemEntity drop && drop.isAlive()) {
                    return true;
                }
            }
            return false;
        }).toList());
        List<KitchenTools.Lease> missing = loans.stream().filter(lease -> !accounted.contains(lease.id())
            && borrowed.apply(lease).isPresent() && !drops.containsKey(lease.id())
            && (execution || !reloaded(lease))
            && (execution || observed.residents().containsKey(lease.resident())
                && observed.residents().get(lease.resident()).state() == InventorySnapshot.State.OBSERVED)).toList();
        if (missing.isEmpty()) {
            return;
        }
        Map<ServerLevel, List<AABB>> regions = new LinkedHashMap<>();
        Set<WorldPos> nearby = new LinkedHashSet<>(lastBody.values());
        observed.stores().keySet().forEach(stash -> nearby.add(stash.pos()));
        for (WorldPos at : nearby) {
            at.level(server).filter(level -> level.isLoaded(at.block(level))).ifPresent(level ->
                WorldSpaces.world(level, at).ifPresent(center -> {
                    AABB box = new AABB(center, center).inflate(8);
                    List<AABB> boxes = regions.computeIfAbsent(level, ignored -> new ArrayList<>());
                    for (int index = 0; index < boxes.size();) {
                        if (box.intersects(boxes.get(index))) {
                            box = box.minmax(boxes.remove(index));
                            index = 0;
                        } else {
                            index++;
                        }
                    }
                    boxes.add(box);
                }));
        }
        Map<UUID, ItemStack> found = new LinkedHashMap<>();
        regions.forEach((level, boxes) -> boxes.forEach(box -> {
            for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, box, ItemEntity::isAlive)) {
                found.putIfAbsent(drop.getUUID(), drop.getItem().copy());
            }
        }));
        for (KitchenTools.Lease lease : missing) {
            ItemStack expected = borrowed.apply(lease).orElse(ItemStack.EMPTY);
            var match = found.entrySet().stream().filter(entry -> LoanClothing.same(entry.getValue(), expected))
                .findFirst();
            drops.put(lease.id(), match.map(entry -> List.of(new KitchenTools.Tool(entry.getKey(),
                entry.getValue().copyWithCount(1), KitchenTools.Whereabouts.IN_TRANSIT))).orElse(List.of()));
            match.ifPresent(entry -> found.remove(entry.getKey()));
        }
    }

    List<Destination> destinations(ServerLevel level, ItemStack stack) {
        List<Destination> targets = new ArrayList<>();
        for (InventorySnapshot.Store store : colony.inventory().snapshot().stores().values()) {
            if (!store.dimension().equals(level.dimension()) || store.state() != InventorySnapshot.State.OBSERVED
                    || !StoreRule.stores(store.rules())) {
                continue;
            }
            long room = roomCount(store, stack);
            if (room > 0) {
                int priority = store.block().filter(id -> id.equals(
                    BuiltInRegistries.BLOCK.getKey(ModBlocks.KITCHENWARE_RACKS.get()))).isPresent() ? 100 : 0;
                approach(store).ifPresent(stances -> targets.add(new Destination(store.stash().pos(), stances,
                    priority, room, store.version())));
            }
        }
        targets.sort(java.util.Comparator.comparingInt(Destination::priority).reversed());
        return List.copyOf(targets);
    }

    private static long roomCount(InventorySnapshot.Store store, ItemStack stack) {
        if (store.refused().contains(stack.getItem()) || !StoreRule.admits(store.rules(), stack)) {
            return 0;
        }
        long room = 0;
        for (SlotSnapshot slot : store.slots()) {
            ItemStack held = slot.stackCopy();
            int cap = Math.min(slot.stackLimit(), stack.getMaxStackSize());
            if (slot.accessibleSlot().isPresent() && (held.isEmpty()
                    || ItemStack.isSameItemSameComponents(held, stack))) {
                room += Math.max(0, cap - held.getCount());
            }
        }
        return room;
    }

    ItemStack equipment(UUID resident, EquipmentSlot slot) {
        var observed = colony.inventory().snapshot().residents().get(resident);
        SlotSnapshot held = observed == null ? null : observed.equipment().get(slot);
        return held == null ? ItemStack.EMPTY : held.stackCopy();
    }

    boolean observed(UUID resident) {
        var entry = colony.inventory().snapshot().residents().get(resident);
        if (entry == null || entry.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObservedResident(resident);
            return false;
        }
        return true;
    }

    boolean borrowedSlot(UUID resident, EquipmentSlot slot) {
        InventorySnapshot observed = colony.inventory().snapshot();
        return pool.leases(colony.id()).stream().anyMatch(lease -> lease.resident().equals(resident)
            && slot == worn.get(lease.id()) && !held(observed, lease).isEmpty());
    }

    ItemStack held(InventorySnapshot observed, KitchenTools.Lease lease) {
        InventorySnapshot.Resident resident = observed.residents().get(lease.resident());
        if (resident == null) {
            return ItemStack.EMPTY;
        }
        SlotSnapshot slot = worn.containsKey(lease.id()) ? resident.equipment().get(worn.get(lease.id()))
            : resident.pack().stream().filter(held -> held.physicalSlot() == carried.getOrDefault(lease.id(), -1))
                .findFirst().orElse(null);
        ItemStack stack = slot == null || !matchesSlot(slot, lease) ? ItemStack.EMPTY : slot.stackCopy();
        return borrowed.apply(lease).filter(expected -> LoanClothing.same(stack, expected)).isPresent()
            ? stack : ItemStack.EMPTY;
    }

    private boolean matchesSlot(SlotSnapshot slot, KitchenTools.Lease lease) {
        return !incarnations.containsKey(lease.id()) || incarnations.get(lease.id()) == slot.incarnation();
    }

    void remember(KitchenTools.Lease lease, ItemStack stack, Container pack) {
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            if (pack.getItem(slot) == stack) {
                long identity = colony.inventoryFeedback().changedResidentSlot(lease.resident(), slot, stack);
                if (identity != 0) {
                    incarnations.put(lease.id(), identity);
                }
                carried.put(lease.id(), slot);
                worn.remove(lease.id());
                live.put(lease.id(), stack);
                rememberResident(lease);
                break;
            }
        }
        body(lease.resident()).ifPresent(body ->
            lastBody.put(lease.resident(), WorldPos.of(body.mob().level(), body.mob().blockPosition())));
        drops.remove(lease.id());
    }

    void equipped(KitchenTools.Lease lease, EquipmentSlot slot, ItemStack stack) {
        long identity = colony.inventoryFeedback().changedEquipment(lease.resident(), slot, stack);
        if (identity != 0) {
            incarnations.put(lease.id(), identity);
        }
        worn.put(lease.id(), slot);
        carried.remove(lease.id());
        live.put(lease.id(), stack);
        rememberResident(lease);
        drops.remove(lease.id());
    }

    private void rememberResident(KitchenTools.Lease lease) {
        var resident = colony.inventory().snapshot().residents().get(lease.resident());
        if (resident != null && resident.incarnation() != 0) {
            residents.put(lease.id(), resident.incarnation());
        }
    }

    void restored(KitchenTools.Lease lease, int slot, Optional<EquipmentSlot> equipment, long incarnation,
                  long residentIncarnation) {
        if (incarnation != 0) {
            incarnations.put(lease.id(), incarnation);
        }
        if (residentIncarnation != 0) {
            residents.put(lease.id(), residentIncarnation);
        }
        if (equipment.isPresent()) {
            worn.put(lease.id(), equipment.get());
        } else if (slot >= 0) {
            carried.put(lease.id(), slot);
        }
    }

    int carriedSlot(Container pack, KitchenTools.Lease lease, ItemStack expected) {
        int slot = carried.getOrDefault(lease.id(), -1);
        if (slot < 0 || slot >= pack.getContainerSize()) {
            return -1;
        }
        ItemStack stack = pack.getItem(slot);
        if (reloaded(lease) && observed(lease.resident()) && LoanClothing.same(stack, expected)) {
            remember(lease, stack, pack);
        }
        if (!owns(lease, stack) || !LoanClothing.same(stack, expected)) {
            InventorySnapshot snapshot = colony.inventory().snapshot();
            var resident = snapshot.residents().get(lease.resident());
            if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED
                || !held(snapshot, lease).isEmpty()) {
                staleResident(lease.resident());
            }
            return -1;
        }
        live.put(lease.id(), stack);
        return slot;
    }

    boolean reloaded(KitchenTools.Lease lease) {
        var resident = colony.inventory().snapshot().residents().get(lease.resident());
        return resident != null && residents.containsKey(lease.id()) && resident.incarnation() != 0
            && residents.get(lease.id()) != resident.incarnation();
    }

    boolean waitingForResident(KitchenTools.Lease lease) {
        return !observed(lease.resident());
    }

    boolean owns(KitchenTools.Lease lease, ItemStack stack) {
        InventorySnapshot observed = colony.inventory().snapshot();
        var resident = observed.residents().get(lease.resident());
        return (!live.containsKey(lease.id()) || live.get(lease.id()) == stack)
            && (resident == null || resident.state() != InventorySnapshot.State.OBSERVED
                || !held(observed, lease).isEmpty());
    }

    long incarnation(KitchenTools.Lease lease) {
        return incarnations.getOrDefault(lease.id(), 0L);
    }

    long residentIncarnation(KitchenTools.Lease lease) {
        return residents.getOrDefault(lease.id(), 0L);
    }

    int carriedSlot(KitchenTools.Lease lease) {
        return carried.getOrDefault(lease.id(), -1);
    }

    Optional<EquipmentSlot> equipmentSlot(KitchenTools.Lease lease) {
        return Optional.ofNullable(worn.get(lease.id()));
    }

    void unaccounted(KitchenTools.Lease lease) {
        if (drops.getOrDefault(lease.id(), List.of()).isEmpty()) {
            drops.remove(lease.id());
        }
        observeDrops(colony.inventory().snapshot(), List.of(lease), Set.of(), true);
    }

    void returned(KitchenTools.Lease lease) {
        drops.remove(lease.id());
    }

    void forget(KitchenTools.Lease lease) {
        carried.remove(lease.id());
        worn.remove(lease.id());
        live.remove(lease.id());
        incarnations.remove(lease.id());
        residents.remove(lease.id());
    }

    void staleResident(UUID resident) {
        var observed = colony.inventory().snapshot().residents().get(resident);
        if (observed != null) {
            colony.inventoryFeedback().staleResident(resident, observed.version());
        }
    }

    boolean valid(Source source) {
        var current = colony.inventory().snapshot().stores().get(source.physical().store());
        return current != null && current.state() == InventorySnapshot.State.OBSERVED
            && current.incarnation() == source.incarnation()
            && current.version().epoch().equals(source.version().epoch())
            && current.version().policy() == source.version().policy()
            && current.slots().stream().anyMatch(slot -> slot.physicalSlot() == source.physical().physicalSlot()
                && slot.accessibleSlot().orElse(-1) == source.slot()
                && slot.incarnation() == source.slotIncarnation());
    }

    void stale(Source source) {
        var current = colony.inventory().snapshot().stores().get(source.physical().store());
        colony.inventoryFeedback().stale(source.physical(), valid(source) && current != null
            ? current.version() : source.version());
    }

    void staleApproach(Stash stash, InventorySnapshot.Version observed) {
        var current = colony.inventory().snapshot().stores().get(stash);
        InventorySnapshot.Version version = current != null && current.version().epoch().equals(observed.epoch())
            && current.version().approaches() == observed.approaches() ? current.version() : observed;
        colony.inventoryFeedback().staleApproach(stash, version);
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

    boolean takes(WorldPos at) {
        return StoreRule.takes(colony.storeRules(at));
    }

    boolean stores(WorldPos at) {
        return StoreRule.stores(colony.storeRules(at));
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
