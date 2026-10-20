package io.github.izakyl.folkways.core.engine.batch;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.batch.BatchSession;
import io.github.izakyl.folkways.core.api.work.batch.BatchAction;
import io.github.izakyl.folkways.core.api.work.batch.InventoryTransfer;
import io.github.izakyl.folkways.core.api.work.batch.BatchSpec;
import io.github.izakyl.folkways.core.api.work.batch.StepReceipt;
import io.github.izakyl.folkways.core.api.work.batch.StepResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Server-thread bookkeeping only. The ordinary runner, inventory transactions and planner still do the work.
public final class BatchExecution {
    private final Node parent;
    private final BatchSession session;
    private final BatchSpec limits;
    private final boolean drainsAfterFailure;
    private final List<Need> needs;
    private final long[] remaining;
    public record Debit(Placement.Draw source, ItemStack stack, Optional<WorldPos> store, Optional<UUID> resident) { }
    private record Spent(Placement.Draw source, ItemSpec concrete) { }
    private final Map<Spent, Long> spent = new LinkedHashMap<>();
    public record Output(Optional<WorldPos> store, Optional<UUID> resident, ItemSpec goods, long count) { }
    private record OutputKey(Optional<WorldPos> store, Optional<UUID> resident, ItemSpec goods) { }
    private final Map<OutputKey, Long> outputs = new LinkedHashMap<>();
    private final Set<UUID> attempts = new HashSet<>();
    private final Set<String> committed = new HashSet<>();
    private StepResult.Step active;
    private Node auxiliary;
    private StepReceipt pending;
    private RefusalKind failure;
    private int continuous;
    private long nextPoll;

    public BatchExecution(Node parent) {
        this.parent = parent;
        this.session = (BatchSession) parent.spec().workload();
        this.limits = session.batch();
        this.drainsAfterFailure = session.drainsAfterFailure();
        this.needs = parent.spec().needs();
        this.remaining = needs.stream().mapToLong(Need::count).toArray();
        if (needs.stream().anyMatch(need -> need.count() < 0)) {
            throw new IllegalArgumentException("batch input budgets cannot be negative");
        }
    }

    public UUID parent() { return parent.id(); }

    public boolean hasCommittedSteps() { return !committed.isEmpty(); }

    public void produced(List<Output> placed) {
        for (Output output : placed) {
            if (output.count() > 0) outputs.merge(new OutputKey(output.store(), output.resident(), output.goods()),
                output.count(), Math::addExact);
        }
    }

    public List<ReservedInput> outputHolds() {
        return outputs.entrySet().stream().map(entry -> new ReservedInput(parent.id(), entry.getKey().store(),
            entry.getKey().resident(), entry.getKey().goods(), entry.getValue())).toList();
    }

    public StepResult next(ServerLevel level, Worker who) {
        if (failure != null) return new StepResult.Failed(failure);
        if (active != null || auxiliary != null) throw new IllegalStateException("a batch already has an active attempt");
        long now = level.getGameTime();
        if (continuous >= limits.maxContinuousSteps()) {
            continuous = 0;
            nextPoll = now + 1;
            return new StepResult.Pause(1);
        }
        if (now < nextPoll) return new StepResult.Wait((int) Math.min(Integer.MAX_VALUE, nextPoll - now));
        StepResult result = session.advance(level, who);
        if (result instanceof StepResult.Step step) {
            if (step.node().spec().workload() instanceof Workload.Within) {
                throw new IllegalArgumentException("nested batches must be flattened into ordinary steps");
            }
            if (committed.contains(step.key()) || !attempts.add(step.node().id())) {
                throw new IllegalArgumentException("a committed step or an old attempt cannot run again");
            }
            active = step;
            continuous++;
        } else if (result instanceof StepResult.Auxiliary auxiliary) {
            if (!transfer(auxiliary.node()) || auxiliary.node().spec().workload() instanceof Workload.Within
                    || !attempts.add(auxiliary.node().id())) {
                throw new IllegalArgumentException("an auxiliary action must be a fresh inventory transfer without production");
            }
            this.auxiliary = auxiliary.node();
            continuous++;
        } else if (result instanceof StepResult.Wait wait) {
            nextPoll = now + Math.min(wait.ticks(), limits.maxWaitTicks());
        } else if (result instanceof StepResult.Pause pause) {
            continuous = 0;
            nextPoll = now + Math.min(pause.ticks(), limits.maxWaitTicks());
        } else if (result instanceof StepResult.Failed failed) {
            failure = failed.why();
        }
        return result;
    }

    // Validate concrete supplies before the world-changing call, including overlapping input declarations.
    public boolean permits(List<ItemStack> goods) { return debit(goods, remaining.clone()); }

    public boolean permitsPlanned(List<ItemStack> goods, List<Placement.Draw> draws) {
        if (draws.isEmpty()) return true;
        List<Placement.Draw> available = remainingDraws(draws);
        return match(goods, available.stream().map(Placement.Draw::spec).toList(),
            available.stream().mapToLong(Placement.Draw::count).toArray());
    }

    public boolean active(Node node) { return active != null && active.node().id().equals(node.id()); }

    public boolean auxiliary(Node node) { return auxiliary != null && auxiliary.id().equals(node.id()); }

    public static boolean transfer(Node node) {
        return node instanceof InventoryTransfer && !(node instanceof BatchAction) && !(node instanceof Staged) && node.spec().needs().isEmpty()
            && node.spec().gives().isEmpty() && node.spec().leaves().stream().allMatch(amount -> amount.spec().equals(Leaves.UNSEEN));
    }

