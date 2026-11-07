package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

abstract class ToolAction implements WorkAction {

    final TaskTools service;
    final KitchenTools.Lease lease;
    final WorldPos at;
    private final NodeSpec spec;

    ToolAction(TaskTools service, KitchenTools.Lease lease, WorldPos at, Stances stances) {
        this.service = service;
        this.lease = lease;
        this.at = at;
        spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), stances,
            Workload.Once.of(20)).leaves(Leaves.room(1)).doing(CookeryContent.PREPARING,
                lease.request().tools()).done();
    }

    @Override
    public NodeSpec spec() { return spec; }

    public Optional<RefusalKind> refusal(ServerLevel level) {
        InventorySnapshot observed = service.colony.inventory().snapshot();
        var store = observed.store(at);
        return store.isEmpty() && !observed.coverage().membershipPending()
            ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
    }

    public boolean ready(ServerLevel level) {
        if (service.stock.waitingForResident(lease)) {
            return false;
        }
        InventorySnapshot observed = service.colony.inventory().snapshot();
        var store = observed.store(at);
        if (store.isPresent() && store.get().state() == InventorySnapshot.State.OBSERVED
            || store.isEmpty() && !observed.coverage().membershipPending()) {
            return true;
        }
        service.colony.inventory().ensureObserved(Stash.at(at));
        return false;
    }

    boolean reaches(ServerLevel level, Worker who) {
        return at.in(level) && level.isLoaded(at.block(level)) && WorkStances.stanceHolding(who.body(), spec.stances())
            .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(at.realm(), feet)))
            .filter(feet -> Reach.withinReach(feet, at.block(level)) && Reach.inSight(level, feet, at.block(level)))
            .isPresent();
    }

    // These are real container transfers, outside the food graph's consumable inputs. If an adapter throws partway
    // through a transfer, both inventories roll back together before any loan is marked carried or returned.
    static List<ItemStack> snapshot(Container inventory) {
        List<ItemStack> saved = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            saved.add(inventory.getItem(slot).copy());
        }
        return saved;
    }

    static int insertedSlot(Container inventory, List<ItemStack> before, ItemStack expected) {
        for (int slot = 0; slot < before.size(); slot++) {
            ItemStack actual = inventory.getItem(slot);
            ItemStack previous = before.get(slot);
            if (ItemStack.isSameItemSameComponents(actual, expected)
                    && (!ItemStack.isSameItemSameComponents(previous, expected)
                        || actual.getCount() > previous.getCount())) {
                return slot;
            }
        }
        return -1;
    }

    static void restore(Container inventory, List<ItemStack> saved) {
        for (int slot = 0; slot < saved.size(); slot++) {
            inventory.setItem(slot, saved.get(slot).copy());
        }
        inventory.setChanged();
    }

    private static int move(Container from, int slot, Container into) {
        List<ItemStack> beforeFrom = snapshot(from);
        List<ItemStack> beforeInto = snapshot(into);
        try {
            ItemStack taken = from.removeItem(slot, 1);
            if (taken.isEmpty() || !Containers.insert(into, taken).isEmpty()) {
                restore(from, beforeFrom);
                restore(into, beforeInto);
                return -1;
            }
            int inserted = insertedSlot(into, beforeInto, taken);
            if (inserted < 0) {
                restore(from, beforeFrom);
                restore(into, beforeInto);
                return -1;
            }
            from.setChanged();
            return inserted;
        } catch (RuntimeException | LinkageError broken) {
            try {
                restore(from, beforeFrom);
                restore(into, beforeInto);
            } catch (RuntimeException | LinkageError restoring) {
                broken.addSuppressed(restoring);
            }
            throw broken;
        }
    }

    static final class Borrow extends ToolAction {
        final ToolStock.Source source;
        final Optional<Vocation> vocation;
        boolean moved;

        Borrow(TaskTools service, KitchenTools.Lease lease, ToolStock.Source source,
                Optional<Vocation> vocation) {
            super(service, lease, source.at(), source.stances());
            this.source = source;
            this.vocation = vocation;
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            if (!who.resident().id().equals(lease.resident())
                    || service.pool.leases(service.colony.id()).stream().noneMatch(current ->
                        current.id().equals(lease.id()) && current.phase() == KitchenTools.Phase.IN_TRANSIT)) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (!reaches(level, who)) {
                service.stock.staleApproach(source.physical().store(), source.version());
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (!service.stock.takes(at)) {
                service.stock.stale(source);
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (!service.stock.valid(source)) {
                service.stock.stale(source);
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            Optional<Container> found = service.stock.store(level, at);
            if (found.isEmpty() || source.slot() >= found.get().getContainerSize()) {
                service.stock.stale(source);
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            Container inventory = found.get();
            ItemStack actual = inventory.getItem(source.slot());
            if (!ItemStack.isSameItemSameComponents(actual, source.expected()) || actual.isEmpty()) {
                service.stock.stale(source);
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (!KitRules.allows(service.colony, vocation, actual.getItem())) {
                return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            }
            int slot = move(inventory, source.slot(), who.pack());
            if (slot < 0) {
                return Outcome.failed(CookeryRefusal.STATION_BUSY);
            }
            moved = true;
            service.pool.carried(lease.id());
            service.record(lease, who.pack().getItem(slot), who.pack());
            service.colony.inventoryFeedback().changed(source.physical().store());
            service.colony.inventoryFeedback().changedResident(lease.resident());
            service.borrowedFrom(level, lease, source.at());
            return Outcome.done();
        }

        void ended(Ending how) {
            if (!moved) {
                service.returned(lease);
            }
        }
    }

    static final class Return extends ToolAction {
        private final ToolStock.Destination target;
        boolean moved;
        boolean missing;

        Return(TaskTools service, KitchenTools.Lease lease, ToolStock.Destination target) {
            super(service, lease, target.at(), target.stances());
            this.target = target;
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            if (!who.resident().id().equals(lease.resident())) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (!reaches(level, who)) {
                service.stock.staleApproach(Stash.at(at), target.version());
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (!service.stock.stores(at)) {
                service.colony.inventoryFeedback().stale(new InventoryFeedback.StoreSlot(Stash.at(at), 0),
                    target.version());
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            Optional<Container> found = service.stock.store(level, at);
            if (found.isEmpty()) {
                service.colony.inventoryFeedback().stale(new InventoryFeedback.StoreSlot(Stash.at(at), 0),
                    target.version());
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (service.stock.waitingForResident(lease)) {
                return Outcome.failed(CookeryRefusal.STATION_BUSY);
            }
            int slot = service.carriedSlot(who.pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY));
            if (slot < 0) {
                missing = true;
                service.missing(lease);
                return Outcome.done();
            }
            Container destination = found.get();
            if (!ToolStock.room(destination, who.pack().getItem(slot)) || move(who.pack(), slot, destination) < 0) {
                service.colony.inventoryFeedback().stale(new InventoryFeedback.StoreSlot(Stash.at(at), 0),
                    target.version());
                return Outcome.failed(CookeryRefusal.STATION_BUSY);
            }
            moved = true;
            service.colony.inventoryFeedback().changed(Stash.at(at));
            service.colony.inventoryFeedback().changedResident(lease.resident());
            service.returned(lease);
            return Outcome.done();
        }
    }
}
