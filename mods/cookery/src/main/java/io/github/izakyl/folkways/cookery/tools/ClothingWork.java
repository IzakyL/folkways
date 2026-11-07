package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.gear.CookeryGear;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.ActionSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Unmet;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.work.support.Staged;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** An optional native clothing benefit around one task, using the existing physical borrowing pool. */
final class ClothingWork implements Node {
    private final TaskTools service;
    private final Node original;
    private final CookeryGear.Request request;
    private final NodeSpec spec;
    private final int loanRoom;
    private final Map<EquipmentSlot, ItemSpec> garments = new LinkedHashMap<>();
    private final Map<ItemSpec, KitchenTools.Lease> loans = new LinkedHashMap<>();
    private final Map<ItemSpec, ToolStock.Source> sources = new LinkedHashMap<>();
    private List<ItemSpec> families = List.of();
    private ServerLevel level;
    private UUID borrower;
    private boolean resolved;
    private boolean dressed;
    private boolean originalReleased;
    private boolean ended;
    private boolean workDone;
    private boolean complete;
    private RefusalKind failure;

    ClothingWork(TaskTools service, Node original, CookeryGear.Request request) {
        this.service = service;
        this.original = original;
        this.request = request;
        garments.putAll(request.slots());
        NodeSpec from = original.spec();
        var candidate = from.site().where().level(service.server).flatMap(where ->
            candidates(where).stream().findFirst());
        candidate.ifPresent(body -> borrower = body.id());
        loanRoom = candidate.filter(body -> service.stock.observed(body.id()))
            .map(this::loanRoom).orElse(garments.size());
        List<Amount> leaves = new ArrayList<>(from.leaves());
        leaves.addAll(Leaves.room(loanRoom));
        // The real inner action retains its own worksite. Borrow before travelling into the water.
        spec = new NodeSpec(from.id(), from.owner(), from.site(), Stances.WHEREVER, from.needs(), from.gives(),
            leaves, from.tools(),
            new Workload.Custom(), WorkEffort.NONE, from.pace(), from.vocation(), from.gesture(),
            from.focus(), from.estimate(), from.doing(), from.about(), from.subject());
    }

    @Override
    public boolean producesBeforeSettlement() {
        return !original.spec().gives().isEmpty();
    }

    private int loanRoom(io.github.izakyl.folkways.core.resident.api.Body body) {
        if (blocked(body) && !request.required()) {
            return 0;
        }
        return (int) garments.keySet().stream()
            .filter(slot -> service.stock.equipment(body.id(), slot).isEmpty()
                || service.stock.borrowedSlot(body.id(), slot))
            .count();
    }

    public NodeSpec spec() { return spec; }

    public Optional<UUID> worker() { return borrower == null ? original.worker() : Optional.of(borrower); }

    ServerLevel level() { return level; }

    List<ItemSpec> families() { return families; }

    boolean needsTools() {
        return !workDone && borrower != null && service.stock.body(borrower)
            .filter(body -> CookeryGear.permits(service.colony, body, request)).isPresent();
    }

    public List<Amount> planningLeaves(List<Amount> promised) {
        List<Amount> leaves = new ArrayList<>(original.planningLeaves(promised));
        leaves.addAll(Leaves.room(loanRoom));
        return Leaves.merged(leaves);
    }

    public Optional<RefusalKind> planned(ServerLevel level) {

        this.level = level;

        var candidates = candidates(level);
        if (candidates.isEmpty()) { return Optional.of(CookeryRefusal.NOTHING_TO_WORK_WITH); }
        var chosen = candidates.getFirst();
        borrower = chosen.id();
        if (service.stock.observed(chosen.id())) { resolve(chosen); }
        if (failure != null || families.size() > loanRoom) {
            io.github.izakyl.folkways.core.resident.api.TaskTools.release(id());
            return Optional.of(failure != null ? failure : CookeryRefusal.STATION_CHANGED);
        }
        var refused = original.planned(level);
        if (refused.isPresent()) {
            io.github.izakyl.folkways.core.resident.api.TaskTools.release(id());
            return refused;
        }
        service.started(this);
        return Optional.empty();
    }

