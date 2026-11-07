package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Gone;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Shown;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AskedLifecycleGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(AskedLifecycleGameTests.class);
    }

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "asked_lifecycle");

    @GameTest(template = "empty")
    public static void aFailedAskEndsOnceAndItsOwnerMayAskAgainAtOnce(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            UUID id = UUID.randomUUID();
            List<Ending> heard = new ArrayList<>();
            List<Ending> again = new ArrayList<>();
            s.ask(s.node(id), (any, how) -> {
                heard.add(how);
                s.ask(s.node(id), (other, later) -> again.add(later));
            });
            s.hand();
            s.apply(List.of(), s.node(id));
            s.labor.failed(id, LaborRefusal.WORK_BROKE);
            helper.assertTrue(heard.isEmpty(), "the owner hears of the ending once labor has told the planner");
            s.tellEndings();
            s.labor.failed(id, LaborRefusal.WORK_BROKE);
            s.tellEndings();
            helper.assertTrue(heard.size() == 1 && heard.getFirst() instanceof Ending.Failed && again.isEmpty(),
                "a failed ask ends exactly once");
            List<Message> told = s.hand();
            helper.assertTrue(told.size() == 2 && told.get(0) instanceof Message.Failed(UUID failed, var why)
                    && failed.equals(id) && told.get(1) instanceof Message.Submitted(var owner, var work)
                    && work.nodes().getFirst().id().equals(id),
                "the same node asked again from the ending lands after the failure it answers");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anAskEndsDoneOnceAndIsNotSwallowedWhenAskedAgain(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            Node asked = s.node(UUID.randomUUID());
            List<Ending> heard = new ArrayList<>();
            s.ask(asked, (any, how) -> {
                heard.add(how);
                s.ask(s.node(asked.id()), (other, ignored) -> { });
            });
            s.hand();
            s.apply(List.of(), asked);
            s.labor.execution().phases().ready(asked, s.level);
            s.labor.execution().phases().started(asked.id());
            s.labor.execution().phases().handedOver(asked.id());
            s.labor.execution().phases().settled(asked, s.level);
            s.labor.finished(asked.id());
            s.tellEndings();
            List<Message> told = s.hand();
            helper.assertTrue(heard.equals(List.of(Ending.DONE)), "a finished ask ends once as done");
            helper.assertTrue(told.size() == 2 && told.get(0) instanceof Message.Finished
                    && told.get(1) instanceof Message.Submitted,
                "asking for the same node again inside its ending is heard after the finish, not swallowed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWithdrawnAskEndsOnceThePlannerHasLetItGo(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            Node planted = s.node(UUID.randomUUID());
            Node waiting = s.node(UUID.randomUUID());
            List<String> heard = new ArrayList<>();
            s.ask(planted, (any, how) -> heard.add("planted " + how));
            s.ask(waiting, (any, how) -> heard.add("waiting " + how));
            s.hand();
            s.apply(List.of(), planted);
            s.colony.works().withdraw(OWNER, planted.id());
            s.colony.works().withdraw(OWNER, waiting.id());
            List<Message> batch = s.hand();
            s.tellEndings();
            helper.assertTrue(heard.isEmpty(), "a withdrawal is not an ending before the planner has read it");
            s.apply(batch);
            s.tellEndings();
            helper.assertTrue(heard.equals(List.of("planted " + Ending.REVOKED, "waiting " + Ending.REVOKED)),
                "a withdrawn ask ends as revoked once the planner has read its withdrawal, planted or not");
            s.tellEndings();
            helper.assertTrue(heard.size() == 2, "and only once");
        }
        helper.succeed();
    }

    // Held off the plan it has not ended; let go once it is no longer held, it ends as dropped.
    @GameTest(template = "empty")
    public static void anAskHeldOffThePlanHasNotEnded(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            Node asked = s.node(UUID.randomUUID());
            List<Ending> heard = new ArrayList<>();
            s.ask(asked, (any, how) -> heard.add(how));
            s.hand();
            s.apply(List.of(), asked);
            PlanningFixture.applyWhole(s.labor, s.level, List.of(), Map.of(), List.of(), List.of(), Set.of(),
                List.of(), Set.of(asked.id()));
            s.tellEndings();
            helper.assertTrue(heard.isEmpty(), "work the planner holds off the plan for now has not ended");
            s.apply(List.of(), asked);
            s.apply(List.of());
            s.tellEndings();
            helper.assertTrue(heard.equals(List.of(Ending.DROPPED)), "let go once it is no longer held, it ends");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anAskThePlannerGivesUpEndsAsThePlannerSays(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            Node planted = s.node(UUID.randomUUID());
            Node waiting = s.node(UUID.randomUUID());
            List<String> heard = new ArrayList<>();
            s.ask(planted, (any, how) -> heard.add("planted " + how));
            s.ask(waiting, (any, how) -> heard.add("waiting " + how));
            s.hand();
            s.apply(List.of(), planted);
            Ending plantedFailed = new Ending.Failed(planted.id(), LaborRefusal.BEFORE_NOT_DONE);
            Ending waitingFailed = new Ending.Failed(waiting.id(), LaborRefusal.BEFORE_NOT_DONE);
            PlanningFixture.applyWhole(s.labor, s.level, List.of(), Map.of(), List.of(), List.of(), Set.of(),
                List.of(new Gone(planted, plantedFailed), new Gone(waiting, waitingFailed)), Set.of());
            s.tellEndings();
            helper.assertTrue(heard.equals(List.of("planted " + plantedFailed, "waiting " + waitingFailed)),
                "work the planner gave up ends as it says, once, planted or not");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wordAboutAnOldLifetimeDoesNotReachTheNewOne(GameTestHelper helper) {
        try (Scene s = new Scene(helper)) {
            UUID id = UUID.randomUUID();
            s.apply(List.of(), s.node(id));
            s.labor.released(id);
            PlanningFixture.applyWhole(s.labor, s.level, List.of(), Shown.vertices(s.node(id)), List.of(), List.of(),
                Set.of(id), List.of(), Set.of());
            helper.assertTrue(s.hand().stream().noneMatch(Message.Released.class::isInstance),
                "a release of the old lifetime is not read against the new one");
        }
        helper.succeed();
    }

    // One colony's labor, and the asks its owner makes of it.
    private static final class Scene implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final ColonyData colony = new ColonyData();
        final ColonyLabor labor;

        Scene(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            labor = new ColonyLabor(colony, level.dimension());
        }

        // A fresh node each call, even for the same id: each is its own lifetime.
        Node node(UUID id) {
            NodeSpec spec = NodeSpec.of(id, OWNER, WorkSite.at(level, helper.absolutePos(BlockPos.ZERO)),
                Stances.WHEREVER, Workload.Once.of(1)).done();
            return new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            };
        }

        void ask(Node node, BiConsumer<UUID, Ending> ended) {
            colony.works().submit(OWNER, level.dimension(), Grown.of(node), ended);
        }

        // The whole plan is now these nodes, after the planner has read `batch`.
        void apply(List<Message> batch, Node... nodes) {
            PlanningFixture.applyWhole(labor, level, batch, Shown.vertices(nodes));
        }

        void tellEndings() {
            try {
                var method = ColonyLabor.class.getDeclaredMethod("tellEndings");
                method.setAccessible(true);
                method.invoke(labor);
            } catch (ReflectiveOperationException broken) {
                throw new AssertionError(broken);
            }
        }

        List<Message> hand() {
            try {
                var field = ColonyLabor.class.getDeclaredField("inbox");
                field.setAccessible(true);
                Inbox inbox = (Inbox) field.get(labor);
                List<Message> batch = inbox.hand();
                inbox.digested();
                return batch;
            } catch (ReflectiveOperationException broken) {
                throw new AssertionError(broken);
            }
        }

        public void close() {
            labor.close();
            ColonyWays.forget(colony.colonyId());
        }
    }
}
