package io.github.izakyl.folkways.plugins.cookery.tools;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Unmet;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class ToolWork implements Node, Workload.Within {

    private final TaskTools service;
    private final Node original;
    private final NodeSpec spec;
    private final List<ItemSpec> declared;
    private List<ItemSpec> families;
    private final Map<ItemSpec, KitchenTools.Lease> loans = new LinkedHashMap<>();
    private ServerLevel level;
    private volatile UUID borrower;
    private boolean started;
    private boolean actionIssued;
    private boolean workDone;
    private boolean complete;
    private RefusalKind failure;

    ToolWork(TaskTools service, Node original) {
        this.service = service;
        this.original = original;
        declared = original.spec().tools().stream().map(ToolUse::need).map(Tools::shared)
            .flatMap(Optional::stream).distinct().sorted(java.util.Comparator.comparing(ItemSpec::describe)).toList();
        families = declared;
        List<Amount> leaves = new ArrayList<>(original.spec().leaves());
        leaves.addAll(Leaves.room(declared.size()));
        List<ToolUse> remaining = original.spec().tools().stream().filter(use -> Tools.shared(use.need()).isEmpty()).toList();
        spec = copy(original.spec(), original.id(), this, remaining, leaves, WorkEffort.NONE);
    }

    @Override
    public NodeSpec spec() { return spec; }

    @Override
    public List<Amount> planningLeaves(List<Amount> promised) {
        // Preserve the wrapped node's contract; the shared tool still needs its own temporary pack cell.
        List<Amount> leaves = new ArrayList<>(original.planningLeaves(promised));
        leaves.addAll(Leaves.room(declared.size()));
        return Leaves.merged(leaves);
    }

    ServerLevel level() { return level; }
    List<ItemSpec> families() { return families; }
    boolean needsTools() { return !workDone; }

    @Override
    public Optional<UUID> worker() { return borrower == null ? original.worker() : Optional.of(borrower); }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level) {
        Optional<RefusalKind> refused = original.planned(level);
        if (refused.isPresent()) {
            return refused;
        }
        this.level = level;
        List<ItemSpec> allowed = new ArrayList<>();
        for (ItemSpec family : declared) {
            Optional<ItemSpec> choice = service.stock.allowed(family);
            if (choice.isEmpty()) {
                return Optional.of(CookeryRefusal.NOTHING_TO_WORK_WITH);
            }
            allowed.add(choice.get());
        }
        families = List.copyOf(allowed);
        service.started(this);
        return Optional.empty();
    }

    @Override
    public boolean ready(ServerLevel level) {
        if (!original.ready(level)) {
            return false;
        }
        for (ItemSpec family : families) {
            var state = service.pool.request(service.colony.id(), id(), family, service.stock.read(level, family).inventory()).status();
            if (state != KitchenTools.Status.AVAILABLE && state != KitchenTools.Status.BORROWED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) { return workDone ? Optional.empty() : original.refusal(level); }

    @Override
    public boolean acceptsInput(Stash from) { return original.acceptsInput(from); }

    @Override
    public boolean replannable() { return original.replannable(); }

    @Override
    public boolean settled(ServerLevel level) { return original.settled(level); }

    @Override
    public void changed(ServerLevel level, NodeState state) { original.changed(level, state); }

    @Override
    public boolean permitsProduction(ItemSpec goods) { return original.permitsProduction(goods); }

    @Override
    public Turn next(ServerLevel level, Worker who) {
        if (complete) {
            return LEAVE;
        }
        if (borrower != null && !borrower.equals(who.resident().id())) {
            // Never use or return an identical-looking stack from a replacement worker. The original
            // body retains its physical loan for the separate cancellation/reload return recovery.
            failure = CookeryRefusal.STATION_CHANGED;
            complete = true;
            return LEAVE;
        }
        if (!started) {
            for (ItemSpec family : families) {
                if (loans.containsKey(family)) {
                    continue;
                }
                ToolStock.Snapshot available = service.stock.read(level, family);
                service.pool.request(service.colony.id(), id(), family, available.inventory());
                Optional<KitchenTools.Lease> claim = service.pool.reserve(service.colony.id(), id(), family,
                    who.resident().id(), available.inventory());
                if (claim.isEmpty()) {
                    return WAIT;
                }
                KitchenTools.Lease lease = claim.get();
                ToolStock.Source source = available.sources().get(lease.tool());
                if (source == null) {
                    service.returned(lease);
                    return WAIT;
                }
                borrower = who.resident().id();
                loans.put(family, lease);
                return new Turn.Piece(new ToolAction.Borrow(service, lease, source));
            }
            started = true;
            service.protect(id(), who.resident().id(), families);
        }
        if (!workDone) {
            if (original.spec().workload() instanceof Within within) {
                Turn turn = within.next(level, new LoanWorker(who, service, loans));
                if (turn instanceof Turn.Piece piece) {
                    if (!equipped(piece.node(), who)) {
                        failure = CookeryRefusal.NOTHING_TO_WORK_WITH;
                        workDone = true;
                    } else {
                        return new Turn.Piece(action(piece.node()));
                    }
                } else if (turn instanceof Turn.Wait) {
                    return WAIT;
                } else {
                    workDone = true;
                }
            } else if (original.spec().workload() instanceof Workload.Once || original.spec().workload() instanceof Workload.Continuous) {
                if (!equipped(original, who)) {
                    failure = CookeryRefusal.NOTHING_TO_WORK_WITH;
                    workDone = true;
                } else {
                    return new Turn.Piece(action(original));
                }
            } else {
                failure = CookeryRefusal.STATION_CHANGED;
                workDone = true;
            }
        }
        for (KitchenTools.Lease lease : List.copyOf(loans.values())) {
            ItemStack expected = service.tool(lease).orElse(ItemStack.EMPTY);
            int slot = service.carriedSlot(who.pack(), lease, expected);
            if (slot < 0) {
                service.lost(lease);
                loans.remove(lease.request().tools());
                continue;
            }
            ItemStack tool = who.pack().getItem(slot);
            var target = service.destination(level, lease, tool);
            if (target.isEmpty()) {
                return WAIT;
            }
            return new Turn.Piece(new ToolAction.Return(service, lease, target.get()));
        }
        complete = true;
        io.github.izakyl.folkways.core.api.resident.body.TaskTools.release(id());
        return LEAVE;
    }

    @Override
    public void ended(ServerLevel level, Node piece, Ending how) {
        if (piece instanceof ToolAction.Borrow borrow) {
            borrow.ended(how);
            if (!borrow.moved) {
                loans.remove(borrow.lease.request().tools());
            }
            return;
        }
        if (piece instanceof ToolAction.Return returning) {
            if (returning.moved || returning.missing) {
                loans.remove(returning.lease.request().tools());
            }
            return;
        }
        if (piece instanceof Action action) {
            if (original.spec().workload() instanceof Within within) {
                within.ended(level, action.delegate, how);
            } else if (how == Ending.DONE) {
                workDone = true;
            }
            if (how instanceof Ending.Failed failed) {
                failure = failed.why();
                workDone = true;
            }
        }
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (!complete) {
            return Outcome.failed(CookeryRefusal.STATION_BUSY);
        }
        if (failure != null) {
            return Outcome.failed(failure);
        }
        return original.spec().workload() instanceof Within ? original.commit(level, who) : Outcome.done();
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        try {
            original.ended(level, how);
        } finally {
            service.ended(this);
        }
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        // Once/Continuous are already released as the real inner action. A Within owner still has its
        // own outer execution to release, separate from each original inner piece.
        if (original.spec().workload() instanceof Within || !actionIssued) {
            original.released(level, who, how);
        }
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) { return original.failing(level, why); }

    @Override
    public Unmet unmet(UUID before, Ending how) { return original.unmet(before, how); }

    private boolean equipped(Node action, Worker who) {
        Worker actual = new LoanWorker(who, service, loans);
        return action.spec().tools().stream().allMatch(use -> use.need() instanceof io.github.izakyl.folkways.core.api.work.ToolNeed.None
            || !actual.held(use.need()).isEmpty());
    }

    private Node action(Node node) {
        if (node == original) {
            actionIssued = true;
        }
        return node instanceof Staged ? new StagedAction(node) : new Action(node);
    }

    private class Action implements Node {
        final Node delegate;
        private final NodeSpec action;

        Action(Node delegate) {
            this.delegate = delegate;
            Workload workload = delegate.spec().workload() instanceof Workload.Continuous continuous
                ? (Workload.Continuous) (level, who) -> continuous.holds(level, new LoanWorker(who, service, loans))
                : delegate.spec().workload();
            action = copy(delegate.spec(), UUID.randomUUID(), workload,
                delegate.spec().tools(), delegate.spec().leaves(), delegate.spec().effort());
        }

        public NodeSpec spec() { return action; }
        public Optional<RefusalKind> planned(ServerLevel level) {
            return delegate == original ? Optional.empty() : delegate.planned(level);
        }
        public boolean acceptsInput(Stash from) { return delegate.acceptsInput(from); }
        public boolean ready(ServerLevel level) { return delegate.ready(level); }
        public boolean settled(ServerLevel level) { return delegate.settled(level); }
        public Optional<RefusalKind> refusal(ServerLevel level) { return delegate.refusal(level); }
        public void released(ServerLevel level, Worker who, Ending how) {
            delegate.released(level, new LoanWorker(who, service, loans), how);
        }
        public void ended(ServerLevel level, Ending how) {
            if (delegate != original) {
                delegate.ended(level, how);
            }
        }
        public void changed(ServerLevel level, NodeState state) { delegate.changed(level, state); }

        public Outcome commit(ServerLevel level, Worker who) {
            if (!equipped(delegate, who)) {
                return Outcome.failed(CookeryRefusal.NOTHING_TO_WORK_WITH);
            }
            Map<KitchenTools.Lease, ItemStack> held = new LinkedHashMap<>();
            for (KitchenTools.Lease lease : loans.values()) {
                int slot = service.carriedSlot(who.pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY));
                if (slot >= 0) {
                    held.put(lease, who.pack().getItem(slot));
                }
            }
            try {
                return delegate.commit(level, new LoanWorker(who, service, loans));
            } finally {
                held.forEach((lease, tool) -> {
                    if (tool.isEmpty()) {
                        service.lost(lease);
                        loans.remove(lease.request().tools());
                    } else {
                        service.record(lease, tool);
                    }
                });
            }
        }
    }

    private final class StagedAction extends Action implements Staged {
        StagedAction(Node delegate) { super(delegate); }

        public Drawn draw(ServerLevel level, Worker who) {
            return ((Staged) delegate).draw(level, new LoanWorker(who, service, loans));
        }
        public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
            ((Staged) delegate).restore(level, new LoanWorker(who, service, loans), goods);
        }
    }

    static NodeSpec copy(NodeSpec from, UUID id, Workload workload, List<ToolUse> tools,
                         List<Amount> leaves, WorkEffort effort) {
        return new NodeSpec(id, from.owner(), from.site(), from.stances(), from.needs(), from.gives(), leaves,
            tools, workload, effort, from.pace(), from.vocation(), from.gesture(), from.focus(),
            from.estimate(), from.doing(), from.about(), from.subject());
    }
}
