package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.testing.Nodes;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.HaulWork;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StandingPlanGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StandingPlanGameTests.class);
    }

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "standing");

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void withdrawingAnAskTakesEverythingGrownForIt(GameTestHelper helper) {
        BreadStores scene = new BreadStores(helper);
        Node delivery = delivery(scene);
        Solving planner = new Solving();
        try {
            Planned grown = planner.handle(scene.known(true),
                List.of(new Message.Submitted(OWNER, Grown.of(delivery))), 0);
            helper.assertTrue(grown.weave().vertices().size() == 3, "a delivery and two fetches are grown");
            ResourceLocation stranger = ResourceLocation.withDefaultNamespace("stranger");
            Planned notTheirs = planner.handle(scene.known(true),
                List.of(new Message.Withdrawn(stranger, delivery.id())), 1);
            helper.assertTrue(notTheirs.weave().vertices().size() == 3, "only the owner may withdraw its work");
            Planned gone = planner.handle(scene.known(true),
                List.of(new Message.Withdrawn(OWNER, delivery.id())), 2);
            helper.assertTrue(gone.weave().vertices().isEmpty(), "withdrawing the ask takes its fetches too");
            Planned later = planner.handle(scene.known(true), List.of(Message.ANSWERED), 3);
            helper.assertTrue(later.weave().vertices().isEmpty(), "withdrawn work is not grown again");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void withdrawingASupplyFindsTheConsumerAnother(GameTestHelper helper) {
        BreadStores scene = new BreadStores(helper);
        Node delivery = delivery(scene);
        Solving planner = new Solving();
        try {
            Planned grown = planner.handle(scene.known(true),
                List.of(new Message.Submitted(OWNER, Grown.of(delivery))), 0);
            UUID dropped = fetches(grown, delivery).getFirst();
            UUID kept = fetches(grown, delivery).getLast();
            Planned wrongOwner = planner.handle(scene.known(true),
                List.of(new Message.Withdrawn(OWNER, dropped)), 1);
            helper.assertTrue(wrongOwner.weave().vertices().containsKey(dropped),
                "a node grown by the engine is withdrawn only by whoever it belongs to");
            Planned repaired = planner.handle(scene.known(true),
                List.of(new Message.Withdrawn(HaulWork.DOMAIN, dropped)), 2);
            helper.assertTrue(!repaired.weave().vertices().containsKey(dropped)
                    && repaired.weave().vertices().containsKey(kept)
                    && repaired.weave().vertices().containsKey(delivery.id())
                    && !repaired.weave().fresh().contains(delivery.id()),
                "only the withdrawn supply goes; the delivery keeps its lifetime and its other fetch");
            helper.assertTrue(fetches(repaired, delivery).size() == 2 && repaired.diagnosis().shortfalls().isEmpty(),
                "the delivery is supplied anew for what the withdrawn fetch would have brought");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void withdrawingALaterSubmittedNodeLeavesTheRest(GameTestHelper helper) {
        BreadStores scene = new BreadStores(helper);
        Node delivery = delivery(scene);
        Node first = node(scene.fixture.source);
        Node second = node(scene.fixture.target);
        Solving planner = new Solving();
        try {
            Planned grown = planner.handle(scene.known(false),
                List.of(new Message.Submitted(OWNER, Grown.then(first, second))), 0);
            helper.assertTrue(grown.weave().vertices().keySet().equals(Set.of(first.id(), second.id())),
                "both submitted nodes are planned");
            Planned cut = planner.handle(scene.known(false), List.of(new Message.Withdrawn(OWNER, second.id())), 1);
            helper.assertTrue(cut.weave().vertices().keySet().equals(Set.of(first.id()))
                    && !cut.weave().fresh().contains(first.id()) && cut.weave().orders().isEmpty(),
                "the first node stands on, untouched, without its order to the withdrawn one");
            Planned finished = planner.handle(scene.known(false), List.of(new Message.Finished(first.id())), 2);
            Planned after = planner.handle(scene.known(false), List.of(Message.ANSWERED), 3);
            helper.assertTrue(finished.weave().vertices().isEmpty() && after.weave().vertices().isEmpty(),
                "once what is left is done, the submission is over and nothing grows again");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workThatCannotGrowWaitsForAnAnswer(GameTestHelper helper) {
        BreadStores scene = new BreadStores(helper);
        Node delivery = delivery(scene);
        Solving planner = new Solving();
        try {
            Planned empty = planner.handle(scene.known(false),
                List.of(new Message.Submitted(OWNER, Grown.of(delivery))), 0);
            helper.assertTrue(empty.weave().vertices().isEmpty() && !empty.diagnosis().shortfalls().isEmpty(),
                "with no bread known, the delivery cannot grow and says why");
            helper.assertTrue(empty.asks().survey(), "a shortfall asks for the stores to be looked over");
            Planned unrelated = planner.handle(scene.known(true),
                List.of(new Message.Released(UUID.randomUUID())), 1);
            helper.assertTrue(unrelated.weave().vertices().isEmpty(),
                "a message that answers nothing does not retry waiting work");
            Planned answered = planner.handle(scene.known(true), List.of(Message.ANSWERED), 2);
            helper.assertTrue(answered.weave().vertices().size() == 3 && answered.diagnosis().shortfalls().isEmpty()
                    && !answered.asks().survey(),
                "once the stores were read again, the delivery grows and stops asking");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void askingFollowsWhatThePlanNeeds(GameTestHelper helper) {
        BreadStores scene = new BreadStores(helper);
        Node delivery = delivery(scene);
        Solving planner = new Solving();
        try {
            Known nobody = new Known(scene.known(true).world(), List.of());
            Planned waiting = planner.handle(nobody,
                List.of(new Message.Submitted(OWNER, Grown.of(delivery))), 0);
            helper.assertTrue(waiting.weave().vertices().isEmpty() && waiting.asks().hands(),
                "nothing is sized before anyone who works is known, and the residents are asked for");
            Planned grown = planner.handle(scene.known(true), List.of(Message.ANSWERED), 1);
            helper.assertTrue(grown.weave().vertices().size() == 3 && !grown.asks().hands(),
                "once residents are read, the waiting work grows");
            UUID failing = fetches(grown, delivery).getFirst();
            Stash from = grown.weave().vertices().get(failing).plan().firstFrom().orElseThrow();
            Planned failed = planner.handle(scene.known(true),
                List.of(new Message.Failed(failing, LaborRefusal.GOODS_GONE)), 2);
            helper.assertTrue(failed.asks().looks().contains(from),
                "a failed fetch asks for its store to be read again");
            UUID unread = UUID.randomUUID();
            Planned idle = planner.handle(scene.known(true), List.of(new Message.Idle(unread)), 3);
            helper.assertTrue(idle.asks().handsOf().contains(unread) && !idle.asks().looks().contains(from),
                "an idle resident not yet read is asked for; a store asked for once is not asked again");
            Planned read = planner.handle(scene.known(true),
                List.of(new Message.Idle(scene.fixture.crew.hands().getFirst().id())), 4);
            helper.assertTrue(!read.asks().handsOf().contains(scene.fixture.crew.hands().getFirst().id()),
                "an idle resident already read with nothing in hand needs no further look");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aDeliveryIntoAStoreNotYetReadAsksForTheStores(GameTestHelper helper) {
        BreadStores scene = new BreadStores(helper);
        Node delivery = delivery(scene);
        Solving planner = new Solving();
        try {
            Known unread = new Known(new Situation(scene.fixture.ways, new Stock(List.of(), Map.of()), List.of(),
                scene.stands), scene.fixture.crew.hands());
            Planned waiting = planner.handle(unread,
                List.of(new Message.Submitted(OWNER, Grown.of(delivery))), 0);
            helper.assertTrue(waiting.weave().vertices().isEmpty() && waiting.asks().survey(),
                "a delivery into a store never read cannot land, and the stores are asked for");
            Planned grown = planner.handle(scene.known(true), List.of(Message.ANSWERED), 1);
            helper.assertTrue(grown.weave().vertices().size() == 3,
                "once the stores are read, the waiting delivery grows");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    private static Node node(WorldPos at) {
        return Nodes.done(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at), Stances.at(at),
            Workload.Once.of(1)).done());
    }

    private static Node delivery(BreadStores scene) {
        var target = scene.fixture.target;
        return Delivery.to(UUID.randomUUID(), HaulWork.DOMAIN, new WorkSite.AtBlock(target), Stances.at(target),
            BreadStores.BREAD, 8, count -> { });
    }

    private static List<UUID> fetches(Planned solved, Node delivery) {
        return solved.weave().vertices().keySet().stream().filter(id -> !id.equals(delivery.id())).toList();
    }
}
