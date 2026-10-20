package io.github.izakyl.folkways.cookery.seeds;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
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
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.StockCheck;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Maintains one reserve per sowing purpose and obtains missing grass seeds through real native loot. */
public final class SeedPresence implements Facing {
    public static final ResourceLocation ID = id("cookery_seeds");
    public static final ResourceLocation GRASS = id("seed_grass");
    public static final String RESTORE_GRASS = "restore_grass";
    private static final ResourceLocation KEEPING = id("keeping_seeds");
    private static final ResourceLocation GATHERING = id("gathering_seeds");
    private static final ResourceLocation RESTORING = id("restoring_grass");

    private final Colony colony;
    private final Function<ServerLevel, SeedBudget> budgets;
    private final BooleanSupplier enabled;
    private final Sweep sweep;
    private final KitchenBookings bookings = new KitchenBookings();
    private final Map<UUID, WorldPos> areas = new LinkedHashMap<>();
    private final Map<UUID, Source> active = new LinkedHashMap<>();

    public SeedPresence(Colony colony, Function<ServerLevel, SeedBudget> budgets, BooleanSupplier enabled) {
        this.colony = colony;
        this.budgets = budgets;
        this.enabled = enabled;
        sweep = new Sweep(colony, ID, 40);
    }

    public SeedBudget budget(ServerLevel level) { return budgets.apply(level); }
    public boolean enabled() { return enabled.getAsBoolean(); }

    public void tick(MinecraftServer server) { sweep.tick(server, this::refresh, this::goals); }

    void refresh(MinecraftServer server) {
        Map<UUID, WorldPos> current = new LinkedHashMap<>();
        for (ColonyView view : colony.views(server)) {
            for (ZoneView zone : Fronts.of(colony).zonesIn(view.level())) {
                if (zone.delegation().equals(GRASS) && !zone.cells().isEmpty()) {
                    WorldPos anchor = zone.at(zone.cells().stream().min(BlockPos::compareTo).orElseThrow());
                    current.put(zone.id(), anchor);
                }
            }
        }
        areas.forEach((zone, at) -> { if (!at.equals(current.get(zone))) bookings.withdraw(at); });
        current.values().forEach(bookings::admit);
        areas.clear();
        areas.putAll(current);
        active.entrySet().removeIf(entry -> !entry.getValue().anchor.equals(current.get(entry.getKey())));
    }

    public void closed(Closing why) { bookings.clear(); areas.clear(); active.clear(); }

