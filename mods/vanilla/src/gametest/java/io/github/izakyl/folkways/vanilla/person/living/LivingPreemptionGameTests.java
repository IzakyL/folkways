package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.effect.perk.PerkPool;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Shown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.runtime.ColonyLabor;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkExertion;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class LivingPreemptionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LivingPreemptionGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void abstractEffortSeparatesFoodFromTimeAndPersistsFractions(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.mob().moveTo(helper.absolutePos(new BlockPos(1, 2, 1)).getCenter());
        helper.getLevel().addFreshEntity(body.mob());
        colony.hold(PersonContent.ID, Claimed.Entity.of(body.mob())).orElseThrow();
        try {
            WorkExertion.performed(body.mob(), 2, 1, 3);
            colony.service(LivingContent.ID, LivingPresence.class).orElseThrow()
                .refresh(helper.getLevel().getServer());
            var saved = colony.kept(LivingContent.ID);
            helper.assertTrue(Math.abs(saved.getList("hunger", 10).getCompound(0).getFloat("Exhaustion")
                - 0.19F) < 0.00001F, "food consumes standard work plus distance, independent of labor ticks");
            var rest = saved.getList("rest", 10).getCompound(0);
            helper.assertTrue(
                Math.abs(rest.getFloat("Tired") - 0.9F) < 0.00001F,
                "settled effort adds distance fatigue once; elapsed time belongs exclusively to"
                + " the live awake clock");
            Rest loaded = new Rest();
            loaded.load(io.github.izakyl.folkways.core.base.persist.Reader.of(rest));
            helper.assertTrue(Math.abs(loaded.save().getFloat("Tired") - rest.getFloat("Tired")) < 0.00001F,
                "fractional fatigue survives a save/load");
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void ordinaryNeedsPreemptButStoicNeedsWaitUntilUrgent(GameTestHelper helper) {
        Colony colony = Colonies.mint(helper.getLevel().getServer());
        Body body = PersonBody.RESIDENT.get().create(helper.getLevel());
        UUID resident = body.resident().id();
        WorldPos bed = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        try {
            body.pack().setItem(0, new ItemStack(Items.BREAD));
            InventoryObservations.publish(colony, helper.getLevel(), Map.of(resident, body));
            for (boolean urgent : List.of(false, true)) {
                colony.keep(LivingContent.ID, Writer.of(new LivingPresence(colony).save()).blob("pets",
                    Writer.of().longValue("next_attempt", -1L).uuids("pets", List.of()).tag())
                    .children("hunger", List.of(resident), id -> Writer.of().uuid("resident", id)
                        .integer("FoodLevel", urgent ? 4 : 14).decimal("Saturation", 0).decimal("Exhaustion", 0).tag())
                    .children("rest", List.of(resident), id -> Writer.of().uuid("resident", id)
                        .decimal("Tired", urgent ? Rest.SPENT : Rest.SLEEPY).tag())
                    .children("claims", List.of(resident), id -> Writer.of().uuid("resident", id)
                        .blob("bed", bed.save()).tag()).tag());
                for (int stoic : List.of(0, 1)) {
                    LivingPresence living = new LivingPresence(colony);
                    List<Urge> offers = living.urges(worker(body, stoic), colony.view(helper.getLevel()));
                    for (var id : List.of(LivingPresence.EAT, LivingPresence.SLEEP)) {
                        Urge urge = offers.stream().filter(offer -> offer.id().equals(id)).findFirst().orElseThrow();
                        helper.assertTrue(urge.preempts() == (stoic == 0 || urgent),
                            id + " preemption policy for stoic=" + stoic + ", urgent=" + urgent);
                        helper.assertTrue(urge.weight() == (urgent ? Urge.NOW : Urge.SPARE),
                            "stoic affects preemption permission, not the weight of " + id);
                        if (stoic == 0) {
                            assertTakeover(helper, colony, body, urge, id.equals(LivingPresence.SLEEP), urgent);
                        }
                    }
                }
            }
        } finally {
            body.mob().discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void onlyConsecutiveLoadedAwakeTicksAddTimeFatigue(GameTestHelper helper) {
        AwakeTime clock = new AwakeTime();
        Rest rest = new Rest();
        helper.assertValueEqual(clock.observe(100, true), 0, "the first loaded observation has no"
            + " elapsed interval");
        for (long tick = 101; tick <= 130; tick++) {
            rest.awake(clock.observe(tick, true));
        }
        helper.assertValueEqual(rest.tiredness(), 1, "thirty actual awake ticks charge one"
            + " time-fatigue point");
        helper.assertValueEqual(
            clock.observe(130, true), 0, "duplicate observation in the same tick never double"
            + " charges");
        helper.assertValueEqual(clock.observe(131, false), 0, "sleeping does not count as awake"
            + " time");
        helper.assertValueEqual(
            clock.observe(5000, true), 0, "an unloaded or offline gap is not retroactive awake"
            + " time");
        helper.assertValueEqual(
            new AwakeTime().observe(5001, true), 0, "a reloaded clock starts without catch-up"
            + " debt");
        Rest day = new Rest();
        day.awake(24000);
        helper.assertValueEqual(
            day.tiredness(), Rest.SLEEPY, "one loaded awake day reaches the existing sleepy"
            + " threshold");
        helper.succeed();
    }

    private static void assertTakeover(GameTestHelper helper, Colony colony, Body body, Urge urge,
            boolean sleep, boolean urgent) {
        ServerLevel level = helper.getLevel();
        ColonyData data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
        ColonyLabor labor = new ColonyLabor(data, level.dimension());
        BodyRunner runner = new BodyRunner(body.id(), labor.execution());
        Body other = PersonBody.RESIDENT.get().create(level);
        BodyRunner next = new BodyRunner(other.id(), labor.execution());
        List<Ending> releases = new ArrayList<>();
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID, WorkSite.at(level, body.mob().blockPosition()),
            Stances.WHEREVER, Workload.Once.of(1000)).done();
        Node work = new Node() {
            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public Outcome commit(ServerLevel world, Worker who) {
                return Outcome.done();
            }

            @Override
            public void released(ServerLevel world, Worker who, Ending ending) {
                releases.add(ending);
            }
        };
        try {
            PlanningFixture.apply(labor, level, List.of(), Shown.delta(work));
            runner.follow(List.of(work.id()));
            labor.tick(runner, level, body);
            helper.assertValueEqual(runner.holding().orElseThrow(), work.id(), "the first resident starts colony work");

            boolean hard = sleep && urgent;
            helper.assertTrue(urge.urgent() == hard, "only exhausted sleep requests an immediate release");
            Node doing = urge.take().get();
            helper.assertTrue(labor.execution().personal().ready(doing, level), "the real need is ready to start");
            runner.takeover(level, body, urge, doing);
            if (hard) {
                helper.assertTrue(runner.softPreempt().isEmpty(), "exhaustion ends the holding immediately");
            } else {
                var soft = runner.softPreempt().orElseThrow();
                helper.assertValueEqual(soft.reason(), PreemptReason.URGE, "the soft preempt records its cause");
                helper.assertValueEqual(soft.result(), sleep ? Disposition.END : Disposition.SUSPEND,
                    "sleep and food request different dispositions");
                labor.tick(runner, level, body);
            }
            helper.assertValueEqual(runner.ownUrge().orElseThrow(), urge.id(), "the need takes over the runner");
            if (sleep) {
                helper.assertValueEqual(releases, List.of(Ending.DROPPED), "sleep ends the holding exactly once");
                helper.assertValueEqual(labor.state(work.id()).orElseThrow(), NodeState.PENDING,
                    "sleep requeues colony work");
                helper.assertFalse(colony.isHeld(work.id()) || colony.isSuspended(work.id()),
                    "released work has no holding");
                helper.assertTrue(runner.suspendedNodes().isEmpty(), "sleep leaves no entry for resuming this job");
                next.follow(List.of(work.id()));
                labor.tick(next, level, other);
                helper.assertValueEqual(next.holding().orElseThrow(), work.id(), "another resident can take the job");
            } else {
                helper.assertTrue(colony.isSuspended(work.id()), "eating keeps the colony holding suspended");
                helper.assertTrue(releases.isEmpty(), "eating does not end the holding");
                runner.letGo(level, body, "urge-gone");
                runner.resume(level, body);
                helper.assertValueEqual(runner.holding().orElseThrow(), work.id(), "the same resident resumes after food");
            }
        } finally {
            runner.letGo(level, body);
            next.letGo(level, other);
            labor.close();
            other.mob().discard();
        }
    }

    private static Worker worker(Body body, int stoic) {
        return TestWorker.of(body).effects(() -> PerkPool.effects(Map.of(PerkPool.STOIC, stoic)));
    }
}
