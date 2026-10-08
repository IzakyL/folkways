package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.plan.Asks;
import io.github.izakyl.folkways.core.engine.plan.Diagnosis;
import io.github.izakyl.folkways.core.engine.plan.Gone;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.Schedule;
import io.github.izakyl.folkways.core.engine.plan.Solved;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.Weave;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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
        ServerLevel level = helper.getLevel();
        ColonyData colony = new ColonyData();
        ColonyLabor labor = new ColonyLabor(colony, level.dimension());
        try {
            UUID id = UUID.randomUUID();
            List<Ending> heard = new ArrayList<>();
            List<Ending> again = new ArrayList<>();
            colony.works().submit(OWNER, level.dimension(), Grown.of(node(helper, id)), (any, how) -> {
                heard.add(how);
                colony.works().submit(OWNER, level.dimension(), Grown.of(node(helper, id)), (other, later) -> again.add(later));
            });
            hand(labor);
            apply(labor, level, List.of(), weave(node(helper, id)));
            labor.failed(id, LaborRefusal.WORK_BROKE);
            helper.assertTrue(heard.isEmpty(), "the owner hears of the ending once labor has told the planner");
            tellEndings(labor);
            labor.failed(id, LaborRefusal.WORK_BROKE);
            tellEndings(labor);
            helper.assertTrue(heard.size() == 1 && heard.getFirst() instanceof Ending.Failed && again.isEmpty(),
                "a failed ask ends exactly once");
            List<Message> told = hand(labor);
            helper.assertTrue(told.size() == 2 && told.get(0) instanceof Message.Failed(UUID failed, var why)
                    && failed.equals(id) && told.get(1) instanceof Message.Submitted(var owner, var work)
                    && work.nodes().getFirst().id().equals(id),
                "the same node asked again from the ending lands after the failure it answers");
        } finally {
            labor.close();
            ColonyWays.forget(colony.colonyId());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anAskEndsDoneOnceAndIsNotSwallowedWhenAskedAgain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonyData colony = new ColonyData();
        ColonyLabor labor = new ColonyLabor(colony, level.dimension());
        try {
            Node asked = node(helper, UUID.randomUUID());
            List<Ending> heard = new ArrayList<>();
            colony.works().submit(OWNER, level.dimension(), Grown.of(asked), (any, how) -> {
                heard.add(how);
                colony.works().submit(OWNER, level.dimension(), Grown.of(node(helper, asked.id())), (other, ignored) -> { });
            });
            hand(labor);
            apply(labor, level, List.of(), weave(asked));
            labor.phases().ready(asked, level);
            labor.phases().started(asked.id());
            labor.phases().handedOver(asked.id());
            labor.phases().settled(asked, level);
            labor.finished(asked.id());
            tellEndings(labor);
            List<Message> told = hand(labor);
            helper.assertTrue(heard.equals(List.of(Ending.DONE)), "a finished ask ends once as done");
            helper.assertTrue(told.size() == 2 && told.get(0) instanceof Message.Finished
                    && told.get(1) instanceof Message.Submitted,
                "asking for the same node again inside its ending is heard after the finish, not swallowed");
        } finally {
            labor.close();
            ColonyWays.forget(colony.colonyId());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWithdrawnAskEndsOnceThePlannerHasLetItGo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonyData colony = new ColonyData();
        ColonyLabor labor = new ColonyLabor(colony, level.dimension());
        try {
            Node planted = node(helper, UUID.randomUUID());
            Node waiting = node(helper, UUID.randomUUID());
            List<String> heard = new ArrayList<>();
            colony.works().submit(OWNER, level.dimension(), Grown.of(planted), (any, how) -> heard.add("planted " + how));
            colony.works().submit(OWNER, level.dimension(), Grown.of(waiting), (any, how) -> heard.add("waiting " + how));
            hand(labor);
            apply(labor, level, List.of(), weave(planted));
            colony.works().withdraw(OWNER, planted.id());
            colony.works().withdraw(OWNER, waiting.id());
            List<Message> batch = hand(labor);
            tellEndings(labor);
            helper.assertTrue(heard.isEmpty(), "a withdrawal is not an ending before the planner has read it");
            apply(labor, level, batch, weave());
            tellEndings(labor);
            helper.assertTrue(heard.equals(List.of("planted " + Ending.REVOKED, "waiting " + Ending.REVOKED)),
                "a withdrawn ask ends as revoked once the planner has read its withdrawal, planted or not");
            tellEndings(labor);
            helper.assertTrue(heard.size() == 2, "and only once");
        } finally {
            labor.close();
            ColonyWays.forget(colony.colonyId());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anAskThePlannerDropsEndsAsDropped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonyData colony = new ColonyData();
        ColonyLabor labor = new ColonyLabor(colony, level.dimension());
        try {
            Node asked = node(helper, UUID.randomUUID());
            List<Ending> heard = new ArrayList<>();
            colony.works().submit(OWNER, level.dimension(), Grown.of(asked), (any, how) -> heard.add(how));
            hand(labor);
            apply(labor, level, List.of(), weave(asked));
            apply(labor, level, List.of(), weave());
            tellEndings(labor);
            helper.assertTrue(heard.equals(List.of(Ending.DROPPED)), "an ask the planner let go ends as dropped");
        } finally {
            labor.close();
            ColonyWays.forget(colony.colonyId());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anAskHeldOffThePlanHasNotEnded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonyData colony = new ColonyData();
        ColonyLabor labor = new ColonyLabor(colony, level.dimension());
        try {
            Node asked = node(helper, UUID.randomUUID());
            List<Ending> heard = new ArrayList<>();
            colony.works().submit(OWNER, level.dimension(), Grown.of(asked), (any, how) -> heard.add(how));
            hand(labor);
            apply(labor, level, List.of(), weave(asked));
            apply(labor, level, List.of(), held(asked.id()));
            tellEndings(labor);
            helper.assertTrue(heard.isEmpty(), "work the planner holds off the plan for now has not ended");
            apply(labor, level, List.of(), weave(asked));
            apply(labor, level, List.of(), weave());
            tellEndings(labor);
            helper.assertTrue(heard.equals(List.of(Ending.DROPPED)), "let go once it is no longer held, it ends");
        } finally {
            labor.close();
            ColonyWays.forget(colony.colonyId());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anAskThePlannerGivesUpEndsAsThePlannerSays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonyData colony = new ColonyData();
        ColonyLabor labor = new ColonyLabor(colony, level.dimension());
        try {
            Node planted = node(helper, UUID.randomUUID());
            Node waiting = node(helper, UUID.randomUUID());
            List<String> heard = new ArrayList<>();
            colony.works().submit(OWNER, level.dimension(), Grown.of(planted), (any, how) -> heard.add("planted " + how));
            colony.works().submit(OWNER, level.dimension(), Grown.of(waiting), (any, how) -> heard.add("waiting " + how));
            hand(labor);
            apply(labor, level, List.of(), weave(planted));
            Ending plantedFailed = new Ending.Failed(planted.id(), LaborRefusal.BEFORE_NOT_DONE);
            Ending waitingFailed = new Ending.Failed(waiting.id(), LaborRefusal.BEFORE_NOT_DONE);
            apply(labor, level, List.of(), new Weave(Map.of(), List.of(), List.of(), Map.of(), Set.of(), Map.of(),
                Map.of(), List.of(new Gone(planted, plantedFailed), new Gone(waiting, waitingFailed))));
            tellEndings(labor);
            helper.assertTrue(heard.equals(List.of("planted " + plantedFailed, "waiting " + waitingFailed)),
                "work the planner gave up ends as it says, once, planted or not");
        } finally {
            labor.close();
            ColonyWays.forget(colony.colonyId());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wordAboutAnOldLifetimeDoesNotReachTheNewOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ColonyData colony = new ColonyData();
        ColonyLabor labor = new ColonyLabor(colony, level.dimension());
        try {
            UUID id = UUID.randomUUID();
            Node old = node(helper, id);
            apply(labor, level, List.of(), weave(old));
            labor.released(id);
            Node fresh = node(helper, id);
            apply(labor, level, List.of(), new Weave(Map.of(id, new Vertex(id, fresh, Placement.NOWHERE)),
                List.of(), List.of(), Map.of(), Set.of(id)));
            helper.assertTrue(hand(labor).stream().noneMatch(Message.Released.class::isInstance),
                "a release of the old lifetime is not read against the new one");
        } finally {
            labor.close();
            ColonyWays.forget(colony.colonyId());
        }
        helper.succeed();
    }

    private static Weave weave(Node... nodes) {
        Map<UUID, Vertex> vertices = new java.util.LinkedHashMap<>();
        for (Node node : nodes) {
            vertices.put(node.id(), new Vertex(node.id(), node, Placement.NOWHERE));
        }
        return new Weave(vertices, List.of(), List.of());
    }

    private static Weave held(UUID... ids) {
        return new Weave(Map.of(), List.of(), List.of(), Map.of(), Set.of(), Map.of(), Map.of(), List.of(), List.of(),
            Set.of(ids));
    }

    private static void apply(ColonyLabor labor, ServerLevel level, List<Message> batch, Weave weave) {
        try {
            var method = ColonyLabor.class.getDeclaredMethod("apply", ServerLevel.class, List.class, Solved.class);
            method.setAccessible(true);
            method.invoke(labor, level, batch, new Solved(weave, Schedule.EMPTY, Diagnosis.NOTHING, Asks.NONE));
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static void tellEndings(ColonyLabor labor) {
        try {
            var method = ColonyLabor.class.getDeclaredMethod("tellEndings");
            method.setAccessible(true);
            method.invoke(labor);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static List<Message> hand(ColonyLabor labor) {
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

    private static Node node(GameTestHelper helper, UUID id) {
        NodeSpec spec = NodeSpec.of(id, OWNER, WorkSite.at(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)),
            Stances.WHEREVER, Workload.Once.of(1)).done();
        return new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
    }
}
