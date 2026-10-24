package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.terms.ItemDrops;
import io.github.izakyl.folkways.core.api.terms.Reach;
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
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;

/** Fills native composters in average-cost batches, using only a fresh surplus seed allowance. */
public final class CompostStation {
    private final Function<UUID, Optional<SeedBudget>> budgets;
    private final Supplier<Set<Item>> seeds;
    private final Predicate<UUID> enabled;
    private record Site(UUID colony, WorldPos at) { }
    private final Map<Site, Work> active = new LinkedHashMap<>();

    public CompostStation(Function<UUID, Optional<SeedBudget>> budgets, Supplier<Set<Item>> seeds) {
        this(budgets, seeds, ignored -> true);
    }

    public CompostStation(Function<UUID, Optional<SeedBudget>> budgets, Supplier<Set<Item>> seeds, Predicate<UUID> enabled) {
        this.budgets = budgets;
        this.seeds = seeds;
        this.enabled = enabled;
    }

    // Vanilla always accepts the first item in an empty composter. Remaining layers take
    // 1 / chance items on average; round the entire remaining batch up to whole items.
    public static int averageInputs(ItemStack stack, int fill) {
        float chance = ComposterBlock.getValue(stack);
        if (!Float.isFinite(chance) || chance <= 0 || fill < 0 || fill >= 7) return 0;
        double probability = Math.min(1.0, chance);
        double expected = fill == 0 ? 1 + 6 / probability : (7 - fill) / probability;
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(expected));
    }

    public boolean matches(BlockState state) { return state.is(Blocks.COMPOSTER); }

    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
            CompostBookings bookings, Runnable changed) {
        BlockState state = level.getBlockState(at.block(level));
        if (!matches(state)) return List.of();
        Site site = new Site(colony, at);
        List<Grown> running = activeRecovery(colony, level, at, bookings);
        if (!running.isEmpty()) return running;
        if (!bookings.free(at)) return List.of();
        int fill = state.getValue(ComposterBlock.LEVEL);
        if (fill == 8) return List.of(Grown.of(new Work(site, stances, bookings, changed, Optional.empty(), 0)));
        if (fill >= 7 || !enabled.test(colony)) return List.of();
        Optional<SeedBudget> budget = budgets.apply(colony);
        if (budget.isEmpty()) return List.of();
        return seeds.get().stream().sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
            .filter(item -> averageInputs(item.getDefaultInstance(), fill) > 0
                && budget.get().tally(item).compostable() >= averageInputs(item.getDefaultInstance(), fill))
            .findFirst().map(item -> List.of(Grown.of(new Work(site, stances, bookings, changed,
                Optional.of(item), averageInputs(item.getDefaultInstance(), fill))))).orElseGet(List::of);
    }

    public List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, CompostBookings bookings) {
        Work running = active.get(new Site(colony, at));
        return running != null && running.bookings == bookings && !bookings.cancelled(at, running.id(), running.generation)
            ? List.of(Grown.of(running)) : List.of();
    }

    public void release(UUID colony, ServerLevel level, WorldPos at) {
        Work work = active.remove(new Site(colony, at));
        if (work != null) work.hold.release(Ending.REVOKED);
    }

    public void stop(UUID colony, Closing why) {
        for (Site site : List.copyOf(active.keySet())) {
            if (site.colony().equals(colony)) {
                Work work = active.remove(site);
                work.hold.release(Ending.REVOKED);
            }
        }
    }

    public Component status(ServerLevel level, WorldPos at) {
        BlockState state = level.getBlockState(at.block(level));
        return Component.translatable(matches(state) && state.getValue(ComposterBlock.LEVEL) == 7
            ? "folkways.compost.status.maturing" : "folkways.compost.status.active");
    }

    private final class Work implements Node {
        private final Site site;
        private final NodeSpec spec;
        private final WorldPos at;
        private final CompostBookings bookings;
        private final Hold hold;
        private final long generation;
        private final Runnable changed;
        private final Optional<Item> seed;
        private final int inputs;

        Work(Site site, Stances stances, CompostBookings bookings, Runnable changed,
                Optional<Item> seed, int inputs) {
            this.site = site;
            this.at = site.at();
            this.bookings = bookings;
            this.changed = changed;
            this.seed = seed;
            this.inputs = inputs;
            UUID id = UUID.nameUUIDFromBytes(("folkways:compost/" + at + "/"
                + seed.map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).orElse("collect"))
                .getBytes(StandardCharsets.UTF_8));
            generation = bookings.generation(at);
            hold = bookings.hold(at, id);
            spec = NodeSpec.of(id, CompostContent.ID, new WorkSite.AtBlock(at), stances, Workload.Once.of(10))
                .vocation(FarmingContent.trade()).leaves(Leaves.room(1))
                .needs(seed.map(item -> List.of(new Need(ItemSpec.of(item), inputs, true))).orElseGet(List::of))
                .doing(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "composting")).done();
        }

        public NodeSpec spec() { return spec; }
        public Optional<io.github.izakyl.folkways.core.api.terms.RefusalKind> planned(ServerLevel level) {
            var refused = hold.take();
            if (refused.isEmpty()) active.put(site, this);
            return refused;
        }
        public boolean ready(ServerLevel level) { return bookings.first(at, id()); }
        public Optional<io.github.izakyl.folkways.core.api.terms.RefusalKind> refusal(ServerLevel level) {
            return bookings.cancelled(at, id(), generation) ? Optional.of(CompostRefusal.STATION_CHANGED) : Optional.empty();
        }
        public void ended(ServerLevel level, Ending how) { active.remove(site, this); hold.release(how); nudge(); }

        public Outcome commit(ServerLevel level, Worker who) {
            if (!at.in(level) || bookings.cancelled(at, id(), generation) || !bookings.first(at, id())
                    || io.github.izakyl.folkways.core.api.resident.body.Bodies.of(who.body())
                        .filter(body -> body.licences().of(FarmingContent.trade()).isPresent()).isEmpty()
                    || !Reach.stanceHolding(who.body(), spec.stances())
                        .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(at.realm(), feet)))
                        .filter(feet -> Reach.withinReach(feet, at.block(level))
                            && Reach.inSight(level, feet, at.block(level))).isPresent()) {
                return Outcome.failed(CompostRefusal.STATION_CHANGED);
            }
            BlockState before = level.getBlockState(at.block(level));
            if (!before.is(Blocks.COMPOSTER)) return Outcome.failed(CompostRefusal.NOT_A_STATION);
            if (seed.isEmpty()) return collect(level, who, before);
            if (before.getValue(ComposterBlock.LEVEL) >= 7) return Outcome.failed(CompostRefusal.STATION_CHANGED);
            List<ItemStack> supplied = who.supplied().stream().map(ItemStack::copy).toList();
            int required = averageInputs(seed.get().getDefaultInstance(), before.getValue(ComposterBlock.LEVEL));
            long provided = supplied.stream().filter(stack -> stack.is(seed.get())).mapToLong(ItemStack::getCount).sum();
            Optional<SeedBudget> fresh = budgets.apply(site.colony());
            // The full escrow is still physical stock: restore it to the snapshot, then test only
            // the amount this batch will consume (a player may have partly filled the bucket).
            ItemStack escrow = new ItemStack(seed.get(), inputs);
            if (!enabled.test(site.colony()) || provided != inputs || required <= 0 || required > inputs
                    || fresh.isEmpty() || fresh.get().tally(seed.get(), escrow, Set.of(id())).compostable() < required) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), supplied);
            }
            BlockState filled = before.setValue(ComposterBlock.LEVEL, 7);
            try {
                // Schedule before notifying neighbors so a notification failure cannot strand a
                // committed level-seven batch without its normal native maturity tick.
                level.scheduleTick(at.block(level), Blocks.COMPOSTER, 20);
                if (!level.setBlock(at.block(level), filled, Block.UPDATE_ALL)) {
                    return new Outcome.Done(List.of(), List.of(), Optional.empty(), supplied);
                }
                level.gameEvent(GameEvent.BLOCK_CHANGE, at.block(level), GameEvent.Context.of(who.body(), filled));
                level.levelEvent(1500, at.block(level), 1);
            } catch (RuntimeException | LinkageError broken) {
                if (!level.getBlockState(at.block(level)).equals(filled)) throw broken;
                org.slf4j.LoggerFactory.getLogger("folkways-compost")
                    .warn("composter {} filled its batch before notification failed", at, broken);
            }
            int left = required;
            for (ItemStack stack : supplied) {
                if (!stack.is(seed.get())) continue;
                int taken = Math.min(left, stack.getCount());
                stack.shrink(taken);
                left -= taken;
            }
            nudge();
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), supplied.stream().filter(stack -> !stack.isEmpty()).toList());
        }

        private void nudge() {
            try { changed.run(); } catch (RuntimeException | LinkageError broken) {
                org.slf4j.LoggerFactory.getLogger("folkways-compost").warn("compost refresh failed after settlement", broken);
            }
        }

        private Outcome collect(ServerLevel level, Worker who, BlockState before) {
            if (before.getValue(ComposterBlock.LEVEL) != 8) return Outcome.failed(CompostRefusal.STATION_CHANGED);
            List<ItemStack> items;
            try {
                List<AABB> bounds = new java.util.ArrayList<>();
                bounds.add(new AABB(at.block(level)).inflate(1.5));
                WorldSpaces.world(level, at).ifPresent(world -> bounds.add(new AABB(world, world).inflate(1.5)));
                bounds.add(who.body().getBoundingBox().inflate(1.5));
                items = ItemDrops.capture(level, bounds,
                    () -> ComposterBlock.extractProduce(who.body(), before, level, at.block(level))).items();
            } catch (ItemDrops.CaptureFailure broken) {
                // extractProduce spawns before emptying. Captured output is deliverable only once the
                // real composter was emptied; otherwise discarding it leaves the original batch intact.
                if (level.getBlockState(at.block(level)).equals(before)) throw broken;
                items = broken.items();
                org.slf4j.LoggerFactory.getLogger("folkways-compost")
                    .warn("composter {} emptied before notification failed", at, broken.getCause());
            }
            nudge();
            return new Outcome.Done(List.of(), items, Optional.empty());
        }
    }
}
