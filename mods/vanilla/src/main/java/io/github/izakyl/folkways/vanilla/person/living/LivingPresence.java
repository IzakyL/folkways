package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.KitCapabilities;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkExertion;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.core.effect.Modifier;
import io.github.izakyl.folkways.core.effect.Stats;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.Placard;
import io.github.izakyl.folkways.core.front.api.notice.DebugText;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.api.notice.Notice;
import io.github.izakyl.folkways.core.front.api.notice.Sentence;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.core.combat.api.DefenseControl;
import io.github.izakyl.folkways.vanilla.patrol.Wards;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

final class LivingPresence implements Facing {

    private static final int VITALS_INTERVAL = 10;

    // A resident carries food for this many days, counted by what it nourishes, and goes for more once down to
    // this many days' worth: one walk to the pantry in a couple of days, not one after every meal.
    static final int RATION_DAYS = 3;
    static final int RESTOCK_DAYS = 1;

    static final ResourceLocation EAT = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "eat");

    static final ResourceLocation SLEEP = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "sleep");

    private static final ResourceLocation UNCLAIMED =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "delegation/bed");

    private static final Notice UNCLAIMED_SAID = new Notice("folkways.page.living.unclaimed", List.of());
    private static final Notice CLAIMED_SAID = new Notice("folkways.housing.claimed", List.of());

    private final Colony colony;

    private final Sweep sweep;
    private final Beds beds = new Beds();

    private final Watermark hunger = new Watermark(14, 4, Hunger.REGEN_FOOD_LEVEL);

    private final Watermark tiredness =
        new Watermark(Rest.SPENT - Rest.SLEEPY, 0, Rest.SPENT);

    private final Map<UUID, Hunger> bellies = new LinkedHashMap<>();

    private final Map<UUID, Rest> wear = new LinkedHashMap<>();
    private final Map<UUID, AwakeTime> awakeTime = new LinkedHashMap<>();

    private final Map<UUID, Mood> moods = new LinkedHashMap<>();
    private final HousingRooms rooms = new HousingRooms();
    private record Nameplate(List<String> lines, Set<WorldPos> beds) { }
    private final Map<WorldPos, Nameplate> nameplates = new LinkedHashMap<>();
    private final Set<WorldPos> editedSigns = new LinkedHashSet<>();
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
        awakeTime.keySet().retainAll(belonging);
        unwritten |= moods.keySet().retainAll(belonging);
        leisure.retain(belonging);
        hunger.retain(belonging);
        tiredness.retain(belonging);
        nameplates(server);
        write();
    }

    private void nameplates(MinecraftServer server) {
        Map<ServerLevel, Map<HousingRooms.Cached, Set<BlockPos>>> signs = new LinkedHashMap<>();
        for (ColonyView view : colony.views(server)) { signs.put(view.level(), rooms.signsOf(view.level())); }
        // A sign broken and placed again is a new sign, not the one a player edited.
        unwritten |= editedSigns.removeIf(at -> at.level(server)
            .filter(level -> level.isLoaded(at.block(level)))
            .filter(level -> !(level.getBlockEntity(at.block(level)) instanceof SignBlockEntity)).isPresent());
        for (WorldPos at : List.copyOf(nameplates.keySet())) {
            Optional<ServerLevel> where = at.level(server);
            if (where.isEmpty() || !where.get().isLoaded(at.block(where.get()))) { continue; }
            ServerLevel level = where.get();
            Nameplate plate = nameplates.get(at);
            if (!(level.getBlockEntity(at.block(level)) instanceof SignBlockEntity sign)) {
                nameplates.remove(at);
                unwritten = true;
            } else if (!matches(level, sign, plate.lines())) {
                nameplates.remove(at);
                editedSigns.add(at);
                unwritten = true;
            } else if (plate.beds().stream().noneMatch(bed -> beds.sleeperOf(bed).isPresent())
                    || misplaced(level, at, plate, signs.getOrDefault(level, Map.of()))) {
                lettering(level, sign, List.of());
                nameplates.remove(at);
                unwritten = true;
            }
        }
        Set<WorldPos> used = new LinkedHashSet<>();
        for (var in : signs.entrySet()) {
            ServerLevel level = in.getKey();
            for (var owned : in.getValue().entrySet()) {
                HousingRooms.Cached room = owned.getKey();
                if (!level.isLoaded(room.anchor())) { continue; }
                Set<WorldPos> members = new LinkedHashSet<>();
                List<Component> names = new ArrayList<>();
                boolean resolved = true;
                for (BlockPos bed : room.beds().stream().sorted().toList()) {
                    WorldPos member = WorldPos.of(level, bed);
                    members.add(member);
                    Optional<UUID> sleeper = beds.sleeperOf(member);
                    if (sleeper.isEmpty()) { continue; }
                    Entity found = level.getEntity(sleeper.get());
                    if (found == null || !level.isLoaded(bed)) {
                        resolved = false;
                    } else {
                        names.add(found.getDisplayName());
                    }
                }
                if (!resolved || names.isEmpty()) { continue; }
                boolean waiting = nameplates.entrySet().stream().anyMatch(entry -> entry.getKey().in(level)
                    && !level.isLoaded(entry.getKey().block(level))
                    && entry.getValue().beds().stream().anyMatch(members::contains));
                if (waiting) { continue; }
                names.sort(Comparator.comparing(Component::getString));
                List<WorldPos> candidates = owned.getValue().stream()
                    .sorted(Comparator.<BlockPos>comparingDouble(at -> at.distSqr(room.anchor()))
                        .thenComparing(Comparator.naturalOrder()))
                    .map(at -> WorldPos.of(level, at))
                    .filter(at -> !used.contains(at) && !editedSigns.contains(at)).toList();
                Optional<WorldPos> chosen = candidates.stream().filter(at -> nameplates.containsKey(at)
                    && nameplates.get(at).beds().stream().anyMatch(members::contains)).findFirst();
                if (chosen.isEmpty()) {
                    chosen = candidates.stream().filter(at -> !nameplates.containsKey(at))
                        .filter(at -> level.isLoaded(at.block(level)))
                        .filter(at -> level.getBlockEntity(at.block(level)) instanceof SignBlockEntity)
                        .filter(at -> empty((SignBlockEntity) level.getBlockEntity(at.block(level)))).findFirst();
                }
                if (chosen.isEmpty()) { continue; }
                WorldPos at = chosen.get();
                if (!level.isLoaded(at.block(level))) { continue; }
                used.add(at);
                List<String> lines = new ArrayList<>();
                for (int line = 0; line < 8; line++) {
                    Component name = line < names.size() ? names.get(line) : Component.empty();
                    lines.add(Component.Serializer.toJson(name, level.registryAccess()));
                }
                Nameplate plate = new Nameplate(List.copyOf(lines), Set.copyOf(members));
                if (!plate.equals(nameplates.get(at))) {
                    lettering(level, (SignBlockEntity) level.getBlockEntity(at.block(level)), lines);
                    nameplates.put(at, plate);
                    unwritten = true;
                }
            }
        }
    }

    private static boolean misplaced(ServerLevel level, WorldPos at, Nameplate plate,
                                      Map<HousingRooms.Cached, Set<BlockPos>> signs) {
        boolean surveyed = false;
        for (var entry : signs.entrySet()) {
            if (entry.getKey().beds().stream().map(bed -> WorldPos.of(level, bed))
                    .noneMatch(plate.beds()::contains)) { continue; }
            surveyed = true;
            if (entry.getValue().contains(at.block(level))) { return false; }
        }
        return surveyed;
    }

    private static boolean empty(SignBlockEntity sign) {
        for (boolean front : List.of(true, false)) {
            for (int line = 0; line < 4; line++) {
                if (!sign.getText(front).getMessage(line, false).getString().isBlank()
                        || !sign.getText(front).getMessage(line, true).getString().isBlank()) { return false; }
            }
        }
        return true;
    }

    private static boolean matches(ServerLevel level, SignBlockEntity sign, List<String> lines) {
        for (int line = 0; line < 8; line++) {
            SignText text = sign.getText(line < 4);
            for (boolean filtered : List.of(false, true)) {
                String current = Component.Serializer.toJson(text.getMessage(line % 4, filtered), level.registryAccess());
                if (!current.equals(lines.get(line))) { return false; }
            }
        }
        return true;
    }

    private static void lettering(ServerLevel level, SignBlockEntity sign, List<String> lines) {
        for (boolean front : List.of(true, false)) {
            SignText text = sign.getText(front);
            for (int line = 0; line < 4; line++) {
                int index = front ? line : line + 4;
                Component name = index < lines.size()
                    ? Component.Serializer.fromJson(lines.get(index), level.registryAccess()) : Component.empty();
                text = text.setMessage(line, name);
            }
            sign.setText(text, front);
        }
        sign.setChanged();
        level.sendBlockUpdated(sign.getBlockPos(), sign.getBlockState(), sign.getBlockState(), Block.UPDATE_CLIENTS);
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
                if (!(found instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) {
                    awakeTime.remove(resident.id());
                    continue;
                }
                int awake =
                    awakeTime
                    .computeIfAbsent(resident.id(), id -> new AwakeTime())
                    .observe(level.getGameTime(), !living.isSleeping());
                if (awake > 0) {
                    wear.computeIfAbsent(resident.id(), id -> new Rest())
                        .awake(awake * view.effectsOf(resident).value(Stats.FATIGUE));
                    unwritten = true;
                }
                Hunger his = bellies.computeIfAbsent(resident.id(), id -> new Hunger());
                his.setExhaustionRateMul((float) view.effectsOf(resident).value(Stats.APPETITE));
                if (server.getTickCount() % VITALS_INTERVAL == 0) {
                    autoFeed(living, his, level);
                    step(living, his, level, naturalRegen);
                    Mood mood = mood(resident.id());
                    Optional<WorldPos> bed = bedFor(resident.id()).filter(at -> at.in(level));
                    int home = bed.isEmpty() ? -12 : rooms.get(level, bed.get().block(level)).grade().mood;
                    if (living.getLastHurtByMob() != null
                            && living.tickCount - living.getLastHurtByMobTimestamp() < VITALS_INTERVAL) {
                        mood.attacked(level.getGameTime());
                    }
                    boolean safe = Wards.warded(level, living.blockPosition());
                    boolean rain = level.isRainingAt(living.blockPosition());
                    mood.comfortable = rooms.comfortableAt(level, WorldSpaces.at(living).block(level));
                    Effects traits = view.effectsOf(resident);
                    int shift = mood.step(level.getGameTime(), his.foodLevel(), alertnessOf(resident.id()).orElse(Rest.SPENT),
                        home, safe, rain, traits.value(Stats.DISCOMFORT), Wards.comfort(level, living.blockPosition()));
                    if (shift != 0) {
                        MoodSparks.show(level, living, shift > 0);
                    }
                    unwritten = true;
                }
                if (living instanceof ResidentEntity person) {
                    person.foodLevel(20.0F * his.foodLevel() / Hunger.MAX_FOOD_LEVEL);
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
        // Elapsed awake time is charged by tick(), never again by a delayed execution total.
        wear.computeIfAbsent(body.id(), id -> new Rest()).walked(effort.distance() * travelFatigue);
        unwritten = true;
    }

    private void autoFeed(LivingEntity entity, Hunger hunger, ServerLevel level) {
        var loaded = Bodies.of(entity);
        if (loaded.isEmpty() || hunger.foodLevel() >= Hunger.REGEN_FOOD_LEVEL
            || KitProviders.capabilities(loaded.get())
                .ability(KitCapabilities.AUTO_FEED) <= 0) {
            return;
        }
        Body body = loaded.get();
        var pack = body.pack();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            FoodProperties food = stack.isEmpty() ? null : stack.getFoodProperties(entity);
            if (!wouldEat(body.id(), stack, food)) {
                continue;
            }
            var outcome = EatAction.consume(level, body.mob(), body.id(), body.mob()::spawnAtLocation,
                this, pack, slot);
            if (outcome instanceof io.github.izakyl.folkways.core.work.api.Outcome.Done done) {
                for (ItemStack made : done.made()) {
                    ItemStack rest = io.github.izakyl.folkways.core.item.api.Containers.insert(pack, made);
                    if (!rest.isEmpty()) {
                        body.mob().spawnAtLocation(rest);
                    }
                }
            }
            return;
        }
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
        var observed = this.colony.inventory().snapshot().residents().get(who.resident().id());
        if (observed == null || observed.state() != InventorySnapshot.State.OBSERVED) { return Optional.empty(); }
        return Optional.of(new Urge(EAT, weight, urgent || !who.effects().has(Stats.PATIENT),
            () -> new EatAction(where, this, who.resident().id(), at, observed.version())));
    }

    private Optional<Urge> sleeping(Worker who, ColonyView colony) {
        // Withdrawing this urge also lets the same runner finish an existing sleeping gesture
        // safely.
        // A passive selection, a retained shortcut and failed waiting all remain player control.
        if (directed(who.resident().id())) {
            return Optional.empty();
        }
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

    @Override
    public List<Line> debugResidentLines(UUID resident, ColonyView view) {
        Hunger belly = bellies.get(resident);
        Rest tired = wear.get(resident);
        if (belly == null && tired == null) {
            return List.of();
        }
        List<Line> lines = new ArrayList<>();
        if (belly != null) {
            lines.add(debugLine("hunger", belly.foodLevel(), belly.saturation(), belly.exhaustion()));
            var reading = hunger.reading(resident);
            lines.add(marks("hunger_mark", reading.engaged(), reading.holdAt(), reading.urgentAt(),
                reading.restoreTo()));
        }
        if (tired != null) {
            lines.add(debugLine("rest", tired.tiredness(), tired.alertness(), tired.ticksToSleepOff()));
            var reading = tiredness.reading(resident);
            lines.add(marks("rest_mark", reading.engaged(), reading.holdAt(), reading.urgentAt(),
                reading.restoreTo()));
        }
        Optional<WorldPos> bed = bedFor(resident);
        String sleep = directed(resident) ? "directed" : tired == null ? "vitals_unobserved"
            : !tiredness.reading(resident).engaged() ? "not_tired" : bed.isEmpty() ? "no_bed"
            : !bed.get().in(view.level()) ? "bed_elsewhere" : "none";
        lines.add(debugLine("sleep", debugWord(sleep), DebugText.where(bed.map(at -> at.in(view.level().dimension())
            ? at.cell().toShortString() : at.toString()).orElse("-"))));
        var observed = colony.inventory().snapshot().residents().get(resident);
        Entity entity = view.level().getEntity(resident);
        boolean seen = observed != null && observed.state() == InventorySnapshot.State.OBSERVED;
        boolean meal = seen && entity instanceof LivingEntity living && observed.pack().stream().anyMatch(slot -> {
            ItemStack stack = slot.stackCopy();
            return wouldEat(resident, stack, stack.isEmpty() ? null : stack.getFoodProperties(living));
        });
        String eat = belly == null ? "vitals_unobserved" : !hunger.reading(resident).engaged() ? "not_hungry"
            : !seen ? "inventory_unobserved" : !meal ? "no_meal" : "none";
        lines.add(debugLine("eat", debugWord(eat)));
        if (seen && entity instanceof LivingEntity living) {
            Optional<ItemSpec> food = LivingContent.food(colony);
            food.ifPresent(spec -> lines.add(debugLine("ration", nourishing(observed.pack(), spec, living),
                RESTOCK_DAYS * Hunger.FOOD_PER_DAY)));
        }
        if (entity instanceof net.minecraft.world.entity.Mob mob && !mob.getNavigation().isDone()) {
            BlockPos target = mob.getNavigation().getTargetPos();
            if (target != null) {
                lines.add(debugLine("walking_target", DebugText.where(target.toShortString())));
            }
        }
        return List.copyOf(lines);
    }

    private static Line debugLine(String key, Object... args) {
        return DebugText.line(key, args);
    }

    // The watermarks always matter; whether they are engaged only when they are.
    private static Line marks(String key, boolean engaged, Object hold, Object urgent, Object restore) {
        var line = DebugText.text(key, hold, urgent, restore);
        if (engaged) {
            line.append(" · ").append(DebugText.word("engaged"));
        }
        return Line.text(line);
    }

    private static Component debugWord(String key) {
        return DebugText.word(key);
    }

    private boolean directed(UUID resident) {
        return DefenseControl.of(colony).reserved(resident) || DefenseControl.of(colony).current(resident).isPresent();
    }

    void staleMeal(UUID resident, InventorySnapshot.Version observed) {
        colony.inventoryFeedback().staleResident(resident, observed);
    }

    private boolean carriesAMeal(Worker who) {
        var resident = colony.inventory().snapshot().residents().get(who.resident().id());
        if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObservedResident(who.resident().id());
            return false;
        }
        for (SlotSnapshot slot : resident.pack()) {
            ItemStack stack = slot.stackCopy();
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
            && belly.canEatMeal(food)
            && FoodSafety.matches(Fronts.of(colony).settings(LivingContent.ID), stack);
    }

    // A served table meal is chosen by the dining area's own list, so only the belly decides.
    boolean wouldEat(UUID resident, FoodProperties food) {
        Hunger belly = bellies.get(resident);
        return belly != null && belly.canEatMeal(food);
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
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "fine_meals"));

    private static String foodGroup(ItemStack stack) {
        for (String group : List.of("meat", "fish", "stew", "grain", "vegetable", "fruit")) {
            var tag = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "food_groups/" + group));
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
        var inventory = colony.inventory().snapshot();
        for (Resident resident : view.residents()) {
            if (!LivingContent.looksAfter(resident)) {
                continue;
            }
            Entity body = level.getEntity(resident.id());
            if (body == null) {
                continue;
            }
            var observed = inventory.residents().get(resident.id());
            if (observed == null || observed.state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObservedResident(resident.id());
                continue;
            }
            if (!(body instanceof LivingEntity living)) { continue; }
            int carried = nourishing(observed.pack(), food, living);
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
    private int nourishing(List<SlotSnapshot> pack, ItemSpec food, LivingEntity body) {
        int points = 0;
        for (SlotSnapshot slot : pack) {
            ItemStack stack = slot.stackCopy();
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
        awakeTime.clear();
        dining.clear();
        if (why == Closing.RAZED) {
            beds.clear();
            bellies.clear();
            wear.clear();
            moods.clear();
            nameplates.clear();
            editedSigns.clear();
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
    public List<Line> zoneLines(io.github.izakyl.folkways.core.front.api.ZoneView zone, ColonyView view) {
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
        return List.copyOf(lines);
    }

    @Override
    public Sentence nameMarks(UUID resident, ColonyView colony) {
        if (!bellies.containsKey(resident)) {
            return Sentence.EMPTY;
        }
        double mood = mood(resident).value;
        return Sentence.of(Sentence.glyph(mood < 40 ? "mood_sad" : mood >= 70 ? "mood_happy" : "mood_neutral"));
    }

    CompoundTag reading(UUID resident, ColonyView view) {
        Mood mood = mood(resident);
        CompoundTag tag = new CompoundTag();
        tag.putInt("mood", (int) mood.value);
        tag.putDouble("speed", Math.round(Mood.speed(mood.value) * 100) / 100.0);
        tag.putInt("alertness", alertnessOf(resident).orElse(Rest.SPENT));
        CompoundTag reasons = new CompoundTag();
        mood.reasons.forEach(reasons::putInt);
        tag.put("reasons", reasons);
        ListTag events = new ListTag();
        for (Mood.Event event : mood.events) {
            CompoundTag entry = new CompoundTag();
            entry.putString("reason", event.reason());
            entry.putInt("amount", event.amount());
            entry.putLong("age", Math.max(0, view.level().getGameTime() - event.at()) / (Mood.DAY / 24) * (Mood.DAY / 24));
            events.add(entry);
        }
        tag.put("events", events);
        bedFor(resident).filter(at -> at.in(view.level())).ifPresent(at -> {
            HousingRooms.Room room = rooms.get(view.level(), at.block(view.level()));
            tag.put("room", Pages.text(view.level().registryAccess(), room.description()));
            tag.put("advice", Pages.text(view.level().registryAccess(), room.advice()));
        });
        return tag;
    }

    @Override
    public List<Line> blockLines(BlockPos at, ColonyView view) {
        if (!view.blocks(LivingContent.BED).contains(at)) { return List.of(); }
        ServerLevel level = view.level();
        List<Line> lines = new ArrayList<>();
        Optional<UUID> sleeper = beds.sleeperOf(WorldPos.of(level, at));
        lines.add(sleeper.map(level::getEntity).<Line>map(found -> Line.text(
                Component.translatable("folkways.housing.sleeper", found.getDisplayName())))
            .orElseGet(() -> Line.said(sleeper.isPresent() ? CLAIMED_SAID : UNCLAIMED_SAID)));
        HousingRooms.Room room = rooms.get(level, at);
        lines.add(Line.text(room.description()));
        lines.add(Line.text(room.advice()));
        return List.copyOf(lines);
    }

    // Over every bed of the colony, a card naming who sleeps there and what its room is worth.
    @Override
    public List<Placard> placards(ColonyView view) {
        ServerLevel level = view.level();
        List<Placard> placards = new ArrayList<>();
        for (BlockPos at : view.blocks(LivingContent.BED)) {
            if (!level.isLoaded(at)) {
                continue;
            }
            BlockState state = level.getBlockState(at);
            if (!Housing.isBed(state)) {
                continue;
            }
            List<Line> lines = new ArrayList<>();
            lines.add(Line.said(new Notice(state.getBlock().getDescriptionId(), List.of())));
            lines.addAll(blockLines(at, view));
            UUID id = UUID.nameUUIDFromBytes(("folkways_vanilla:bed/" + level.dimension().location() + "/" + at.asLong())
                .getBytes(StandardCharsets.UTF_8));
            placards.add(new Placard(id,
                new Placard.Outline.Box(at, Housing.pillow(level, at, state).orElse(at)), lines));
        }
        return List.copyOf(placards);
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
            if (!need.complete()) {
                this.colony.inventory().ensureObserved();
                rows.add(Board.Row.of(new ItemStack(BuiltInRegistries.ITEM.get(need.kind().icon())),
                    Component.translatable(need.kind().nameKey()),
                    Component.translatable("folkways.settlers.checking_stock")));
            } else {
                rows.add(Board.Row.of(new ItemStack(BuiltInRegistries.ITEM.get(need.kind().icon())),
                    Component.translatable(need.kind().nameKey()),
                    Component.literal(need.have() + " / " + need.want(mouths))).told(told(need, mouths)));
            }
        }
        rows.add(new Board.Row(ItemStack.EMPTY,
            Component.translatable(LivingContent.FOOD_MODE.nameKey()), Component.empty(),
            List.of(new Board.Act.Edit(LivingContent.FOOD_MODE.key()))).wide());
        rows.add(new Board.Row(ItemStack.EMPTY, Component.empty(), Component.empty(),
            List.of(new Board.Act.Edit(LivingContent.FOOD_SAFETY.key()))).wide());
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
                .filled(Fronts.of(colony).stocked(server, food), colony.inventory().snapshot().coverage().complete()))
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

    CompoundTag save() {
        return Writer.of(beds.save())
            .blob("pets", pets.save())
            .children("nameplates", nameplates.entrySet(), entry -> Writer.of()
                .blob("sign", entry.getKey().save())
                .children("lines", entry.getValue().lines(), line -> Writer.of().string("text", line).tag())
                .children("beds", entry.getValue().beds(), WorldPos::save).tag())
            .children("edited_signs", editedSigns, WorldPos::save)
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
        if (reader.keys().isEmpty()) {
            return;
        }
        beds.load(reader);
        pets.load(reader.child("pets").orElseThrow());
        for (Reader saved : reader.requiredChildren("nameplates")) {
            List<String> lines = saved.requiredChildren("lines").stream().map(line -> line.string("text").orElseThrow()).toList();
            Set<WorldPos> members = new LinkedHashSet<>();
            for (Reader bed : saved.requiredChildren("beds")) { WorldPos.load(bed).ifPresent(members::add); }
            if (lines.size() == 8) {
                saved.child("sign").flatMap(WorldPos::load)
                    .ifPresent(at -> nameplates.put(at, new Nameplate(lines, Set.copyOf(members))));
            }
        }
        for (Reader saved : reader.requiredChildren("edited_signs")) { WorldPos.load(saved).ifPresent(editedSigns::add); }
        for (Reader saved : reader.requiredChildren("moods")) {
            saved.uuid("resident").ifPresent(id -> { Mood mood = new Mood(); mood.load(saved); moods.put(id, mood); });
        }
        for (Reader belly : reader.requiredChildren("hunger")) {
            belly.uuid("resident").ifPresent(resident -> {
                Hunger his = new Hunger();
                his.load(belly);
                bellies.put(resident, his);
            });
        }
        for (Reader spent : reader.requiredChildren("rest")) {
            spent.uuid("resident").ifPresent(resident -> {
                Rest tired = new Rest();
                tired.load(spent);
                wear.put(resident, tired);
            });
        }
    }
}
