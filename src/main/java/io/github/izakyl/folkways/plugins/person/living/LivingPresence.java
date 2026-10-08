package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.perk.PerkPool;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.panel.Board;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

final class LivingPresence implements Facing {

    private static final int VITALS_INTERVAL = 10;

    static final long RATIONS = 2;

    private static final int RATION_RANK = 0;

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

    private boolean unwritten;

    LivingPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, LivingContent.ID, 100);
        load(Reader.of(colony.kept(LivingContent.ID)));
    }

    void refresh(MinecraftServer server) {
        Set<UUID> belonging = new LinkedHashSet<>();
        for (Resident resident : colony.residents()) {
            belonging.add(resident.id());
        }
        for (ColonyView view : colony.views(server)) {
            unwritten |= beds.sweep(view, belonging);
        }
        unwritten |= bellies.keySet().retainAll(belonging);
        unwritten |= wear.keySet().retainAll(belonging);
        hunger.retain(belonging);
        tiredness.retain(belonging);
        write();
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
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
                his.setExhaustionRateMul((float) appetite(view.rankOf(resident, LivingContent.ASCETIC)));
                if (server.getTickCount() % VITALS_INTERVAL == 0) {
                    step(living, his, level, naturalRegen);
                }
            }
        }
    }

    private static double appetite(int asceticRank) {
        List<? extends Number> ladder = FolkwaysConfig.asceticAppetite();
        return asceticRank >= 1 && asceticRank <= ladder.size()
            ? ladder.get(asceticRank - 1).doubleValue()
            : 1.0;
    }

    void exerted(Body body, WorkExertion effort) {
        if (!LivingContent.looksAfter(body.resident())) {
            return;
        }
        ServerLevel level = (ServerLevel) body.mob().level();
        Hunger his = bellies.computeIfAbsent(body.id(), id -> new Hunger());
        his.setExhaustionRateMul((float) appetite(colony.view(level).rankOf(body.resident(), LivingContent.ASCETIC)));
        his.addExhaustion((float) (effort.workDone() * Hunger.WORK_EXHAUSTION_PER_UNIT
            + effort.distance() * Hunger.WALK_EXHAUSTION_PER_BLOCK), level.getDifficulty());
        wear.computeIfAbsent(body.id(), id -> new Rest()).spend(effort.activeTicks(), effort.distance());
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
        return List.copyOf(felt);
    }

    private Optional<Urge> eating(Worker who, ColonyView colony) {
        Optional<Integer> level = foodLevelOf(who.resident().id());
        if (level.isEmpty() || !hunger.holds(who.resident().id(), level.get()) || !carriesAMeal(who)) {
            return Optional.empty();
        }
        ServerLevel where = colony.level();
        BlockPos at = who.body().blockPosition();
        boolean urgent = hunger.urgent(level.get());
        double weight = urgent ? Urge.NOW : Urge.SPARE;
        return Optional.of(new Urge(EAT, weight, urgent || who.rankOf(PerkPool.STOIC) == 0,
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
        return Optional.of(new Urge(SLEEP, weight, urgent || who.rankOf(PerkPool.STOIC) == 0,
            () -> new SleepAction(this, level, where, ticksToSleepOff(who.resident().id()))));
    }

    private boolean carriesAMeal(Worker who) {
        Container pack = who.pack();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            FoodProperties food = stack.isEmpty() ? null : stack.get(DataComponents.FOOD);
            if (food != null && wouldEat(who.resident().id(), food)) {
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

    boolean wouldEat(UUID resident, FoodProperties food) {
        Hunger belly = bellies.get(resident);
        return belly != null && belly.canEatWithoutWaste(food);
    }

    void ate(UUID resident, FoodProperties food) {
        Hunger belly = bellies.get(resident);
        if (belly != null) {
            belly.eat(food);
            sweep.nudge();
            unwritten = true;
            write();
        }
    }

    boolean slept(UUID resident, int ticks) {
        Rest tired = wear.get(resident);
        if (tired == null) {
            return false;
        }
        tired.sleep(ticks);
        unwritten = true;
        write();
        return true;
    }

    Optional<WorldPos> bedFor(UUID resident) {
        return beds.bedFor(resident);
    }

    public List<Grown> goals(ColonyView view) {
        Optional<ItemSpec> allowed = Fronts.of(colony).settings(LivingContent.ID)
            .items(LivingContent.FOOD.key());
        if (allowed.isEmpty()) {
            return List.of();
        }
        ItemSpec food = allowed.get();
        ServerLevel level = view.level();
        List<Grown> wanted = new ArrayList<>();
        for (Resident resident : view.residents()) {
            if (!LivingContent.looksAfter(resident)) {
                continue;
            }
            Entity body = level.getEntity(resident.id());
            if (body == null) {
                continue;
            }
            long carried = Bodies.of(body).map(had -> Goods.countIn(had.pack(), food)).orElse(0L);
            if (carried >= RATIONS) {
                continue;
            }
            wanted.add(Grown.of(Delivery.to(idOf(resident.id(), food), LivingContent.ID,
                    WorkSite.on(level, resident.id(), body.blockPosition()), Stances.WHEREVER,
                    food, RATIONS - carried, count -> sweep.nudge()))
                .ranked(RATION_RANK));
        }
        return List.copyOf(wanted);
    }

    private static UUID idOf(UUID resident, ItemSpec food) {
        String said = LivingContent.ID + "|" + resident + "|" + food.describe() + "|" + RATIONS;
        return UUID.nameUUIDFromBytes(said.getBytes(StandardCharsets.UTF_8));
    }

    public void closed(Closing why) {
        if (why == Closing.RAZED) {
            beds.clear();
            bellies.clear();
            wear.clear();
            hunger.forget();
            tiredness.forget();
            unwritten = false;
            return;
        }
        write();
    }

    @Override
    public List<Line> lookLines(UUID resident, ColonyView colony) {
        Hunger his = bellies.get(resident);
        if (his == null) {
            return List.of();
        }
        int alertness = alertnessOf(resident).orElse(Rest.SPENT);
        return List.of(Line.gauge(LivingContent.FULLNESS, his.foodLevel(), Hunger.MAX_FOOD_LEVEL),
            Line.gauge(LivingContent.ALERTNESS, alertness, Rest.SPENT));
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
        return Fronts.of(colony).settings(LivingContent.ID).items(LivingContent.FOOD.key())
            .map(food -> List.of(bed, SettlerNeed.counting(LivingNeed.FOOD, food,
                    FolkwaysConfig.foodPerResident(), SettlerNeed.Per.RESIDENT)
                .filled(Fronts.of(colony).stocked(server, food))))
            .orElseGet(() -> List.of(bed));
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
            .children("hunger", bellies.entrySet(),
                entry -> stamped(entry.getKey(), entry.getValue().save()))
            .children("rest", wear.entrySet(),
                entry -> stamped(entry.getKey(), entry.getValue().save()))
            .tag();
    }

    private static CompoundTag stamped(UUID resident, CompoundTag saved) {
        return Writer.of(saved).uuid("resident", resident).tag();
    }

    private void load(Reader reader) {
        beds.load(reader);
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
