package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.ReservedInput;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.farming.CropSeeds;
import io.github.izakyl.folkways.vanilla.orders.Orders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/** Server-thread bridge from real colony inventory, current plan claims and persisted player orders. */
public final class SeedSupport {
    private final Colony colony;
    private final MinecraftServer server;
    private final SeedBudget budget;
    private record PlanningKey(InventorySnapshot.Version inventory, List<ReservedInput> claims,
                               List<CropSeeds.Sowing> sowing, List<Orders.Target> targets) { }
    private PlanningKey read;
    private SeedBudget.Snapshot planned;

    public SeedSupport(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
        budget = new SeedBudget(this::snapshot, this::catalogue,
            item -> CompostSettings.reserve(colony, item));
    }

    public SeedBudget budget(ServerLevel level) { return budget; }

    public Set<Item> catalogue() { return CropSeeds.catalogue(server.overworld(), BlockPos.ZERO); }

    public SeedBudget.Snapshot snapshot() {
        InventorySnapshot inventory = colony.inventory().snapshot();
        if (!inventory.coverage().complete()) {
            colony.inventory().ensureObserved();
        }
        List<ReservedInput> reserved = new ArrayList<>();
        List<CropSeeds.Sowing> sowing = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            reserved.addAll(colony.reservedInputs(level));
            sowing.addAll(CropSeeds.sowing(colony, level));
        }
        List<Orders.Target> orders = Orders.targets(colony);
        PlanningKey key = new PlanningKey(inventory.version(), List.copyOf(reserved), List.copyOf(sowing), orders);
        if (key.equals(read)) { return planned; }
        boolean complete = !inventory.coverage().membershipPending();
        Map<WorldPos, SeedBudget.Stock> stores = new LinkedHashMap<>();
        List<SeedBudget.Stock> stocks = new ArrayList<>();
        List<SeedBudget.FutureNeed> future = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (WorldPos offered : colony.storesIn(level.dimension())) {
                var store = inventory.store(offered);
                if (store.isEmpty()) { complete = false; continue; }
                var observed = store.get();
                if (observed.state() != InventorySnapshot.State.OBSERVED) { complete = false; }
                WorldPos at = observed.stash().pos();
                stores.putIfAbsent(at, new SeedBudget.Stock("store/" + at, SeedBudget.Location.store(at),
                    observed.slots().stream().filter(slot -> slot.accessibleSlot().isPresent())
                        .map(SlotSnapshot::stackCopy).toList()));
            }
        }
        for (var resident : colony.residents()) {
            var pack = inventory.residents().get(resident.id());
            if (pack == null) { complete = false; continue; }
            if (pack.state() != InventorySnapshot.State.OBSERVED) { complete = false; }
            stocks.add(new SeedBudget.Stock("pack/" + resident.id(), SeedBudget.Location.pack(resident.id()),
                pack.pack().stream().map(SlotSnapshot::stackCopy).toList()));
        }
        stocks.addAll(0, stores.values());
        // Repeated stage/dimension observations are one promise. The core has already summed distinct
        // draws within a placement; different producer lineages must remain distinct until that point.
        record InputKey(UUID node, Optional<WorldPos> store, Optional<UUID> resident, ItemSpec goods,
                        Optional<UUID> producer) { }
        Map<InputKey, ReservedInput> distinct = new LinkedHashMap<>();
        for (ReservedInput input : reserved) {
            distinct.merge(new InputKey(input.node(), input.store(), input.resident(), input.goods(), input.producer()),
                input,
                (before, next) -> before.count() >= next.count() ? before : next);
        }
        record ClaimKey(UUID node, SeedBudget.Location at, ItemSpec goods) { }
        Map<ClaimKey, Long> physical = new LinkedHashMap<>();
        for (ReservedInput input : distinct.values()) {
            // A future hand-off covers a planned sowing demand, but cannot consume unrelated warehouse stock.
            if (input.pending()) { continue; }
            SeedBudget.Location at = SeedBudget.Location.UNKNOWN;
            if (input.store().isPresent()) {
                WorldPos where = input.store().get();
                var store = inventory.store(where);
                if (store.isEmpty() || store.get().state() != InventorySnapshot.State.OBSERVED) {
                    complete = false;
                }
                at = SeedBudget.Location.store(store.map(value -> value.stash().pos()).orElse(where));
            } else if (input.resident().isPresent()) {
                at = SeedBudget.Location.pack(input.resident().get());
                var resident = inventory.residents().get(input.resident().get());
                if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED) {
                    complete = false;
                }
            }
            physical.merge(new ClaimKey(input.node(), at, input.goods()), input.count(), Math::addExact);
        }
        List<SeedBudget.Claim> claims = physical.entrySet().stream().map(entry ->
            new SeedBudget.Claim(entry.getKey().node(), entry.getKey().at(), entry.getKey().goods(),
                entry.getValue())).toList();
        for (CropSeeds.Sowing demand : sowing) {
            // Repeated stages of one sowing do not add demands or count unknown claims twice.
            long covered = distinct.values().stream().filter(input -> input.node().equals(demand.node())
                && Goods.overlap(input.goods(), demand.seed())).mapToLong(ReservedInput::count).sum();
            long left = Math.max(0, demand.count() - covered);
            if (left > 0) { future.add(new SeedBudget.FutureNeed("sowing/" + demand.node(), demand.seed(), left)); }
        }
        List<SeedBudget.StockTarget> targets = new ArrayList<>();
        for (Orders.Target target : orders) {
            WorldPos where;
            var targetStore = inventory.store(target.into());
            if (targetStore.isEmpty() || targetStore.get().state() != InventorySnapshot.State.OBSERVED) {
                complete = false;
            }
            where = targetStore.map(value -> value.stash().pos()).orElse(target.into());
            targets.add(new SeedBudget.StockTarget("order/" + where + "/" + target.goods().describe(),
                target.goods(), target.high()));
        }
        SeedBudget.Snapshot result = new SeedBudget.Snapshot(stocks, claims, future, Map.of(), targets, complete);
        read = key;
        planned = result;
        return result;
    }

}