    private List<Body> farmers(ColonyView view) {
        return view.residents().stream().map(resident -> view.level().getEntity(resident.id())).filter(java.util.Objects::nonNull)
            .flatMap(entity -> Bodies.of(entity).stream()).filter(body ->
                io.github.izakyl.folkways.cookery.gear.CookeryGear.permitsSeeds(colony, body)
                && (body.mob().getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                    || body.mob().getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT)
                        && io.github.izakyl.folkways.cookery.gear.CookeryGear.allowed(colony,
                            body.mob().getItemBySlot(EquipmentSlot.HEAD)))).toList();
    }

    private UUID keepId(WorldPos at, Item seed) {
        return named("keep/" + colony.id() + "/" + at.realm() + "/" + BuiltInRegistries.ITEM.getKey(seed));
    }

    public List<Grown> goals(ColonyView view) {
        if (!enabled.getAsBoolean()) return List.of();
        List<Body> farmers = farmers(view);
        if (farmers.isEmpty()) return List.of();
        ServerLevel level = view.level();
        List<io.github.izakyl.folkways.plugins.compost.SeedBudget.Reserve> reserves = budgets.apply(level).reserves();
        List<Grown> goals = new ArrayList<>();
        List<WorldPos> stores = colony.storesIn(level.dimension());
        if (!stores.isEmpty()) {
            WorldPos at = stores.getFirst();
            if (at.in(level)) Stances.of(level, Reach.workableCells(level, at.block(level))).ifPresent(stances -> {
                for (io.github.izakyl.folkways.plugins.compost.SeedBudget.Reserve reserve : reserves) {
                    if (reserve.missing() > 0) goals.add(Grown.of(new Keep(at, stances, reserve)));
                }
            });
        }
        if (reserves.stream().noneMatch(reserve -> reserve.missing() > 0
                && reserve.alternatives().stream().anyMatch(grassSeeds()::contains))) return goals;
        for (ZoneView zone : Fronts.of(colony).zonesIn(level)) {
            if (!zone.delegation().equals(GRASS) || !areas.containsKey(zone.id())) continue;
            Source running = active.get(zone.id());
            if (running != null && running.ground.in(level) && running.refusal(level).isEmpty()
                    && farmers.stream().anyMatch(farmer -> farmer.id().equals(running.worker))) {
                // Asking withdraws omitted goals. Keep the adopted turn wanted while its resident walks,
                // without admitting a second source through this zone's exclusive booking.
                goals.add(Grown.of(running));
                continue;
            }
            if (!bookings.free(areas.get(zone.id()))) continue;
            Optional<BlockPos> grass = zone.cells().stream().filter(level::isLoaded)
                .filter(ground -> level.getBlockState(ground.above()).is(Blocks.SHORT_GRASS))
                .sorted(BlockPos::compareTo).findFirst();
            if (grass.isPresent()) {
                offer(goals, level, zone, grass.get(), farmers.getFirst(), false);
            } else if (zone.settings().flag(RESTORE_GRASS)) {
                zone.cells().stream().filter(ground -> level.isLoaded(ground.above()) && level.getBlockState(ground.above()).isAir())
                    .sorted(Comparator.comparingDouble(ground -> ground.distSqr(zone.cells().iterator().next())))
                    .filter(ground -> GrassSources.safeRestore(level, ground, zone.cells())).findFirst()
                    .ifPresent(ground -> offer(goals, level, zone, ground, farmers.getFirst(), true));
            }
        }
        return goals;
    }

    private void offer(List<Grown> goals, ServerLevel level, ZoneView zone, BlockPos ground, Body farmer, boolean restore) {
        Stances.of(level, Reach.workableCells(level, ground.above())).ifPresent(stances ->
            goals.add(Grown.of(new Source(zone.id(), zone.at(ground), stances, farmer.id(), restore))));
    }

    private Optional<ZoneView> zone(ServerLevel level, UUID id) {
        return Fronts.of(colony).zonesIn(level).stream().filter(zone -> zone.id().equals(id) && zone.delegation().equals(GRASS)).map(ZoneView.class::cast).findFirst();
    }

    @Override
    public List<Line> zoneLines(ZoneView zone, ColonyView view) {
        if (!zone.delegation().equals(GRASS)) return List.of();
        String status = "ready";
        if (!budgets.apply(view.level()).complete()) status = "unknown_inventory";
        else if (farmers(view).isEmpty()) status = "need_hat";
        else if (budgets.apply(view.level()).reserves().stream().anyMatch(reserve -> reserve.missing() > 0
                && reserve.alternatives().stream().anyMatch(grassSeeds()::contains))) {
            if (zone.cells().stream().noneMatch(ground -> view.level().isLoaded(ground.above())
                    && view.level().getBlockState(ground.above()).is(Blocks.SHORT_GRASS))) {
                if (!zone.settings().flag(RESTORE_GRASS)) status = "need_grass";
                else if (zone.cells().stream().noneMatch(ground -> GrassSources.safeRestore(view.level(), ground, zone.cells()))) status = "need_growth_space";
                else status = Fronts.of(colony).stocked(view.level().getServer(),
                    io.github.izakyl.folkways.core.api.terms.Goods.specOf(Items.BONE_MEAL.getDefaultInstance())) == 0
                    ? "need_bonemeal" : "restoring";
            } else status = "gathering";
        }
        return List.of(Line.text(Component.translatable("folkways.page.cookery.seeds." + status)));
    }

    // A planner demand for bootstrap production, not a request to pick up the reserve stack.
    private final class Keep implements Node, StockCheck {
        private final NodeSpec spec;
        private final Item seed;
        Keep(WorldPos at, Stances stances, io.github.izakyl.folkways.plugins.compost.SeedBudget.Reserve reserve) {
            seed = reserve.alternatives().getFirst();
            spec = NodeSpec.of(keepId(at, seed), ID, new WorkSite.AtBlock(at), stances, Workload.Once.of(1))
                .vocation(FarmingContent.trade()).needs(new Need(reserve.goods(), reserve.count(), false))
                .doing(KEEPING).done();
        }
        public NodeSpec spec() { return spec; }
        private boolean present(ServerLevel level) {
            var observed = budgets.apply(level).frozen();
            return enabled.getAsBoolean() && observed.complete()
                && observed.tally(seed).missing() == 0;
        }
        public Staged.Drawn draw(ServerLevel level, Worker who) {
            return present(level) ? new Staged.Taken(List.of()) : new Staged.Short(SeedRefusal.RESERVE_UNAVAILABLE);
        }
        public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
            if (!goods.isEmpty()) throw new IllegalStateException("a seed stock check never draws material");
        }
        public Outcome commit(ServerLevel level, Worker who) {
            return present(level) ? Outcome.done() : Outcome.failed(SeedRefusal.RESERVE_UNAVAILABLE);
        }
    }

    private final class Source implements io.github.izakyl.folkways.cookery.gear.CookeryGear.SeedGathering {
        private final UUID zone;
        private final WorldPos ground;
        private final UUID worker;
        private final boolean restore;
        private final WorldPos anchor;
        private final long generation;
        private final Hold hold;
        private final NodeSpec spec;

        Source(UUID zone, WorldPos ground, Stances stances, UUID worker, boolean restore) {
            this.zone = zone;
            this.ground = ground;
            this.worker = worker;
            this.restore = restore;
            anchor = areas.get(zone);
            generation = bookings.generation(anchor);
            UUID id = named((restore ? "restore/" : "gather/") + colony.id() + "/" + zone + "/" + ground);
            hold = bookings.hold(anchor, id);
            spec = NodeSpec.of(id, ID, new WorkSite.AtBlock(ground), stances, Workload.Once.of(10))
                .vocation(FarmingContent.trade()).gesture(WorkGesture.SWING).leaves(Leaves.room(4))
                .needs(restore ? List.of(new Need(io.github.izakyl.folkways.core.api.terms.Goods.specOf(Items.BONE_MEAL.getDefaultInstance()), 1, true)) : List.of())
                .doing(restore ? RESTORING : GATHERING).done();
        }
        public NodeSpec spec() { return spec; }
        public boolean gathersGrass() { return !restore; }
        public Optional<UUID> worker() { return Optional.of(worker); }
        public Optional<RefusalKind> planned(ServerLevel level) {
            Optional<RefusalKind> refused = hold.take();
            if (refused.isEmpty()) active.put(zone, this);
            return refused;
        }
        public boolean ready(ServerLevel level) { return ground.in(level) && bookings.first(anchor, id()); }
        public Optional<RefusalKind> refusal(ServerLevel level) {
            return bookings.cancelled(anchor, id(), generation) || zone(level, zone)
                    .filter(marked -> !restore || marked.settings().flag(RESTORE_GRASS)).isEmpty()
                ? Optional.of(SeedRefusal.AREA_CHANGED) : Optional.empty();
        }
        public void ended(ServerLevel level, Ending how) { active.remove(zone, this); hold.release(how); sweep.nudge(); }
        public Outcome commit(ServerLevel level, Worker who) {
            Optional<ZoneView> marked = zone(level, zone);
            if (!enabled.getAsBoolean() || marked.isEmpty() || restore && !marked.get().settings().flag(RESTORE_GRASS) || !ground.in(level)
                    || bookings.cancelled(anchor, id(), generation) || !bookings.first(anchor, id())
                    || !who.body().getUUID().equals(worker)
                    || Bodies.of(who.body()).filter(body -> io.github.izakyl.folkways.cookery.gear.CookeryGear.permitsSeeds(colony, body)).isEmpty()) {
                return Outcome.failed(SeedRefusal.AREA_CHANGED);
            }
            if (budgets.apply(level).reserves().stream().noneMatch(reserve -> reserve.missing() > 0
                    && reserve.alternatives().stream().anyMatch(grassSeeds()::contains))) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), who.supplied());
            }
            BlockPos cell = ground.block(level);
            if (!marked.get().cells().contains(cell) || !Reach.stanceHolding(who.body(), spec.stances())
                    .flatMap(feet -> WorldSpaces.storage(level, new WorldPos(ground.realm(), feet)))
                    .filter(feet -> Reach.withinReach(feet, cell.above()) && Reach.inSight(level, feet, cell.above())).isPresent()) {
                return Outcome.failed(SeedRefusal.AREA_CHANGED);
            }
            if (!restore && !who.body().getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT)) return Outcome.failed(SeedRefusal.NO_STRAW_HAT);
            return restore ? GrassSources.restore(level, who, cell, marked.get().cells())
                : GrassSources.gather(level, who, cell.above(), marked.get().cells());
        }
    }

    private static Set<Item> grassSeeds() {
        return Set.of(Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.PUMPKIN_SEEDS, Items.MELON_SEEDS,
            ModItems.TOMATO_SEED.get(), ModItems.CHILI_SEED.get(), ModItems.WILD_RICE_SEED.get(),
            ModItems.LETTUCE_SEED.get(), ModItems.TEA_SEED.get());
    }

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("folkways", path); }
    private static UUID named(String text) { return UUID.nameUUIDFromBytes(("folkways:seeds/" + text).getBytes(StandardCharsets.UTF_8)); }
}
