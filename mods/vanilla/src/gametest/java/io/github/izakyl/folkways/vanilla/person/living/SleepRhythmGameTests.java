package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.runtime.ColonyLabor;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class SleepRhythmGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SleepRhythmGameTests.class);
    }

    // Far enough that the walk home starts before dusk, near enough that the lead stays under its cap.
    private static final int FAR = 150;

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void theClockGatesBedtimeAndBoundsTheNight(GameTestHelper helper) {
        SleepRhythm noon = new SleepRhythm(true, 6000);
        SleepRhythm midnight = new SleepRhythm(true, 18000);
        SleepRhythm still = new SleepRhythm(false, 18000);
        helper.assertValueEqual(noon.holdAt(0), SleepRhythm.DAY_HOLD, "by day only exhaustion engages sleep");
        helper.assertValueEqual(midnight.holdAt(0), SleepRhythm.NIGHT_HOLD, "by night light wear engages sleep");
        helper.assertValueEqual(still.holdAt(0), Rest.SPENT - Rest.SLEEPY, "a still clock keeps the old mark");
        helper.assertTrue(SleepRhythm.DAY_HOLD < Rest.SPENT - Rest.SLEEPY
            && Rest.SPENT - Rest.SLEEPY < SleepRhythm.NIGHT_HOLD, "night is easier than the old mark, day harder");
        helper.assertValueEqual(midnight.sleepTicks(0, 100), SleepRhythm.DAWN - 18000, "a night's sleep ends at dawn");
        helper.assertValueEqual(midnight.sleepTicks(0, 9000), 9000, "a night never cuts the wear short");
        helper.assertValueEqual(noon.sleepTicks(0, 100), 100, "a nap sleeps only the wear off");
        helper.assertValueEqual(still.sleepTicks(0, 100), 100, "a still clock never waits for dawn");
        helper.assertValueEqual(new SleepRhythm(true, 23000).sleepTicks(1000, 100), 100,
            "a walk ending after dawn is a nap");
        helper.assertValueEqual(new SleepRhythm(true, 24000 + 12000).sleepTicks(1000, 100),
            SleepRhythm.DAWN - 13000, "the night is read from the hour of arrival, whatever the day count");
        helper.assertTrue(new SleepRhythm(true, 12000).nightAt(1000) && !new SleepRhythm(true, 12000).nightAt(0),
            "a walk that ends after dusk counts as night");
        helper.assertValueEqual(SleepRhythm.lead(0), 0, "a resident at the bed needs no lead");
        helper.assertValueEqual(SleepRhythm.lead(100_000), SleepRhythm.MAX_LEAD, "the lead is capped");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void tiredResidentsWaitForNightButExhaustedOnesNap(GameTestHelper helper) {
        clocked(helper, true, () -> {
            helper.assertTrue(sleep(helper, 900, 6000, 0).isEmpty(), "tired but not exhausted waits out the day");
            int night = ticks(sleep(helper, 900, 18000, 0).orElseThrow(() -> new GameTestAssertException(
                "the same wear sleeps at night")));
            helper.assertValueEqual(night, SleepRhythm.DAWN - 18000, "night sleep lasts until dawn");
            int nap = ticks(sleep(helper, Rest.SPENT, 6000, 0).orElseThrow(() -> new GameTestAssertException(
                "exhaustion naps by day")));
            helper.assertValueEqual(nap, Rest.SPENT * 30 / 6, "a nap sleeps only the wear off");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void farResidentsSetOffBeforeDusk(GameTestHelper helper) {
        clocked(helper, true, () -> {
            int dusk = SleepRhythm.DUSK - 500;
            helper.assertTrue(sleep(helper, 400, dusk, 0).isEmpty(), "a resident beside the bed waits for dusk");
            int far = ticks(sleep(helper, 400, dusk, FAR).orElseThrow(() -> new GameTestAssertException(
                "a resident far from the bed sets off before dusk")));
            int lead = SleepRhythm.lead(FAR);
            helper.assertTrue(lead > 500 && lead < SleepRhythm.MAX_LEAD, "the fixture's walk ends after dusk: " + lead);
            helper.assertValueEqual(far, SleepRhythm.DAWN - (dusk + lead), "the night is counted from arrival");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void engagedSleepHoldsUntilRestedWhateverTheHour(GameTestHelper helper) {
        clocked(helper, true, () -> {
            Fixture fixture = new Fixture(helper, 300, 0);
            try {
                helper.getLevel().setDayTime(18000);
                helper.assertTrue(fixture.sleep().isPresent(), "night engages light wear");
                helper.getLevel().setDayTime(6000);
                helper.assertTrue(fixture.sleep().isPresent(), "dawn does not drop an engaged, unrested sleeper");
            } finally {
                fixture.close();
            }
        });
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aStillClockKeepsTheValueDrivenMark(GameTestHelper helper) {
        clocked(helper, false, () -> {
            helper.assertTrue(sleep(helper, Rest.SLEEPY - 100, 18000, 0).isEmpty(),
                "a frozen night does not lower the mark");
            int sleep = ticks(sleep(helper, Rest.SLEEPY, 6000, FAR).orElseThrow(() -> new GameTestAssertException(
                "a frozen day keeps the old mark")));
            helper.assertValueEqual(sleep, Rest.SLEEPY * 30 / 6, "a still clock sleeps only the wear off");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aLongNightBanksRestAndTheResidentStillSleepsAtDusk(GameTestHelper helper) {
        clocked(helper, true, () -> {
            helper.getLevel().setDayTime(SleepRhythm.DUSK);
            Fixture fixture = new Fixture(helper, Rest.SPENT, 0);
            Rest rest = new Rest();
            try {
                Urge sleep = fixture.sleep().orElseThrow();
                helper.assertTrue(fixture.living.slept(fixture.body.id(), ticks(sleep), helper.getLevel()),
                    "the scheduled night settles its full duration");
                helper.assertValueEqual(fixture.living.alertnessOf(fixture.body.id()).orElseThrow(),
                    Rest.MAX_ALERTNESS, "surplus night sleep banks all the way to the floor");
                var saved = fixture.living.save().getList("rest", 10).getCompound(0);
                rest.load(Reader.of(saved));
                helper.assertValueEqual(rest.tiredness(), Rest.FLOOR, "banked rest survives loading");
                helper.assertValueEqual(rest.ticksToSleepOff(), 1, "banked rest adds no time to a nap");
                helper.getLevel().setDayTime(SleepRhythm.DAWN);
                helper.assertTrue(fixture.sleep().isEmpty(), "restoration clears the engaged sleep urge");
            } finally {
                fixture.close();
            }
            rest.awake(SleepRhythm.DAY - SleepRhythm.DAWN + SleepRhythm.DUSK);
            helper.assertTrue(sleep(helper, rest.tiredness(), SleepRhythm.DUSK, 0).isPresent(),
                "a day awake uses enough of the bank to engage at dusk");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void loadedTirednessClampsToTheBankAndExhaustion(GameTestHelper helper) {
        Rest rest = new Rest();
        rest.load(Reader.of(Writer.of().decimal("Tired", Rest.FLOOR - 1).tag()));
        helper.assertValueEqual(rest.tiredness(), Rest.FLOOR, "the load clamp respects the bank floor");
        rest.load(Reader.of(Writer.of().decimal("Tired", Rest.SPENT + 1).tag()));
        helper.assertValueEqual(rest.tiredness(), Rest.SPENT, "the load clamp respects exhaustion");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void interruptedSleepCreditsOnlyTheTicksInBed(GameTestHelper helper) {
        try (SleepRun run = new SleepRun(helper, 100, false)) {
            Mood mood = run.fixture.living.mood(run.fixture.body.id());
            long rested = mood.rested;
            long housed = mood.housed;
            run.tick(11);
            helper.assertTrue(run.fixture.body.mob().isSleeping(), "the resident is lying in bed");
            run.runner.letGo(run.level, run.fixture.body);
            helper.assertTrue(Math.abs(run.tiredness() - (Rest.SLEEPY - 11 * (6.0 / 30.0))) < 0.0001,
                "the interrupted sleep credits exactly eleven ticks");
            helper.assertValueEqual(mood.rested, rested, "a short partial sleep does not refresh rested mood");
            helper.assertValueEqual(mood.housed, housed, "a short partial sleep does not refresh housing mood");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void eatingSuspendsSleepWithoutRestartingItsProgress(GameTestHelper helper) {
        try (SleepRun run = new SleepRun(helper, 50, false)) {
            run.tick(17);
            run.eat();
            helper.assertValueEqual(run.tiredness(), (float) Rest.SLEEPY, "suspension does not settle sleep");
            run.tick(33);
            helper.assertTrue(run.fixture.body.pack().getItem(0).isEmpty(), "the resident finished eating");
            run.fixture.body.mob().moveTo(Vec3.atBottomCenterOf(run.stand));
            run.runner.resume(run.level, run.fixture.body);
            run.tick(33);
            helper.assertTrue(run.fixture.body.mob().isSleeping(), "the resumed resident is lying in bed");
            helper.assertValueEqual(run.tiredness(), (float) Rest.SLEEPY, "credit waits until completion");
            run.tick(1);
            helper.assertValueEqual(run.runner.lastDone(), run.sleep, "sleep finishes after fifty total ticks");
            helper.assertValueEqual(run.tiredness(), Rest.SLEEPY - 10.0F, "completion credits fifty ticks once");
            helper.assertValueEqual(run.fixture.living.mood(run.fixture.body.id()).rested,
                run.level.getGameTime(), "full sleep refreshes rested mood");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void suspendedSleepStillCreditsItsTicksWhenRevoked(GameTestHelper helper) {
        try (SleepRun run = new SleepRun(helper, 40, false)) {
            run.tick(10);
            Urge eat = run.eat();
            run.runner.cancelMissingUrges(run.level, run.fixture.body, List.of(eat));
            helper.assertValueEqual(run.tiredness(), Rest.SLEEPY - 2.0F, "revocation credits the suspended sleep");
            helper.assertValueEqual(run.fixture.living.mood(run.fixture.body.id()).rested,
                run.level.getGameTime(), "a quarter of the plan refreshes rested mood");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void droppingSleepOnTheWayToBedCreditsNothing(GameTestHelper helper) {
        try (SleepRun run = new SleepRun(helper, 50, true)) {
            helper.assertTrue(run.runner.approaching(run.sleep.id()), "the resident is approaching the bed");
            run.tick(3);
            helper.assertFalse(run.fixture.body.mob().isSleeping(), "the resident has yet to lie down");
            run.runner.letGo(run.level, run.fixture.body);
            helper.assertValueEqual(run.tiredness(), (float) Rest.SLEEPY, "approaching earns no sleep credit");
        }
        helper.succeed();
    }

    private static final class SleepRun implements AutoCloseable {
        private final Fixture fixture;
        private final ServerLevel level;
        private final ColonyLabor labor;
        private final BodyRunner runner;
        private final Node sleep;
        private final BlockPos stand;

        SleepRun(GameTestHelper helper, int ticks, boolean walking) {
            fixture = new Fixture(helper, Rest.SLEEPY, 0, 14, 0);
            level = helper.getLevel();
            BlockPos bed = helper.absolutePos(new BlockPos(1, 2, 1));
            stand = bed.east();
            level.setBlockAndUpdate(stand.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(bed, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT));
            level.setBlockAndUpdate(bed.south(), Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD));
            fixture.body.mob().moveTo(Vec3.atBottomCenterOf(walking ? bed.east(8) : stand));
            ColonyData data = ColonyData.find(level.getServer(), fixture.colony.id()).orElseThrow();
            labor = new ColonyLabor(data, level.dimension());
            runner = new BodyRunner(fixture.body.id(), labor.execution());
            Urge urge = new Urge(LivingPresence.SLEEP, Urge.SPARE, true,
                () -> new SleepAction(fixture.living, level, bed, ticks));
            sleep = urge.take().get();
            helper.assertTrue(labor.execution().personal().ready(sleep, level), "sleep is ready");
            runner.offer(level, fixture.body, urge, sleep);
        }

        void tick(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                labor.tick(runner, level, fixture.body);
            }
        }

        Urge eat() {
            fixture.body.pack().setItem(0, new ItemStack(Items.BREAD));
            InventoryObservations.publish(fixture.colony, level, Map.of(fixture.body.id(), fixture.body));
            Urge urge = fixture.living.urges(TestWorker.of(fixture.body), fixture.colony.view(level)).stream()
                .filter(offer -> offer.id().equals(LivingPresence.EAT)).findFirst().orElseThrow();
            Node meal = urge.take().get();
            fixture.helper.assertTrue(labor.execution().personal().ready(meal, level), "the meal is ready");
            runner.takeover(level, fixture.body, urge, meal);
            tick(1);
            fixture.helper.assertValueEqual(runner.suspendedNodes(), List.of(sleep), "eating suspends sleep");
            fixture.helper.assertFalse(fixture.body.mob().isSleeping(), "the resident gets out of bed to eat");
            return urge;
        }

        float tiredness() {
            return fixture.living.save().getList("rest", 10).getCompound(0).getFloat("Tired");
        }

        @Override
        public void close() {
            runner.abandon();
            labor.close();
            fixture.close();
        }
    }

    private static Optional<Urge> sleep(GameTestHelper helper, int tired, int dayTime, int apart) {
        helper.getLevel().setDayTime(dayTime);
        Fixture fixture = new Fixture(helper, tired, apart);
        try {
            return fixture.sleep();
        } finally {
            fixture.close();
        }
    }

    private static int ticks(Urge urge) {
        return ((Workload.Once) urge.take().get().spec().workload()).baseTicks();
    }

    // The clock is moved and restored inside one synchronous call, so no other fixture sees it.
    private static void clocked(GameTestHelper helper, boolean cycles, Runnable body) {
        ServerLevel level = helper.getLevel();
        GameRules.BooleanValue rule = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
        boolean was = rule.get();
        long day = level.getDayTime();
        rule.set(cycles, level.getServer());
        try {
            body.run();
        } finally {
            level.setDayTime(day);
            rule.set(was, level.getServer());
        }
    }

    private static final class Fixture {
        private final Colony colony;
        private final Body body;
        private final LivingPresence living;
        private final GameTestHelper helper;

        Fixture(GameTestHelper helper, int tired, int apart) {
            this(helper, tired, apart, 20, 5);
        }

        Fixture(GameTestHelper helper, int tired, int apart, int food, int saturation) {
            this.helper = helper;
            colony = Colonies.mint(helper.getLevel().getServer());
            body = PersonBody.RESIDENT.get().create(helper.getLevel());
            BlockPos bed = helper.absolutePos(new BlockPos(1, 2, 1));
            body.mob().moveTo(bed.east(apart).getCenter());
            UUID resident = body.resident().id();
            colony.keep(LivingContent.ID, Writer.of(new LivingPresence(colony).save()).blob("pets",
                Writer.of().longValue("next_attempt", -1L).uuids("pets", List.of()).tag())
                .children("hunger", List.of(resident), id -> Writer.of().uuid("resident", id)
                    .integer("FoodLevel", food).decimal("Saturation", saturation).decimal("Exhaustion", 0).tag())
                .children("rest", List.of(resident), id -> Writer.of().uuid("resident", id)
                    .decimal("Tired", tired).tag())
                .children("claims", List.of(resident), id -> Writer.of().uuid("resident", id)
                    .blob("bed", WorldPos.of(helper.getLevel(), bed).save()).tag()).tag());
            living = new LivingPresence(colony);
        }

        Optional<Urge> sleep() {
            return living.urges(TestWorker.of(body), colony.view(helper.getLevel())).stream()
                .filter(urge -> urge.id().equals(LivingPresence.SLEEP)).findFirst();
        }

        void close() {
            body.mob().discard();
            colony.raze();
        }
    }
}
