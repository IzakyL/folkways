package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture.Owed;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
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
public final class PlanLifecycleGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PlanLifecycleGameTests.class);
    }

    private static final ItemSpec BREAD = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));

    private record Scene(PlanningFixture fixture, List<Stash> stashes, Stash target, Stands stands, Owed task) {

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
            Owed task = new Owed(new WorkSite.AtBlock(fixture.target), Stances.at(fixture.target), BREAD, 8);
            return new Scene(fixture, List.copyOf(stashes), new Stash(fixture.target), stands.done(), task);
        }

        Situation stocked(Set<Stash> emptied) {
            List<Stock.Holding> holdings = new ArrayList<>();
            Map<Stash, Integer> room = new LinkedHashMap<>();
            for (Stash stash : stashes) {
                if (!emptied.contains(stash)) {
                    holdings.add(new Stock.Holding(stash, new ItemStack(Items.BREAD, 4)));
                }
                room.put(stash, 26);
            }
            room.put(target, 27);
            return new Situation(fixture.ways, new Stock(holdings, room), List.of(), stands);
        }

        Map<Stash, UUID> fetches(Solved solved) {
            Map<Stash, UUID> found = new LinkedHashMap<>();
            solved.weave().vertices().forEach((id, vertex) -> {
                if (!id.equals(task.id())) {
                    vertex.plan().firstFrom().ifPresent(stash -> found.put(stash, id));
                }
            });
            return found;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failedFetchIsReplacedWhileTheDeliveryAndItsOtherFetchStand(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            Solved first = solver.solve(scene.stocked(Set.of()), scene.fixture.crew, scene.task.goals(), List.of(), 0);
            Map<Stash, UUID> fetches = scene.fetches(first);
            helper.assertTrue(fetches.size() == 2 && first.weave().vertices().containsKey(scene.task.id()),
                "eight bread come from two stashes of four");
            var used = new ArrayList<>(fetches.keySet());
            Stash failing = used.get(0);
            Stash kept = used.get(1);
            Stash spare = scene.stashes.stream().filter(stash -> !fetches.containsKey(stash)).findFirst().orElseThrow();

            Solved repaired = solver.solve(scene.stocked(Set.of(failing)), scene.fixture.crew, scene.task.goals(),
                List.of(new Message.Failed(fetches.get(failing), LaborRefusal.GOODS_GONE)), 1);
            Map<Stash, UUID> now = scene.fetches(repaired);
            helper.assertTrue(repaired.weave().vertices().containsKey(scene.task.id())
                    && !repaired.weave().fresh().contains(scene.task.id()),
                "the delivery keeps its lifetime while one of its supplies is replaced");
            helper.assertTrue(fetches.get(kept).equals(now.get(kept)), "the fetch that did not fail stays as it was");
            helper.assertTrue(!now.containsKey(failing) && now.containsKey(spare) && now.size() == 2,
                "only the failed four bread are sought again, from the stash still holding them");
            helper.assertTrue(repaired.diagnosis().shortfalls().isEmpty(), "the repair covers the whole delivery");
            long carried = repaired.weave().vertices().values().stream()
                .filter(vertex -> vertex.id().equals(scene.task.id()))
                .flatMap(vertex -> vertex.plan().carrying().stream())
                .mapToLong(amount -> -amount.count()).sum();
            helper.assertTrue(carried == 8, "the delivery's plan unloads eight, not twelve or four");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unrepairableSupplyLetsTheWholePlanGoWithoutOrphans(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        Claims claims = new Claims();
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(claims, new Cooldowns());
        try {
            Solved first = solver.solve(scene.stocked(Set.of()), scene.fixture.crew, scene.task.goals(), List.of(), 0);
            Map<Stash, UUID> fetches = scene.fetches(first);
            Stash failing = fetches.keySet().iterator().next();
            Stash spare = scene.stashes.stream().filter(stash -> !fetches.containsKey(stash)).findFirst().orElseThrow();
            Solved broken = solver.solve(scene.stocked(Set.of(failing, spare)), scene.fixture.crew,
                scene.task.goals(), List.of(new Message.Failed(fetches.get(failing), LaborRefusal.GOODS_GONE)), 1);
            helper.assertTrue(broken.weave().vertices().isEmpty(),
                "with four bread left, neither the delivery nor its surviving fetch stays planned");
            helper.assertTrue(!broken.diagnosis().shortfalls().isEmpty(), "the shortfall is reported");
            helper.assertTrue(claims.size() == 0, "nothing keeps a claim on stock for a plan that is gone");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failedConsumerTakesItsUnstartedSuppliesWith(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            Solved first = solver.solve(scene.stocked(Set.of()), scene.fixture.crew, scene.task.goals(), List.of(), 0);
            Map<Stash, UUID> before = scene.fetches(first);
            Solved gone = solver.solve(scene.stocked(Set.of()), scene.fixture.crew, scene.task.goals(),
                List.of(new Message.Failed(scene.task.id(), LaborRefusal.NOWHERE_TO_PUT_IT)), 1);
            helper.assertTrue(gone.weave().vertices().isEmpty(),
                "a failed goal is not grown again by the planner, and its fetches go with it");
            Solved again = solver.solve(scene.stocked(Set.of()), scene.fixture.crew, scene.task.goals(), List.of(), 2);
            Map<Stash, UUID> after = scene.fetches(again);
            helper.assertTrue(again.weave().fresh().contains(scene.task.id()),
                "offered again by its owner, it is a new lifetime");
            helper.assertTrue(after.size() == 2 && before.values().stream().noneMatch(after::containsValue),
                "with fresh fetches of its own");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void repeatedFailureBacksOffAndOpensAgainOnTime(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        Situation situation = scene.stocked(Set.of());
        var goals = scene.task.goals();
        var failed = List.<Message>of(new Message.Failed(scene.task.id(), LaborRefusal.NOWHERE_TO_PUT_IT));
        try {
            solver.solve(situation, scene.fixture.crew, goals, List.of(), 0);
            helper.assertTrue(solver.solve(situation, scene.fixture.crew, goals, failed, 1)
                .weave().vertices().isEmpty(), "a failure retires the goal instead of retrying it");
            helper.assertTrue(solver.solve(situation, scene.fixture.crew, goals, List.of(), 2)
                .weave().vertices().containsKey(scene.task.id()), "offered again after one failure, it plants at once");
            solver.solve(situation, scene.fixture.crew, goals, failed, 3);
            helper.assertTrue(solver.solve(situation, scene.fixture.crew, goals, List.of(), 4)
                .weave().vertices().isEmpty(), "offered again after a second failure in a row, it waits");
            helper.assertTrue(solver.solve(situation, scene.fixture.crew, goals, List.of(), 22)
                .weave().vertices().isEmpty(), "and holds until it is over");
            helper.assertTrue(solver.solve(situation, scene.fixture.crew, goals, List.of(), 23)
                .weave().vertices().containsKey(scene.task.id()), "then the goal is planned again");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workersAreFreedByReleaseAndAbsenceButKeepWhatTheyCarry(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        var hand = scene.fixture.crew.hands().getFirst();
        Resident other = new Resident(UUID.randomUUID(), hand.who().kind());
        Crew.Hand second = new Crew.Hand(other, scene.fixture.source, 6, 6, 1, hand.licences(), List.of(),
            Optional.empty());
        Crew both = new Crew(List.of(hand, second));
        Situation situation = scene.stocked(Set.of());
        var goals = scene.task.goals();
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            Solved first = solver.solve(situation, both, goals, List.of(), 0);
            UUID taken = scene.fetches(first).values().iterator().next();
            Crew holding = new Crew(List.of(holding(hand, taken), second));
            helper.assertTrue(hand.id().equals(solver.solve(situation, holding, goals, List.of(), 1)
                .weave().pins().get(taken)), "a held node is bound to its holder");
            Solved released = solver.solve(situation, both, goals, List.of(new Message.Released(taken)), 2);
            helper.assertTrue(!released.weave().pins().containsKey(taken),
                "a released node may be taken by anyone");

            solver.solve(situation, holding, goals, List.of(), 3);
            Solved gone = solver.solve(situation, new Crew(List.of(second)), goals,
                List.of(new Message.Left(hand.id())), 4);
            helper.assertTrue(!gone.weave().pins().containsKey(taken)
                    && gone.schedule().tours().containsKey(other.id()),
                "a holder who is no longer here does not keep the node from the rest");

            Solved carried = solver.solve(situation, both, goals, List.of(), 5);
            var tour = carried.schedule().tours().values().iterator().next();
            UUID fetched = tour.nodes().getFirst();
            Solved pinned = solver.solve(situation, both, goals, List.of(new Message.Finished(fetched)), 6);
            helper.assertTrue(tour.resident().equals(pinned.weave().pins().get(scene.task.id())),
                "once goods are in a pack, the rest of that work stays with the one carrying them");
            Solved away = solver.solve(situation,
                new Crew(List.of(tour.resident().equals(hand.id()) ? second : hand)), goals,
                List.of(new Message.Left(tour.resident())), 7);
            Crew stayed = new Crew(List.of(tour.resident().equals(hand.id()) ? second : hand));
            helper.assertTrue(!away.weave().vertices().containsKey(scene.task.id()),
                "when the carrier is gone, the work they were bound to leaves with them");
            Solved offered = solver.solve(situation, stayed, goals, List.of(), 8);
            helper.assertTrue(offered.weave().vertices().containsKey(scene.task.id())
                    && offered.schedule().unassigned().isEmpty(),
                "offered again by its owner, it is sought afresh for those still here");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void heldFetchThatCannotBeRefilledLeavesAndIsNeverGrownBack(GameTestHelper helper) {
        Scene scene = Scene.of(helper);
        var hand = scene.fixture.crew.hands().getFirst();
        var goals = scene.task.goals();
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            Solved first = solver.solve(scene.stocked(Set.of()), scene.fixture.crew, goals, List.of(), 0);
            Map<Stash, UUID> fetches = scene.fetches(first);
            Stash failing = fetches.keySet().iterator().next();
            UUID held = fetches.get(failing);
            Crew holding = new Crew(List.of(holding(hand, held)));
            solver.solve(scene.stocked(Set.of()), holding, goals, List.of(), 1);
            Set<Stash> empty = Set.copyOf(scene.stashes);
            Solved broken = solver.solve(scene.stocked(empty), holding, goals,
                List.of(new Message.Failed(fetches.values().stream().filter(id -> !id.equals(held))
                    .findFirst().orElseThrow(), LaborRefusal.GOODS_GONE)), 2);
            helper.assertTrue(broken.weave().fresh().stream().noneMatch(first.weave().vertices()::containsKey),
                "no node that was on the graph comes back under the same id in the same round");
            helper.assertTrue(!broken.weave().vertices().containsKey(scene.task.id())
                    && broken.weave().vertices().isEmpty(),
                "the goal that cannot be fed leaves with every node that served it, held or not");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    private static Crew.Hand holding(Crew.Hand hand, UUID node) {
        return new Crew.Hand(hand.who(), hand.at(), hand.packCells(), hand.packCapacity(), hand.pace(),
            hand.licences(), hand.tools(), Optional.of(node));
    }
}
