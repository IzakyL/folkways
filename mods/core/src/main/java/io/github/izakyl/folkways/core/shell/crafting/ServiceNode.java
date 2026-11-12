package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.ActionSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.Settlement;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkBatch;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.Staged;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Binds the original workshop action without changing its craft licence, tools or holding implementation. */
final class ServiceNode implements Node, Staged {

    private final Node delegate;
    private final UUID worker;
    private final boolean colonySupplied;
    private final boolean committed;
    private final Runnable checkpoint;

    private ServiceNode(Node delegate, UUID worker, boolean committed, Runnable checkpoint) {
        this.delegate = delegate;
        this.worker = worker;
        this.committed = committed;
        this.checkpoint = checkpoint;
        colonySupplied = delegate.recipeInputs().isEmpty() && !delegate.spec().needs().isEmpty();
    }

    static Node bind(Node node, UUID worker) {
        return bind(node, worker, false, () -> { });
    }

    static Node bind(Node node, UUID worker, boolean committed, Runnable checkpoint) {
        return new ServiceNode(node, worker, committed, checkpoint);
    }

    @Override
    public NodeSpec spec() {
        NodeSpec spec = delegate.spec();
        if (committed) {
            return new NodeSpec(spec.id(), spec.owner(), spec.site(), spec.stances(), List.of(), List.of(), List.of(),
                List.of(), io.github.izakyl.folkways.core.work.api.Workload.Once.of(1),
                new io.github.izakyl.folkways.core.work.api.WorkEffort.Total(0), spec.pace(), spec.vocation(),
                spec.gesture(), spec.focus(), 1, spec.doing(), spec.about(), spec.subject());
        }
        if (colonySupplied) {
            return spec;
        }
        var leaves = new ArrayList<>(spec.leaves());
        leaves.addAll(spec.gives());
        spec.needs().forEach(need -> leaves.add(new Amount(need.spec(), -need.count())));
        return new NodeSpec(spec.id(), spec.owner(), spec.site(), spec.stances(), List.of(), List.of(), leaves,
            spec.tools(), spec.workload(), spec.effort(), spec.pace(), spec.vocation(), spec.gesture(), spec.focus(),
            spec.estimate(), spec.doing(), spec.about(), spec.subject());
    }

    @Override
    public Optional<UUID> worker() {
        return Optional.of(worker);
    }

    @Override
    public boolean acceptsInput(Stash from) {
        return colonySupplied && delegate.acceptsInput(from);
    }

    @Override
    public boolean permitsProduction(ItemSpec input) {
        // Network stock is offered through a depot workshop, so operating supplies must retain that route.
        return colonySupplied && delegate.permitsProduction(input);
    }

    @Override
    public boolean ready(ServerLevel level) {
        var entity = level.getEntity(worker);
        return entity != null && Bodies.of(entity)
            .filter(NetworkBuffers::enabled).isPresent()
            && (committed ? delegate.settled(level) : delegate.ready(level));
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return committed ? new Outcome.Done(List.of(), List.of(), Optional.empty()) : delegate.commit(level, who);
    }

    @Override
    public WorkHolding acquire(HoldingContext context) {
        if (committed) {
            return context.holdings().standard(context, this);
        }
        return colonySupplied ? delegate.acquire(context) : new ServiceHolding(delegate.acquire(supplied(context)));
    }

    @Override
    public boolean arrived(ServerLevel level, Body body, Stances positions, boolean atStance) {
        return delegate.arrived(level, body, positions, atStance);
    }

    @Override
    public List<Amount> planningLeaves(List<Amount> promised) {
        return colonySupplied ? delegate.planningLeaves(promised) : spec().leaves();
    }

    @Override
    public boolean producesBeforeSettlement() {
        return delegate.producesBeforeSettlement();
    }

    @Override
    public boolean mountedWork() {
        return delegate.mountedWork();
    }

    @Override
    public boolean directTravel() {
        return delegate.directTravel();
    }

    @Override
    public boolean staysAtWorkSite() {
        return delegate.staysAtWorkSite();
    }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level) {
        return delegate.planned(level);
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        return delegate.refusal(level);
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return Recourse.waitFor(40);
    }

    @Override
    public Optional<WorkBatch> batch(Body body) {
        return delegate.batch(body);
    }

    @Override
    public boolean settled(ServerLevel level) {
        return delegate.settled(level);
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        delegate.released(level, who, how);
    }

    @Override
    public void changed(ServerLevel level, NodeState state) {
        if (state == NodeState.SETTLING) {
            checkpoint.run();
        }
        delegate.changed(level, state);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        delegate.ended(level, how);
    }

    @Override
    public Drawn draw(ServerLevel level, Worker who) {
        return committed ? new Taken(List.of()) : new PackInputs(delegate).draw(level, who);
    }

    @Override
    public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
        new PackInputs(delegate).restore(level, who, goods);
    }

    private static HoldingContext supplied(HoldingContext context) {
        Settlement settlement = new Settlement() {
            @Override
            public Outcome commit(WorkAction action) {
                return context.settlement().commit(new PackInputs(action));
            }

            @Override
            public void stow(List<ItemStack> made) {
                context.settlement().stow(made);
            }
        };
        return new HoldingContext(context.level(), context.body(), context.worker(), context.actions(), settlement,
            context.holdings(), context.joining());
    }

    // A workshop may run its original node or private actions inside a custom holding. Both settle through
    // this context so their hidden planning needs still consume the network inputs from the worker's pack.
    private record ServiceHolding(WorkHolding delegate) implements WorkHolding {
        @Override
        public Optional<String> debugStage() {
            return delegate.debugStage();
        }

        @Override
        public HoldingStep tick(HoldingContext context) {
            return delegate.tick(supplied(context));
        }

        @Override
        public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
            delegate.requestSoftPreempt(supplied(context), request);
        }

        @Override
        public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
            delegate.hardRelease(supplied(context), result, reason);
        }

        @Override
        public void suspended(HoldingContext context, PreemptReason reason) {
            delegate.suspended(supplied(context), reason);
        }

        @Override
        public void resumed(HoldingContext context) {
            delegate.resumed(supplied(context));
        }
    }

    private record PackInputs(WorkAction delegate) implements WorkAction, Staged {
        @Override
        public ActionSpec spec() {
            return delegate.spec();
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            return delegate.commit(level, who);
        }

        @Override
        public Drawn draw(ServerLevel level, Worker who) {
            if (delegate instanceof Staged staged) {
                return staged.draw(level, who);
            }
            List<ItemStack> goods = new ArrayList<>();
            for (var need : delegate.spec().needs()) {
                long left = need.count();
                while (left > 0) {
                    ItemStack taken = Containers.extract(who.pack(), stack -> Goods.matches(stack, need.spec()),
                        (int) Math.min(left, Integer.MAX_VALUE));
                    if (taken.isEmpty()) {
                        restore(level, who, goods);
                        return new Short(LaborRefusal.GOODS_GONE);
                    }
                    goods.add(taken);
                    left -= taken.getCount();
                }
            }
            return new Taken(goods);
        }

        @Override
        public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
            if (delegate instanceof Staged staged) {
                staged.restore(level, who, goods);
                return;
            }
            for (ItemStack stack : goods) {
                ItemStack left = Containers.insert(who.pack(), stack.copy());
                if (!left.isEmpty()) {
                    who.spill(left);
                }
            }
        }
    }
}
