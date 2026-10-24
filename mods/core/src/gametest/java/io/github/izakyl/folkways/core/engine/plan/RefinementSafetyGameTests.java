package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.core.engine.travel.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
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
public final class RefinementSafetyGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "refinement");
    private record Action(NodeSpec spec) implements Node {
        public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(RefinementSafetyGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void exhaustedImprovementStillAssignsReadyWorkAndAcceptsTheNextChange(GameTestHelper helper) {
        Tours tours = new Tours(3000, 8, 0);
        WorldPos origin = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        Crew crew = new Crew(List.of(hand(origin, 6), hand(origin, 6)));
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        for (int n = 0; n < 12; n++) {
            Node node = action(origin.at(origin.cell().offset(n % 4, 0, n / 4)), Workload.Once.of(10));
            vertices.put(node.id(), new Vertex(node.id(), node, Placement.NOWHERE));
        }
        WeaveView weave = new WeaveView();
        weave.apply(Shown.delta(vertices, List.of(), List.of(), Map.of(), Set.of()));
        Schedule first = tours.schedule(weave, vertices.keySet(), crew, ways(), 0, false);
        helper.assertTrue(tours.limited(), "the deliberately exhausted budget really takes the fallback");
        assignedExactly(helper, first, vertices.keySet());
        Node added = action(origin, Workload.Once.of(10));
        weave.apply(Shown.delta(Map.of(added.id(), new Vertex(added.id(), added, Placement.NOWHERE)),
            List.of(), List.of(), Map.of(), Set.of()));
        Schedule next = tours.schedule(weave, Set.of(added.id()), crew, ways(), 1, false);
        Set<UUID> expected = new HashSet<>(vertices.keySet());
        expected.add(added.id());
        assignedExactly(helper, next, expected);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deadlineStopsRefinementEvenBeforeTheWorkCountIsUsedUp(GameTestHelper helper) {
        AtomicLong clock = new AtomicLong();
        RefinementBudget budget = new RefinementBudget(1_000_000, clock::get, 100);
        budget.spend(1);
        clock.set(100);
        boolean stopped = false;
        try { budget.spend(1); } catch (RefinementBudget.Exhausted expected) { stopped = true; }
        helper.assertTrue(stopped, "elapsed time limits work even when most candidates were rejected cheaply");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    @SuppressWarnings("unchecked")
    public static void failedTransferRestoresTheTargetsContinuousRouteAndItsReservations(GameTestHelper helper) throws Exception {
        Tours tours = new Tours(3000, 8, 17);
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        WorldPos far = at.at(at.cell().offset(10, 0, 0));
        Node head = action(far, Workload.Once.of(100));
        Node moved = action(far, Workload.Once.of(100));
        Node first = action(at, Workload.Once.of(100));
        Node ongoing = action(at, (Workload.Continuous) (level, who) -> true);
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        for (Node node : List.of(head, moved, first, ongoing)) {
            Placement placement = node == moved ? new Placement(Set.of(),
                List.of(new Amount(ItemSpec.of(ResourceLocation.withDefaultNamespace("bread")), 1)), List.of(), List.of())
                : Placement.NOWHERE;
            vertices.put(node.id(), new Vertex(node.id(), node, placement));
        }
        WeaveView weave = new WeaveView();
        weave.apply(Shown.delta(vertices, List.of(new Before(head.id(), moved.id())), List.of(), Map.of(), Set.of()));
        field(Tours.class, "weave").set(tours, weave);
        field(Tours.class, "ways").set(tours, ways());
        Class<?> seatType = Class.forName(Tours.class.getName() + "$Seat");
        Constructor<?> seatConstructor = seatType.getDeclaredConstructor(Crew.Hand.class);
        seatConstructor.setAccessible(true);
        Object source = seatConstructor.newInstance(hand(far, 1));
        Object target = seatConstructor.newInstance(hand(at, 1));
        Crew.Hand fullHand = new Crew.Hand(new Resident(UUID.randomUUID(), OWNER), at, 0, 1, 1,
            List.of(), List.of(), Optional.empty());
        Object full = seatConstructor.newInstance(fullHand);
        List<Object> seats = List.of(source, target, full);
        Map<UUID, Object> indexed = (Map<UUID, Object>) field(Tours.class, "seats").get(tours);
        for (Object seat : seats) {
            Crew.Hand hand = (Crew.Hand) field(seatType, "hand").get(seat);
            indexed.put(hand.id(), seat);
            method("resume", seatType, int.class).invoke(seat, 0);
        }
        Class<?> placedType = Class.forName(Tours.class.getName() + "$Placed");
        Constructor<?> placed = placedType.getDeclaredConstructor(int.class, int.class, Stand.class, int.class, Map.class);
        placed.setAccessible(true);
        Method settle = method("settle", Tours.class, seatType, UUID.class, placedType);
        settle.invoke(tours, source, head.id(), placed.newInstance(0, 100, new Stand(far), 0, Map.of()));
        settle.invoke(tours, source, moved.id(), placed.newInstance(100, 200, new Stand(far), 0,
            Map.of(ItemSpec.of(ResourceLocation.withDefaultNamespace("bread")), 1L)));
        settle.invoke(tours, target, first.id(), placed.newInstance(0, 100, new Stand(at), 0, Map.of()));
        settle.invoke(tours, target, ongoing.id(), placed.newInstance(100, 101, new Stand(at), 0, Map.of()));
        List<?> before = List.copyOf((List<?>) field(seatType, "when").get(target));
        List<?> sourceBefore = List.copyOf((List<?>) field(seatType, "when").get(source));
        String reservations = field(Tours.class, "spots").get(tours).toString();
        boolean improved = (boolean) method("shedFrom", Tours.class, List.class, seatType).invoke(tours, seats, source);
        helper.assertFalse(improved, "the inserted detour cannot improve the original short routes");
        helper.assertTrue(before.equals(field(seatType, "when").get(target)),
            "a rejected insertion restores the target's exact original Placed times");
        helper.assertValueEqual(field(seatType, "clock").getInt(target), 101, "the target clock is restored");
        helper.assertTrue(reservations.equals(field(Tours.class, "spots").get(tours).toString()),
            "trial reservations are completely removed");
        method("improve", Tours.class, List.class).invoke(tours, seats);
        helper.assertTrue(tours.limited(), "the small work budget interrupts the transfer trial");
        helper.assertTrue(before.equals(field(seatType, "when").get(target))
                && sourceBefore.equals(field(seatType, "when").get(source)),
            "budget exhaustion inside a partially changed layout restores both residents' original routes");
        helper.assertTrue(reservations.equals(field(Tours.class, "spots").get(tours).toString()),
            "budget exhaustion also restores every occupied stand");
        helper.succeed();
    }

    private static void assignedExactly(GameTestHelper helper, Schedule schedule, Set<UUID> expected) {
        List<UUID> assigned = schedule.tours().values().stream().flatMap(tour -> tour.nodes().stream()).toList();
        helper.assertTrue(new HashSet<>(assigned).equals(expected) && assigned.size() == expected.size(),
            "each ready job is handed out exactly once even when optimization is abandoned");
    }

    private static Node action(WorldPos at, Workload workload) {
        return new Action(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at), Stances.at(at), workload).done());
    }

    private static Crew.Hand hand(WorldPos at, int free) {
        return new Crew.Hand(new Resident(UUID.randomUUID(), OWNER), at, free, free, 1, List.of(), List.of(), Optional.empty());
    }

    private static Ways ways() {
        return new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                return Faring.yes(new Journey((int) (10 * Math.sqrt(from.cell().distSqr(goals.iterator().next().cell()))), 0, List.of()));
            }
            public Faring anyoneReaches(Set<WorldPos> goals) { return Faring.yes(new Journey(0, 0, List.of())); }
        };
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(String name, Class<?> owner, Class<?>... args) throws Exception {
        Method method = owner.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }
}
