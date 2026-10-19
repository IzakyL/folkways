package io.github.izakyl.folkways.cookery.compost;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.terms.Goods;
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
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenStation;
import io.github.izakyl.folkways.cookery.seeds.SeedBudget;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Native composter work is autonomous and only consumes a fresh, surplus seed allowance. */
public final class CompostStation implements KitchenStation {
    private final Function<UUID, Optional<SeedBudget>> budgets;
    private final Supplier<Set<Item>> seeds;
    private record Site(UUID colony, WorldPos at) { }
    private final Map<Site, Work> active = new LinkedHashMap<>();

    public CompostStation(Function<UUID, Optional<SeedBudget>> budgets, Supplier<Set<Item>> seeds) {
        this.budgets = budgets;
        this.seeds = seeds;
    }

    @Override
    public boolean matches(BlockState state) { return state.is(Blocks.COMPOSTER); }

    @Override
    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
            KitchenBookings bookings, Runnable changed) {
        BlockState state = level.getBlockState(at.block(level));
        if (!matches(state)) return List.of();
        Site site = new Site(colony, at);
        List<Grown> running = activeRecovery(colony, level, at, bookings);
        if (!running.isEmpty()) return running;
        if (!bookings.free(at)) return List.of();
        int fill = state.getValue(ComposterBlock.LEVEL);
        if (fill == 8) return List.of(Grown.of(new Work(site, stances, bookings, changed, Optional.empty(), Optional.empty())));
        if (fill >= 7) return List.of();
        Optional<SeedBudget> budget = budgets.apply(colony);
        if (budget.isEmpty()) return List.of();
        return seeds.get().stream().sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
            .filter(item -> ComposterBlock.getValue(item.getDefaultInstance()) > 0.0F
                && budget.get().tally(item).compostable() > 0)
            .findFirst().map(item -> List.of(Grown.of(new Work(site, stances, bookings, changed,
                Optional.of(item), budget)))).orElseGet(List::of);
    }

    @Override
    public List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
        Work running = active.get(new Site(colony, at));
        return running != null && running.bookings == bookings && !bookings.cancelled(at, running.id(), running.generation)
            ? List.of(Grown.of(running)) : List.of();
    }

    @Override
    public void release(UUID colony, ServerLevel level, WorldPos at) {
        Work work = active.remove(new Site(colony, at));
        if (work != null) work.hold.release(Ending.REVOKED);
    }

    @Override
    public void stop(UUID colony, Closing why) {
        for (Site site : List.copyOf(active.keySet())) {
            if (site.colony().equals(colony)) {
                Work work = active.remove(site);
                work.hold.release(Ending.REVOKED);
            }
        }
    }

    @Override
    public Component status(ServerLevel level, WorldPos at) {
        BlockState state = level.getBlockState(at.block(level));
        return Component.translatable(matches(state) && state.getValue(ComposterBlock.LEVEL) == 7
            ? "folkways.page.cookery.compost_maturing" : "folkways.page.cookery.compost");
    }

    private final class Work implements Node {
        private final Site site;
        private final NodeSpec spec;
        private final WorldPos at;
        private final KitchenBookings bookings;
        private final Hold hold;
        private final long generation;
        private final Runnable changed;
        private final Optional<Item> seed;
        private final Optional<SeedBudget> budget;

        Work(Site site, Stances stances, KitchenBookings bookings, Runnable changed,
                Optional<Item> seed, Optional<SeedBudget> budget) {
            this.site = site;
            this.at = site.at();
            this.bookings = bookings;
            this.changed = changed;
            this.seed = seed;
            this.budget = budget;
            UUID id = UUID.nameUUIDFromBytes(("folkways:compost/" + at + "/"
                + seed.map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).orElse("collect"))
                .getBytes(StandardCharsets.UTF_8));
            generation = bookings.generation(at);
            hold = bookings.hold(at, id);
            spec = NodeSpec.of(id, CookeryContent.ID, new WorkSite.AtBlock(at), stances, Workload.Once.of(10))
                .vocation(FarmingContent.trade()).leaves(Leaves.room(1))
                .needs(seed.map(item -> List.of(new Need(Goods.specOf(item.getDefaultInstance()), 1, true))).orElseGet(List::of))
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
            return bookings.cancelled(at, id(), generation) ? Optional.of(CookeryRefusal.STATION_CHANGED) : Optional.empty();
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
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            BlockState before = level.getBlockState(at.block(level));
            if (!before.is(Blocks.COMPOSTER)) return Outcome.failed(CookeryRefusal.NOT_A_STATION);
            if (seed.isEmpty()) return collect(level, who, before);
            if (before.getValue(ComposterBlock.LEVEL) >= 7) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            List<ItemStack> supplied = who.supplied().stream().map(ItemStack::copy).toList();
            ItemStack single = supplied.stream().filter(stack -> stack.is(seed.get())).findFirst().orElse(ItemStack.EMPTY);
            if (single.getCount() != 1 || budget.isEmpty() || budgets.apply(site.colony()).isEmpty() || !budget.get().permits(id(), single)
                    || ComposterBlock.getValue(single) <= 0.0F) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), supplied);
            }
            try {
                ComposterBlock.insertItem(who.body(), before, level, single, at.block(level));
            } catch (RuntimeException | LinkageError broken) {
                // Native addItem mutates the level before it shrinks the input. If notification fails
                // in between, settle that real mutation instead of refunding a free compost layer.
                if (single.getCount() == 1 && !level.getBlockState(at.block(level)).equals(before)) single.shrink(1);
                if (!single.isEmpty()) throw broken;
                org.slf4j.LoggerFactory.getLogger("folkways-cookery")
                    .warn("composter {} consumed its seed before notification failed", at, broken);
            }
            nudge();
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), supplied.stream().filter(stack -> !stack.isEmpty()).toList());
        }

        private void nudge() {
            try { changed.run(); } catch (RuntimeException | LinkageError broken) {
                org.slf4j.LoggerFactory.getLogger("folkways-cookery").warn("compost refresh failed after settlement", broken);
            }
        }

        private Outcome collect(ServerLevel level, Worker who, BlockState before) {
            if (before.getValue(ComposterBlock.LEVEL) != 8) return Outcome.failed(CookeryRefusal.STATION_CHANGED);
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
                org.slf4j.LoggerFactory.getLogger("folkways-cookery")
                    .warn("composter {} emptied before notification failed", at, broken.getCause());
            }
            nudge();
            return new Outcome.Done(List.of(), items, Optional.empty());
        }
    }
}
