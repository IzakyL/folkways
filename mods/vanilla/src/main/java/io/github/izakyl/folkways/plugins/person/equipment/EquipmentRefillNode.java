package io.github.izakyl.folkways.plugins.person.equipment;

import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.body.TaskTools;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** One component-checked physical draw and equipment installation through the existing BodyRunner. */
final class EquipmentRefillNode implements Node, Staged {
    private final EquipmentPlans plans;
    private final ResidentEntity body;
    private final long revision;
    private final ResidentCommands.Order order;
    private final EquipmentPlans.Demand demand;
    private final EquipmentPlans.Offer offer;
    private final long deadline;
    private final NodeSpec spec;
    private Container borrowed;
    private String failure;

    EquipmentRefillNode(EquipmentPlans plans, ResidentEntity body, long revision, ResidentCommands.Order order,
            EquipmentPlans.Demand demand, EquipmentPlans.Offer offer, long deadline) {
        this.plans = plans; this.body = body; this.revision = revision; this.order = order;
        this.demand = demand; this.offer = offer; this.deadline = deadline;
        WorkSite site = offer.store().<WorkSite>map(WorkSite.AtBlock::new)
            .orElseGet(() -> WorkSite.on(body.level(), body.id(), body.blockPosition()));
        spec = NodeSpec.of(UUID.randomUUID(), EquipmentPlans.ID, site, offer.stances(), Workload.Once.of(5))
            .needs(new Need(demand.spec(), offer.count(), offer.store().isEmpty())).doing(EquipmentPlans.ID).done();
    }

    ResidentEntity body() { return body; }
    long revision() { return revision; }
    ResidentCommands.Order order() { return order; }
    EquipmentPlans.Demand demand() { return demand; }
    EquipmentPlans.Offer offer() { return offer; }
    long deadline() { return deadline; }
    Urge urge() { return new Urge(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "equipment_refill/" + spec.id()),
        Urge.DIRE - .25, true, () -> this); }
    @Override public NodeSpec spec() { return spec; }
    @Override public boolean directTravel() { return true; }
    @Override public boolean ready(ServerLevel level) { return level.getGameTime() < deadline && plans.valid(this); }

    private boolean reaches(ServerLevel level) {
        if (offer.store().isEmpty()) return true;
        var at = offer.store().orElseThrow();
        return WorldSpaces.at(body).sameRealm(at) && WorldSpaces.storage(level, WorldSpaces.at(body))
            .filter(feet -> Reach.withinReach(feet, at.block(level)) && Reach.inSight(level, feet, at.block(level))).isPresent();
    }

    @Override public Drawn draw(ServerLevel level, Worker who) {
        if (!ready(level) || who.body() != body || who.pack() != body.pack() || !reaches(level)) {
            failure = "busy"; return new Short(LaborRefusal.GOODS_GONE);
        }
        Container source = offer.store().flatMap(at -> plans.source(level, at)).orElseGet(() -> offer.store().isEmpty() ? body.pack() : null);
        if (source == null || offer.cell() < 0 || offer.cell() >= source.getContainerSize()) {
            failure = "missing"; return new Short(LaborRefusal.GOODS_GONE);
        }
        Container free = plans.free(level, offer.store(), body);
        ItemStack actual = source.getItem(offer.cell());
        ItemStack available = free.getItem(offer.cell());
        if (actual.isEmpty() || available.getCount() < offer.count()
                || !ItemStack.isSameItemSameComponents(actual, offer.expected())
                || !ItemStack.isSameItemSameComponents(available, offer.expected())
                || TaskTools.holds(body.id(), actual)
                || PersonalItems.marked(actual) && (offer.store().isPresent() || !PersonalItems.belongsTo(body, actual))) {
            failure = "missing"; return new Short(LaborRefusal.GOODS_GONE);
        }
        int before = actual.getCount(); borrowed = source;
        ItemStack taken;
        try { taken = source.removeItem(offer.cell(), offer.count()); source.setChanged(); }
        catch (RuntimeException | LinkageError broken) {
            int left = source.getItem(offer.cell()).getCount();
            int removed = Math.max(0, Math.min(offer.count(), before - left));
            restore(level, who, removed == 0 ? List.of() : List.of(offer.expected().copyWithCount(removed)));
            throw broken;
        }
        if (taken.getCount() != offer.count()) {
            restore(level, who, taken.isEmpty() ? List.of() : List.of(taken));
            failure = "missing"; return new Short(LaborRefusal.GOODS_GONE);
        }
        return new Taken(List.of(taken));
    }

    @Override public Outcome commit(ServerLevel level, Worker who) {
        if (!ready(level) || !reaches(level) || borrowed == null || who.body() != body || who.pack() != body.pack()) {
            failure = "busy"; return Outcome.failed(LaborRefusal.GOODS_GONE);
        }
        String result = plans.install(this, who.supplied());
        if (!result.equals("ok")) { failure = result; return Outcome.failed(LaborRefusal.NOWHERE_TO_PUT_IT); }
        borrowed = null;
        return Outcome.done();
    }

    @Override public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
        if (borrowed == null) return;
        Container source = borrowed; borrowed = null;
        for (ItemStack stack : goods) {
            ItemStack left = refund(source, stack);
            if (!left.isEmpty() && source != body.pack() && body.isAlive()) left = refund(body.pack(), left);
            if (!left.isEmpty()) body.spawnAtLocation(left);
        }
    }

    private static ItemStack refund(Container into, ItemStack stack) {
        long before;
        try { before = sameCount(into, stack); }
        catch (RuntimeException | LinkageError unreadable) { return stack.copy(); }
        try { return Containers.insert(into, stack); }
        catch (RuntimeException | LinkageError broken) {
            long added = Math.max(0, sameCount(into, stack) - before);
            return stack.copyWithCount((int) Math.max(0, stack.getCount() - added));
        }
    }

    private static long sameCount(Container into, ItemStack expected) {
        long count = 0;
        for (int cell = 0; cell < into.getContainerSize(); cell++) {
            ItemStack stack = into.getItem(cell);
            if (ItemStack.isSameItemSameComponents(stack, expected)) count += stack.getCount();
        }
        return count;
    }

    @Override public void released(ServerLevel level, Worker who, Ending ending) { plans.finished(this, ending, failure); }
}
