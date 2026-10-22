package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.HashSet;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

// Furniture is surveyed once per colony sweep. Food stays in the real table or world block across reloads.
final class Dining {
    private final Colony colony;
    private final LivingPresence living;
    private final DiningSupplies supplies;
    private final java.util.random.RandomGenerator random;
    private volatile List<Table> tables = List.of();
    private final Map<WorldPos, UUID> seats = new ConcurrentHashMap<>();
    private final Map<Portion, UUID> portions = new ConcurrentHashMap<>();
    private final Map<UUID, Meal> meals = new ConcurrentHashMap<>();
    private final Map<Refill, ItemSpec> refills = new ConcurrentHashMap<>();

    record Seat(BlockPos chair, BlockPos approach) { }
    private static final int MAX_TABLE_CELLS = 128;
    private static final int MAX_DINING_ZONE_CELLS = 4096;
    record Table(ServerLevel level, UUID zone, BlockPos at, ItemSpec food, int target,
                 Set<BlockPos> tops, List<Seat> seats, List<BlockPos> approaches, Optional<ItemSpec> drinks) { }
    private record Portion(WorldPos at, int slot) { }
    private record Choice(Table table, Seat seat, int slot) { }
    private record DrinkChoice(Table table, int slot, ItemStack serving) { }
    private record Refill(Portion portion, UUID zone, ItemSpec menu, int target) { }

    private static Refill refill(Table table, int slot) {
        return new Refill(new Portion(WorldPos.of(table.level, table.at), slot), table.zone, drinkSlot(table, slot) ? table.drinks.orElseThrow() : table.food, table.target);
    }

    Dining(Colony colony, LivingPresence living) { this(colony, living, new java.util.Random()); }

    Dining(Colony colony, LivingPresence living, java.util.random.RandomGenerator random) {
        this.colony = colony;
        this.living = living;
        this.random = random;
        this.supplies = new DiningSupplies(colony, random);
    }

