package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.gear.CookeryGear;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Unmet;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.batch.BatchAction;
import io.github.izakyl.folkways.core.api.work.batch.BatchCommit;
import io.github.izakyl.folkways.core.api.work.batch.BatchSession;
import io.github.izakyl.folkways.core.api.work.batch.BatchSpec;
import io.github.izakyl.folkways.core.api.work.batch.InventoryTransfer;
import io.github.izakyl.folkways.core.api.work.batch.StepReceipt;
import io.github.izakyl.folkways.core.api.work.batch.StepResult;
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
class ClothingWork implements Node, Workload.Within {
    private final TaskTools service;
    protected final Node original;
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
    private boolean actionIssued;
    protected boolean workDone;
    protected boolean complete;
    protected RefusalKind failure;

    static ClothingWork of(TaskTools service, Node original, CookeryGear.Request request) {
        return original.spec().workload() instanceof BatchSession
            ? new Batched(service, original, request) : new ClothingWork(service, original, request);
    }

    ClothingWork(TaskTools service, Node original, CookeryGear.Request request) {
        this.service = service;
        this.original = original;
        this.request = request;
        garments.putAll(request.slots());
        NodeSpec from = original.spec();
        var candidate = from.site().where().level(service.server).flatMap(where -> candidates(where).stream().findFirst());
        candidate.ifPresent(body -> borrower = body.id());
        loanRoom = candidate.map(body -> blocked(body) && !request.required() ? 0 : (int) garments.entrySet().stream()
            .filter(entry -> body.mob().getItemBySlot(entry.getKey()).isEmpty() || LoanClothing.borrowedSlot(body.mob(), entry.getKey()))
            .count()).orElse(garments.size());
        List<Amount> leaves = new ArrayList<>(from.leaves());
        leaves.addAll(Leaves.room(loanRoom));
        // The real inner action retains its own worksite. Borrow before travelling into the water.
        spec = new NodeSpec(from.id(), from.owner(), from.site(), Stances.WHEREVER, from.needs(), from.gives(),
            leaves, from.tools(), this, WorkEffort.NONE, from.pace(), from.vocation(), from.gesture(),
            from.focus(), from.estimate(), from.doing(), from.about(), from.subject());
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
        if (candidates.isEmpty()) return Optional.of(CookeryRefusal.NOTHING_TO_WORK_WITH);
        var chosen = candidates.getFirst();
        borrower = chosen.id();
        resolve(chosen);
        if (failure != null || families.size() > loanRoom) {
            io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(id());
            return Optional.of(failure != null ? failure : CookeryRefusal.STATION_CHANGED);
        }
        var refused = original.planned(level);
        if (refused.isPresent()) {
            io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(id());
            return refused;
        }
        service.started(this);
        return Optional.empty();
    }

    // Adopted demand can bootstrap one shared piece while the resident stays free to make it.
    public boolean ready(ServerLevel level) {
        if (workDone) return true;
        if (!original.ready(level)) return false;
        for (ItemSpec family : families) {
            var status = service.pool.request(service.colony.id(), id(), family, service.stock.read(level, family).inventory()).status();
            if (status != KitchenTools.Status.AVAILABLE && status != KitchenTools.Status.BORROWED) return false;
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
        if (workDone) return Optional.empty();
        if (borrower != null && service.stock.body(borrower)
                .filter(body -> CookeryGear.permits(service.colony, body, request)).isEmpty()) {
            return Optional.of(CookeryRefusal.NOTHING_TO_WORK_WITH);
        }
        return original.refusal(level);
    }

    public Turn next(ServerLevel level, Worker who) {
        if (complete) return LEAVE;
        if (borrower != null && !borrower.equals(who.resident().id())) {
            failure = CookeryRefusal.STATION_CHANGED;
            complete = true;
            return LEAVE;
        }
        borrower = who.resident().id();
        if (!resolved) Bodies.of(who.body()).ifPresent(this::resolve);
        if (!workDone && !dressed) {
            if (loans.isEmpty() && !reserveGroup(level, who)) return WAIT;
            for (ItemSpec family : families) {
                ToolStock.Source source = sources.get(family);
                if (source != null) return new Turn.Piece(new ToolAction.Borrow(service, loans.get(family), source));
            }
            Map<EquipmentSlot, KitchenTools.Lease> slots = new LinkedHashMap<>();
            garments.forEach((slot, family) -> {
                if (loans.containsKey(family)) slots.put(slot, loans.get(family));
            });
            if (!LoanClothing.equip(service, who, slots) || !clothed(who)) {
                failure = CookeryRefusal.NOTHING_TO_WORK_WITH;
                workDone = true;
            }
            dressed = true;
        }
        if (!workDone) {
            if (original.spec().workload() instanceof Within within) {
                Turn turn = delegatedTurn(level, who, within);
                if (turn instanceof Turn.Piece piece) return new Turn.Piece(action(piece.node()));
                if (turn instanceof Turn.Wait) return WAIT;
                workDone = true;
            } else if (original.spec().workload() instanceof Workload.Once
                    || original.spec().workload() instanceof Workload.Continuous) {
                actionIssued = true;
                return new Turn.Piece(new Action(original));
            } else {
                failure = CookeryRefusal.STATION_CHANGED;
                workDone = true;
            }
        }
        for (KitchenTools.Lease lease : List.copyOf(loans.values())) {
            if (sources.containsKey(lease.request().tools())) {
                service.returned(lease);
                loans.remove(lease.request().tools());
                sources.remove(lease.request().tools());
                continue;
            }
            var body = Bodies.of(who.body());
            if (body.isPresent() && !LoanClothing.recover(service, lease, body.get())) return WAIT;
            int slot = service.carriedSlot(who.pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY));
            if (slot < 0) {
                service.lost(lease);
                loans.remove(lease.request().tools());
                continue;
            }
            var target = service.destination(level, lease, who.pack().getItem(slot));
            if (target.isEmpty()) return WAIT;
            return new Turn.Piece(new ToolAction.Return(service, lease, target.get()));
        }
        complete = true;
        return LEAVE;
    }

