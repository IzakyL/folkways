package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StandingPlanGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StandingPlanGameTests.class);
    }

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "standing");

    private static final ItemSpec BREAD = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));

    private record Scene(PlanningFixture fixture, List<Stash> stashes, Stands stands, Node delivery) {

        static Scene of(GameTestHelper helper) {
            PlanningFixture fixture = new PlanningFixture(helper);
            List<Stash> stashes = new ArrayList<>();
            Stands.Builder stands = Stands.building();
            for (int at = 0; at < 3; at++) {
                WorldPos pos = fixture.source.at(fixture.source.cell().offset(0, 0, 2 * at));
                stashes.add(new Stash(pos));
                stands.container(new Stash(pos), Set.of(new Stand(pos)));
            }
            stands.container(new Stash(fixture.target), Set.of(new Stand(fixture.target)));
            Node delivery = Delivery.to(UUID.randomUUID(), Haul.DOMAIN, new WorkSite.AtBlock(fixture.target),
                Stances.at(fixture.target), BREAD, 8, count -> { });
            return new Scene(fixture, List.copyOf(stashes), stands.done(), delivery);
        }

        Known known(boolean stocked) {
            List<Stock.Holding> holdings = new ArrayList<>();
            Map<Stash, Integer> room = new LinkedHashMap<>();
            for (Stash stash : stashes) {
                if (stocked) {
                    holdings.add(new Stock.Holding(stash, new ItemStack(Items.BREAD, 4)));
                }
                room.put(stash, 26);
            }
            room.put(new Stash(fixture.target), 27);
            return new Known(new Situation(fixture.ways, new Stock(holdings, room), List.of(), stands),
                fixture.crew.hands());
        }

        List<UUID> fetches(Solved solved) {
            return solved.weave().vertices().keySet().stream().filter(id -> !id.equals(delivery.id())).toList();
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void withdrawingAnAskTakesEverythingGrownForIt(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            Solved grown = planner.handle(scene.known(true),
                List.of(new Message.Submitted(OWNER, Grown.of(scene.delivery))), 0);
            helper.assertTrue(grown.weave().vertices().size() == 3, "a delivery and two fetches are grown");
            ResourceLocation stranger = ResourceLocation.withDefaultNamespace("stranger");
            Solved notTheirs = planner.handle(scene.known(true),
                List.of(new Message.Withdrawn(stranger, scene.delivery.id())), 1);
            helper.assertTrue(notTheirs.weave().vertices().size() == 3, "only the owner may withdraw its work");
            Solved gone = planner.handle(scene.known(true),
                List.of(new Message.Withdrawn(OWNER, scene.delivery.id())), 2);
            helper.assertTrue(gone.weave().vertices().isEmpty(), "withdrawing the ask takes its fetches too");
            Solved later = planner.handle(scene.known(true), List.of(Message.ANSWERED), 3);
            helper.assertTrue(later.weave().vertices().isEmpty(), "withdrawn work is not grown again");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void withdrawingASupplyFindsTheConsumerAnother(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            Solved grown = planner.handle(scene.known(true),
                List.of(new Message.Submitted(OWNER, Grown.of(scene.delivery))), 0);
            UUID dropped = scene.fetches(grown).getFirst();
            UUID kept = scene.fetches(grown).getLast();
            Solved wrongOwner = planner.handle(scene.known(true),
                List.of(new Message.Withdrawn(OWNER, dropped)), 1);
            helper.assertTrue(wrongOwner.weave().vertices().containsKey(dropped),
                "a node grown by the engine is withdrawn only by whoever it belongs to");
            Solved repaired = planner.handle(scene.known(true),
                List.of(new Message.Withdrawn(Haul.DOMAIN, dropped)), 2);
            helper.assertTrue(!repaired.weave().vertices().containsKey(dropped)
                    && repaired.weave().vertices().containsKey(kept)
                    && repaired.weave().vertices().containsKey(scene.delivery.id())
                    && !repaired.weave().fresh().contains(scene.delivery.id()),
                "only the withdrawn supply goes; the delivery keeps its lifetime and its other fetch");
            helper.assertTrue(scene.fetches(repaired).size() == 2 && repaired.diagnosis().shortfalls().isEmpty(),
                "the delivery is supplied anew for what the withdrawn fetch would have brought");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void withdrawingALaterSubmittedNodeLeavesTheRest(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        Node first = node(helper, scene.fixture.source);
        Node second = node(helper, scene.fixture.target);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            Solved grown = planner.handle(scene.known(false),
                List.of(new Message.Submitted(OWNER, Grown.then(first, second))), 0);
            helper.assertTrue(grown.weave().vertices().keySet().equals(Set.of(first.id(), second.id())),
                "both submitted nodes are planned");
            Solved cut = planner.handle(scene.known(false), List.of(new Message.Withdrawn(OWNER, second.id())), 1);
            helper.assertTrue(cut.weave().vertices().keySet().equals(Set.of(first.id()))
                    && !cut.weave().fresh().contains(first.id()) && cut.weave().links().isEmpty(),
                "the first node stands on, untouched, without its order to the withdrawn one");
            Solved finished = planner.handle(scene.known(false), List.of(new Message.Finished(first.id())), 2);
            Solved after = planner.handle(scene.known(false), List.of(Message.ANSWERED), 3);
            helper.assertTrue(finished.weave().vertices().isEmpty() && after.weave().vertices().isEmpty(),
                "once what is left is done, the submission is over and nothing grows again");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workThatCannotGrowWaitsForAnAnswer(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            Solved empty = planner.handle(scene.known(false),
                List.of(new Message.Submitted(OWNER, Grown.of(scene.delivery))), 0);
            helper.assertTrue(empty.weave().vertices().isEmpty() && !empty.diagnosis().shortfalls().isEmpty(),
                "with no bread known, the delivery cannot grow and says why");
            helper.assertTrue(empty.asks().survey(), "a shortfall asks for the stores to be looked over");
            Solved unrelated = planner.handle(scene.known(true),
                List.of(new Message.Released(UUID.randomUUID())), 1);
            helper.assertTrue(unrelated.weave().vertices().isEmpty(),
                "a message that answers nothing does not retry waiting work");
            Solved answered = planner.handle(scene.known(true), List.of(Message.ANSWERED), 2);
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
        Scene scene = Scene.of(helper);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            Known nobody = new Known(scene.known(true).world(), List.of());
            Solved waiting = planner.handle(nobody,
                List.of(new Message.Submitted(OWNER, Grown.of(scene.delivery))), 0);
            helper.assertTrue(waiting.weave().vertices().isEmpty() && waiting.asks().hands(),
                "nothing is sized before anyone who works is known, and the residents are asked for");
            Solved grown = planner.handle(scene.known(true), List.of(Message.ANSWERED), 1);
            helper.assertTrue(grown.weave().vertices().size() == 3 && !grown.asks().hands(),
                "once residents are read, the waiting work grows");
            UUID failing = scene.fetches(grown).getFirst();
            Stash from = grown.weave().vertices().get(failing).plan().firstFrom().orElseThrow();
            Solved failed = planner.handle(scene.known(true),
                List.of(new Message.Failed(failing, LaborRefusal.GOODS_GONE)), 2);
            helper.assertTrue(failed.asks().looks().contains(from),
                "a failed fetch asks for its store to be read again");
            UUID unread = UUID.randomUUID();
            Solved idle = planner.handle(scene.known(true), List.of(new Message.Idle(unread)), 3);
            helper.assertTrue(idle.asks().handsOf().contains(unread) && !idle.asks().looks().contains(from),
                "an idle resident not yet read is asked for; a store asked for once is not asked again");
            Solved read = planner.handle(scene.known(true),
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
        Scene scene = Scene.of(helper);
        Planner planner = new Planner(new Claims(), new Cooldowns());
        try {
            Known unread = new Known(new Situation(scene.fixture.ways, new Stock(List.of(), Map.of()), List.of(),
                scene.stands), scene.fixture.crew.hands());
            Solved waiting = planner.handle(unread,
                List.of(new Message.Submitted(OWNER, Grown.of(scene.delivery))), 0);
            helper.assertTrue(waiting.weave().vertices().isEmpty() && waiting.asks().survey(),
                "a delivery into a store never read cannot land, and the stores are asked for");
            Solved grown = planner.handle(scene.known(true), List.of(Message.ANSWERED), 1);
            helper.assertTrue(grown.weave().vertices().size() == 3,
                "once the stores are read, the waiting delivery grows");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    private static Node node(GameTestHelper helper, WorldPos at) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at), Stances.at(at),
            Workload.Once.of(1)).done();
        return new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
    }
}
