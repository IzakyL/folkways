package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Unmet;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// An order between asked work is a logic edge: when the work before ends undone, the plan cannot feed it again some
// other way, so the owner of the work after says what is left of the order.
@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UnmetOrderGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(UnmetOrderGameTests.class);
    }

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "unmet");

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workGoesOnWhenItsOwnerLetsTheOrderGo(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        Asked first = new Asked(f.source, Unmet.GO_ON);
        Asked second = new Asked(f.target, Unmet.GO_ON);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            Solved grown = planner.handle(known(f), List.of(new Message.Submitted(OWNER, Grown.then(first, second))), 0);
            helper.assertTrue(grown.weave().orders().equals(List.of(new Before(first.id(), second.id()))),
                "the asked order stands between the two");
            Solved failed = planner.handle(known(f),
                List.of(new Message.Failed(first.id(), LaborRefusal.GOODS_GONE)), 1);
            helper.assertTrue(second.told.size() == 1 && second.told.getFirst().equals(first.id()),
                "the owner of the work after is asked once about the work before it");
            helper.assertTrue(failed.weave().vertices().keySet().equals(Set.of(second.id()))
                    && failed.weave().orders().isEmpty() && failed.weave().held().isEmpty()
                    && failed.weave().gone().isEmpty(),
                "let go, the order is gone and the work after stands free");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workFailsWithWhatItComesAfterAndTellsWhatComesAfterIt(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        Asked first = new Asked(f.source, Unmet.GO_ON);
        Asked second = new Asked(f.target, Unmet.FAIL);
        Asked third = new Asked(f.source, Unmet.FAIL);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            Grown chain = new Grown(List.of(first, second, third),
                List.of(new Before(first.id(), second.id()), new Before(second.id(), third.id())));
            Solved grown = planner.handle(known(f), List.of(new Message.Submitted(OWNER, chain)), 0);
            helper.assertTrue(grown.weave().vertices().size() == 3, "the whole chain is planned");
            Solved failed = planner.handle(known(f),
                List.of(new Message.Failed(first.id(), LaborRefusal.GOODS_GONE)), 1);
            helper.assertTrue(failed.weave().vertices().isEmpty() && failed.weave().held().isEmpty(),
                "work that fails with what it comes after leaves the plan");
            helper.assertTrue(failed.weave().gone().equals(List.of(
                    new Gone(second, new Ending.Failed(second.id(), LaborRefusal.BEFORE_NOT_DONE)),
                    new Gone(third, new Ending.Failed(third.id(), LaborRefusal.BEFORE_NOT_DONE)))),
                "each owner hears its work failed because the work before it was not done, down the chain");
            helper.assertTrue(third.told.equals(List.of(second.id())),
                "the work after asks about the work it comes after itself, not the first failure");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workWaitsOffThePlanUntilWhatItComesAfterIsAskedAgainAndDone(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        Asked first = new Asked(f.source, Unmet.GO_ON);
        Asked second = new Asked(f.target, Unmet.WAIT);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            planner.handle(known(f), List.of(new Message.Submitted(OWNER, Grown.then(first, second))), 0);
            Solved failed = planner.handle(known(f),
                List.of(new Message.Failed(first.id(), LaborRefusal.GOODS_GONE)), 1);
            helper.assertTrue(failed.weave().vertices().isEmpty() && failed.weave().held().equals(Set.of(second.id()))
                    && failed.weave().gone().isEmpty(),
                "the waiting work is held off the plan, still asked, and has not ended");
            Solved still = planner.handle(known(f), List.of(Message.ANSWERED), 2);
            helper.assertTrue(still.weave().vertices().isEmpty() && still.weave().held().equals(Set.of(second.id())),
                "while what it waits on is not asked, it does not grow");
            Solved again = planner.handle(known(f), List.of(new Message.Submitted(OWNER, Grown.of(first))), 3);
            helper.assertTrue(again.weave().vertices().keySet().equals(Set.of(first.id(), second.id()))
                    && again.weave().orders().equals(List.of(new Before(first.id(), second.id())))
                    && again.weave().held().isEmpty(),
                "asked again on its own, the work before has the waiting work back after it");
            Solved done = planner.handle(known(f), List.of(new Message.Finished(first.id())), 4);
            helper.assertTrue(done.weave().vertices().keySet().equals(Set.of(second.id()))
                    && done.weave().orders().isEmpty(),
                "done, the order is met and the work after stands free");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void withdrawnWorkIsAnUnmetOrderAndHeldWorkMayBeWithdrawn(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        Asked first = new Asked(f.source, Unmet.GO_ON);
        Asked second = new Asked(f.target, Unmet.WAIT);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            planner.handle(known(f), List.of(new Message.Submitted(OWNER, Grown.then(first, second))), 0);
            Solved withdrawn = planner.handle(known(f), List.of(new Message.Withdrawn(OWNER, first.id())), 1);
            helper.assertTrue(second.ends.equals(List.of(Ending.REVOKED))
                    && withdrawn.weave().held().equals(Set.of(second.id())),
                "the owner hears the work before was withdrawn, and keeps its own work waiting");
            Solved gone = planner.handle(known(f), List.of(new Message.Withdrawn(OWNER, second.id())), 2);
            helper.assertTrue(gone.weave().held().isEmpty() && gone.weave().vertices().isEmpty(),
                "held work withdrawn is no longer held");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void askingAgainKeepsTheOrderOfWorkStillAsked(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        Asked first = new Asked(f.source, Unmet.GO_ON);
        Asked second = new Asked(f.target, Unmet.FAIL);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            planner.handle(known(f), List.of(new Message.Submitted(OWNER, Grown.then(first, second))), 0);
            Solved again = planner.handle(known(f), List.of(new Message.Submitted(OWNER, Grown.of(first))), 1);
            helper.assertTrue(second.told.isEmpty()
                    && again.weave().orders().equals(List.of(new Before(first.id(), second.id()))),
                "work asked again at once has not ended undone: what comes after it still does");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    private static Known known(PlanningFixture f) {
        return new Known(f.situation(new Stock(List.of(), Map.of()), List.of()), f.crew.hands());
    }

    // Asked work that answers the same of every unmet order, and remembers what it was asked about.
    private static final class Asked implements Node {

        private final NodeSpec spec;
        private final Unmet answer;
        final List<UUID> told = new ArrayList<>();
        final List<Ending> ends = new ArrayList<>();

        Asked(WorldPos at, Unmet answer) {
            this.spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at), Stances.at(at),
                Workload.Once.of(1)).done();
            this.answer = answer;
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }

        @Override
        public Unmet unmet(UUID before, Ending how) {
            told.add(before);
            ends.add(how);
            return answer;
        }
    }
}
