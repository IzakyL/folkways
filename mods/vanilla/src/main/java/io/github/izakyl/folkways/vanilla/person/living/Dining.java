package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.BlockChanges;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// Furniture is surveyed once per colony sweep. Food stays in the real table or world block across reloads.
final class Dining {
    private final Colony colony;
    private final LivingPresence living;
    private final DiningSupplies supplies;
    private final java.util.random.RandomGenerator random;
    private volatile List<Table> tables = List.of();
    private List<ZoneKey> geometry = List.of();
    private final List<BlockChanges.Heard> watches = new ArrayList<>();
    private boolean geometryDirty = true;
    private final Map<WorldPos, Servings> servings = new HashMap<>();
    private final Map<WorldPos, UUID> seats = new ConcurrentHashMap<>();
    private final Map<Portion, UUID> portions = new ConcurrentHashMap<>();
    private final Map<UUID, BarDrink> drinks = new ConcurrentHashMap<>();
    private final Map<UUID, Meal> meals = new ConcurrentHashMap<>();
    private final Map<Refill, ItemSpec> refills = new ConcurrentHashMap<>();

    record Seat(BlockPos chair, BlockPos approach) { }
    private static final int MAX_TABLE_CELLS = 128;
    private static final int MAX_DINING_ZONE_CELLS = 4096;
    record Table(ServerLevel level, UUID zone, BlockPos at, ItemSpec food, int target,
                 Set<BlockPos> tops, List<Seat> seats, List<BlockPos> approaches, boolean display, boolean provider) { }
    private record Portion(WorldPos at, int slot) { }
    private record Choice(Table table, Seat seat, int slot, InventorySnapshot.Version version, ItemStack expected) { }
    private record Servings(InventorySnapshot.Version version, ItemSpec menu, List<Integer> slots) { }
    private record ZoneKey(ResourceKey<Level> dimension, UUID id, Set<BlockPos> cells, Optional<ItemSpec> food,
                           Optional<ItemSpec> drink, int target, Set<Long> loaded) { }
    private record Refill(Portion portion, UUID zone, ItemSpec menu, int target) { }

    private static Refill refill(Table table, int slot) {
        return new Refill(new Portion(WorldPos.of(table.level, table.at), slot), table.zone, table.food, table.target);
    }

    Dining(Colony colony, LivingPresence living) { this(colony, living, new java.util.Random()); }

    Dining(Colony colony, LivingPresence living, java.util.random.RandomGenerator random) {
        this.colony = colony;
        this.living = living;
        this.random = random;
        this.supplies = new DiningSupplies(colony, random);
    }

