package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
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
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.Unmet;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.work.support.Staged;
import io.github.izakyl.folkways.core.work.support.Tools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class ToolWork implements Node {

    private final TaskTools service;
    private final Node original;
    private final NodeSpec spec;
    private final List<ItemSpec> declared;
    private List<ItemSpec> families;
    private final Map<ItemSpec, KitchenTools.Lease> loans = new LinkedHashMap<>();
    private ServerLevel level;
    private volatile UUID borrower;
    private boolean started;
    private boolean originalReleased;
    private boolean ended;
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
        List<ToolUse> remaining = original.spec().tools().stream().filter(use ->
            Tools.shared(use.need()).isEmpty()).toList();
        spec = copy(original.spec(), original.id(), new Workload.Custom(), remaining, leaves, WorkEffort.NONE);
    }

    @Override
    public boolean producesBeforeSettlement() {
        return !original.spec().gives().isEmpty();
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
    // Replenishment follows the current list while a task waits to borrow.
    List<ItemSpec> families() { return allowed(); }

    boolean needsTools() { return !workDone; }

    private List<ItemSpec> allowed() {
        List<ItemSpec> allowed = new ArrayList<>();
        for (ItemSpec family : declared) {
            Optional<ItemSpec> choice = service.stock.allowed(spec.vocation(), family);
            if (choice.isEmpty()) {
                return List.of();
            }
            allowed.add(choice.get());
        }
        return List.copyOf(allowed);
    }

    @Override
    public Optional<UUID> worker() { return borrower == null ? original.worker() : Optional.of(borrower); }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level) {
        Optional<RefusalKind> refused = original.planned(level);
        if (refused.isPresent()) {
            return refused;
        }
        this.level = level;
        List<ItemSpec> allowed = allowed();
        if (allowed.isEmpty()) {
            return Optional.of(CookeryRefusal.NOTHING_TO_WORK_WITH);
        }
        families = allowed;
        service.started(this);
        return Optional.empty();
    }

    @Override
    public boolean ready(ServerLevel level) {
        // Completed native work only has its physical returns and settlement left.
        if (workDone) { return true; }
        if (!original.ready(level)) {
            return false;
        }
        if (!started && loans.isEmpty()) {
            List<ItemSpec> allowed = allowed();
            if (allowed.isEmpty()) {
                return false;
            }
            families = allowed;
        }
        for (ItemSpec family : families) {
            var state = service.pool.request(service.colony.id(), id(), family, service.stock.read(level,
                family).inventory()).status();
            if (state != KitchenTools.Status.AVAILABLE && state != KitchenTools.Status.BORROWED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (ended) {
            return Optional.of(CookeryRefusal.STATION_CHANGED);
        }
        return workDone ? Optional.empty() :
        original.refusal(level); }

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
    public WorkHolding acquire(HoldingContext context) {
        return new EquipmentHolding(original, () -> started, () -> workDone, this::loanContext,
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

    private HoldingContext loanContext(HoldingContext context) {
        return EquipmentHolding.context(context, new LoanWorker(context.worker(), service, loans),
            operation -> operation instanceof Staged ? new StagedAction(operation) : new Action(operation));
    }

    private ToolAction operation(ServerLevel level, Worker who) {
        if (complete) {
            return null;
        }
        if (borrower != null && !borrower.equals(who.resident().id())) {
            // Never use or return an identical-looking stack from a replacement worker. The original
            // body retains its physical loan for the separate cancellation/reload return recovery.
            failure = CookeryRefusal.STATION_CHANGED;
            complete = true;
            return null;
        }
        if (!started && !workDone && !loans.keySet().containsAll(families) && allowed().isEmpty()) {
            failure = CookeryRefusal.NOTHING_TO_WORK_WITH;
            workDone = true;
        }
        if (!started && !workDone) {
            if (loans.isEmpty()) {
                families = allowed();
            }
            for (ItemSpec family : families) {
                if (loans.containsKey(family)) {
                    continue;
                }
                ToolStock.Snapshot available = service.stock.read(level, family);
                service.pool.request(service.colony.id(), id(), family, available.inventory());
                Optional<KitchenTools.Lease> claim = service.pool.reserve(service.colony.id(), id(), family,
                    who.resident().id(), available.inventory());
                if (claim.isEmpty()) {
                    return null;
                }
                KitchenTools.Lease lease = claim.get();
                ToolStock.Source source = available.sources().get(lease.tool());
                if (source == null) {
                    service.returned(lease);
                    return null;
                }
                borrower = who.resident().id();
                loans.put(family, lease);
                return new ToolAction.Borrow(service, lease, source, spec.vocation());
            }
            started = true;
            service.protect(id(), who.resident().id(), families);
        }
        if (!workDone) {
            return null;
        }
        for (KitchenTools.Lease lease : List.copyOf(loans.values())) {
            if (service.stock.waitingForResident(lease)) {
                return null;
            }
            ItemStack expected = service.tool(lease).orElse(ItemStack.EMPTY);
            int slot = service.carriedSlot(who.pack(), lease, expected);
            if (slot < 0) {
                service.missing(lease);
                loans.remove(lease.request().tools());
                continue;
            }
            ItemStack tool = who.pack().getItem(slot);
            var target = service.destination(level, lease, tool);
            if (target.isEmpty()) {
                return null;
            }
            return new ToolAction.Return(service, lease, target.get());
        }
        complete = true;
        io.github.izakyl.folkways.core.resident.api.TaskTools.release(id());
        return null;
    }

    private void completed(ToolAction piece, Ending how) {
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
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (!complete) {
            return Outcome.failed(CookeryRefusal.STATION_BUSY);
        }
        if (failure != null) {
            return Outcome.failed(failure);
        }
        return Outcome.done();
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        if (ended) {
            return;
        }
        ended = true;
        try {
            original.ended(level, how);
        } finally {
            service.ended(this);
        }
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        releaseOriginal(level, who, how);
        if (!(how instanceof Ending.Done)) {
            ended(level, how);
        }
    }

    private void releaseOriginal(ServerLevel level, Worker who, Ending how) {
        if (!originalReleased) {
            originalReleased = true;
            original.released(level, new LoanWorker(who, service, loans), how);
        }
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) { return original.failing(level, why); }

    @Override
    public Unmet unmet(UUID before, Ending how) { return original.unmet(before, how); }

    private boolean equipped(WorkAction action, Worker who) {
        Worker actual = new LoanWorker(who, service, loans);
        return action.spec().tools().stream().allMatch(use -> use.need() instanceof
            io.github.izakyl.folkways.core.work.api.ToolNeed.None
            || !actual.held(use.need()).isEmpty());
    }

    private class Action implements WorkAction {
        final WorkAction delegate;

        Action(WorkAction delegate) {
            this.delegate = delegate;
        }

        public ActionSpec spec() { return delegate.spec(); }

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
                        service.record(lease, tool, who.pack());
                    }
                });
            }
        }
    }

    private final class StagedAction extends Action implements Staged {
        StagedAction(WorkAction delegate) { super(delegate); }

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
