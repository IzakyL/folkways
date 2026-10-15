package io.github.izakyl.folkways.plugins.cookery.tools;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

abstract class ToolAction implements Node {

    final TaskTools service;
    final KitchenTools.Lease lease;
    final WorldPos at;
    private final NodeSpec spec;

    ToolAction(TaskTools service, KitchenTools.Lease lease, WorldPos at, Stances stances) {
        this.service = service;
        this.lease = lease;
        this.at = at;
        spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(at), stances,
            Workload.Once.of(20)).leaves(Leaves.room(1)).doing(CookeryContent.PREPARING, lease.request().tools()).done();
    }

    @Override
    public NodeSpec spec() { return spec; }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        return at.in(level) && level.isLoaded(at.block(level)) && service.stock.store(level, at).isEmpty()
            ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
    }

    boolean reaches(ServerLevel level, Worker who) {
        return at.in(level) && level.isLoaded(at.block(level)) && Reach.stanceHolding(who.body(), spec.stances())
            .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(at.realm(), feet)))
            .filter(feet -> Reach.withinReach(feet, at.block(level)) && Reach.inSight(level, feet, at.block(level)))
            .isPresent();
    }

    // These are real container transfers, outside the food graph's consumable inputs. If an adapter throws partway
    // through a transfer, both inventories roll back together before any loan is marked carried or returned.
    private static List<ItemStack> snapshot(Container inventory) {
        List<ItemStack> saved = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            saved.add(inventory.getItem(slot).copy());
        }
        return saved;
    }

    private static void restore(Container inventory, List<ItemStack> saved) {
        for (int slot = 0; slot < saved.size(); slot++) {
            inventory.setItem(slot, saved.get(slot).copy());
        }
        inventory.setChanged();
    }

    private static boolean move(Container from, int slot, Container into) {
        List<ItemStack> beforeFrom = snapshot(from);
        List<ItemStack> beforeInto = snapshot(into);
        try {
            ItemStack taken = from.removeItem(slot, 1);
            if (taken.isEmpty() || !Containers.insert(into, taken).isEmpty()) {
                restore(from, beforeFrom);
                restore(into, beforeInto);
                return false;
            }
            from.setChanged();
            return true;
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
        boolean moved;

        Borrow(TaskTools service, KitchenTools.Lease lease, ToolStock.Source source) {
            super(service, lease, source.at(), source.stances());
            this.source = source;
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            if (!who.resident().id().equals(lease.resident()) || !reaches(level, who)
                    || service.pool.leases(service.colony.id()).stream().noneMatch(current ->
                        current.id().equals(lease.id()) && current.phase() == KitchenTools.Phase.IN_TRANSIT)) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            Optional<Container> found = service.stock.store(level, at);
            if (found.isEmpty() || source.slot() >= found.get().getContainerSize()) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            Container inventory = found.get();
            ItemStack actual = inventory.getItem(source.slot());
            if (!ItemStack.isSameItemSameComponents(actual, source.expected()) || actual.isEmpty()) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            if (!move(inventory, source.slot(), who.pack())) {
                return Outcome.failed(CookeryRefusal.STATION_BUSY);
            }
            moved = true;
            service.pool.carried(lease.id());
            service.record(lease, source.expected());
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
        boolean moved;
        boolean missing;

        Return(TaskTools service, KitchenTools.Lease lease, ToolStock.Destination target) {
            super(service, lease, target.at(), target.stances());
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            if (!who.resident().id().equals(lease.resident()) || !reaches(level, who)) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            Optional<Container> found = service.stock.store(level, at);
            if (found.isEmpty()) {
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            int slot = service.carriedSlot(who.pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY));
            if (slot < 0) {
                missing = true;
                service.lost(lease);
                return Outcome.done();
            }
            Container destination = found.get();
            if (!ToolStock.room(destination, who.pack().getItem(slot)) || !move(who.pack(), slot, destination)) {
                return Outcome.failed(CookeryRefusal.STATION_BUSY);
            }
            moved = true;
            service.returned(lease);
            return Outcome.done();
        }
    }
}