    // Adopted demand can bootstrap one shared piece while the resident stays free to make it.
    public boolean ready(ServerLevel level) {
        if (workDone) { return true; }
        if (!original.ready(level)) { return false; }
        if (!resolved) {
            if (borrower == null || !service.stock.observed(borrower)) { return false; }
            service.stock.body(borrower).ifPresent(this::resolve);
            if (!resolved || failure != null || families.size() > loanRoom) { return false; }
        }
        for (ItemSpec family : families) {
            var status = service.pool.request(service.colony.id(), id(), family, service.stock.read(level,
                family).inventory()).status();
            if (status != KitchenTools.Status.AVAILABLE && status != KitchenTools.Status.BORROWED) { return false; }
        }
        return true;
    }

    public boolean acceptsInput(Stash from) { return original.acceptsInput(from); }

    public boolean permitsProduction(ItemSpec goods) { return original.permitsProduction(goods); }

    public boolean replannable() { return original.replannable(); }

    public boolean settled(ServerLevel level) { return original.settled(level); }

    public void changed(ServerLevel level, NodeState state) { original.changed(level, state); }

    public Recourse failing(ServerLevel level, RefusalKind why) { return original.failing(level, why); }

    public Unmet unmet(UUID before, Ending how) { return original.unmet(before, how); }

    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (ended) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        if (workDone) { return Optional.empty(); }
        if (borrower != null && service.stock.body(borrower)
                .filter(body -> CookeryGear.permits(service.colony, body, request)).isEmpty()) {
            return Optional.of(CookeryRefusal.NOTHING_TO_WORK_WITH);
        }
        return original.refusal(level);
    }

    @Override
    public WorkHolding acquire(HoldingContext context) {
        return new EquipmentHolding(original, () -> dressed, () -> workDone, this::clothedContext,
            (actual, step, release) -> {
                workDone = true;
                if (step instanceof HoldingStep.Failed failed) {
                    failure = failed.why();
                }
                release.run();
                releaseOriginal(actual.level(), actual.worker(),
                    failure == null ? Ending.DONE : new Ending.Failed(id(), failure));
            }, this::operation, () -> complete, this::commit, this::completed);
    }

    private HoldingContext clothedContext(HoldingContext context) {
        return EquipmentHolding.context(context, context.worker(),
            operation -> operation instanceof Staged ? new StagedAction(operation) : new Action(operation));
    }

    private ToolAction operation(ServerLevel level, Worker who) {
        if (complete) { return null; }
        if (borrower != null && !borrower.equals(who.resident().id())) {
            failure = CookeryRefusal.STATION_CHANGED;
            complete = true;
            return null;
        }
        borrower = who.resident().id();
        if (!resolved) {
            if (!service.stock.observed(borrower)) { return null; }
            Bodies.of(who.body()).ifPresent(this::resolve);
            if (!resolved) { return null; }
        }
        if (!workDone && !dressed) {
            if (loans.isEmpty() && !reserveGroup(level, who)) { return null; }
            for (ItemSpec family : families) {
                ToolStock.Source source = sources.get(family);
                if (source != null) {
                    return new ToolAction.Borrow(service, loans.get(family), source, Optional.empty());
                }
            }
            Map<EquipmentSlot, KitchenTools.Lease> slots = new LinkedHashMap<>();
            garments.forEach((slot, family) -> {
                if (loans.containsKey(family)) { slots.put(slot, loans.get(family)); }
            });
            if (!LoanClothing.equip(service, who, slots) || !clothed(who)) {
                failure = CookeryRefusal.NOTHING_TO_WORK_WITH;
                workDone = true;
            }
            dressed = true;
        }
        if (!workDone) {
            return null;
        }
        for (KitchenTools.Lease lease : List.copyOf(loans.values())) {
            if (service.stock.waitingForResident(lease)) { return null; }
            if (sources.containsKey(lease.request().tools())) {
                service.returned(lease);
                loans.remove(lease.request().tools());
                sources.remove(lease.request().tools());
                continue;
            }
            var body = Bodies.of(who.body());
            if (body.isPresent() && !LoanClothing.recover(service, lease, body.get())) { return null; }
            if (service.stock.waitingForResident(lease)) { return null; }
            int slot = service.carriedSlot(who.pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY));
            if (service.stock.waitingForResident(lease)) { return null; }
            if (slot < 0) {
                service.missing(lease);
                loans.remove(lease.request().tools());
                continue;
            }
            var target = service.destination(level, lease, who.pack().getItem(slot));
            if (target.isEmpty()) { return null; }
            return new ToolAction.Return(service, lease, target.get());
        }
        complete = true;
        return null;
    }

    private void resolve(io.github.izakyl.folkways.core.resident.api.Body body) {
        resolved = true;
        if (!CookeryGear.permits(service.colony, body, request)) {
            failure = CookeryRefusal.NOTHING_TO_WORK_WITH;
            workDone = true;
            return;
        }
        boolean blocked = garments.entrySet().stream().anyMatch(entry -> {
            ItemStack existing = service.stock.equipment(body.id(), entry.getKey());
            return !existing.isEmpty() && (!Goods.matches(existing, entry.getValue())
                || !CookeryGear.allowed(service.colony, existing));
        });
        if (blocked) {
            if (request.required()) {
                failure = CookeryRefusal.NOTHING_TO_WORK_WITH;
                workDone = true;
            }
            garments.clear();
        }
        families = garments.entrySet().stream().filter(entry -> (service.stock.equipment(body.id(),
            entry.getKey()).isEmpty() || service.stock.borrowedSlot(body.id(), entry.getKey())))
            .map(Map.Entry::getValue).distinct().sorted(java.util.Comparator.comparing(ItemSpec::describe)).toList();
        service.protect(id(), body.id(), families);
    }

    private List<io.github.izakyl.folkways.core.resident.api.Body> candidates(ServerLevel level) {
        return service.colony.residents().stream().map(resident -> service.stock.body(resident.id()))
            .flatMap(Optional::stream).filter(body -> body.mob().level() == level)
            .filter(body -> borrower == null || borrower.equals(body.id()))
            .filter(body -> original.worker().map(id -> id.equals(body.id())).orElse(true))
            .filter(body -> CookeryGear.permits(service.colony, body, request))
            .sorted(java.util.Comparator.comparingInt((io.github.izakyl.folkways.core.resident.api.Body body)
                -> score(body)).reversed()
                .thenComparing(body -> body.id().toString())).toList();
    }

    private boolean blocked(io.github.izakyl.folkways.core.resident.api.Body body) {
        return garments.entrySet().stream().anyMatch(entry -> {
            ItemStack existing = service.stock.equipment(body.id(), entry.getKey());
            return !existing.isEmpty() && (!Goods.matches(existing, entry.getValue())
                || !CookeryGear.allowed(service.colony, existing));
        });
    }

    private int score(io.github.izakyl.folkways.core.resident.api.Body body) {
        int score = 0;
        for (var entry : garments.entrySet()) {
            ItemStack existing = service.stock.equipment(body.id(), entry.getKey());
            if (Goods.matches(existing, entry.getValue()) && CookeryGear.allowed(service.colony, existing)) {
                score += 1;
            }
            else if (!existing.isEmpty()) { score -= 100; }
        }
        return score;
    }

    private boolean reserveGroup(ServerLevel level, Worker who) {
        Map<ItemSpec, KitchenTools.Lease> group = new LinkedHashMap<>();
        Map<ItemSpec, ToolStock.Source> offers = new LinkedHashMap<>();
        for (ItemSpec family : families) {
            ToolStock.Snapshot stock = service.stock.read(level, family);
            service.pool.request(service.colony.id(), id(), family, stock.inventory());
            var lease = service.pool.reserve(service.colony.id(), id(), family, who.resident().id(), stock.inventory());
            if (lease.isEmpty() || !stock.sources().containsKey(lease.get().tool())) {
                lease.ifPresent(service::returned);
                group.values().forEach(service::returned);
                return false;
            }
            group.put(family, lease.get());
            offers.put(family, stock.sources().get(lease.get().tool()));
        }
        loans.putAll(group);
        sources.putAll(offers);
        return true;
    }

    private boolean clothed(Worker who) {
        return garments.entrySet().stream().allMatch(entry -> {
            KitchenTools.Lease loan = loans.get(entry.getValue());
            if (loan != null && LoanClothing.slot(service, who.body(),
                loan).filter(entry.getKey()::equals).isEmpty()) { return false; }
            return Goods.matches(who.body().getItemBySlot(entry.getKey()), entry.getValue())
                && CookeryGear.allowed(service.colony, who.body().getItemBySlot(entry.getKey()));
        });
    }

    private void completed(ToolAction piece, Ending how) {
        if (piece instanceof ToolAction.Borrow borrow) {
            borrow.ended(how);
            sources.remove(borrow.lease.request().tools());
            if (!borrow.moved) {
                loans.remove(borrow.lease.request().tools());
                failure = how instanceof Ending.Failed failed ? failed.why() : CookeryRefusal.STATION_CHANGED;
                workDone = true;
            }
        } else if (piece instanceof ToolAction.Return returned) {
            if (returned.moved || returned.missing) { loans.remove(returned.lease.request().tools()); }
        }
    }

    public Outcome commit(ServerLevel level, Worker who) {
        if (!complete) { return Outcome.failed(CookeryRefusal.STATION_BUSY); }
        if (failure != null) { return Outcome.failed(failure); }
        return Outcome.done();
    }

    public void ended(ServerLevel level, Ending how) {
        if (ended) {
            return;
        }
        ended = true;
        try {
            original.ended(level, how);
        } finally {
            // Reservations never moved by the body are safe to release immediately.
            for (ItemSpec family : List.copyOf(sources.keySet())) {
                service.returned(loans.get(family));
                loans.remove(family);
                sources.remove(family);
            }
            service.ended(this);
        }
    }

    public void released(ServerLevel level, Worker who, Ending how) {
        releaseOriginal(level, who, how);
        if (!(how instanceof Ending.Done)) {
            ended(level, how);
        }
    }

    private void releaseOriginal(ServerLevel level, Worker who, Ending how) {
        if (!originalReleased) {
            originalReleased = true;
            original.released(level, who, how); }
    }

    private class Action implements WorkAction {
        final WorkAction delegate;
        Action(WorkAction delegate) {
            this.delegate = delegate;
        }

        public ActionSpec spec() { return delegate.spec(); }

        public Outcome commit(ServerLevel level, Worker who) {
            if (!clothed(who) || ClothingWork.this.refusal(level).isPresent()) {
                return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            }
            try {
                return delegate.commit(level, who);
            } finally {
                for (KitchenTools.Lease lease : loans.values()) {
                    ItemStack actual = LoanClothing.held(service, who.body(), lease);
                    if (!actual.isEmpty()) { service.record(lease, actual); }
                }
            }
        }
    }

    private final class StagedAction extends Action implements Staged {
        StagedAction(WorkAction delegate) {
            super(delegate);
        }

        public Drawn draw(ServerLevel level, Worker who) {
            return ((Staged) delegate).draw(level, who);
        }

        public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
            ((Staged) delegate).restore(level, who, goods);
        }
    }
}
