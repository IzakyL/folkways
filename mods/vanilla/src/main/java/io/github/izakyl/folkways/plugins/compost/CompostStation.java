package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemDrops;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.batch.BatchAction;
import io.github.izakyl.folkways.core.api.work.batch.BatchCommit;
import io.github.izakyl.folkways.core.api.work.batch.BatchSession;
import io.github.izakyl.folkways.core.api.work.batch.BatchSpec;
import io.github.izakyl.folkways.core.api.work.batch.StepReceipt;
import io.github.izakyl.folkways.core.api.work.batch.StepResult;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** One bounded physical seed delivery, followed by native inputs and native output at the same barrel. */
public final class CompostStation {
    public static final int MAX_SEEDS = 16;
    private final Function<UUID, Optional<SeedBudget>> budgets;
    private final Supplier<Set<Item>> seeds;
    private final java.util.function.BiFunction<Item, Integer, Integer> limit;
    private record Site(UUID colony, WorldPos at) { }
    private final Map<Site, Work> active = new LinkedHashMap<>();
    private long acceptedSeeds;
    private long collections;
    private long completedBatches;

    public CompostStation(Function<UUID, Optional<SeedBudget>> budgets, Supplier<Set<Item>> seeds) {
        this(budgets, seeds, (item, count) -> count);
    }

    public CompostStation(Function<UUID, Optional<SeedBudget>> budgets, Supplier<Set<Item>> seeds,
            java.util.function.BiFunction<Item, Integer, Integer> limit) {
        this.budgets = budgets;
        this.seeds = seeds;
        this.limit = limit;
    }