    void refresh(MinecraftServer server) {
        for (ColonyView view : colony.views(server)) {
            for (var resident : view.residents()) {
                if (meals.values().stream().noneMatch(meal -> meal.who.resident().id().equals(resident.id()))
                        && drinks.values().stream().noneMatch(drink -> drink.who.resident().id().equals(resident.id()))
                        && view.level().getEntity(resident.id()) instanceof Mob body) { DiningTables.stand(body); }
            }
        }
        List<ZoneKey> next = geometry(server);
        if (!geometryDirty && geometry.equals(next)) { return; }
        geometryDirty = false;
        geometry = next;
        watches.forEach(BlockChanges.Heard::close);
        watches.clear();
        for (ColonyView view : colony.views(server)) {
            for (ZoneView zone : Fronts.of(colony).zonesIn(view.level())) {
                if (!zone.delegation().equals(LivingContent.DINING) || zone.cells().isEmpty()
                        || zone.cells().size() > MAX_DINING_ZONE_CELLS) { continue; }
                var box = BoundingBox.encapsulatingPositions(zone.cells()).orElseThrow().inflatedBy(3);
                watches.add(BlockChanges.listen(view.level(), box, at -> geometryDirty = true));
            }
        }
        supplies.refresh(server);
        List<Table> found = new ArrayList<>();
        Set<WorldPos> seen = new HashSet<>();
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            for (ZoneView zone : Fronts.of(colony).zonesIn(level)) {
                if (!zone.delegation().equals(LivingContent.DINING)) { continue; }
                Optional<ItemSpec> food = zone.settings().items(LivingContent.DINING_FOOD.key());
                if (food.isEmpty()) { continue; }
                Set<BlockPos> candidates = tableCells(level, zone);
                Set<BlockPos> remaining = new HashSet<>(candidates);
                while (!remaining.isEmpty()) {
                    BlockPos first = remaining.iterator().next();
                    Set<BlockPos> tops = component(first, candidates);
                    remaining.removeAll(tops);
                    if (tops.size() > MAX_TABLE_CELLS || tops.stream().anyMatch(at -> seen.contains(WorldPos.of(level, at)))) { continue; }
                    var chairs = new LinkedHashMap<BlockPos, Seat>();
                    for (BlockPos at : tops) for (Direction side : Direction.Plane.HORIZONTAL) {
                        BlockPos chair = at.relative(side);
                        if (tops.contains(chair) || !includes(zone, chair) || !level.isLoaded(chair)
                                || !DiningTables.chair(level, chair)) { continue; }
                        // Every physical chair appears once, even when it borders several table cells.
                        for (Direction aisle : Direction.Plane.HORIZONTAL) {
                            BlockPos approach = chair.relative(aisle);
                            if (!tops.contains(approach) && level.isLoaded(approach)
                                    && Footing.withFeetAt(level, approach) && level.getFluidState(approach).isEmpty()) {
                                chairs.putIfAbsent(chair.immutable(), new Seat(chair.immutable(), approach.immutable())); break;
                            }
                        }
                    }
                    if (!chairs.isEmpty()) {
                        List<Seat> seats = List.copyOf(chairs.values());
                        Set<BlockPos> approaches = new HashSet<>();
                        seats.forEach(seat -> approaches.add(seat.approach));
                        for (BlockPos at : tops) for (Direction side : Direction.Plane.HORIZONTAL) {
                            BlockPos edge = at.relative(side);
                            if (!tops.contains(edge) && level.isLoaded(edge) && Footing.withFeetAt(level, edge)
                                    && level.getFluidState(edge).isEmpty()) approaches.add(edge.immutable());
                        }
                        for (BlockPos at : tops) {
                            seen.add(WorldPos.of(level, at));
                            found.add(new Table(level, zone.id(), at, food.get(),
                                Math.clamp(zone.settings().count(LivingContent.DINING_SERVINGS.key()), 1, 4),
                                tops, seats, List.copyOf(approaches), DiningTables.inventory(level, at).isPresent(),
                                Containers.at(level, at).isEmpty()));
                        }
                    }
                }
            }
        }
        for (Table previous : tables) {
            if (unavailable(previous.level, previous.at) && current(previous)
                    && seen.add(WorldPos.of(previous.level, previous.at))) {
                found.add(previous);
            }
        }
        tables = List.copyOf(found);
        servings.clear();
        for (ServerLevel level : server.getAllLevels()) {
            colony.inventory().observe(DiningInventory.ID, level, tables.stream()
                .filter(table -> table.level == level && table.display && !table.provider)
                .map(table -> WorldPos.of(level, table.at)).toList());
            DiningInventory.update(colony, level, tables.stream()
                .filter(table -> table.level == level && table.display && table.provider)
                .map(table -> WorldPos.of(level, table.at))
                .sorted(Comparator.comparing(at -> at.toString())).toList(), complete(level));
        }
        refills.keySet().removeIf(refill -> tables.stream().noneMatch(table -> refill.equals(refill(table, refill.portion.slot))));
    }

    private boolean unavailable(ServerLevel level, BlockPos at) {
        return !level.isLoaded(at) || colony.inventory().snapshot().store(WorldPos.of(level, at))
            .filter(store -> store.state() == InventorySnapshot.State.UNLOADED).isPresent();
    }

    private boolean complete(ServerLevel level) {
        for (ZoneView zone : Fronts.of(colony).zonesIn(level)) {
            if (!zone.delegation().equals(LivingContent.DINING)) { continue; }
            if (zone.settings().items(LivingContent.DINING_FOOD.key()).isEmpty()
                    && zone.settings().items(LivingContent.DINING_DRINK.key()).isEmpty()) { continue; }
            for (BlockPos cell : zone.cells()) {
                if (!level.isLoaded(cell) || !level.isLoaded(cell.above())) { return false; }
            }
        }
        return true;
    }

    private List<ZoneKey> geometry(MinecraftServer server) {
        List<ZoneKey> result = new ArrayList<>();
        for (ColonyView view : colony.views(server)) {
            for (ZoneView zone : Fronts.of(colony).zonesIn(view.level())) {
                if (!zone.delegation().equals(LivingContent.DINING)) { continue; }
                Set<Long> chunks = new HashSet<>();
                for (BlockPos cell : zone.cells()) {
                    for (int x = (cell.getX() - 3) >> 4; x <= (cell.getX() + 3) >> 4; x++) {
                        for (int z = (cell.getZ() - 3) >> 4; z <= (cell.getZ() + 3) >> 4; z++) {
                            if (view.level().hasChunk(x, z)) { chunks.add(ChunkPos.asLong(x, z)); }
                        }
                    }
                }
                result.add(new ZoneKey(view.level().dimension(), zone.id(), Set.copyOf(zone.cells()),
                    zone.settings().items(LivingContent.DINING_FOOD.key()),
                    zone.settings().items(LivingContent.DINING_DRINK.key()),
                    zone.settings().count(LivingContent.DINING_SERVINGS.key()), Set.copyOf(chunks)));
            }
        }
        result.sort(Comparator.comparing((ZoneKey key) -> key.dimension.location().toString())
            .thenComparing(ZoneKey::id));
        return List.copyOf(result);
    }

    private static Set<BlockPos> tableCells(ServerLevel level, ZoneView zone) {
        // Inspect only explicitly marked cells and the furniture immediately above their floor.
        if (zone.cells().size() > MAX_DINING_ZONE_CELLS) { return Set.of(); }
        Set<BlockPos> cells = new HashSet<>();
        for (BlockPos floor : zone.cells()) for (BlockPos at : List.of(floor, floor.above())) {
            if (level.isLoaded(at) && DiningTables.table(level, at)) cells.add(at.immutable());
        }
        return cells;
    }

    private static Set<BlockPos> component(BlockPos first, Set<BlockPos> candidates) {
        if (!candidates.contains(first)) { return Set.of(); }
        Set<BlockPos> found = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        found.add(first); pending.add(first);
        while (!pending.isEmpty()) {
            BlockPos at = pending.remove();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = at.relative(side);
                if (candidates.contains(next) && found.add(next)) pending.add(next);
            }
        }
        return Set.copyOf(found);
    }

    List<Grown> goals(ColonyView view) {
        if (geometryDirty) { refresh(view.level().getServer()); }
        List<Grown> wanted = new ArrayList<>(supplies.goals(view));
        Set<Refill> pending = new HashSet<>();
        Set<WorldPos> unknown = new HashSet<>();
        var snapshot = colony.inventory().snapshot();
        for (Table table : tables) {
            if (table.level != view.level() || !current(table)) { continue; }
            var display = snapshot.store(WorldPos.of(view.level(), table.at)).orElse(null);
            if (table.display && (display == null || display.state() != InventorySnapshot.State.OBSERVED)) {
                colony.inventory().ensureObserved(Stash.at(WorldPos.of(view.level(), table.at)));
                unknown.add(WorldPos.of(view.level(), table.at));
                continue;
            }
            int count = table.display ? display.slots().size() : 1;
            for (int slot = 0; slot < count; slot++) {
                if (slot >= (table.display ? Math.min(table.target, count) : 1)) { continue; }
                List<ItemSpec> accepted = accepted(table);
                if (accepted.isEmpty()) { continue; }
                if (table.display ? !display.slots().get(slot).stackCopy().isEmpty()
                        : !view.level().getBlockState(table.at.above()).isAir()) { continue; }
                Refill refill = refill(table, slot);
                pending.add(refill);
                // Pick a dish before planning, so cheap recipes cannot monopolize a multi-food menu.
                // Keep that choice through repeated sweeps until this physical serving is delivered.
                ItemSpec food = refills.compute(refill, (key, chosen) -> chosen != null && accepted.contains(chosen)
                    ? chosen : accepted.get(random.nextInt(accepted.size())));
                wanted.add(Grown.of(new SetTable(table, slot, food, table.display ? display.version() : null)));
            }
        }
        refills.keySet().removeIf(refill -> refill.portion.at.in(view.level())
            && !unknown.contains(refill.portion.at) && !pending.contains(refill));
        return List.copyOf(wanted);
    }

    Optional<Urge> urge(Worker who, ColonyView view, double weight, boolean preempts) {
        // The urge must remain offered while its seat and portion belong to this resident, including while mounted.
        Optional<Meal> active = meals.values().stream().filter(meal -> !meal.finished
            && meal.who.resident().id().equals(who.resident().id())).findFirst();
        if (active.isPresent()) {
            Meal meal = active.get();
            return Optional.of(new Urge(LivingPresence.EAT, weight, preempts, () -> meal));
        }
        if (unsafe(who.body()) || who.body().isPassenger()) { return Optional.empty(); }
        List<Choice> available = choices(who, view);
        if (available.isEmpty()) { return Optional.empty(); }
        return Optional.of(new Urge(LivingPresence.EAT, weight, preempts, () -> {
            List<Choice> current = choices(who, view);
            for (Choice choice : current) {
                UUID token = UUID.randomUUID();
                WorldPos seat = WorldPos.of(view.level(), choice.seat.chair);
                Portion portion = new Portion(WorldPos.of(view.level(), choice.table.at), choice.slot);
                if (seats.putIfAbsent(seat, token) != null) { continue; }
                if (portions.putIfAbsent(portion, token) != null) { seats.remove(seat, token); continue; }
                Meal meal = new Meal(token, who, choice);
                meals.put(token, meal);
                return meal;
            }
            return new EatAction(view.level(), living, who.resident().id(), who.body().blockPosition());
        }));
    }

    private List<Choice> choices(Worker who, ColonyView view) {
        if (geometryDirty) { refresh(view.level().getServer()); }
        List<Choice> choices = new ArrayList<>();
        ServerLevel level = view.level();
        var snapshot = colony.inventory().snapshot();
        BlockPos from = WorldSpaces.at(who.body()).block(level);
        for (Table table : tables) {
            if (table.level != level || table.tops.stream().noneMatch(at -> at.distSqr(from) <= 16 * 16)
                    || !current(table)) { continue; }
            var display = snapshot.store(WorldPos.of(level, table.at)).orElse(null);
            if (table.display && (display == null || display.state() != InventorySnapshot.State.OBSERVED)) {
                colony.inventory().ensureObserved(Stash.at(WorldPos.of(level, table.at)));
                continue;
            }
            List<Integer> candidates = table.display ? servingSlots(table, display) : List.of(0);
            for (int slot : candidates) {
                if (portions.containsKey(new Portion(WorldPos.of(level, table.at), slot))) { continue; }
                Optional<DiningFoods.Serving> serving = table.display
                    ? serving(table, display.slots().get(slot).stackCopy(), who) : serving(table, slot, who);
                if (serving.isEmpty() || !living.wouldEat(who.resident().id(), serving.get().food())) { continue; }
                for (Seat seat : table.seats) {
                    if (!seats.containsKey(WorldPos.of(level, seat.chair))
                            && DiningTables.available(level, seat.chair, who.body())) {
                        choices.add(new Choice(table, seat, slot, table.display ? display.version() : null,
                            table.display ? display.slots().get(slot).stackCopy() : ItemStack.EMPTY));
                    }
                }
            }
        }
        choices.sort(Comparator.comparingDouble(choice -> choice.seat.approach.distSqr(from)));
        return choices;
    }

    private List<Integer> servingSlots(Table table, InventorySnapshot.Store display) {
        WorldPos at = WorldPos.of(table.level, table.at);
        Servings cached = servings.get(at);
        if (cached != null && cached.version.equals(display.version()) && cached.menu.equals(table.food)) {
            return cached.slots;
        }
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < display.slots().size(); slot++) {
            ItemStack stack = display.slots().get(slot).stackCopy();
            if (!stack.isEmpty() && Goods.matches(stack, table.food)) { slots.add(slot); }
        }
        servings.put(at, new Servings(display.version(), table.food, List.copyOf(slots)));
        return slots;
    }

    private Optional<DiningFoods.Serving> serving(Table table, ItemStack stack, Worker who) {
        FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(who.body());
        return food == null || !Goods.matches(stack, table.food) ? Optional.empty()
            : Optional.of(new DiningFoods.Serving(stack.copyWithCount(1), food));
    }

    private Optional<DiningFoods.Serving> serving(Table table, int slot, Worker who) {
        Optional<Container> display = DiningTables.inventory(table.level, table.at);
        if (display.isPresent()) {
            if (slot >= display.get().getContainerSize()) { return Optional.empty(); }
            ItemStack stack = display.get().getItem(slot);
            FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(who.body());
            return food == null || !Goods.matches(stack, table.food) ? Optional.empty()
                : Optional.of(new DiningFoods.Serving(stack.copyWithCount(1), food));
        }
        return DiningFoods.serving(table.level, table.at.above())
            .filter(portion -> Goods.matches(portion.item(), table.food));
    }

    private boolean current(Table table) {
        return Fronts.of(colony).zonesIn(table.level).stream().anyMatch(zone -> zone.id().equals(table.zone)
            && zone.delegation().equals(LivingContent.DINING) && includes(zone, table.at)
            && Math.clamp(zone.settings().count(LivingContent.DINING_SERVINGS.key()), 1, 4) == table.target
            && zone.settings().items(LivingContent.DINING_FOOD.key()).filter(table.food::equals).isPresent());
    }

    private boolean valid(Table table) {
        return table.level.isLoaded(table.at) && DiningTables.table(table.level, table.at)
            && Fronts.of(colony).zonesIn(table.level).stream().anyMatch(zone -> zone.id().equals(table.zone)
                && zone.delegation().equals(LivingContent.DINING) && includes(zone, table.at)
                && Math.clamp(zone.settings().count(LivingContent.DINING_SERVINGS.key()), 1, 4) == table.target
                && zone.settings().items(LivingContent.DINING_FOOD.key()).filter(table.food::equals).isPresent()
                && sameTops(table, zone));
    }

    private static boolean sameTops(Table table, ZoneView zone) {
        if (zone.cells().size() > MAX_DINING_ZONE_CELLS) { return false; }
        // The surveyed component was connected. An unchanged member set and unchanged boundary
        // prove it still is, without repeatedly scanning the whole dining zone for every diner.
        for (BlockPos top : table.tops) {
            if (!includes(zone, top) || !table.level.isLoaded(top) || !DiningTables.table(table.level, top)) { return false; }
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = top.relative(side);
                if (!table.tops.contains(next) && includes(zone, next) && table.level.isLoaded(next)
                        && DiningTables.table(table.level, next)) { return false; }
            }
        }
        return true;
    }

    private boolean valid(Table table, Seat seat) {
        return table.level.isLoaded(seat.chair) && DiningTables.chair(table.level, seat.chair)
            && table.level.isLoaded(seat.approach) && Footing.withFeetAt(table.level, seat.approach)
            && Fronts.of(colony).zonesIn(table.level).stream().anyMatch(zone -> zone.id().equals(table.zone)
                && includes(zone, seat.chair));
    }

    private static boolean includes(ZoneView zone, BlockPos furniture) {
        return zone.cells().contains(furniture) || zone.cells().contains(furniture.below());
    }

    static boolean edible(ItemStack stack, Mob body) {
        FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(body);
        return food != null && food.nutrition() > 0;
    }

    private static List<ItemSpec> accepted(Table table) {
        boolean display = table.display;
        return Goods.members(table.food).stream().map(item -> item.getDefaultInstance())
            .filter(stack -> display ? edible(stack, null) : DiningFoods.placeable(stack))
            .map(stack -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem()))).toList();
    }

    private static int foodSlots(Table table) {
        return DiningTables.inventory(table.level, table.at).map(display -> Math.min(table.target, display.getContainerSize())).orElse(1);
    }

    private static boolean unsafe(Mob body) {
        return !body.isAlive() || body.isOnFire() || body.getLastHurtByMob() != null
            && body.tickCount - body.getLastHurtByMobTimestamp() < 100
            || !body.level().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(6), mob -> mob instanceof Enemy).isEmpty();
    }

    private static boolean near(Table table, Mob body) {
        return table.tops.stream().anyMatch(top -> WorldSpaces.world(table.level, WorldPos.of(table.level, top))
            .map(at -> body.position().distanceToSqr(at) <= 4 * 4).orElse(false));
    }

    void tick(MinecraftServer server) {
        for (BarDrink drink : List.copyOf(drinks.values())) {
            if (drink.refusal(drink.cabinet.level()).isPresent() || drink.cabinet.level().getGameTime() > drink.expires) { drink.finish(); }
        }
        for (Meal meal : List.copyOf(meals.values())) {
            if (!meal.present(false) || meal.engaged && !meal.present(true)
                    || meal.choice.table.level.getGameTime() > meal.expires
                    || server.getTickCount() % 10 == 0 && (!valid(meal.choice.table)
                        || !valid(meal.choice.table, meal.choice.seat) || unsafe(meal.who.body())
                        || !DiningTables.available(meal.choice.table.level, meal.choice.seat.chair, meal.who.body()))) { meal.finish(); }
        }
    }

    void clear() {
        List.copyOf(drinks.values()).forEach(BarDrink::finish);
        List.copyOf(meals.values()).forEach(Meal::finish);
        tables.stream().map(Table::level).distinct().forEach(level -> {
            colony.inventory().observe(DiningInventory.ID, level, List.of());
            DiningInventory.update(colony, level, List.of(), true);
        });
        DiningInventory.forget(colony);
        watches.forEach(BlockChanges.Heard::close);
        watches.clear();
        geometry = List.of();
        geometryDirty = true;
        servings.clear();
        tables = List.of();
        refills.clear();
        supplies.clear();
    }

    List<Line> lines(ZoneView zone, ColonyView view) {
        List<Table> here = tables.stream().filter(table -> table.zone.equals(zone.id())).toList();
        long count = here.stream().map(Table::tops).distinct().count();
        long chairs = here.stream().flatMap(table -> table.seats.stream()).map(Seat::chair).distinct().count();
        return List.of(Line.text(Component.translatable("folkways.dining.summary", count, chairs)));
    }

    /** A stocked service counter with an adjacent chair offers drinks independently of meals. */
    Optional<Urge> drinking(Worker who, ColonyView view) {
        if (geometryDirty) { refresh(view.level().getServer()); }
        Optional<BarDrink> active = drinks.values().stream().filter(drink -> drink.who.body() == who.body()).findFirst();
        if (active.isPresent()) { return Optional.of(new Urge(LivingPresence.EAT, Urge.SPARE, false, active::get)); }
        if (unsafe(who.body()) || who.body().isPassenger()) { return Optional.empty(); }
        var history = who.body().getPersistentData();
        if (history.contains("folkways_vanilla:last_table_drink")
                && view.level().getGameTime() - history.getLong("folkways_vanilla:last_table_drink") < Mood.DAY / 4) { return Optional.empty(); }
        for (var cabinet : supplies.cabinets().stream().sorted(Comparator.comparingDouble(one -> one.at().distSqr(who.body().blockPosition()))).toList()) {
            if (cabinet.level() != view.level() || cabinet.at().distSqr(who.body().blockPosition()) > 16 * 16
                    || !supplies.current(cabinet)) { continue; }
            var stock = colony.inventory().snapshot().store(WorldPos.of(view.level(), cabinet.at())).orElse(null);
            if (stock == null || stock.state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObserved(Stash.at(WorldPos.of(view.level(), cabinet.at())));
                continue;
            }
            for (Seat seat : cabinet.seats()) {
                BlockPos chair = seat.chair;
                BlockPos approach = seat.approach;
                if (!barChair(cabinet, chair, who)) { continue; }
                for (var observed : stock.slots()) {
                    int slot = observed.physicalSlot();
                    ItemStack serving = observed.stackCopy();
                    Portion portion = new Portion(WorldPos.of(view.level(), cabinet.at()), slot);
                    if (serving.isEmpty() || !Goods.matches(serving, cabinet.menu()) || !DiningDrinks.accepts(serving)
                            || portions.containsKey(portion)) { continue; }
                    ItemStack bottle = serving.copyWithCount(1);
                    return Optional.of(new Urge(LivingPresence.EAT, Urge.SPARE, false, () -> {
                        UUID token = UUID.randomUUID();
                        WorldPos chairPos = WorldPos.of(view.level(), chair);
                        if (seats.putIfAbsent(chairPos, token) != null) { return unavailableBar(cabinet, approach); }
                        if (portions.putIfAbsent(portion, token) != null) {
                            seats.remove(chairPos, token);
                            return unavailableBar(cabinet, approach);
                        }
                        BarDrink drink = new BarDrink(token, who, cabinet, seat, slot, bottle, stock.version());
                        drinks.put(token, drink);
                        return drink;
                    }));
                }
            }
        }
        return Optional.empty();
    }

    private static Node unavailableBar(DiningSupplies.Cabinet cabinet, BlockPos approach) {
        return new Node() {
            private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID, WorkSite.at(cabinet.level(), cabinet.at()),
                Stances.at(cabinet.level(), approach), Workload.Once.of(1)).effort(WorkEffort.NONE)
                .doing(LivingContent.DINING_DRINKING).focus(cabinet.at()).done();
            public NodeSpec spec() { return spec; }
            public Optional<io.github.izakyl.folkways.core.resident.api.RefusalKind> refusal(ServerLevel level) {
                return Optional.of(LivingRefusal.NOTHING_TO_EAT);
            }
            public Outcome commit(ServerLevel level, Worker worker) { return Outcome.failed(LivingRefusal.NOTHING_TO_EAT); }
        };
    }

    private boolean barChair(DiningSupplies.Cabinet cabinet, BlockPos chair, Worker who) {
        return !seats.containsKey(WorldPos.of(cabinet.level(), chair)) && DiningTables.chair(cabinet.level(), chair)
            && DiningTables.available(cabinet.level(), chair, who.body())
            && Fronts.of(colony).zonesIn(cabinet.level()).stream().anyMatch(zone -> zone.id().equals(cabinet.zone()) && includes(zone, chair));
    }

    private final class BarDrink implements Node {
        final UUID token;
        final Worker who;
        final DiningSupplies.Cabinet cabinet;
        final Seat seat;
        final int slot;
        final ItemStack bottle;
        final InventorySnapshot.Version observed;
        final NodeSpec spec;
        final long expires;
        boolean finished;
        boolean engaged;
        BarDrink(UUID token, Worker who, DiningSupplies.Cabinet cabinet, Seat seat, int slot, ItemStack bottle,
                 InventorySnapshot.Version observed) {
            this.token = token; this.who = who; this.cabinet = cabinet; this.seat = seat;
            this.slot = slot; this.bottle = bottle; this.observed = observed;
            int duration = FolkwaysVanillaConfig.diningMealTicks();
            expires = cabinet.level().getGameTime() + 500 + duration;
            spec = NodeSpec.of(token, LivingContent.ID, WorkSite.at(cabinet.level(), cabinet.at()),
                Stances.at(cabinet.level(), seat.approach), Workload.Once.of(duration))
                .effort(WorkEffort.NONE).doing(LivingContent.DINING_DRINKING).focus(cabinet.at())
                .gesture(new WorkGesture() {
                    public void perform(net.minecraft.world.entity.LivingEntity body, int tick, BlockPos focus) {
                        if (refusal(cabinet.level()).isPresent()) { finish(); return; }
                        engaged = true;
                        if (!body.isPassenger()) { DiningTables.sit(cabinet.level(), seat.chair, who.body()); }
                        if (body instanceof ResidentEntity person) { person.leisureMotion("drinking"); }
                        body.getPersistentData().putBoolean("folkways_vanilla:dining_drinking", true);
                        io.github.izakyl.folkways.core.resident.api.Bodies.of(who.body()).ifPresent(actor -> actor.presentHeldItem(bottle));
                        who.body().getLookControl().setLookAt(cabinet.at().getX() + .5, cabinet.at().getY() + 1, cabinet.at().getZ() + .5);
                    }
                    public void release(net.minecraft.world.entity.LivingEntity body) { finish(); }
                }).done();
        }
        public NodeSpec spec() { return spec; }

        public boolean ready(ServerLevel level) {
            WorldPos at = WorldPos.of(cabinet.level(), cabinet.at());
            var stock = colony.inventory().snapshot().store(at);
            if (stock.isPresent() && stock.get().state() == InventorySnapshot.State.OBSERVED) {
                return true;
            }
            colony.inventory().ensureObserved(Stash.at(at));
            return false;
        }

        public Optional<io.github.izakyl.folkways.core.resident.api.RefusalKind> refusal(ServerLevel level) {
            var stock = colony.inventory().snapshot().store(WorldPos.of(cabinet.level(), cabinet.at())).orElse(null);
            boolean serving = stock == null || stock.state() != InventorySnapshot.State.OBSERVED
                || stock.version().epoch().equals(observed.epoch())
                    && stock.slots().stream().anyMatch(item -> item.physicalSlot() == slot
                        && ItemStack.isSameItemSameComponents(item.stackCopy(), bottle));
            return finished || level != cabinet.level() || who.body().level() != level || !who.body().isAlive()
                || who.body().isRemoved() || level.getEntity(who.body().getUUID()) != who.body() || unsafe(who.body())
                || !supplies.current(cabinet) || level.isLoaded(cabinet.at()) && !DiningDrinks.hasSupply(level, cabinet.at())
                || !DiningTables.chair(level, seat.chair)
                || !DiningTables.available(level, seat.chair, who.body())
                || !level.isLoaded(seat.approach) || !Footing.withFeetAt(level, seat.approach)
                || Fronts.of(colony).zonesIn(level).stream()
                    .noneMatch(zone -> zone.id().equals(cabinet.zone()) && includes(zone, seat.chair))
                || engaged && who.body().blockPosition().distSqr(seat.chair) > 16 || !serving
                ? Optional.of(LivingRefusal.NOTHING_TO_EAT) : Optional.empty();
        }

        public Outcome commit(ServerLevel level, Worker worker) {
            try {
                if (worker.body() != who.body() || refusal(level).isPresent() || who.body().blockPosition().distSqr(seat.chair) > 16) {
                    return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
                }
                var publication = colony.inventory().snapshot().store(WorldPos.of(level, cabinet.at())).orElse(null);
                if (publication == null || publication.state() != InventorySnapshot.State.OBSERVED) {
                    return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
                }
                Container stock = DiningDrinks.supply(level, cabinet.at()).orElse(null);
                if (stock == null || slot >= stock.getContainerSize()
                        || !ItemStack.isSameItemSameComponents(stock.getItem(slot), bottle)) {
                    colony.inventoryFeedback().stale(Stash.at(WorldPos.of(level, cabinet.at())),
                        publication.version());
                    return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
                }
                int before = who.body().getPersistentData().getInt("folkways_vanilla:table_drinks");
                List<ItemStack> left = DiningDrinks.consume(level, who.body(), stock, slot);
                if (who.body().getPersistentData().getInt("folkways_vanilla:table_drinks") == before) { return Outcome.failed(LivingRefusal.NOTHING_TO_EAT); }
                colony.inventoryFeedback().changed(Stash.at(WorldPos.of(level, cabinet.at())));
                living.drank(who.resident().id(), level.getGameTime());
                return new Outcome.Done(List.of(), left, Optional.empty());
            } finally { finish(); }
        }
        public void released(ServerLevel level, Worker worker, Ending how) { finish(); }
        public void ended(ServerLevel level, Ending how) { finish(); }
        synchronized void finish() {
            if (finished) { return; }
            finished = true;
            seats.remove(WorldPos.of(cabinet.level(), seat.chair), token);
            portions.remove(new Portion(WorldPos.of(cabinet.level(), cabinet.at()), slot), token);
            drinks.remove(token, this);
            Runnable stand = () -> {
                DiningTables.stand(who.body());
                who.body().getPersistentData().remove("folkways_vanilla:dining_drinking");
                io.github.izakyl.folkways.core.resident.api.Bodies.of(who.body()).ifPresent(actor -> actor.clearHandPresentation());
                if (who.body() instanceof ResidentEntity person && person.leisureMotion().equals("drinking")) { person.leisureMotion(""); }
            };
            if (cabinet.level().getServer().isSameThread()) { stand.run(); } else { cabinet.level().getServer().execute(stand); }
        }
    }

    private final class SetTable implements Node {
        final Table table;
        final int slot;
        final ItemSpec food;
        final NodeSpec spec;
        final InventorySnapshot.Version observed;
        SetTable(Table table, int slot, ItemSpec food, InventorySnapshot.Version observed) {
            this.table = table; this.slot = slot; this.food = food; this.observed = observed;
            UUID id = UUID.nameUUIDFromBytes(("dining/" + table.zone + "/" + table.at + "/" + slot + "/" + food.describe() + "/" + table.tops.stream().sorted(BlockPos::compareTo).toList() + "/" + table.target)
                .getBytes(StandardCharsets.UTF_8));
            spec = NodeSpec.of(id, LivingContent.ID, WorkSite.at(table.level, table.at),
                Stances.of(table.level, table.approaches).orElseThrow(), Workload.Once.of(20))
                .needs(new Need(food, 1)).doing(LivingContent.SETTING_TABLE, food).focus(table.at).done();
        }
        @Override public NodeSpec spec() { return spec; }
        @Override public Outcome commit(ServerLevel level, Worker who) {
            List<ItemStack> back = new ArrayList<>(who.supplied().stream().map(ItemStack::copy).toList());
            if (level != table.level || who.body().level() != level || !near(table, who.body())
                    || !valid(table) || table.seats.stream().noneMatch(seat -> valid(table, seat))
                    || table.approaches.stream().noneMatch(at -> level.isLoaded(at) && Footing.withFeetAt(level, at)
                        && level.getFluidState(at).isEmpty())) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
            }
            Optional<Container> display = DiningTables.inventory(level, table.at);
            if (display.isPresent() ? slot >= display.get().getContainerSize() || slot >= foodSlots(table)
                    || !display.get().getItem(slot).isEmpty() : !level.getBlockState(table.at.above()).isAir()) {
                if (observed != null) {
                    colony.inventoryFeedback().stale(Stash.at(WorldPos.of(level, table.at)), observed);
                }
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
            }
            for (ItemStack stack : back) {
                if (stack.isEmpty() || !Goods.matches(stack, food)) { continue; }
                if (display.isPresent()) {
                    if (!edible(stack, who.body()) || !display.get().canPlaceItem(slot, stack)) { continue; }
                    display.get().setItem(slot, stack.copyWithCount(1));
                    display.get().setChanged();
                    colony.inventoryFeedback().changed(Stash.at(WorldPos.of(level, table.at)));
                    stack.shrink(1);
                } else {
                    ItemStack actual = stack.copyWithCount(1);
                    if (!DiningFoods.place(level, table.at.above(), actual)) { continue; }
                    stack.shrink(1);
                }
                refills.remove(refill(table, slot), food);
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), back.stream().filter(item -> !item.isEmpty()).toList());
            }
            return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
        }
    }

    private final class Meal implements Node {
        final UUID token;
        final Worker who;
        final Choice choice;
        final long expires;
        final NodeSpec spec;
        volatile boolean finished;
        boolean engaged;
        Meal(UUID token, Worker who, Choice choice) {
            this.token = token; this.who = who; this.choice = choice;
            int duration = FolkwaysVanillaConfig.diningMealTicks();
            expires = choice.table.level.getGameTime() + 500 + duration;
            spec = NodeSpec.of(token, LivingContent.ID, WorkSite.at(choice.table.level, choice.table.at),
                Stances.at(choice.table.level, choice.seat.approach), Workload.Once.of(duration))
                .effort(WorkEffort.NONE).doing(LivingContent.DINING_EATING).focus(choice.table.at)
                .gesture(new WorkGesture() {
                    @Override public void perform(net.minecraft.world.entity.LivingEntity body, int tick, BlockPos focus) {
                        if (finished || !Meal.this.present(true) || unsafe(who.body())) { finish(); return; }
                        engaged = true;
                        if (!body.isPassenger()) { DiningTables.sit(choice.table.level, choice.seat.chair, who.body()); }
                        if (body instanceof ResidentEntity person) { person.leisureMotion("dining"); }
                        who.body().getLookControl().setLookAt(choice.table.at.getX() + 0.5, choice.table.at.getY() + 1, choice.table.at.getZ() + 0.5);
                    }
                    @Override public void release(net.minecraft.world.entity.LivingEntity body) { finish(); }
                }).done();
        }
        @Override public NodeSpec spec() { return spec; }
        boolean present(boolean nearby) {
            Mob body = who.body();
            ServerLevel level = choice.table.level;
            return body.level() == level && body.isAlive() && !body.isRemoved()
                && level.getEntity(body.getUUID()) == body && (!nearby || WorldSpaces.world(level,
                    WorldPos.of(level, choice.seat.chair)).map(at -> body.position().distanceToSqr(at) <= 4 * 4).orElse(false));
        }
        @Override public Optional<io.github.izakyl.folkways.core.resident.api.RefusalKind> refusal(ServerLevel level) {
            return level != choice.table.level || finished || !present(engaged)
                    || !valid(choice.table) || !valid(choice.table, choice.seat) || unsafe(who.body())
                    || !DiningTables.available(level, choice.seat.chair, who.body())
                ? Optional.of(LivingRefusal.NOTHING_TO_EAT) : Optional.empty();
        }
        @Override public Outcome commit(ServerLevel level, Worker eater) {
            if (refusal(level).isPresent() || eater.body() != who.body() || !present(true)) {
                finish(); return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
            }
            if (choice.version != null) {
                var current = DiningTables.inventory(level, choice.table.at).orElse(null);
                if (current == null || choice.slot >= current.getContainerSize()
                        || !ItemStack.isSameItemSameComponents(current.getItem(choice.slot), choice.expected)) {
                    colony.inventoryFeedback().stale(Stash.at(WorldPos.of(level, choice.table.at)), choice.version);
                    finish();
                    return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
                }
            }
            Optional<DiningFoods.Serving> portion = serving(choice.table, choice.slot, eater);
            if (portion.isEmpty() || !living.wouldEat(eater.resident().id(), portion.get().food())) {
                finish(); return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
            }
            try {
                Optional<Container> display = DiningTables.inventory(level, choice.table.at);
                Outcome outcome;
                if (display.isPresent()) {
                    outcome = EatAction.consume(level, eater, living, display.get(), choice.slot);
                    colony.inventoryFeedback().changed(Stash.at(WorldPos.of(level, choice.table.at)));
                } else {
                    List<ItemStack> left = DiningFoods.eat(level, choice.table.at.above(), eater);
                    living.ate(eater.resident().id(), portion.get().food(), portion.get().item(), level.getGameTime());
                    outcome = new Outcome.Done(List.of(), left, Optional.empty());
                }
                if (outcome instanceof Outcome.Done) {
                    living.dined(eater.resident().id(), level.getGameTime());
                }
                return outcome;
            } finally { finish(); }
        }
        @Override public void released(ServerLevel level, Worker worker, Ending how) { finish(); }
        @Override public void ended(ServerLevel level, Ending how) { finish(); }
        synchronized void finish() {
            if (finished) { return; }
            finished = true;
            seats.remove(WorldPos.of(choice.table.level, choice.seat.chair), token);
            portions.remove(new Portion(WorldPos.of(choice.table.level, choice.table.at), choice.slot), token);
            meals.remove(token, this);
            Runnable stand = () -> {
                DiningTables.stand(who.body());
                who.body().getPersistentData().remove("folkways_vanilla:dining_drinking");
                io.github.izakyl.folkways.core.resident.api.Bodies.of(who.body()).ifPresent(body -> body.clearHandPresentation());
                if (who.body() instanceof ResidentEntity person && person.leisureMotion().equals("dining")) { person.leisureMotion(""); }
            };
            MinecraftServer server = choice.table.level.getServer();
            if (server.isSameThread()) { stand.run(); } else { server.execute(stand); }
        }
    }
}
