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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private volatile List<Table> tables = List.of();
    private final Map<WorldPos, UUID> seats = new ConcurrentHashMap<>();
    private final Map<Portion, UUID> portions = new ConcurrentHashMap<>();
    private final Map<UUID, Meal> meals = new ConcurrentHashMap<>();

    record Seat(BlockPos chair, BlockPos approach) { }
    record Table(ServerLevel level, UUID zone, BlockPos at, ItemSpec food, int target, List<Seat> seats) { }
    private record Portion(WorldPos at, int slot) { }
    private record Choice(Table table, Seat seat, int slot) { }

    Dining(Colony colony, LivingPresence living) { this.colony = colony; this.living = living; }

    void refresh(MinecraftServer server) {
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
                // A one-layer outline may mark the floor beneath the furniture or the furniture itself.
                for (BlockPos at : zone.cells().stream().flatMap(cell -> java.util.stream.Stream.of(cell, cell.above())).toList()) {
                    if (!level.isLoaded(at) || !DiningTables.table(level, at)
                            || seen.contains(WorldPos.of(level, at))) { continue; }
                    List<Seat> chairs = new ArrayList<>();
                    for (Direction side : Direction.Plane.HORIZONTAL) {
                        BlockPos chair = at.relative(side);
                        if (!includes(zone, chair) || !level.isLoaded(chair)
                                || !DiningTables.chair(level, chair)) { continue; }
                        // Walk to an aisle beside the chair; a native seat provider mounts the actual chair.
                        for (Direction aisle : Direction.Plane.HORIZONTAL) {
                            BlockPos approach = chair.relative(aisle);
                            if (!approach.equals(at) && level.isLoaded(approach)
                                    && Footing.withFeetAt(level, approach) && level.getFluidState(approach).isEmpty()) {
                                chairs.add(new Seat(chair.immutable(), approach.immutable())); break;
                            }
                        }
                    }
                    if (!chairs.isEmpty()) {
                        seen.add(WorldPos.of(level, at));
                        found.add(new Table(level, zone.id(), at.immutable(), food.get(),
                            Math.clamp(zone.settings().count(LivingContent.DINING_SERVINGS.key()), 1, 4), List.copyOf(chairs)));
                    }
                }
            }
        }
        tables = List.copyOf(found);
    }

    List<Grown> goals(ColonyView view) {
        List<Grown> wanted = new ArrayList<>();
        for (Table table : tables) {
            if (table.level != view.level() || !valid(table)) { continue; }
            Optional<Container> display = DiningTables.inventory(view.level(), table.at);
            List<ItemSpec> accepted = accepted(table);
            if (accepted.isEmpty()) { continue; }
            ItemSpec food = ItemSpec.anyOf(accepted);
            int count = display.map(pack -> Math.min(table.target, pack.getContainerSize())).orElse(1);
            for (int slot = 0; slot < count; slot++) {
                if (display.isPresent() ? !display.get().getItem(slot).isEmpty()
                        : !view.level().getBlockState(table.at.above()).isAir()) { continue; }
                wanted.add(Grown.of(new SetTable(table, slot, food)));
            }
        }
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
            if (table.level != level || table.at.distSqr(from) > 16 * 16 || !valid(table)) { continue; }
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
            FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(who.body());
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
                && zone.settings().items(LivingContent.DINING_FOOD.key()).filter(table.food::equals).isPresent());
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

    private static boolean unsafe(Mob body) {
        return !body.isAlive() || body.isOnFire() || body.getLastHurtByMob() != null
            && body.tickCount - body.getLastHurtByMobTimestamp() < 100
            || !body.level().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(6), mob -> mob instanceof Enemy).isEmpty();
    }

    private static boolean near(Table table, Mob body) {
        return WorldSpaces.world(table.level, WorldPos.of(table.level, table.at))
            .map(at -> body.position().distanceToSqr(at) <= 4 * 4).orElse(false);
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

    void clear() { List.copyOf(meals.values()).forEach(Meal::finish); tables = List.of(); }

    List<Line> lines(ZoneView zone, ColonyView view) {
        List<Table> here = tables.stream().filter(table -> table.zone.equals(zone.id())).toList();
        int chairs = here.stream().mapToInt(table -> table.seats.size()).sum();
        return List.of(Line.text(Component.translatable("folkways.dining.summary", here.size(), chairs)),
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
            UUID id = UUID.nameUUIDFromBytes(("dining/" + table.zone + "/" + table.at + "/" + slot + "/" + food.describe())
                .getBytes(StandardCharsets.UTF_8));
            spec = NodeSpec.of(id, LivingContent.ID, WorkSite.at(table.level, table.at),
                Stances.of(table.level, table.seats.stream().map(Seat::approach).toList()).orElseThrow(), Workload.Once.of(20))
                .needs(new Need(food, 1)).doing(LivingContent.SETTING_TABLE, food).focus(table.at).done();
        }
        @Override public NodeSpec spec() { return spec; }
        @Override public Outcome commit(ServerLevel level, Worker who) {
            List<ItemStack> back = new ArrayList<>(who.supplied().stream().map(ItemStack::copy).toList());
            if (level != table.level || who.body().level() != level || !near(table, who.body())
                    || !valid(table) || table.seats.stream().noneMatch(seat -> valid(table, seat))) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
            }
            Optional<Container> display = DiningTables.inventory(level, table.at);
            if (display.isPresent() ? slot >= Math.min(table.target, display.get().getContainerSize())
                    || !display.get().getItem(slot).isEmpty() : !level.getBlockState(table.at.above()).isAir()) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
            }
            for (ItemStack stack : back) {
                if (stack.isEmpty() || !Goods.matches(stack, food)) { continue; }
                if (display.isPresent()) {
                    if (!edible(stack, who.body()) || !display.get().canPlaceItem(slot, stack)) { continue; }
                    display.get().setItem(slot, stack.copyWithCount(1));
                    display.get().setChanged();
                    stack.shrink(1);
                } else {
                    ItemStack actual = stack.copyWithCount(1);
                    if (!DiningFoods.place(level, table.at.above(), actual)) { continue; }
                    stack.shrink(1);
                }
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
            expires = choice.table.level.getGameTime() + 600;
            spec = NodeSpec.of(token, LivingContent.ID, WorkSite.at(choice.table.level, choice.table.at),
                Stances.at(choice.table.level, choice.seat.approach), Workload.Once.of(100))
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
                && level.getEntity(body.getUUID()) == body && (!nearby || near(choice.table, body));
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
                if (outcome instanceof Outcome.Done) { living.dined(eater.resident().id(), level.getGameTime()); }
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
                if (who.body() instanceof ResidentEntity person && person.leisureMotion().equals("dining")) { person.leisureMotion(""); }
            };
            MinecraftServer server = choice.table.level.getServer();
            if (server.isSameThread()) { stand.run(); } else { server.execute(stand); }
        }
    }
}
