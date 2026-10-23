package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Modifier;
import io.github.izakyl.folkways.effect.Stats;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.patrol.Wards;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

final class LivingPresence implements Facing {

    private static final int VITALS_INTERVAL = 10;

    // A resident carries food for this many days, counted by what it nourishes, and goes for more once down to
    // this many days' worth: one walk to the pantry in a couple of days, not one after every meal.
    static final int RATION_DAYS = 3;
    static final int RESTOCK_DAYS = 1;


    static final ResourceLocation EAT = ResourceLocation.fromNamespaceAndPath("folkways", "eat");

    static final ResourceLocation SLEEP = ResourceLocation.fromNamespaceAndPath("folkways", "sleep");

    private static final ResourceLocation UNCLAIMED =
        ResourceLocation.fromNamespaceAndPath("folkways", "delegation/bed");

    private static final Notice UNCLAIMED_SAID = new Notice("folkways.page.living.unclaimed", List.of());

    private final Colony colony;

    private final Sweep sweep;
    private final Beds beds = new Beds();

    private final Watermark hunger = new Watermark(14, 4, Hunger.REGEN_FOOD_LEVEL);

    private final Watermark tiredness =
        new Watermark(Rest.SPENT - Rest.SLEEPY, 0, Rest.SPENT);

    private final Map<UUID, Hunger> bellies = new LinkedHashMap<>();

    private final Map<UUID, Rest> wear = new LinkedHashMap<>();

    private final Map<UUID, Mood> moods = new LinkedHashMap<>();
    private final HousingRooms rooms = new HousingRooms();
    private final ColonyPets pets;
    private final Leisure leisure;
    private final Dining dining;

    Mood mood(UUID resident) { return moods.computeIfAbsent(resident, ignored -> new Mood()); }
    void changed() { unwritten = true; }
    void changed(ServerLevel level, BlockPos at) { rooms.changed(level, at); }
    void petRemoved(UUID id) {
        if (pets.removed(id)) { unwritten = true; write(); }
    }

    Effects effects(UUID resident) {
        Mood mood = moods.get(resident);
        return mood == null ? Effects.NONE
            : Effects.of(List.of(
                new Effects.Part("mood",
                    Modifier.multiply(Stats.WORK_SPEED, Mood.speed(mood.value)))));
    }

    private boolean unwritten;

    LivingPresence(Colony colony) {
        this.colony = colony;
        this.pets = new ColonyPets(colony, rooms);
        this.leisure = new Leisure(colony, this);
        this.dining = new Dining(colony, this);
        this.sweep = new Sweep(colony, LivingContent.ID, 100);
        load(Reader.of(colony.kept(LivingContent.ID)));
    }

    void refresh(MinecraftServer server) {
        dining.refresh(server);
        Set<UUID> belonging = new LinkedHashSet<>();
        for (Resident resident : colony.residents()) {
            belonging.add(resident.id());
        }
        for (ColonyView view : colony.views(server)) {
            unwritten |= beds.sweep(view, belonging);
            rooms.retain(view.level(), view.blocks(LivingContent.BED));
        }
        unwritten |= bellies.keySet().retainAll(belonging);
        unwritten |= wear.keySet().retainAll(belonging);
        unwritten |= moods.keySet().retainAll(belonging);
        leisure.retain(belonging);
        hunger.retain(belonging);
        tiredness.retain(belonging);
        write();
    }