    void refresh(MinecraftServer server) {
        supplies.refresh(server);
        List<Table> found = new ArrayList<>();
        Set<WorldPos> seen = new HashSet<>();
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            for (var resident : view.residents()) {
                if (meals.values().stream().noneMatch(meal -> meal.who.resident().id().equals(resident.id()))
                        && level.getEntity(resident.id()) instanceof Mob body) { DiningTables.stand(body); }
            }
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
                                Math.clamp(zone.settings().count(LivingContent.DINING_SERVINGS.key()), 1, 4), tops, seats, List.copyOf(approaches),
                                zone.settings().items(LivingContent.DINING_DRINK.key())));
                        }
                    }
                }
            }
        }
        tables = List.copyOf(found);
        refills.keySet().removeIf(refill -> tables.stream().noneMatch(table -> refill.equals(refill(table, refill.portion.slot))));
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
        List<Grown> wanted = new ArrayList<>(supplies.goals(view));
        Set<Refill> pending = new HashSet<>();
        for (Table table : tables) {
            if (table.level != view.level() || !valid(table)) { continue; }
            Optional<Container> display = DiningTables.inventory(view.level(), table.at);
            int count = display.map(pack -> pack.getContainerSize()).orElse(1);
            for (int slot = 0; slot < count; slot++) {
                boolean drink = drinkSlot(table, slot);
                if (!drink && slot >= foodSlots(table)) { continue; }
                List<ItemSpec> accepted = drink ? acceptedDrinks(table) : accepted(table);
                if (accepted.isEmpty()) { continue; }
                if (display.isPresent() ? !display.get().getItem(slot).isEmpty()
                        : !view.level().getBlockState(table.at.above()).isAir()) { continue; }
                Refill refill = refill(table, slot);
                pending.add(refill);
                // Pick a dish before planning, so cheap recipes cannot monopolize a multi-food menu.
                // Keep that choice through repeated sweeps until this physical serving is delivered.
                ItemSpec food = refills.compute(refill, (key, chosen) -> chosen != null && accepted.contains(chosen)
                    ? chosen : accepted.get(random.nextInt(accepted.size())));
                wanted.add(Grown.of(new SetTable(table, slot, food)));
            }
        }
        refills.keySet().removeIf(refill -> refill.portion.at.in(view.level()) && !pending.contains(refill));
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
            for (Choice choice : choices(who, view)) {
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
        List<Choice> choices = new ArrayList<>();
        ServerLevel level = view.level();
        BlockPos from = WorldSpaces.at(who.body()).block(level);
        for (Table table : tables) {
            if (table.level != level || table.tops.stream().noneMatch(at -> at.distSqr(from) <= 16 * 16) || !valid(table)) { continue; }
            Optional<Container> display = DiningTables.inventory(level, table.at);
            int count = display.map(Container::getContainerSize).orElse(1);
            for (int slot = 0; slot < count; slot++) {
                if (portions.containsKey(new Portion(WorldPos.of(level, table.at), slot))) { continue; }
                Optional<DiningFoods.Serving> serving = serving(table, slot, who);
                if (serving.isEmpty() || !living.wouldEat(who.resident().id(), serving.get().food())) { continue; }
                for (Seat seat : table.seats) {
                    if (!seats.containsKey(WorldPos.of(level, seat.chair)) && valid(table, seat)
                            && DiningTables.available(level, seat.chair, who.body())) {
                        choices.add(new Choice(table, seat, slot));
                    }
                }
            }
        }
        choices.sort(Comparator.comparingDouble(choice -> choice.seat.approach.distSqr(from)));
        return choices;
    }

    private Optional<DiningFoods.Serving> serving(Table table, int slot, Worker who) {
        Optional<Container> display = DiningTables.inventory(table.level, table.at);
        if (display.isPresent()) {
            if (slot >= display.get().getContainerSize()) { return Optional.empty(); }
            ItemStack stack = display.get().getItem(slot);
            FoodProperties food = stack.isEmpty() || drinkSlot(table, slot) ? null : stack.getFoodProperties(who.body());
            return food == null || !Goods.matches(stack, table.food) ? Optional.empty()
                : Optional.of(new DiningFoods.Serving(stack.copyWithCount(1), food));
        }
        return DiningFoods.serving(table.level, table.at.above())
            .filter(portion -> Goods.matches(portion.item(), table.food));
    }

    private boolean valid(Table table) {
        return table.level.isLoaded(table.at) && DiningTables.table(table.level, table.at)
            && Fronts.of(colony).zonesIn(table.level).stream().anyMatch(zone -> zone.id().equals(table.zone)
                && zone.delegation().equals(LivingContent.DINING) && includes(zone, table.at)
                && Math.clamp(zone.settings().count(LivingContent.DINING_SERVINGS.key()), 1, 4) == table.target
                && zone.settings().items(LivingContent.DINING_FOOD.key()).filter(table.food::equals).isPresent()
                && zone.settings().items(LivingContent.DINING_DRINK.key()).equals(table.drinks)
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
        boolean display = DiningTables.inventory(table.level, table.at).isPresent();
        return Goods.members(table.food).stream().map(item -> item.getDefaultInstance())
            .filter(stack -> display ? edible(stack, null) : DiningFoods.placeable(stack))
            .map(stack -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem()))).toList();
    }

    private static boolean drinkSlot(Table table, int slot) {
        return table.drinks.isPresent() && DiningTables.inventory(table.level, table.at)
            .filter(display -> display.getContainerSize() > 1 && slot == display.getContainerSize() - 1).isPresent();
    }

    private static int foodSlots(Table table) {
        return DiningTables.inventory(table.level, table.at)
            .map(display -> Math.min(table.target, display.getContainerSize() - (table.drinks.isPresent() && display.getContainerSize() > 1 ? 1 : 0)))
            .orElse(1);
    }

    private static List<ItemSpec> acceptedDrinks(Table table) {
        return table.drinks.stream().flatMap(menu -> Goods.members(menu).stream())
            .map(item -> item.getDefaultInstance()).filter(DiningDrinks::accepts)
            .map(stack -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem()))).toList();
    }

    private Optional<DrinkChoice> reserveDrink(Choice choice, UUID token) {
        for (Table table : tables) {
            if (table.level != choice.table.level || !table.zone.equals(choice.table.zone)
                    || !table.tops.equals(choice.table.tops) || !valid(table) || table.drinks.isEmpty()) { continue; }
            var display = DiningTables.inventory(table.level, table.at).orElse(null);
            if (display == null) { continue; }
            int slot = display.getContainerSize() - 1;
            if (!drinkSlot(table, slot)) { continue; }
            ItemStack stack = display.getItem(slot);
            if (stack.isEmpty() || !Goods.matches(stack, table.drinks.get()) || !DiningDrinks.accepts(stack)) { continue; }
            Portion portion = new Portion(WorldPos.of(table.level, table.at), slot);
            if (portions.putIfAbsent(portion, token) == null) { return Optional.of(new DrinkChoice(table, slot, stack.copyWithCount(1))); }
        }
        return Optional.empty();
    }

    private boolean drinkPresent(DrinkChoice drink) {
        if (!valid(drink.table)) { return false; }
        var display = DiningTables.inventory(drink.table.level, drink.table.at).orElse(null);
        if (display == null) { return false; }
        var actual = display.getItem(drink.slot);
        return !actual.isEmpty() && ItemStack.isSameItemSameComponents(actual, drink.serving);
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
        for (Meal meal : List.copyOf(meals.values())) {
            if (!meal.present(false) || meal.engaged && !meal.present(true)
                    || meal.choice.table.level.getGameTime() > meal.expires
                    || server.getTickCount() % 10 == 0 && (!valid(meal.choice.table)
                        || !valid(meal.choice.table, meal.choice.seat) || unsafe(meal.who.body())
                        || !DiningTables.available(meal.choice.table.level, meal.choice.seat.chair, meal.who.body()))) { meal.finish(); }
        }
    }

    void clear() { List.copyOf(meals.values()).forEach(Meal::finish); tables = List.of(); refills.clear(); supplies.clear(); }

    List<Line> lines(ZoneView zone, ColonyView view) {
        List<Table> here = tables.stream().filter(table -> table.zone.equals(zone.id())).toList();
        long count = here.stream().map(Table::tops).distinct().count();
        long chairs = here.stream().flatMap(table -> table.seats.stream()).map(Seat::chair).distinct().count();
        return List.of(Line.text(Component.translatable("folkways.dining.summary", count, chairs)),
            Line.text(Component.translatable(here.isEmpty() ? "folkways.dining.furniture"
                : here.stream().allMatch(table -> accepted(table).isEmpty()) ? "folkways.dining.no_food" : "folkways.dining.help")));
    }

    private final class SetTable implements Node {
        final Table table;
        final int slot;
        final ItemSpec food;
        final NodeSpec spec;
        SetTable(Table table, int slot, ItemSpec food) {
            this.table = table; this.slot = slot; this.food = food;
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
            if (display.isPresent() ? slot >= display.get().getContainerSize() || !drinkSlot(table, slot) && slot >= foodSlots(table)
                    || !display.get().getItem(slot).isEmpty() : !level.getBlockState(table.at.above()).isAir()) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
            }
            for (ItemStack stack : back) {
                if (stack.isEmpty() || !Goods.matches(stack, food)) { continue; }
                if (display.isPresent()) {
                    if (!(drinkSlot(table, slot) ? DiningDrinks.accepts(stack) : edible(stack, who.body())) || !display.get().canPlaceItem(slot, stack)) { continue; }
                    display.get().setItem(slot, stack.copyWithCount(1));
                    display.get().setChanged();
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
        final Optional<DrinkChoice> drink;
        final long expires;
        final NodeSpec spec;
        volatile boolean finished;
        boolean engaged;
        Meal(UUID token, Worker who, Choice choice) {
            this.token = token; this.who = who; this.choice = choice;
            this.drink = reserveDrink(choice, token);
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
                        if (tick >= Math.max(0, duration - 40) && !who.body().getPersistentData().getBoolean("folkways:dining_drinking")) {
                            drink.filter(Dining.this::drinkPresent).ifPresent(serving -> {
                                who.body().getPersistentData().putBoolean("folkways:dining_drinking", true);
                                io.github.izakyl.folkways.core.api.resident.body.Bodies.of(who.body())
                                    .ifPresent(actor -> actor.presentHeldItem(serving.serving.copy()));
                            });
                        }
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
        @Override public Optional<io.github.izakyl.folkways.core.api.terms.RefusalKind> refusal(ServerLevel level) {
            return level != choice.table.level || finished || !present(engaged)
                    || !valid(choice.table) || !valid(choice.table, choice.seat) || unsafe(who.body())
                    || !DiningTables.available(level, choice.seat.chair, who.body())
                ? Optional.of(LivingRefusal.NOTHING_TO_EAT) : Optional.empty();
        }
        @Override public Outcome commit(ServerLevel level, Worker eater) {
            if (refusal(level).isPresent() || eater.body() != who.body() || !present(true)) {
                finish(); return Outcome.failed(LivingRefusal.NOTHING_TO_EAT);
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
                } else {
                    List<ItemStack> left = DiningFoods.eat(level, choice.table.at.above(), eater);
                    living.ate(eater.resident().id(), portion.get().food(), portion.get().item(), level.getGameTime());
                    outcome = new Outcome.Done(List.of(), left, Optional.empty());
                }
                if (outcome instanceof Outcome.Done done) {
                    living.dined(eater.resident().id(), level.getGameTime());
                    List<ItemStack> made = new ArrayList<>(done.made());
                    drink.ifPresent(serving -> {
                        if (!valid(serving.table)) { return; }
                        var inventory = DiningTables.inventory(level, serving.table.at).orElse(null);
                        if (inventory == null) { return; }
                        ItemStack actual = inventory.getItem(serving.slot);
                        if (ItemStack.isSameItemSameComponents(actual, serving.serving) && !actual.isEmpty()) {
                            made.addAll(DiningDrinks.consume(level, eater.body(), inventory, serving.slot));
                        }
                    });
                    outcome = new Outcome.Done(done.noises(), made, done.xp(), done.unused());
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
            drink.ifPresent(serving -> portions.remove(new Portion(WorldPos.of(serving.table.level, serving.table.at), serving.slot), token));
            meals.remove(token, this);
            Runnable stand = () -> {
                DiningTables.stand(who.body());
                who.body().getPersistentData().remove("folkways:dining_drinking");
                io.github.izakyl.folkways.core.api.resident.body.Bodies.of(who.body()).ifPresent(body -> body.clearHandPresentation());
                if (who.body() instanceof ResidentEntity person && person.leisureMotion().equals("dining")) { person.leisureMotion(""); }
            };
            MinecraftServer server = choice.table.level.getServer();
            if (server.isSameThread()) { stand.run(); } else { server.execute(stand); }
        }
    }
}