    private boolean debit(List<ItemStack> goods, long[] left) {
        return match(goods, needs.stream().map(Need::spec).toList(), left);
    }

    private static boolean match(List<ItemStack> goods, List<ItemSpec> accepts, long[] left) {
        // Supplies and declarations overlap (a specific ingredient can also be in a broad tag).
        // A small capacitated matching avoids both double spending and order-dependent false shortages.
        int count = goods.size() + accepts.size() + 2;
        int sink = count - 1;
        long[][] residual = new long[count][count];
        long total = 0;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < accepts.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt(i -> Goods.members(accepts.get(i)).size()));
        for (int i = 0; i < goods.size(); i++) {
            ItemStack stack = goods.get(i);
            residual[0][i + 1] = stack.getCount();
            total += stack.getCount();
            for (int j = 0; j < accepts.size(); j++) {
                if (Goods.matches(stack, accepts.get(order.get(j)))) {
                    residual[i + 1][goods.size() + j + 1] = stack.getCount();
                }
            }
        }
        for (int j = 0; j < accepts.size(); j++) residual[goods.size() + j + 1][sink] = left[order.get(j)];
        long matched = 0;
        int[] previous = new int[count];
        int[] queue = new int[count];
        while (matched < total) {
            Arrays.fill(previous, -1);
            previous[0] = 0;
            int head = 0;
            int tail = 1;
            queue[0] = 0;
            while (head < tail && previous[sink] < 0) {
                int from = queue[head++];
                for (int to = 1; to < count; to++) {
                    if (previous[to] < 0 && residual[from][to] > 0) {
                        previous[to] = from;
                        queue[tail++] = to;
                    }
                }
            }
            if (previous[sink] < 0) return false;
            long take = total - matched;
            for (int at = sink; at != 0; at = previous[at]) take = Math.min(take, residual[previous[at]][at]);
            for (int at = sink; at != 0; at = previous[at]) {
                residual[previous[at]][at] -= take;
                residual[at][previous[at]] += take;
            }
            matched += take;
        }
        for (int j = 0; j < accepts.size(); j++) left[order.get(j)] = residual[goods.size() + j + 1][sink];
        return true;
    }

    // Existing placement provenance remains authoritative. Keep zero draws so exhausted planned goods never
    // fall through to unreserved inventory in Commit.draw.
    public List<Placement.Draw> remainingDraws(List<Placement.Draw> original) {
        Map<Spent, Long> deducted = new LinkedHashMap<>(spent);
        List<Placement.Draw> result = new ArrayList<>();
        for (Placement.Draw draw : original) {
            long left = draw.count();
            for (var entry : deducted.entrySet()) {
                Spent debit = entry.getKey();
                if (debit.source() != null && !debit.source().equals(sourceKey(draw))) continue;
                if (left <= 0 || !Goods.overlap(debit.concrete(), draw.spec())) continue;
                long take = Math.min(left, entry.getValue());
                entry.setValue(entry.getValue() - take);
                left -= take;
            }
            result.add(new Placement.Draw(draw.from(), draw.spec(), left, draw.resident(), draw.producer()));
        }
        return result;
    }

    public void record(ServerLevel level, Node node, List<Debit> consumption, Outcome.Done done,
                       Optional<RefusalKind> stop) {
        if (active == null || !active.node().id().equals(node.id())) {
            throw new IllegalStateException("settlement does not belong to the active attempt");
        }
        List<ItemStack> consumed = consumption.stream().map(Debit::stack).toList();
        if (!debit(consumed, remaining)) throw new IllegalStateException("batch exceeded its input budget");
        for (Debit debit : consumption) {
            spent.merge(new Spent(sourceKey(debit.source()), Goods.specOf(debit.stack())),
                (long) debit.stack().getCount(), Math::addExact);
            long used = debit.stack().getCount();
            for (var entry : outputs.entrySet()) {
                OutputKey key = entry.getKey();
                if (used <= 0 || !key.store().equals(debit.store()) || !key.resident().equals(debit.resident())
                        || !Goods.matches(debit.stack(), key.goods())) continue;
                long take = Math.min(used, entry.getValue());
                entry.setValue(entry.getValue() - take);
                used -= take;
            }
        }
        outputs.values().removeIf(count -> count <= 0);
        committed.add(active.key());
        pending = new StepReceipt(parent.id(), active.key(), node.id(), consumed,
            done.unused(), done.made(), stop);
        if (!drainsAfterFailure) stop.ifPresent(why -> failure = why);
        // Clear before the callback: a callback failure never permits another settlement of this attempt.
        active = null;
    }

    private static Placement.Draw sourceKey(Placement.Draw draw) {
        return draw == null ? null : new Placement.Draw(draw.from(), draw.spec(), 0, draw.resident(), draw.producer());
    }

    public void notifySettled(ServerLevel level) {
        StepReceipt receipt = pending;
        pending = null;
        if (receipt != null) session.settled(level, receipt);
    }

    public void stop(RefusalKind why) { failure = why; }

    public void ended(ServerLevel level, Node node, Ending how) {
        if (active != null && active.node().id().equals(node.id())) active = null;
        if (auxiliary != null && auxiliary.id().equals(node.id())) auxiliary = null;
        if (!drainsAfterFailure && how instanceof Ending.Failed failed) failure = failed.why();
        session.ended(level, node, how);
    }

    public Outcome finish(ServerLevel level, Worker who) {
        return failure == null ? session.finish(level, who) : Outcome.failed(failure);
    }
}