    protected Turn delegatedTurn(ServerLevel level, Worker who, Within within) {
        return within.next(level, who);
    }

    private void resolve(io.github.izakyl.folkways.core.api.resident.body.Body body) {
        resolved = true;
        if (!CookeryGear.permits(service.colony, body, request)) {
            failure = CookeryRefusal.NOTHING_TO_WORK_WITH;
            workDone = true;
            return;
        }
        boolean blocked = garments.entrySet().stream().anyMatch(entry -> {
            ItemStack existing = body.mob().getItemBySlot(entry.getKey());
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
        families = garments.entrySet().stream().filter(entry -> (body.mob().getItemBySlot(entry.getKey()).isEmpty() || LoanClothing.borrowedSlot(body.mob(), entry.getKey())))
            .map(Map.Entry::getValue).distinct().sorted(java.util.Comparator.comparing(ItemSpec::describe)).toList();
        service.protect(id(), body.id(), families);
    }

    private List<io.github.izakyl.folkways.core.api.resident.body.Body> candidates(ServerLevel level) {
        return service.colony.residents().stream().map(resident -> service.stock.body(resident.id()))
            .flatMap(Optional::stream).filter(body -> body.mob().level() == level)
            .filter(body -> borrower == null || borrower.equals(body.id()))
            .filter(body -> original.worker().map(id -> id.equals(body.id())).orElse(true))
            .filter(body -> CookeryGear.permits(service.colony, body, request))
            .sorted(java.util.Comparator.comparingInt((io.github.izakyl.folkways.core.api.resident.body.Body body) -> score(body)).reversed()
                .thenComparing(body -> body.id().toString())).toList();
    }

    private boolean blocked(io.github.izakyl.folkways.core.api.resident.body.Body body) {
        return garments.entrySet().stream().anyMatch(entry -> {
            ItemStack existing = body.mob().getItemBySlot(entry.getKey());
            return !existing.isEmpty() && (!Goods.matches(existing, entry.getValue())
                || !CookeryGear.allowed(service.colony, existing));
        });
    }

    private int score(io.github.izakyl.folkways.core.api.resident.body.Body body) {
        int score = 0;
        for (var entry : garments.entrySet()) {
            ItemStack existing = body.mob().getItemBySlot(entry.getKey());
            if (Goods.matches(existing, entry.getValue()) && CookeryGear.allowed(service.colony, existing)) score += 1;
            else if (!existing.isEmpty()) score -= 100;
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
            if (loan != null && LoanClothing.slot(service, who.body(), loan).filter(entry.getKey()::equals).isEmpty()) return false;
            return Goods.matches(who.body().getItemBySlot(entry.getKey()), entry.getValue())
                && CookeryGear.allowed(service.colony, who.body().getItemBySlot(entry.getKey()));
        });
    }

    public void ended(ServerLevel level, Node piece, Ending how) {
        if (piece instanceof ToolAction.Borrow borrow) {
            borrow.ended(how);
            sources.remove(borrow.lease.request().tools());
            if (!borrow.moved) {
                loans.remove(borrow.lease.request().tools());
                failure = how instanceof Ending.Failed failed ? failed.why() : CookeryRefusal.STATION_CHANGED;
                workDone = true;
            }
        } else if (piece instanceof ToolAction.Return returned) {
            if (returned.moved || returned.missing) loans.remove(returned.lease.request().tools());
        } else if (piece instanceof Action action) {
            if (original.spec().workload() instanceof Within within) within.ended(level, action.delegate, how);
            else if (how == Ending.DONE) workDone = true;
            if (how instanceof Ending.Failed failed) {
                failure = failed.why();
                workDone = true;
            }
        }
    }

    public Outcome commit(ServerLevel level, Worker who) {
        if (!complete) return Outcome.failed(CookeryRefusal.STATION_BUSY);
        if (failure != null) return Outcome.failed(failure);
        return original.spec().workload() instanceof Within ? original.commit(level, who) : Outcome.done();
    }

    public void ended(ServerLevel level, Ending how) {
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
        if (original.spec().workload() instanceof Within || !actionIssued) original.released(level, who, how);
    }

    private Node action(Node node) {
        if (node instanceof InventoryTransfer) return new TransferAction(node);
        if (node instanceof Staged) return node instanceof BatchAction ? new StagedAcceptedAction(node) : new StagedAction(node);
        return node instanceof BatchAction ? new AcceptedAction(node) : new Action(node);
    }

    private class Action implements Node {
        private final Node delegate;
        private final NodeSpec spec;
        Action(Node delegate) {
            this.delegate = delegate;
            spec = ToolWork.copy(delegate.spec(), UUID.randomUUID(), delegate.spec().workload(),
                delegate.spec().tools(), delegate.spec().leaves(), delegate.spec().effort());
        }
        public NodeSpec spec() { return spec; }
        public Optional<RefusalKind> planned(ServerLevel level) { return delegate == original ? Optional.empty() : delegate.planned(level); }
        public boolean ready(ServerLevel level) { return delegate.ready(level); }
        public Optional<RefusalKind> refusal(ServerLevel level) {
            var refused = ClothingWork.this.refusal(level);
            return refused.isPresent() ? refused : delegate.refusal(level);
        }
        public boolean acceptsInput(Stash from) { return delegate.acceptsInput(from); }
        public void released(ServerLevel level, Worker who, Ending how) { delegate.released(level, who, how); }
        public void ended(ServerLevel level, Ending how) { if (delegate != original) delegate.ended(level, how); }
        public void changed(ServerLevel level, NodeState state) { delegate.changed(level, state); }
        public boolean settled(ServerLevel level) { return delegate.settled(level); }
        public Outcome commit(ServerLevel level, Worker who) {
            return executeDelegate(level, who).outcome();
        }
        BatchCommit executeDelegate(ServerLevel level, Worker who) {
            if (!clothed(who) || refusal(level).isPresent()) return BatchCommit.of(Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH));
            try {
                return delegate instanceof BatchAction action ? action.execute(level, who)
                    : BatchCommit.of(delegate.commit(level, who));
            } finally {
                for (KitchenTools.Lease lease : loans.values()) {
                    ItemStack actual = LoanClothing.held(service, who.body(), lease);
                    if (!actual.isEmpty()) service.record(lease, actual);
                }
            }
        }
    }