    public void tick(MinecraftServer server) {
        dining.tick(server);
        sweep.tick(server, this::refresh, this::goals);
        rooms.tick();
        if (pets.tick(server)) { unwritten = true; write(); }
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            boolean naturalRegen = level.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION);
            for (Resident resident : view.residents()) {
                if (!LivingContent.looksAfter(resident)) {
                    continue;
                }
                Entity found = level.getEntity(resident.id());
                if (!(found instanceof LivingEntity living)) {
                    continue;
                }
                Hunger his = bellies.computeIfAbsent(resident.id(), id -> new Hunger());
                his.setExhaustionRateMul((float) view.effectsOf(resident).value(Stats.APPETITE));
                if (server.getTickCount() % VITALS_INTERVAL == 0) {
                    step(living, his, level, naturalRegen);
                    Mood mood = mood(resident.id());
                    Optional<WorldPos> bed = bedFor(resident.id()).filter(at -> at.in(level));
                    int home = bed.isEmpty() ? -12 : rooms.get(level, bed.get().block(level)).grade().mood;
                    if (living.getLastHurtByMob() != null
                            && living.tickCount - living.getLastHurtByMobTimestamp() < VITALS_INTERVAL) {
                        if (level.getGameTime() - mood.attacked >= 100) { mood.value = Math.max(0, mood.value - 4); }
                        mood.attacked = level.getGameTime();
                    }
                    boolean safe = Wards.warded(level, living.blockPosition());
                    boolean rain = level.isRainingAt(living.blockPosition());
                    mood.comfortable = rooms.comfortableAt(level, WorldSpaces.at(living).block(level));
                    Effects traits = view.effectsOf(resident);
                    mood.step(level.getGameTime(), his.foodLevel(), alertnessOf(resident.id()).orElse(Rest.SPENT),
                        home, safe, rain, traits.value(Stats.DISCOMFORT), Wards.comfort(level, living.blockPosition()));
                    unwritten = true;
                }
            }
        }
    }

    void exerted(Body body, WorkExertion effort) {
        if (!LivingContent.looksAfter(body.resident())) {
            return;
        }
        ServerLevel level = (ServerLevel) body.mob().level();
        Hunger his = bellies.computeIfAbsent(body.id(), id -> new Hunger());
        his.setExhaustionRateMul((float) body.effects().value(Stats.APPETITE));
        his.addExhaustion((float) (effort.workDone() * Hunger.WORK_EXHAUSTION_PER_UNIT
            + effort.distance() * Hunger.WALK_EXHAUSTION_PER_BLOCK), level.getDifficulty());
        double fatigue = body.effects().value(Stats.FATIGUE);
        boolean hauling = body.doing().map(doing -> doing.toward().orElse(doing.what()).getPath())
            .filter(doing -> doing.equals("fetching") || doing.equals("stowing") || doing.equals("picking_up")).isPresent();
        double travelFatigue = hauling ? body.effects().value(Stats.FATIGUE,
            Vocations.HAULING) : fatigue;
        wear.computeIfAbsent(body.id(), id -> new Rest()).spend(effort.activeTicks() * fatigue, effort.distance() * travelFatigue);
        unwritten = true;
    }

    private void step(LivingEntity body, Hunger his, ServerLevel level, boolean naturalRegen) {
        float delta = his.stepVitals(VITALS_INTERVAL, body.getHealth(), body.getMaxHealth(),
            level.getDifficulty(), naturalRegen);
        if (delta > 0.0F) {
            unwritten = true;
            body.heal(delta);
        } else if (delta < 0.0F) {
            body.hurt(level.damageSources().starve(), -delta);
        }
    }

    public List<Urge> urges(Worker who, ColonyView colony) {
        List<Urge> felt = new ArrayList<>();
        eating(who, colony).ifPresent(felt::add);
        sleeping(who, colony).ifPresent(felt::add);
        if (felt.isEmpty() && this.colony.residents().contains(who.resident())) {
            dining.drinking(who, colony).ifPresent(felt::add);
            if (felt.isEmpty()) { leisure.urge(who, colony).ifPresent(felt::add); }
        }
        return List.copyOf(felt);
    }

    private Optional<Urge> eating(Worker who, ColonyView colony) {
        Optional<Integer> level = foodLevelOf(who.resident().id());
        if (level.isEmpty() || !hunger.holds(who.resident().id(), level.get())) {
            return Optional.empty();
        }
        ServerLevel where = colony.level();
        BlockPos at = who.body().blockPosition();
        boolean urgent = hunger.urgent(level.get());
        double weight = urgent ? Urge.NOW : Urge.SPARE;
        Optional<Urge> meal = dining.urge(who, colony, weight, urgent || !who.effects().has(Stats.PATIENT));
        if (meal.isPresent()) { return meal; }
        if (!carriesAMeal(who)) { return Optional.empty(); }
        return Optional.of(new Urge(EAT, weight, urgent || !who.effects().has(Stats.PATIENT),
            () -> new EatAction(where, this, who.resident().id(), at)));
    }

    private Optional<Urge> sleeping(Worker who, ColonyView colony) {
        Optional<Integer> alertness = alertnessOf(who.resident().id());
        if (alertness.isEmpty() || !tiredness.holds(who.resident().id(), alertness.get())) {
            return Optional.empty();
        }
        ServerLevel level = colony.level();
        Optional<WorldPos> bed = bedFor(who.resident().id()).filter(where -> where.in(level));
        if (bed.isEmpty()) {
            return Optional.empty();
        }
        BlockPos where = bed.get().block(level);
        boolean urgent = tiredness.urgent(alertness.get());
        double weight = urgent ? Urge.NOW : Urge.SPARE;
        return Optional.of(new Urge(SLEEP, weight, urgent || !who.effects().has(Stats.PATIENT),
            () -> new SleepAction(this, level, where, ticksToSleepOff(who.resident().id()))));
    }

    private boolean carriesAMeal(Worker who) {
        Container pack = who.pack();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(who.body());
            if (wouldEat(who.resident().id(), stack, food)) {
                return true;
            }
        }
        return false;
    }

    Optional<Integer> foodLevelOf(UUID resident) {
        Hunger belly = bellies.get(resident);
        return belly == null ? Optional.empty() : Optional.of(belly.foodLevel());
    }

    Optional<Integer> alertnessOf(UUID resident) {
        Rest tired = wear.get(resident);
        return tired == null ? Optional.empty() : Optional.of(tired.alertness());
    }

    int ticksToSleepOff(UUID resident) {
        Rest tired = wear.get(resident);
        return tired == null ? 1 : tired.ticksToSleepOff();
    }

    boolean wouldEat(UUID resident, ItemStack stack, FoodProperties food) {
        Hunger belly = bellies.get(resident);
        return belly != null && FoodSafety.usable(Fronts.of(colony).settings(LivingContent.ID), stack, food)
            && belly.canEatWithoutWaste(food)
            && FoodSafety.matches(Fronts.of(colony).settings(LivingContent.ID), stack);
    }

    void ate(UUID resident, FoodProperties food, ItemStack eaten, long now) {
        Hunger belly = bellies.get(resident);
        if (belly != null) {
            belly.eat(food);
            Mood mood = mood(resident);
            mood.meals.put(foodGroup(eaten), now);
            if (eaten.is(FINE_MEALS)) { mood.fineMeal = now; }
            sweep.nudge();
            unwritten = true;
            write();
        }
    }

    void dined(UUID resident, long now) {
        mood(resident).dined = now;
        sweep.nudge();
        unwritten = true;
        write();
    }

    void drank(UUID resident, long now) {
        mood(resident).drank = now;
        sweep.nudge();
        unwritten = true;
        write();
    }

    static final TagKey<Item> FINE_MEALS = TagKey.create(
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath("folkways", "fine_meals"));

    private static String foodGroup(ItemStack stack) {
        for (String group : List.of("meat", "fish", "stew", "grain", "vegetable", "fruit")) {
            var tag = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("folkways", "food_groups/" + group));
            if (stack.is(tag)) { return group; }
        }
        return "other";
    }

    boolean slept(UUID resident, int ticks, ServerLevel level) {
        Rest tired = wear.get(resident);
        if (tired == null) {
            return false;
        }
        tired.sleep(ticks);
        long now = level.getGameTime();
        Mood mood = mood(resident);
        mood.rested = now;
        bedFor(resident).ifPresent(bed -> {
            for (ColonyView view : colony.views(level.getServer())) {
                if (!bed.in(view.level())) { continue; }
                HousingRooms.Room room = rooms.get(view.level(), bed.block(view.level()));
                if (room.grade() != HousingRooms.Grade.PENDING) { mood.housing = room.grade().mood; mood.housed = now; }
            }
        });
        unwritten = true;
        write();
        return true;
    }

    Optional<WorldPos> bedFor(UUID resident) {
        return beds.bedFor(resident);
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> wanted = new ArrayList<>(dining.goals(view));
        Optional<ItemSpec> allowed = LivingContent.food(colony);
        if (allowed.isEmpty()) {
            return List.copyOf(wanted);
        }
        ItemSpec food = allowed.get();
        ServerLevel level = view.level();
        for (Resident resident : view.residents()) {
            if (!LivingContent.looksAfter(resident)) {
                continue;
            }
            Entity body = level.getEntity(resident.id());
            if (body == null) {
                continue;
            }
            int carried = Bodies.of(body).map(had -> nourishing(had.pack(), food, had.mob())).orElse(0);
            if (carried >= RESTOCK_DAYS * Hunger.FOOD_PER_DAY) {
                continue;
            }
            wanted.add(Grown.of(Delivery.to(idOf(resident.id(), food), LivingContent.ID,
                    WorkSite.on(body), Stances.WHEREVER,
                    food, mealsFor(food, RATION_DAYS * Hunger.FOOD_PER_DAY - carried), count -> sweep.nudge())));
        }
        return List.copyOf(wanted);
    }

    // How much the food in the pack nourishes, of the food the colony eats.
    private int nourishing(Container pack, ItemSpec food, LivingEntity body) {
        int points = 0;
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            FoodProperties eaten = stack.isEmpty() ? null : stack.getFoodProperties(body);
            if (FoodSafety.usable(Fronts.of(colony).settings(LivingContent.ID), stack, eaten) && Goods.matches(stack, food)) {
                points += eaten.nutrition() * stack.getCount();
            }
        }
        return points;
    }

    // How many pieces of the food nourish this much, taking one piece for the middling of what the colony eats:
    // which of it is fetched is the plan's to say.
    static long mealsFor(ItemSpec food, int points) {
        int whole = 0;
        int kinds = 0;
        for (Item item : Goods.members(food)) {
            FoodProperties eaten = item.getDefaultInstance().getFoodProperties(null);
            if (eaten != null && eaten.nutrition() > 0) {
                whole += eaten.nutrition();
                kinds++;
            }
        }
        double each = kinds == 0 ? 1.0D : (double) whole / kinds;
        return Math.max(1L, (long) Math.ceil(points / each));
    }

    private static UUID idOf(UUID resident, ItemSpec food) {
        String said = LivingContent.ID + "|" + resident + "|" + food.describe() + "|" + RATION_DAYS;
        return UUID.nameUUIDFromBytes(said.getBytes(StandardCharsets.UTF_8));
    }

    public void closed(Closing why) {
        dining.clear();
        if (why == Closing.RAZED) {
            beds.clear();
            bellies.clear();
            wear.clear();
            moods.clear();
            leisure.clear();
            pets.clear();
            hunger.forget();
            tiredness.forget();
            unwritten = false;
            return;
        }
        write();
    }

    @Override
    public List<Line> zoneLines(io.github.izakyl.folkways.front.api.ZoneView zone, ColonyView view) {
        return zone.delegation().equals(LivingContent.DINING) ? dining.lines(zone, view) : List.of();
    }

    @Override
    public List<Line> lookLines(UUID resident, ColonyView colony) {
        Hunger his = bellies.get(resident);
        if (his == null) {
            return List.of();
        }
        int alertness = alertnessOf(resident).orElse(Rest.SPENT);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.gauge(LivingContent.FULLNESS, his.foodLevel(), Hunger.MAX_FOOD_LEVEL));
        lines.add(Line.gauge(LivingContent.ALERTNESS, alertness, Rest.SPENT));
        Mood mood = mood(resident);
        lines.add(Line.text(Component.translatable("folkways.mood.summary", (int) Math.round(mood.value),
            (int) Math.round(Mood.speed(mood.value) * 100), (int) Math.round(mood.leisure))));
        bedFor(resident).filter(at -> at.in(colony.level())).ifPresent(at ->
            lines.add(Line.text(rooms.get(colony.level(), at.block(colony.level())).grade().title())));
        return List.copyOf(lines);
    }

    List<Component> details(UUID resident, ColonyView view) {
        Mood mood = mood(resident);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("folkways.mood.summary", (int) Math.round(mood.value),
            (int) Math.round(Mood.speed(mood.value) * 100), (int) Math.round(mood.leisure)));
        for (String reason : List.of("food", "dining", "drink", "housing", "rest", "leisure", "social", "safety", "environment")) {
            int value = mood.reasons.getOrDefault(reason, 0);
            lines.add(Component.translatable("folkways.mood.reason." + reason, (value > 0 ? "+" : "") + value));
        }
        bedFor(resident).filter(at -> at.in(view.level())).ifPresent(at -> {
            HousingRooms.Room room = rooms.get(view.level(), at.block(view.level()));
            lines.add(room.description()); lines.add(room.advice());
        });
        return List.copyOf(lines);
    }

    @Override
    public List<Line> blockLines(BlockPos at, ColonyView view) {
        if (!view.blocks(LivingContent.BED).contains(at)) { return List.of(); }
        HousingRooms.Room room = rooms.get(view.level(), at);
        return List.of(Line.text(room.description()), Line.text(room.advice()));
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView colony) {
        if (!page.equals(PersonContent.ID)) {
            return Optional.empty();
        }
        ServerLevel level = colony.level();
        List<Board.Row> rows = new ArrayList<>();
        rows.add(Board.Row.heading(Component.translatable("folkways.page.living")));
        int mouths = mouths(colony);
        for (SettlerNeed need : settlerNeeds(level.getServer())) {
            rows.add(Board.Row.of(new ItemStack(BuiltInRegistries.ITEM.get(need.kind().icon())),
                Component.translatable(need.kind().nameKey()),
                Component.literal(need.have() + " / " + need.want(mouths))).told(told(need, mouths)));
        }
        rows.add(new Board.Row(ItemStack.EMPTY,
            Component.translatable(LivingContent.FOOD_MODE.nameKey()), Component.empty(),
            List.of(new Board.Act.Edit(LivingContent.FOOD_MODE.key()),
                new Board.Act.Edit(LivingContent.FOOD_SAFETY.key()))).wide());
        rows.add(new Board.Row(new ItemStack(BuiltInRegistries.ITEM.get(LivingContent.FOOD.icon())),
            Component.translatable(LivingContent.FOOD.nameKey()),
            Component.empty(),
            List.of(new Board.Act.Edit(LivingContent.FOOD.key()))));
        List<WorldPos> free = beds.freeMemberBeds(colony);
        for (WorldPos cell : free) {
            if (!cell.in(level)) {
                continue;
            }
            rows.add(new Board.Row(new ItemStack(level.getBlockState(cell.block(level)).getBlock()),
                level.getBlockState(cell.block(level)).getBlock().getName(),
                Component.translatable("folkways.page.living.unclaimed"),
                List.of(new Board.Act.Ping(cell.block(level))))
                .told(Sentence.of(Sentence.glyph(UNCLAIMED), Sentence.word(UNCLAIMED_SAID))));
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.living.free",
                Component.literal(Integer.toString(free.size())))),
            rows,
            Optional.empty()));
    }

    // "✗ bread₁₂ / 15": what is there of the need, a slash, and what it wants, marked short when it falls short.
    private static Sentence told(SettlerNeed need, int mouths) {
        ResourceLocation item = need.counts().flatMap(Doings::item).orElse(need.kind().icon());
        List<Sentence.Token> tokens = new ArrayList<>();
        if (!need.met(mouths)) {
            tokens.add(Sentence.glyph("lacks"));
        }
        tokens.add(Sentence.ware(item, need.have()));
        tokens.add(Sentence.glyph("slash"));
        tokens.add(Sentence.word(Notice.count(need.want(mouths))));
        return new Sentence(tokens);
    }

    private static int mouths(ColonyView colony) {
        return (int) colony.residents().stream().filter(LivingContent::looksAfter).count();
    }

    // Across every place the colony holds: a free bed each, and stored food for every mouth plus one.
    List<SettlerNeed> settlerNeeds(MinecraftServer server) {
        int freeBeds = 0;
        for (ColonyView view : colony.views(server)) {
            if (beds.house(view)) {
                unwritten = true;
            }
            freeBeds += beds.freeMemberBeds(view).size();
        }
        write();
        SettlerNeed bed = new SettlerNeed(LivingNeed.BED, freeBeds, 1);
        return List.of(bed, LivingContent.food(colony)
            .map(food -> SettlerNeed.counting(LivingNeed.FOOD, food,
                    FolkwaysVanillaConfig.foodPerResident(), SettlerNeed.Per.RESIDENT)
                .filled(Fronts.of(colony).stocked(server, food)))
            .orElseGet(() -> new SettlerNeed(LivingNeed.FOOD, 0,
                FolkwaysVanillaConfig.foodPerResident(), SettlerNeed.Per.RESIDENT, Optional.empty())));
    }

    private void write() {
        if (!unwritten) {
            return;
        }
        unwritten = false;
        colony.keep(LivingContent.ID, save());
    }

    private CompoundTag save() {
        return Writer.of(beds.save())
            .blob("pets", pets.save())
            .children("hunger", bellies.entrySet(),
                entry -> stamped(entry.getKey(), entry.getValue().save()))
            .children("rest", wear.entrySet(),
                entry -> stamped(entry.getKey(), entry.getValue().save()))
            .children("moods", moods.entrySet(), entry -> stamped(entry.getKey(), entry.getValue().save()))
            .tag();
    }

    private static CompoundTag stamped(UUID resident, CompoundTag saved) {
        return Writer.of(saved).uuid("resident", resident).tag();
    }

    private void load(Reader reader) {
        beds.load(reader);
        reader.child("pets").ifPresent(pets::load);
        for (Reader saved : reader.children("moods")) {
            saved.uuid("resident").ifPresent(id -> { Mood mood = new Mood(); mood.load(saved); moods.put(id, mood); });
        }
        for (Reader belly : reader.children("hunger")) {
            belly.uuid("resident").ifPresent(resident -> {
                Hunger his = new Hunger();
                his.load(belly);
                bellies.put(resident, his);
            });
        }
        for (Reader spent : reader.children("rest")) {
            spent.uuid("resident").ifPresent(resident -> {
                Rest tired = new Rest();
                tired.load(spent);
                wear.put(resident, tired);
            });
        }
    }
}