    public record Stats(int active, long acceptedSeeds, long collections, long completedBatches) { }
    public Stats stats() { return new Stats(active.size(), acceptedSeeds, collections, completedBatches); }
    public List<UUID> activeSessions() { return active.values().stream().map(Node::id).toList(); }
    public boolean matches(BlockState state) { return state.is(Blocks.COMPOSTER); }

    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
            CompostBookings bookings, Runnable changed) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) return List.of();
        BlockState state = level.getBlockState(at.block(level));
        if (!matches(state)) return List.of();
        List<Grown> running = activeRecovery(colony, level, at, bookings);
        if (!running.isEmpty()) return running;
        if (!bookings.free(at)) return List.of();
        Site site = new Site(colony, at);
        int fill = state.getValue(ComposterBlock.LEVEL);
        // Maturation and collection never depend on the continued existence of a seed source or licence.
        if (fill >= 7) return List.of(Grown.of(new Work(site, stances, bookings, changed, Optional.empty(), 0)));
        Optional<SeedBudget> budget = budgets.apply(colony).map(SeedBudget::frozen);
        if (budget.isEmpty()) return List.of();
        for (Item item : seeds.get().stream().sorted(Comparator.comparing(seed -> BuiltInRegistries.ITEM.getKey(seed).toString())).toList()) {
            if (ComposterBlock.getValue(item.getDefaultInstance()) <= 0.0F) continue;
            int count = limit.apply(item, (int) Math.min(MAX_SEEDS, budget.get().tally(item).compostable()));
            if (count > 0) return List.of(Grown.of(new Work(site, stances, bookings, changed, Optional.of(item), count)));
        }
        return List.of();
    }

    public List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, CompostBookings bookings) {
        Work work = active.get(new Site(colony, at));
        return work != null && work.bookings == bookings && !bookings.cancelled(at, work.id(), work.generation)
            ? List.of(Grown.of(work)) : List.of();
    }

    public void release(UUID colony, ServerLevel level, WorldPos at) {
        Work work = active.remove(new Site(colony, at));
        if (work != null) work.hold.release(Ending.REVOKED);
    }

    public void stop(UUID colony, Closing why) {
        for (Site site : List.copyOf(active.keySet())) if (site.colony().equals(colony)) release(colony, null, site.at());
    }

    public Component status(ServerLevel level, WorldPos at) {
        BlockState state = level.getBlockState(at.block(level));
        return Component.translatable(matches(state) && state.getValue(ComposterBlock.LEVEL) == 7
            ? "folkways.page.compost.maturing" : "folkways.page.compost.ready");
    }

    private final class Work implements Node, BatchSession {
        private final Site site;
        private final NodeSpec spec;
        private final WorldPos at;
        private final CompostBookings bookings;
        private final Hold hold;
        private final long generation;
        private final Runnable changed;
        private final Optional<Item> seed;
        private final int allowance;
        private int spent;
        private int sequence;
        private int attempted;
        private long started = -1;
        private boolean stopped;
        private boolean completed;

        Work(Site site, Stances stances, CompostBookings bookings, Runnable changed, Optional<Item> seed, int count) {
            this.site = site; this.at = site.at(); this.bookings = bookings; this.changed = changed; this.seed = seed; this.allowance = count;
            UUID id = UUID.nameUUIDFromBytes(("folkways:compost/" + at + "/" + seed.map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).orElse("collect"))
                .getBytes(StandardCharsets.UTF_8));
            generation = bookings.generation(at);
            hold = bookings.hold(at, id);
            spec = NodeSpec.of(id, CompostContent.ID, new WorkSite.AtBlock(at), stances, this)
                .vocation(FarmingContent.trade()).leaves(Leaves.ofStacks(List.of(new ItemStack(Items.BONE_MEAL, 3))))
                .effort(WorkEffort.NONE).estimate(count * 10 + 30)
                .needs(seed.map(item -> List.of(new Need(Goods.specOf(item.getDefaultInstance()), count, true))).orElseGet(List::of))
                .doing(CompostContent.COMPOSTING).done();
        }

        public NodeSpec spec() { return spec; }
        public BatchSpec batch() { return new BatchSpec(id(), 20, 100); }
        public Optional<RefusalKind> planned(ServerLevel level) {
            var refused = hold.take(); if (refused.isEmpty()) active.put(site, this); return refused;
        }
        public boolean ready(ServerLevel level) { return bookings.first(at, id()); }
        public Optional<RefusalKind> refusal(ServerLevel level) {
            return bookings.cancelled(at, id(), generation) ? Optional.of(CompostRefusal.STATION_CHANGED) : Optional.empty();
        }
        public void ended(ServerLevel level, Ending how) {
            active.remove(site, this); hold.release(how); nudge();
        }
        public Outcome commit(ServerLevel level, Worker who) { return finish(level, who); }
        public Outcome finish(ServerLevel level, Worker who) {
            if (!valid(level, who)) return Outcome.failed(CompostRefusal.STATION_CHANGED);
            if (!completed) { completed = true; completedBatches++; }
            return Outcome.done();
        }
        public void settled(ServerLevel level, StepReceipt receipt) {
            sequence++;
            int consumed = receipt.consumed().stream().mapToInt(ItemStack::getCount).sum();
            spent += consumed; acceptedSeeds += consumed;
            if (receipt.step().startsWith("collect/") && !receipt.made().isEmpty()) collections++;
            if (receipt.stop().isPresent() || receipt.step().startsWith("input/") && consumed == 0) stopped = true;
        }
        public StepResult advance(ServerLevel level, Worker who) {
            if (started < 0) started = level.getGameTime();
            if (!valid(level, who)) return new StepResult.Failed(CompostRefusal.STATION_CHANGED);
            BlockState state = level.getBlockState(at.block(level));
            if (!matches(state)) return new StepResult.Failed(CompostRefusal.NOT_A_STATION);
            if (level.getGameTime() - started > 300 || attempted >= 20) return new StepResult.Complete();
            int fill = state.getValue(ComposterBlock.LEVEL);
            if (fill == 7) return new StepResult.Wait(5);
            if (fill == 8) {
                if (room(who, Items.BONE_MEAL.getDefaultInstance()) < 1) return new StepResult.Complete();
                attempted++;
                return new StepResult.Step("collect/" + sequence, new Action(false));
            }
            if (stopped || seed.isEmpty() || spent >= allowance) return new StepResult.Complete();
            attempted++;
            return new StepResult.Step("input/" + sequence, new Action(true));
        }
        private boolean valid(ServerLevel level, Worker who) {
            return at.in(level) && level.isLoaded(at.block(level)) && !bookings.cancelled(at, id(), generation)
                && bookings.first(at, id())
                && io.github.izakyl.folkways.core.api.resident.body.Bodies.of(who.body())
                    .filter(body -> body.licences().of(FarmingContent.trade()).isPresent()).isPresent()
                && Reach.stanceHolding(who.body(), spec.stances()).flatMap(feet -> WorldSpaces.storage(level, new WorldPos(at.realm(), feet)))
                    .filter(feet -> Reach.withinReach(feet, at.block(level)) && Reach.inSight(level, feet, at.block(level))).isPresent();
        }
        private void nudge() {
            try { changed.run(); } catch (RuntimeException | LinkageError broken) {
                org.slf4j.LoggerFactory.getLogger("folkways-vanilla").warn("compost refresh failed after settlement", broken);
            }
        }
        private final class Action implements BatchAction {
            private final boolean input;
            private final NodeSpec action;
            Action(boolean input) {
                this.input = input;
                action = NodeSpec.of(UUID.randomUUID(), CompostContent.ID, spec.site(), spec.stances(), Workload.Once.of(10))
                    .vocation(FarmingContent.trade()).leaves(input ? List.of() : Leaves.ofStacks(List.of(Items.BONE_MEAL.getDefaultInstance())))
                    .needs(input ? List.of(new Need(Goods.specOf(seed.orElseThrow().getDefaultInstance()), 1, true)) : List.of())
                    .doing(CompostContent.COMPOSTING).done();
            }
            public NodeSpec spec() { return action; }
            public BatchCommit execute(ServerLevel level, Worker who) {
                if (!valid(level, who)) return BatchCommit.of(Outcome.failed(CompostRefusal.STATION_CHANGED));
                BlockState before = level.getBlockState(at.block(level));
                if (!matches(before)) return BatchCommit.of(Outcome.failed(CompostRefusal.NOT_A_STATION));
                return input ? insert(level, who, before) : collect(level, who, before);
            }
            private BatchCommit insert(ServerLevel level, Worker who, BlockState before) {
                List<ItemStack> supplied = who.supplied().stream().map(ItemStack::copy).toList();
                ItemStack single = supplied.stream().filter(stack -> stack.is(seed.orElseThrow())).findFirst().orElse(ItemStack.EMPTY);
                Optional<SeedBudget> fresh = budgets.apply(site.colony());
                if (before.getValue(ComposterBlock.LEVEL) >= 7 || single.getCount() != 1 || fresh.isEmpty()
                        || !fresh.get().permits(Work.this.id(), single) || ComposterBlock.getValue(single) <= 0.0F) {
                    stopped = true;
                    return BatchCommit.of(new Outcome.Done(List.of(), List.of(), Optional.empty(), supplied));
                }
                try {
                    ComposterBlock.insertItem(who.body(), before, level, single, at.block(level));
                } catch (RuntimeException | LinkageError broken) {
                    // Native insertion updates the world before shrinking; settle accepted material exactly once.
                    if (single.getCount() == 1 && !level.getBlockState(at.block(level)).equals(before)) single.shrink(1);
                    if (!single.isEmpty()) throw broken;
                    stopped = true;
                    return BatchCommit.accepted(new Outcome.Done(List.of(), List.of(), Optional.empty(),
                        supplied.stream().filter(stack -> !stack.isEmpty()).toList()), CompostRefusal.STATION_CHANGED);
                }
                nudge();
                return BatchCommit.of(new Outcome.Done(List.of(), List.of(), Optional.empty(), supplied.stream().filter(stack -> !stack.isEmpty()).toList()));
            }
            private BatchCommit collect(ServerLevel level, Worker who, BlockState before) {
                if (before.getValue(ComposterBlock.LEVEL) != 8 || room(who, Items.BONE_MEAL.getDefaultInstance()) < 1) {
                    stopped = true;
                    return BatchCommit.of(Outcome.done());
                }
                List<ItemStack> items;
                try {
                    List<AABB> bounds = new java.util.ArrayList<>();
                    bounds.add(new AABB(at.block(level)).inflate(1.5));
                    WorldSpaces.world(level, at).ifPresent(world -> bounds.add(new AABB(world, world).inflate(1.5)));
                    bounds.add(who.body().getBoundingBox().inflate(1.5));
                    items = ItemDrops.capture(level, bounds,
                        () -> ComposterBlock.extractProduce(who.body(), before, level, at.block(level))).items();
                } catch (ItemDrops.CaptureFailure broken) {
                    if (level.getBlockState(at.block(level)).equals(before)) throw broken;
                    return BatchCommit.accepted(new Outcome.Done(List.of(), broken.items(), Optional.empty()), CompostRefusal.STATION_CHANGED);
                }
                nudge();
                return BatchCommit.of(new Outcome.Done(List.of(), items, Optional.empty()));
            }
        }
    }

    private static int room(Worker who, ItemStack stack) {
        int room = 0;
        for (int slot = 0; slot < who.pack().getContainerSize(); slot++) {
            ItemStack there = who.pack().getItem(slot);
            if (there.isEmpty()) room += Math.min(stack.getMaxStackSize(), who.pack().getMaxStackSize());
            else if (ItemStack.isSameItemSameComponents(there, stack)) room += Math.max(0,
                Math.min(there.getMaxStackSize(), who.pack().getMaxStackSize()) - there.getCount());
        }
        return room;
    }
}
