package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
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
public final class StandTravelGameTests {

    private static final ResourceLocation KIND = ResourceLocation.fromNamespaceAndPath("folkways", "resident");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StandTravelGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void mixedHeightStandsReserveOnlyTheIndividuallyReachableCandidate(GameTestHelper helper) {
        WorldPos origin = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos rack = origin.at(origin.cell().offset(8, 0, 0));
        WorldPos floor = rack.at(rack.cell().offset(0, 0, -1));
        WorldPos top = rack.at(rack.cell().above(2));
        WorldPos onward = origin.at(origin.cell().offset(16, 0, 0));
        // Both directions make the regression independent of Set iteration order. The floor-only case is a
        // thin rack whose top has footing but cannot be climbed; the other direction models access above it.
        for (WorldPos reachable : List.of(floor, top)) {
            for (boolean placed : List.of(true, false)) {
                Resident resident = new Resident(UUID.randomUUID(), KIND);
                Vertex first = node(rack, Set.of(floor, top), 1, placed);
                Vertex next = node(onward, Set.of(onward), 1, true);
                List<WorldPos> onwardOrigins = new ArrayList<>();
                Ways ways = ways((from, goals) -> {
                    if (goals.contains(onward)) {
                        onwardOrigins.add(from);
                        return from.equals(reachable) ? Faring.yes(Journey.afoot(5, goals)) : Faring.NO;
                    }
                    // A multi-goal Journey deliberately retains both candidates in Leg.to. Its Yes says
                    // only that the reachable one can be reached, never that every member can be reserved.
                    return goals.contains(reachable) ? Faring.yes(Journey.afoot(9, goals)) : Faring.NO;
                }, new ArrayList<>());
                Tours tours = new Tours(1_000);
                Schedule schedule = Shown.schedule(tours, weave(
                    Map.of(first.id(), first, next.id(), next), List.of(new Before(first.id(), next.id())),
                    Map.of(first.id(), resident.id(), next.id(), resident.id())),
                    new Crew(List.of(hand(resident, origin, Optional.empty()))), ways, 0, true);
                helper.assertTrue(schedule.unassigned().isEmpty()
                        && schedule.tours().get(resident.id()).nodes().equals(List.of(first.id(), next.id())),
                    "the complete chain must remain reachable from the reserved " + reachable + ", placed=" + placed);
                Booked booked = booked(tours, first.id());
                helper.assertTrue(booked.at().equals(new Stand(reachable)) && booked.start() == 9
                        && booked.finish() == 10,
                    "the exact reachable stand and its fare must be recorded together: " + booked);
                helper.assertTrue(!onwardOrigins.isEmpty() && onwardOrigins.stream().allMatch(reachable::equals),
                    "onward travel must start at the stand really reached: " + onwardOrigins);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void parallelWorkersReserveDifferentFreeStandsWithTheirOwnArrivalTimes(GameTestHelper helper) {
        WorldPos origin = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos left = origin.at(origin.cell().offset(4, 0, 0));
        WorldPos right = origin.at(origin.cell().offset(6, 0, 0));
        WorldPos top = left.at(left.cell().above(2));
        WorldPos onward = origin.at(origin.cell().offset(10, 0, 0));
        Resident first = new Resident(UUID.randomUUID(), KIND);
        Resident second = new Resident(UUID.randomUUID(), KIND);
        Vertex occupying = node(left, Set.of(left), 400, true);
        Vertex beside = node(left, Set.of(left, right, top), 20, true);
        Vertex next = node(onward, Set.of(onward), 1, true);
        Map<Query, Integer> queries = new LinkedHashMap<>();
        Ways ways = ways((from, goals) -> {
            queries.merge(new Query(from, goals), 1, Integer::sum);
            if (goals.contains(onward)) {
                return from.equals(right) ? Faring.yes(Journey.afoot(7, goals)) : Faring.NO;
            }
            if (goals.contains(left)) return Faring.yes(Journey.afoot(5, goals));
            if (goals.contains(right)) return Faring.yes(Journey.afoot(30, goals));
            return Faring.NO;
        }, new ArrayList<>());
        Tours tours = new Tours(1_000);
        Schedule schedule = Shown.schedule(tours, weave(
            Map.of(occupying.id(), occupying, beside.id(), beside, next.id(), next),
            List.of(new Before(beside.id(), next.id())),
            Map.of(occupying.id(), first.id(), beside.id(), second.id(), next.id(), second.id())),
            new Crew(List.of(hand(first, origin, Optional.empty()), hand(second, origin, Optional.empty()))),
            ways, 0, true);
        helper.assertTrue(schedule.unassigned().isEmpty() && schedule.tours().size() == 2,
            "both workers must be assigned their pinned work: " + schedule);
        Booked one = booked(tours, occupying.id());
        Booked two = booked(tours, beside.id());
        Booked after = booked(tours, next.id());
        helper.assertTrue(one.at().equals(new Stand(left)) && one.start() == 5 && one.finish() == 405,
            "the first worker reserves the nearer floor through its actual work span: " + one);
        helper.assertTrue(two.at().equals(new Stand(right)) && two.start() == 30 && two.finish() == 50,
            "the other worker uses the free floor and its own longer journey, not the set's cheapest fare: " + two);
        helper.assertTrue(new Span(one.start(), one.finish()).overlaps(new Span(two.start(), two.finish()))
                && after.start() == 57 && after.finish() == 58,
            "distinct stands allow parallel work and the next leg follows the actual arrival and work times: " + after);
        helper.assertTrue(queries.values().stream().allMatch(count -> count == 1),
            "repeated exact fares must share the existing route cache: " + queries);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void occupiedReachableStandsStillAskForTheirPendingAlternative(GameTestHelper helper) {
        WorldPos origin = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos floor = origin.at(origin.cell().offset(4, 0, 0));
        WorldPos upper = floor.at(floor.cell().above(2));
        Resident first = new Resident(UUID.randomUUID(), KIND);
        Resident second = new Resident(UUID.randomUUID(), KIND);
        Vertex occupying = node(floor, Set.of(floor), 400, true);
        Vertex beside = node(floor, Set.of(floor, upper), 1, true);
        AtomicReference<Faring> alternative = new AtomicReference<>(Faring.NO);
        List<Set<WorldPos>> asked = new ArrayList<>();
        Ways ways = ways((from, goals) -> goals.contains(floor)
            ? Faring.yes(Journey.afoot(5, goals)) : alternative.get(), asked);
        WeaveDelta weave = weave(Map.of(occupying.id(), occupying, beside.id(), beside), List.of(),
            Map.of(occupying.id(), first.id(), beside.id(), second.id()));
        Crew crew = new Crew(List.of(hand(first, origin, Optional.empty()), hand(second, origin, Optional.empty())));
        Tours tours = new Tours(1_000);
        Schedule denied = Shown.schedule(tours, weave, crew, ways, 0, true);
        helper.assertTrue(reason(denied, beside.id()) == Unassigned.Reason.NO_FREE_STAND && asked.isEmpty(),
            "a known reachable but occupied stand must keep the no-free-stand diagnosis");
        alternative.set(Faring.LATER);
        Schedule pending = Shown.schedule(tours, weave, crew, ways, 1, true);
        helper.assertTrue(reason(pending, beside.id()) == Unassigned.Reason.NO_FREE_STAND
                && asked.equals(List.of(Set.of(upper))),
            "the occupied stand keeps its diagnosis while the precise unknown alternative is asked for: " + asked);
        alternative.set(Faring.yes(Journey.afoot(50, Set.of(upper))));
        Schedule known = Shown.schedule(tours, weave, crew, ways, 2, true);
        helper.assertTrue(known.unassigned().isEmpty() && booked(tours, beside.id()).at().equals(new Stand(upper)),
            "the alternative is reserved only after its own route becomes known");
        Schedule none = Shown.schedule(new Tours(1_000), weave, crew, ways((from, goals) -> Faring.NO,
            new ArrayList<>()), 0, true);
        helper.assertTrue(none.tours().isEmpty()
                && none.unassigned().stream().allMatch(unassigned -> unassigned.why() == Unassigned.Reason.NO_WAY),
            "no individually reachable stand remains a path denial, not a stand conflict");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pendingExactStandIsRequestedEvenWhenTheWholeSetHasAYes(GameTestHelper helper) {
        WorldPos origin = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos floor = origin.at(origin.cell().offset(4, 0, 0));
        WorldPos top = floor.at(floor.cell().above(2));
        Resident resident = new Resident(UUID.randomUUID(), KIND);
        Vertex node = node(floor, Set.of(floor, top), 1, true);
        List<Set<WorldPos>> asked = new ArrayList<>();
        AtomicReference<Faring> floorRoute = new AtomicReference<>(Faring.LATER);
        Ways ways = ways((from, goals) -> goals.size() > 1 ? Faring.yes(Journey.afoot(5, goals))
            : goals.contains(floor) ? floorRoute.get() : Faring.NO, asked);
        Tours tours = new Tours(1_000);
        WeaveDelta weave = weave(Map.of(node.id(), node), List.of(), Map.of(node.id(), resident.id()));
        Crew crew = new Crew(List.of(hand(resident, origin, Optional.empty())));
        Schedule pending = Shown.schedule(tours, weave, crew, ways, 0, true);
        helper.assertTrue(pending.tours().isEmpty() && reason(pending, node.id()) == Unassigned.Reason.WAY_PENDING
                && asked.equals(List.of(Set.of(floor))),
            "a collection's cached Yes must not suppress the exact pending path request: " + asked);
        floorRoute.set(Faring.yes(Journey.afoot(11, Set.of(floor))));
        Schedule known = Shown.schedule(tours, weave, crew, ways, 1, true);
        helper.assertTrue(known.unassigned().isEmpty() && booked(tours, node.id()).at().equals(new Stand(floor))
                && booked(tours, node.id()).start() == 12,
            "a later snapshot must use the now-known individual route and its own cost");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void heldWorkOnlyGrantsZeroTravelAtTheWorkersActualStand(GameTestHelper helper) {
        WorldPos floor = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos top = floor.at(floor.cell().above(2));
        WorldPos onward = floor.at(floor.cell().offset(4, 0, 0));
        Resident resident = new Resident(UUID.randomUUID(), KIND);
        Vertex held = node(floor, Set.of(floor, top), 20, true);
        Vertex next = node(onward, Set.of(onward), 1, true);
        Ways ways = ways((from, goals) -> {
            if (goals.contains(onward)) {
                return from.equals(floor) ? Faring.yes(Journey.afoot(3, goals)) : Faring.NO;
            }
            helper.assertTrue(!goals.contains(floor), "a held task at its actual stand needs no new way");
            return Faring.NO;
        }, new ArrayList<>());
        Tours tours = new Tours(1_000);
        Schedule schedule = Shown.schedule(tours, weave(Map.of(held.id(), held, next.id(), next),
            List.of(new Before(held.id(), next.id())), Map.of(held.id(), resident.id(), next.id(), resident.id())),
            new Crew(List.of(hand(resident, floor, Optional.of(held.id())))), ways, 0, true);
        helper.assertTrue(schedule.unassigned().isEmpty() && booked(tours, held.id()).at().equals(new Stand(floor))
                && booked(tours, held.id()).start() == 0 && booked(tours, next.id()).start() == 23,
            "holding a task cannot synthesize a zero-cost arrival at an unreachable rack top");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sharedMixedStandFaresAreQueriedOnceAcrossWorkersAndTasks(GameTestHelper helper) {
        WorldPos origin = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos floor = origin.at(origin.cell().offset(4, 0, 0));
        WorldPos top = floor.at(floor.cell().above(2));
        List<Crew.Hand> hands = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            hands.add(hand(new Resident(UUID.randomUUID(), KIND), origin, Optional.empty()));
        }
        Map<UUID, Vertex> nodes = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            Vertex node = node(floor, Set.of(floor, top), 1, true);
            nodes.put(node.id(), node);
        }
        Map<Query, Integer> queries = new LinkedHashMap<>();
        List<Set<WorldPos>> asked = new ArrayList<>();
        Ways ways = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                asked.add(Set.copyOf(goals));
                return Faring.LATER;
            }
            public Faring reckon(Resident who, WorldPos from, Set<WorldPos> goals) {
                queries.merge(new Query(from, goals), 1, Integer::sum);
                return goals.size() > 1 ? Faring.yes(Journey.afoot(5, goals))
                    : goals.contains(floor) ? Faring.LATER : Faring.NO;
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                throw new AssertionError("scheduling queries individual residents");
            }
        };
        Schedule pending = Shown.schedule(new Tours(1_000), weave(nodes, List.of(), Map.of()),
            new Crew(hands), ways, 0, true);
        helper.assertTrue(pending.tours().isEmpty() && pending.unassigned().size() == 100
                && pending.unassigned().stream().allMatch(unassigned -> unassigned.why() == Unassigned.Reason.WAY_PENDING),
            "all tasks retain the same pending exact-stand diagnosis");
        helper.assertTrue(queries.size() == 3 && queries.values().stream().allMatch(count -> count == 1)
                && asked.equals(List.of(Set.of(floor))),
            "500 workers and 100 mixed-stand tasks query each shared fare and ask the pending path only once: " + queries);
        helper.succeed();
    }

    private record Query(WorldPos from, Set<WorldPos> goals) {
        Query {
            goals = Set.copyOf(goals);
        }
    }

    private record Booked(Stand at, int start, int finish) {
    }

    private static Booked booked(Tours tours, UUID id) {
        try {
            var field = Tours.class.getDeclaredField("settled");
            field.setAccessible(true);
            Object placed = ((Map<?, ?>) field.get(tours)).get(id);
            if (placed == null) throw new AssertionError("work was not placed: " + id);
            var at = placed.getClass().getDeclaredMethod("at");
            var start = placed.getClass().getDeclaredMethod("start");
            var finish = placed.getClass().getDeclaredMethod("finish");
            at.setAccessible(true);
            start.setAccessible(true);
            finish.setAccessible(true);
            return new Booked((Stand) at.invoke(placed), (int) start.invoke(placed), (int) finish.invoke(placed));
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static Unassigned.Reason reason(Schedule schedule, UUID id) {
        return schedule.unassigned().stream().filter(unassigned -> unassigned.node().equals(id))
            .findFirst().orElseThrow().why();
    }

    private static Ways ways(BiFunction<WorldPos, Set<WorldPos>, Faring> answer, List<Set<WorldPos>> asked) {
        return new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                asked.add(Set.copyOf(goals));
                return answer.apply(from, goals);
            }
            public Faring reckon(Resident who, WorldPos from, Set<WorldPos> goals) {
                return answer.apply(from, goals);
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                throw new AssertionError("scheduling queries individual residents");
            }
        };
    }

    private static Crew.Hand hand(Resident resident, WorldPos at, Optional<UUID> held) {
        return new Crew.Hand(resident, at, 6, 6, 1, List.of(), List.of(), held);
    }

    private static WeaveDelta weave(Map<UUID, Vertex> nodes, List<Before> links, Map<UUID, UUID> pins) {
        return Shown.delta(nodes, links, List.of(), pins, Set.of());
    }

    private static Vertex node(WorldPos site, Set<WorldPos> cells, int ticks, boolean placed) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), KIND, new WorkSite.AtBlock(site),
            Stances.of(cells).orElseThrow(), Workload.Once.of(ticks)).done();
        Node node = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) {
                throw new AssertionError("planning must not execute work");
            }
        };
        Set<Stand> stands = new LinkedHashSet<>();
        for (WorldPos cell : cells) stands.add(new Stand(cell));
        Placement placement = placed ? new Placement(stands, List.of(), List.of(), List.of()) : Placement.NOWHERE;
        return new Vertex(node.id(), node, placement);
    }
}
