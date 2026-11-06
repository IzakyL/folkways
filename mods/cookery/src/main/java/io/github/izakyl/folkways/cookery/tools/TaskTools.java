package io.github.izakyl.folkways.cookery.tools;

import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.cookery.gear.CookeryGear;
import io.github.izakyl.folkways.cookery.racks.KitchenRacks;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.inventory.InventorySnapshot;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.orders.OrderReturns;
import io.github.izakyl.folkways.plugins.orders.Orders;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

// One colony's runtime borrowing and merged missing-tool orders. Food graphs keep their original material budget.
public final class TaskTools {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "cookery_tools");

    private record Supply(KitchenTools.Replenishment request) {
    }

    final Colony colony;
    final MinecraftServer server;
    final KitchenTools pool = new KitchenTools();
    final ToolStock stock;
    private final Map<UUID, ToolWork> active = new LinkedHashMap<>();
    private final Map<UUID, ClothingWork> clothing = new LinkedHashMap<>();
    private record Demand(ServerLevel level, List<ItemSpec> families, boolean needsTools) {
    }
    private final Map<UUID, ItemStack> borrowed = new LinkedHashMap<>();
    private final Map<UUID, WorldPos> returningTo = new LinkedHashMap<>();
    private final OrderReturns.Registration returnObserver;
    private int delay;
    private final Map<UUID, Supply> supplying = new LinkedHashMap<>();

    public TaskTools(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        stock = new ToolStock(colony, server, pool, this::tool);
        var saved = colony.kept(ID);
        pool.load(colony.id(), saved, server.overworld().registryAccess());
        var items = saved.getList("items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            var item = items.getCompound(index);
            if (item.hasUUID("loan")) {
                var stack = ItemStack.parseOptional(server.overworld().registryAccess(), item.getCompound("stack"));
                if (!stack.isEmpty()) {
                    borrowed.put(item.getUUID("loan"), stack);
                    pool.leases(colony.id()).stream().filter(lease -> lease.id().equals(item.getUUID("loan")))
                        .findFirst().ifPresent(lease -> stock.restored(lease,
                            item.contains("slot") ? item.getInt("slot") : -1,
                            item.contains("equipment") ? Optional.of(net.minecraft.world.entity.EquipmentSlot
                                .byName(item.getString("equipment"))) : Optional.empty(),
                            item.hasUUID("epoch") && item.getUUID("epoch").equals(
                                colony.inventory().snapshot().version().epoch()) ? item.getLong("incarnation") : 0,
                            item.hasUUID("epoch") && item.getUUID("epoch").equals(
                                colony.inventory().snapshot().version().epoch()) ? item.getLong("resident") : 0));
                    WorldPos.load(Reader.of(item.getCompound("return"))).ifPresent(at ->
                        returningTo.put(item.getUUID("loan"), at));
                }
            }
        }
        pool.leases(colony.id()).forEach(lease ->
            protect(lease.id(), lease.resident(), List.of(lease.request().tools())));
        returnObserver = OrderReturns.register(colony.id(), ID, this::promises);
    }

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(LoanClothing::joined);
        Tools.shared(WaresContent.CRAFTING, new ToolNeed.Tagged(TagMod.KITCHEN_KNIFE),
            ItemSpec.of(TagMod.KITCHEN_KNIFE));
        Tools.shared(WaresContent.CRAFTING, new ToolNeed.Tagged(TagMod.KITCHEN_SHOVEL),
            ItemSpec.of(TagMod.KITCHEN_SHOVEL));
        Tools.shared(WaresContent.CRAFTING, io.github.izakyl.folkways.cookery.stove.StoveWorkshops.IGNITION,
            ItemSpec.of(TagMod.LIT_STOVE));
    }

    public Workshop decorate(Workshop original) {
        return new Workshop() {
            public UUID key() { return original.key(); }

            public boolean alike() { return original.alike(); }


            public ResourceLocation id() { return original.id(); }

            public WorkSite site() { return original.site(); }

            public List<Refinement.Change> options(Intent intent, Grown graph) {
                return original.options(intent, graph).stream().map(TaskTools.this::decorate).toList();
            }
        };
    }

    public Refinement.Change decorate(Refinement.Change change) {
        return new Refinement.Change(decorate(change.replacement()), change.links(), change.workerGroups(),
            change.holds());
    }

    public Grown decorate(Grown original) {
        List<Node> wrapped = original.nodes().stream().map(node -> {
            boolean shared = node.spec().tools().stream().anyMatch(use -> Tools.shared(use.need()).isPresent());
            var clothing = node.spec().site().where().level(server).map(level -> CookeryGear.request(colony, node,
                level)).orElse(CookeryGear.Request.NONE);
            if (node instanceof ToolWork || node instanceof ClothingWork) { return node; }
            if (!clothing.empty()) { return (Node) new ClothingWork(this, node, clothing); }
            return shared ? (Node) new ToolWork(this, node) : node;
        }).toList();
        return new Grown(wrapped, original.links(), original.workerGroups(), original.follows());
    }

    synchronized void started(ToolWork work) {
        active.put(work.id(), work);
    }

    synchronized void ended(ToolWork work) {
        active.remove(work.id(), work);
        pool.cancel(colony.id(), work.id());
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(work.id());
        for (KitchenTools.Lease lease : pool.leases(colony.id())) {
            if (lease.request().node().equals(work.id())) {
                stock.body(lease.resident()).ifPresent(body -> LoanClothing.recover(this, lease, body));
                if (tool(lease).isEmpty()) {
                    returned(lease);
                    continue;
                }
                protect(lease.id(), lease.resident(), List.of(lease.request().tools()));
            }
        }
        persist();
    }

    synchronized void started(ClothingWork work) {
        clothing.put(work.id(), work);
    }

    synchronized void ended(ClothingWork work) {
        clothing.remove(work.id(), work);
        pool.cancel(colony.id(), work.id());
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(work.id());
        for (KitchenTools.Lease lease : pool.leases(colony.id())) {
            if (!lease.request().node().equals(work.id())) { continue; }
            stock.body(lease.resident()).ifPresent(body -> LoanClothing.recover(this, lease, body));
            if (tool(lease).isEmpty()) { returned(lease); }
            else { protect(lease.id(), lease.resident(), List.of(lease.request().tools())); }
        }
        persist();
    }

    void protect(UUID task, UUID resident, List<ItemSpec> tools) {
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.keep(task, resident,
            stack -> tools.stream().anyMatch(family -> Goods.matches(stack, family)));
    }

    void returned(KitchenTools.Lease lease) {
        stock.returned(lease);
        pool.returning(lease.id());
        pool.returned(lease.id());
        stock.forget(lease);
        borrowed.remove(lease.id());
        returningTo.remove(lease.id());
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(lease.id());
        persist();
    }

    void record(KitchenTools.Lease lease, ItemStack tool) {
        borrowed.put(lease.id(), tool.copyWithCount(1));
        protect(lease.id(), lease.resident(), List.of(lease.request().tools()));
        persist();
    }

    void record(KitchenTools.Lease lease, ItemStack tool, Container pack) {
        stock.remember(lease, tool, pack);
        colony.inventoryFeedback().changedResident(lease.resident());
        record(lease, tool);
    }

    Optional<ItemStack> tool(KitchenTools.Lease lease) {
        return Optional.ofNullable(borrowed.get(lease.id())).map(ItemStack::copy);
    }

    int carriedSlot(Container pack, KitchenTools.Lease lease, ItemStack expected) {
        return stock.carriedSlot(pack, lease, expected);
    }

    void missing(KitchenTools.Lease lease) {
        stock.unaccounted(lease);
        lost(lease);
    }

    void lost(KitchenTools.Lease lease) {
        pool.lost(lease.id());
        stock.forget(lease);
        borrowed.remove(lease.id());
        returningTo.remove(lease.id());
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(lease.id());
        persist();
    }

    void borrowedFrom(ServerLevel level, KitchenTools.Lease lease, WorldPos source) {
        ItemStack stack = tool(lease).orElse(ItemStack.EMPTY);
        WorldPos home = WorldPos.of(level, Containers.anchor(level, source.block(level)));
        boolean maintained = Orders.targets(colony).stream().anyMatch(target -> target.standing()
            && target.into().equals(home) && Goods.matches(stack, target.goods()));
        if (maintained || KitchenRacks.matches(level.getBlockState(home.block(level)))) {
            returningTo.put(lease.id(), home);
        }
        if (!returningTo.containsKey(lease.id())) {
            destination(level, lease, stack);
        }
        persist();
    }

    Optional<ToolStock.Destination> destination(ServerLevel level, KitchenTools.Lease lease, ItemStack stack) {
        var snapshot = colony.inventory().snapshot();
        boolean pending = snapshot.coverage().membershipPending();
        for (WorldPos at : colony.storesIn(level.dimension())) {
            var store = snapshot.store(at);
            if (store.isEmpty() || store.get().state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObserved(Stash.at(at));
                pending = true;
            }
        }
        if (pending) {
            colony.inventory().ensureObserved();
            return Optional.empty();
        }
        List<ToolStock.Destination> destinations = stock.destinations(level, stack);
        WorldPos home = returningTo.get(lease.id());
        var homeStore = colony.inventory().snapshot().stores().values().stream().filter(store ->
            home != null && (store.stash().pos().equals(home) || store.aliases().contains(home))).findFirst();
        if (homeStore.isPresent() && (homeStore.get().state() == InventorySnapshot.State.DIRTY
                || homeStore.get().state() == InventorySnapshot.State.QUEUED
                || homeStore.get().state() == InventorySnapshot.State.UNOBSERVED)) {
            colony.inventory().ensureObserved(homeStore.get().stash());
            return Optional.empty();
        }
        java.util.function.Predicate<ToolStock.Destination> room = target -> {
            long others = returningTo.entrySet().stream().filter(entry -> !entry.getKey().equals(lease.id())
                && entry.getValue().equals(target.at()) && borrowed.containsKey(entry.getKey())).count();
            return target.room() > others;
        };
        Optional<ToolStock.Destination> chosen = destinations.stream().filter(target ->
            target.at().equals(home)).filter(room).findFirst();
        if (chosen.isEmpty()) { chosen = destinations.stream().filter(room).findFirst(); }
        if (chosen.isPresent()) {
            WorldPos next = chosen.get().at();
            if (!next.equals(returningTo.put(lease.id(), next))) { persist(); }
        } else if ((homeStore.isPresent() && homeStore.get().state() == InventorySnapshot.State.OBSERVED)
                || (homeStore.isEmpty() && !colony.inventory().snapshot().coverage().membershipPending())) {
            if (returningTo.remove(lease.id()) != null) { persist(); }
        }
        return chosen;
    }

    private synchronized List<OrderReturns.Promise> promises() {
        List<OrderReturns.Promise> promises = new ArrayList<>();
        for (KitchenTools.Lease lease : pool.leases(colony.id())) {
            WorldPos target = returningTo.get(lease.id());
            ItemStack expected = tool(lease).orElse(ItemStack.EMPTY);
            if (target == null || expected.isEmpty()) { continue; }
            var observed = colony.inventory().snapshot();
            boolean offered = observed.stores().values().stream().anyMatch(store ->
                store.stash().pos().equals(target) || store.aliases().contains(target));
            if (!offered && !observed.coverage().membershipPending()) { continue; }
            if (!offered) { colony.inventory().ensureObserved(); }
            ItemStack actual = stock.held(observed, lease);
            var resident = observed.residents().get(lease.resident());
            if (!actual.isEmpty()) {
                promises.add(new OrderReturns.Promise(lease.tool(), target, actual.copyWithCount(1)));
            } else if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED
                    || stock.reloaded(lease)) {
                colony.inventory().ensureObservedResident(lease.resident());
                promises.add(new OrderReturns.Promise(lease.tool(), target, expected.copyWithCount(1)));
            }
        }
        return List.copyOf(promises);
    }

    void persist() {
        var saved = pool.save(colony.id(), server.overworld().registryAccess());
        var items = new ListTag();
        for (var lease : pool.leases(colony.id())) {
            var stack = borrowed.get(lease.id());
            if (stack != null && !stack.isEmpty()) {
                var item = new CompoundTag();
                item.putUUID("loan", lease.id());
                item.putInt("slot", stock.carriedSlot(lease));
                item.putLong("incarnation", stock.incarnation(lease));
                item.putLong("resident", stock.residentIncarnation(lease));
                item.putUUID("epoch", colony.inventory().snapshot().version().epoch());
                stock.equipmentSlot(lease).ifPresent(slot -> item.putString("equipment", slot.getName()));
                item.put("stack", stack.save(server.overworld().registryAccess()));
                WorldPos target = returningTo.get(lease.id());
                if (target != null) { item.put("return", target.save()); }
                items.add(item);
            }
        }
        saved.put("items", items);
        colony.keep(ID, saved);
    }

    public synchronized void tick(MinecraftServer ignored) {
        if (delay-- > 0) {
            return;
        }
        delay = 19;
        // A departed member no longer owns colony work. Unloaded held residents remain in residents() and
        // retain their loans; any nearby death drops are still observed by ToolStock as physical in-transit stock.
        for (KitchenTools.Lease lease : pool.leases(colony.id())) {
            if (colony.residents().stream().noneMatch(resident -> resident.id().equals(lease.resident()))) {
                missing(lease);
            }
        }
        Set<UUID> needed = new LinkedHashSet<>();
        List<Demand> demands = new ArrayList<>();
        active.values().forEach(work -> demands.add(new Demand(work.level(), work.families(), work.needsTools())));
        clothing.values().forEach(work -> demands.add(new Demand(work.level(), work.families(), work.needsTools())));
        for (Demand work : demands) {
            ServerLevel level = work.level();
            if (level == null || !work.needsTools()) {
                continue;
            }
            for (ItemSpec family : work.families()) {
                var inventory = stock.read(level, family).inventory();
                for (Supply supply : supplying.values()) {
                    if (supply.request().tools().equals(family) && inventory.commitments().entrySet().stream()
                            .noneMatch(entry -> !entry.getKey().equals(supply.request().id()) && entry.getValue() >
                                0)) {
                        needed.add(supply.request().id());
                    }
                }
                var decision = pool.supply(colony.id(), family, inventory);
                decision.replenishment().ifPresent(request -> {
                    needed.add(request.id());
                    if (supplying.containsKey(request.id())) {
                        return;
                    }
                    Goods.members(family).stream().map(item -> item.getDefaultInstance()).findFirst()
                        .flatMap(stack -> stock.destinations(level, stack).stream().findFirst()).ifPresent(target -> {
                            Supply supply = new Supply(request);
                            supplying.put(request.id(), supply);
                            Delivery delivery = Delivery.to(request.id(), ID, new WorkSite.AtBlock(target.at()),
                                Stances.WHEREVER, request.tools(), 1, count -> { });
                            colony.submit(ID, level.dimension(), Grown.of(delivery), (node, how) -> {
                                synchronized (TaskTools.this) {
                                    supplying.remove(node);
                                }
                            });
                        });
                });
            }
        }
        for (UUID obsolete : List.copyOf(supplying.keySet())) {
            if (!needed.contains(obsolete)) {
                supplying.remove(obsolete);
                colony.withdraw(ID, obsolete);
            }
        }
    }

    public synchronized List<Grown> goals(ColonyView view) {
        List<Grown> recovery = new ArrayList<>();
        for (KitchenTools.Lease lease : pool.leases(colony.id())) {
            if (lease.phase() != KitchenTools.Phase.RETURNING) {
                continue;
            }
            stock.body(lease.resident()).filter(body -> body.mob().level() == view.level()).ifPresent(body -> {
                protect(lease.id(), lease.resident(), List.of(lease.request().tools()));
                recovery.add(Grown.of(new ToolReturn(this, lease, body)));
            });
        }
        return List.copyOf(recovery);
    }

    public synchronized void closed(Closing why) {
        returnObserver.close();
        for (KitchenTools.Lease lease : pool.leases(colony.id())) {
            pool.returning(lease.id());
            stock.body(lease.resident()).ifPresent(body -> {
                if (why == Closing.RAZED) { LoanClothing.release(this, lease, body); }
                else { LoanClothing.recover(this, lease, body); }
            });
        }
        if (why == Closing.SHUTDOWN) {
            persist();
        } else {
            colony.keep(ID, new CompoundTag());
        }
        for (UUID supply : supplying.keySet()) {
            colony.withdraw(ID, supply);
        }
        active.keySet().forEach(io.github.izakyl.folkways.core.api.resident.body.TaskTools::release);
        pool.leases(colony.id()).forEach(lease ->
            io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(lease.id()));
        clothing.keySet().forEach(io.github.izakyl.folkways.core.api.resident.body.TaskTools::release);
        clothing.clear();
        active.clear();
        supplying.clear();
    }
}
