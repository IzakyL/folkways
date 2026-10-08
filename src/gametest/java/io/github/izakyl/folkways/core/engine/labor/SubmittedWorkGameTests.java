package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.colony.ColonyWorks;
import java.util.ArrayList;
import java.util.List;
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
public final class SubmittedWorkGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SubmittedWorkGameTests.class);
    }

    private static final class Told implements ColonyWorks.Listening {
        final List<String> said = new ArrayList<>();

        public void submitted(ResourceLocation owner, Grown work) {
            said.add("submit " + work.nodes().stream().map(node -> node.id().toString()).toList());
        }

        public void withdrawn(ResourceLocation owner, UUID node) {
            said.add("withdraw " + node);
        }

        public void offered() {
            said.add("offered");
        }

        List<String> drain() {
            List<String> copy = List.copyOf(said);
            said.clear();
            return copy;
        }
    }

    @GameTest(template = "empty")
    public static void submissionsAreToldAsTheyHappen(GameTestHelper helper) {
        var level = helper.getLevel();
        var dimension = level.dimension();
        var colony = new ColonyData();
        var owner = ResourceLocation.fromNamespaceAndPath("folkways", "submitted_test");
        List<String> heard = new ArrayList<>();
        Node single = node(spec(helper, owner));
        colony.works().submit(owner, dimension, Grown.of(single), (any, how) -> heard.add("single " + how));
        Told told = new Told();
        colony.works().listen(dimension, told);
        helper.assertTrue(told.drain().equals(List.of("submit " + List.of(single.id().toString()))),
            "a listener starts from the work already standing");
        helper.assertTrue(refused(() -> colony.works().submit(owner, dimension, Grown.of(single).ranked(5),
                (any, how) -> heard.add("twice " + how)), IllegalStateException.class) && told.drain().isEmpty(),
            "a node is asked once, not again before it ended");
        Node first = node(spec(helper, owner));
        Node second = node(spec(helper, owner));
        List<String> pair = new ArrayList<>();
        colony.works().submit(owner, dimension,
            new Grown(List.of(first, second), List.of(new Before(first.id(), second.id()))),
            (id, how) -> pair.add(id + " " + how));
        helper.assertTrue(told.drain().equals(List.of("submit " + List.of(first.id().toString(),
                second.id().toString()))), "work of any shape is submitted, and told as it came");
        colony.works().ended(dimension, first.id(), Ending.DONE);
        helper.assertTrue(pair.equals(List.of(first.id() + " " + Ending.DONE)), "each node of it ends on its own");
        colony.works().ended(dimension, second.id(), Ending.DONE);

        colony.works().withdraw(ResourceLocation.fromNamespaceAndPath("folkways", "stranger"), single.id());
        helper.assertTrue(told.drain().isEmpty(), "only the owner may withdraw submitted work");
        colony.works().withdraw(owner, single.id());
        helper.assertTrue(told.drain().equals(List.of("withdraw " + single.id())) && heard.isEmpty(),
            "a withdrawn ask has not ended while the planner may still hold it");
        colony.works().submit(owner, dimension, Grown.of(single), (any, how) -> heard.add("again " + how));
        helper.assertTrue(told.drain().isEmpty(), "the same node submitted again waits for the old one to end");
        colony.works().withdrawnGone(dimension, single.id());
        helper.assertTrue(heard.equals(List.of("single " + Ending.REVOKED))
                && told.drain().equals(List.of("submit " + List.of(single.id().toString()))),
            "once the planner let it go, the old one ends and the waiting one is told");
        colony.works().withdrawnGone(dimension, single.id());
        helper.assertTrue(heard.size() == 1, "only a withdrawn ask is ended by its withdrawal settling");
        colony.works().ended(dimension, single.id(), Ending.DONE);
        colony.works().ended(dimension, single.id(), Ending.DONE);
        helper.assertTrue(heard.equals(List.of("single " + Ending.REVOKED, "again " + Ending.DONE)),
            "each submission hears its ending exactly once");

        Node elsewhere = node(spec(helper, owner));
        colony.works().unlisten(dimension, told);
        colony.works().submit(owner, dimension, Grown.of(elsewhere), (any, how) -> heard.add("unheard " + how));
        colony.works().withdraw(owner, elsewhere.id());
        helper.assertTrue(heard.getLast().equals("unheard " + Ending.REVOKED),
            "with no planner listening, a withdrawal ends the ask at once");

        Node held = node(spec(helper, owner));
        colony.works().listen(dimension, told);
        colony.works().submit(owner, dimension, Grown.of(held), (any, how) -> heard.add("held " + how));
        colony.works().withdraw(owner, held.id());
        told.drain();
        Told fresh = new Told();
        colony.works().listen(dimension, fresh);
        helper.assertTrue(heard.getLast().equals("held " + Ending.REVOKED) && fresh.drain().isEmpty(),
            "a new planner never held a withdrawn ask, so it ends when the planner is replaced");
        colony.works().submit(owner, dimension, Grown.of(single), (any, how) -> heard.add("late " + how));
        helper.assertTrue(told.drain().isEmpty(), "a listener that was replaced hears nothing more");
        ColonyWays.forget(colony.colonyId());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anotherOwnersNodeCannotBeResubmitted(GameTestHelper helper) {
        var colony = new ColonyData();
        var mine = ResourceLocation.fromNamespaceAndPath("folkways", "submitted_mine");
        var theirs = ResourceLocation.fromNamespaceAndPath("folkways", "submitted_theirs");
        Grown goal = Grown.of(node(spec(helper, mine)));
        colony.works().submit(mine, helper.getLevel().dimension(), goal, (any, how) -> { });
        helper.assertTrue(refused(() -> colony.works().submit(theirs, helper.getLevel().dimension(), goal,
                (any, how) -> { }), IllegalArgumentException.class),
            "a node belongs to the owner that asked for it");
        helper.succeed();
    }

    private static boolean refused(Runnable attempt, Class<? extends RuntimeException> as) {
        try {
            attempt.run();
            return false;
        } catch (RuntimeException thrown) {
            return as.isInstance(thrown);
        }
    }

    private static NodeSpec spec(GameTestHelper helper, ResourceLocation owner) {
        return NodeSpec.of(UUID.randomUUID(), owner,
            WorkSite.at(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)), Stances.WHEREVER,
            Workload.Once.of(1)).done();
    }

    private static Node node(NodeSpec spec) {
        return new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
    }
}