    private final class AcceptedAction extends Action implements BatchAction {
        AcceptedAction(Node delegate) { super(delegate); }
        public BatchCommit execute(ServerLevel level, Worker who) { return executeDelegate(level, who); }
    }

    private final class TransferAction extends Action implements InventoryTransfer {
        TransferAction(Node delegate) { super(delegate); }
    }

    private class StagedAction extends Action implements Staged {
        private final Staged staged;
        StagedAction(Node delegate) { super(delegate); staged = (Staged) delegate; }
        public Drawn draw(ServerLevel level, Worker who) { return staged.draw(level, who); }
        public void restore(ServerLevel level, Worker who, List<ItemStack> goods) { staged.restore(level, who, goods); }
    }

    private final class StagedAcceptedAction extends StagedAction implements BatchAction {
        StagedAcceptedAction(Node delegate) { super(delegate); }
        public BatchCommit execute(ServerLevel level, Worker who) { return executeDelegate(level, who); }
    }

    private static final class Batched extends ClothingWork implements BatchSession {
        private StepResult delegated;
        private final BatchSession batch;

        Batched(TaskTools service, Node original, CookeryGear.Request request) {
            super(service, original, request);
            batch = (BatchSession) original.spec().workload();
        }
        public BatchSpec batch() { return batch.batch(); }
        public boolean drainsAfterFailure() { return true; }

        @Override
        protected Turn delegatedTurn(ServerLevel level, Worker who, Within within) {
            delegated = batch.advance(level, who);
            return switch (delegated) {
                case StepResult.Step step -> new Turn.Piece(step.node());
                case StepResult.Auxiliary auxiliary -> new Turn.Piece(auxiliary.node());
                case StepResult.Wait ignored -> WAIT;
                case StepResult.Pause ignored -> WAIT;
                case StepResult.Complete ignored -> LEAVE;
                case StepResult.Failed failed -> { failure = failed.why(); yield LEAVE; }
            };
        }
        public StepResult advance(ServerLevel level, Worker who) {
            delegated = null;
            Turn turn = super.next(level, who);
            if (turn instanceof Turn.Piece piece) {
                return delegated instanceof StepResult.Step step ? new StepResult.Step(step.key(), piece.node())
                    : new StepResult.Auxiliary(piece.node());
            }
            if (turn instanceof Turn.Wait) {
                return delegated instanceof StepResult.Pause pause ? pause
                    : delegated instanceof StepResult.Wait wait ? wait : new StepResult.Wait(10);
            }
            return new StepResult.Complete();
        }
        public void settled(ServerLevel level, StepReceipt receipt) {
            batch.settled(level, receipt);
            receipt.stop().ifPresent(why -> { failure = why; workDone = true; });
        }
        public Outcome finish(ServerLevel level, Worker who) {
            if (!complete) return Outcome.failed(CookeryRefusal.STATION_BUSY);
            return failure == null ? batch.finish(level, who) : Outcome.failed(failure);
        }
    }
}
