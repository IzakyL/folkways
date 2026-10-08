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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
public final class TravelPlanningGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TravelPlanningGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pendingTravelStaysPendingAndSchedulesWhenKnown(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        ResourceLocation kind = ResourceLocation.fromNamespaceAndPath("folkways", "resident");
        Resident resident = new Resident(UUID.randomUUID(), kind);
        Crew crew = new Crew(List.of(new Crew.Hand(resident, at, 6, 6, 1,
            List.of(), List.of(), Optional.empty())));
        Vertex first = node(at, kind);
        Vertex second = node(at, kind);
        Weave weave = new Weave(Map.of(first.id(), first, second.id(), second),
            List.of(new Before(first.id(), second.id())), List.of());
        AtomicReference<Faring> answer = new AtomicReference<>(Faring.LATER);
        Ways ways = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                return answer.get();
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                return answer.get();
            }
        };
        Tours tours = new Tours();
        Schedule pending = tours.schedule(weave, crew, ways, 0, true);
        helper.assertTrue(pending.tours().isEmpty(), "pending travel cannot be scheduled yet");
        helper.assertTrue(pending.unassigned().size() == 2
            && pending.unassigned().stream().allMatch(u -> u.why() == Unassigned.Reason.WAY_PENDING),
            "both the pending node and its dependent must retain the pending reason");
        helper.assertTrue(pending.unassigned().stream().anyMatch(u -> first.id().equals(u.blockedBy())),
            "the dependent must identify its actual blocker");
        answer.set(Faring.NO);
        Schedule denied = tours.schedule(weave, crew, ways, 1, true);
        helper.assertTrue(denied.unassigned().stream().allMatch(u -> u.why() == Unassigned.Reason.NO_WAY),
            "a confirmed denial must remain distinguishable from pending travel");
        answer.set(Faring.yes(Journey.afoot(1, Set.of(at))));
        Schedule known = tours.schedule(weave, crew, ways, 2, true);
        helper.assertTrue(known.unassigned().isEmpty()
            && known.tours().get(resident.id()).nodes().equals(List.of(first.id(), second.id())),
            "a resolved route must schedule the chain in dependency order");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void repeatedRoutesQueryOnceAcrossResidentsPerSchedule(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        ResourceLocation kind = ResourceLocation.fromNamespaceAndPath("folkways", "resident");
        List<Crew.Hand> hands = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            hands.add(new Crew.Hand(new Resident(UUID.randomUUID(), kind), at, 6, 6, 1,
                List.of(), List.of(), Optional.empty()));
        }
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            Vertex node = node(at, kind);
            vertices.put(node.id(), node);
        }
        AtomicInteger queries = new AtomicInteger();
        AtomicReference<Faring> answer = new AtomicReference<>(Faring.LATER);
        Ways ways = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                queries.incrementAndGet();
                return answer.get();
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                throw new AssertionError("scheduling queries individual residents");
            }
        };
        Weave weave = new Weave(vertices, List.of(), List.of());
        Tours tours = new Tours();
        Schedule pending = tours.schedule(weave, new Crew(hands), ways, 0, true);
        helper.assertTrue(queries.get() == 1,
            "500 residents and 100 equal destinations must query once, got " + queries);
        helper.assertTrue(pending.unassigned().size() == 100 && pending.unassigned().stream()
            .allMatch(u -> u.why() == Unassigned.Reason.WAY_PENDING),
            "reuse must retain pending diagnostics for every task");
        answer.set(Faring.NO);
        Schedule unanswered = tours.schedule(weave, new Crew(hands), ways, 1, false);
        helper.assertTrue(queries.get() == 1 && unanswered.unassigned().equals(pending.unassigned()),
            "waiting work must not ask again until something it waits on has been answered");
        Schedule denied = tours.schedule(weave, new Crew(hands), ways, 2, true);
        helper.assertTrue(queries.get() == 2,
            "an answer must query the route again in a new schedule");
        helper.assertTrue(denied.unassigned().size() == 100 && denied.unassigned().stream()
            .allMatch(u -> u.why() == Unassigned.Reason.NO_WAY),
            "a new snapshot answer must replace cached pending results");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void sharedRoutesKeepKindsSeparate(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        ResourceLocation walking = ResourceLocation.fromNamespaceAndPath("folkways", "resident");
        ResourceLocation flying = ResourceLocation.fromNamespaceAndPath("folkways", "flying_test");
        Resident walker = new Resident(UUID.randomUUID(), walking);
        Resident flyer = new Resident(UUID.randomUUID(), flying);
        Crew crew = new Crew(List.of(
            new Crew.Hand(walker, at, 6, 6, 1, List.of(), List.of(), Optional.empty()),
            new Crew.Hand(flyer, at, 6, 6, 1, List.of(), List.of(), Optional.empty())));
        Vertex first = node(at, walking);
        Vertex second = node(at, walking);
        Weave weave = new Weave(Map.of(first.id(), first, second.id(), second),
            List.of(new Before(first.id(), second.id())), List.of());
        Map<ResourceLocation, Integer> queries = new LinkedHashMap<>();
        Ways ways = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                queries.merge(who.kind(), 1, Integer::sum);
                return who.kind().equals(flying) ? Faring.yes(Journey.afoot(1, goals)) : Faring.NO;
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                throw new AssertionError("unexpected colony query");
            }
        };
        Schedule result = new Tours().schedule(weave, crew, ways, 0, true);
        helper.assertTrue(queries.equals(Map.of(walking, 1, flying, 1)),
            "each kind must query once even at identical origins and destinations: " + queries);
        helper.assertTrue(result.unassigned().isEmpty() && result.tours().size() == 1
            && result.tours().get(flyer.id()).nodes().equals(List.of(first.id(), second.id())),
            "one kind's denied route must not prevent another kind from taking the work");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void travelReuseFollowsCursorAndStandingRoutesAskNothing(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos away = at.at(at.cell().offset(8, 0, 0));
        ResourceLocation kind = ResourceLocation.fromNamespaceAndPath("folkways", "resident");
        Resident resident = new Resident(UUID.randomUUID(), kind);
        Crew crew = new Crew(List.of(new Crew.Hand(resident, at, 6, 6, 1,
            List.of(), List.of(), Optional.empty())));
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        List<Before> links = new ArrayList<>();
        List<UUID> order = new ArrayList<>();
        for (WorldPos goal : List.of(away, at, away, away, away)) {
            Vertex node = node(goal, kind);
            vertices.put(node.id(), node);
            if (!order.isEmpty()) {
                links.add(new Before(order.getLast(), node.id()));
            }
            order.add(node.id());
        }
        List<WorldPos> origins = new ArrayList<>();
        Ways ways = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                origins.add(from);
                return Faring.yes(Journey.afoot(from.equals(goals.iterator().next()) ? 0 : 8, goals));
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                throw new AssertionError("unexpected colony query");
            }
        };
        Weave weave = new Weave(vertices, links,  List.of());
        Tours tours = new Tours();
        Schedule result = tours.schedule(weave, crew, ways, 0, true);
        helper.assertTrue(result.unassigned().isEmpty()
            && result.tours().get(resident.id()).nodes().equals(order), "the full chain must schedule");
        helper.assertTrue(origins.equals(List.of(at, away, away)),
            "different origins and destinations must query separately; returning to a prior route reuses it");
        origins.clear();
        Schedule standing = tours.schedule(weave, crew, ways, 1, true);
        helper.assertTrue(standing.tours().equals(result.tours()) && standing.unassigned().isEmpty(),
            "a standing route must be kept as it was");
        helper.assertTrue(origins.isEmpty(), "work already on a route must not be travelled again: " + origins);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void standingRoutesPlaceOnlyWhatChanged(GameTestHelper helper) {
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos away = at.at(at.cell().offset(8, 0, 0));
        ResourceLocation kind = ResourceLocation.fromNamespaceAndPath("folkways", "resident");
        Resident resident = new Resident(UUID.randomUUID(), kind);
        Crew crew = new Crew(List.of(new Crew.Hand(resident, at, 6, 6, 1,
            List.of(), List.of(), Optional.empty())));
        Vertex done = node(at.at(at.cell().offset(2, 0, 0)), kind);
        Vertex feeds = node(at.at(at.cell().offset(4, 0, 0)), kind);
        Vertex fed = node(at.at(at.cell().offset(6, 0, 0)), kind);
        Vertex other = node(away, kind);
        Vertex added = node(at.at(at.cell().offset(10, 0, 0)), kind);
        List<Before> chain = List.of(new Before(feeds.id(), fed.id()));
        AtomicInteger queries = new AtomicInteger();
        Ways ways = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                queries.incrementAndGet();
                int blocks = goals.stream().mapToInt(goal -> from.cell().distManhattan(goal.cell())).min().orElse(0);
                return Faring.yes(Journey.afoot(4 * blocks, goals));
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                throw new AssertionError("unexpected colony query");
            }
        };
        Tours tours = new Tours();
        Schedule first = tours.schedule(weave(chain, done, feeds, fed, other), crew, ways, 0, false);
        helper.assertTrue(first.tours().get(resident.id()).nodes().equals(
                List.of(done.id(), feeds.id(), fed.id(), other.id())),
            "the route walks out along the line rather than doubling back");

        queries.set(0);
        Schedule grown = tours.schedule(weave(chain, feeds, fed, other, added), crew, ways, 10, false);
        List<UUID> kept = new ArrayList<>(first.tours().get(resident.id()).nodes());
        kept.remove(done.id());
        kept.add(added.id());
        helper.assertTrue(grown.tours().get(resident.id()).nodes().equals(kept),
            "finished work leaves the route and new work joins its end: " + grown.tours());
        helper.assertTrue(queries.get() == 1, "only the new node may be travelled to, got " + queries);

        queries.set(0);
        Vertex replanned = new Vertex(feeds.id(), feeds.node(),
            new Placement(Set.of(new Stand(at.at(at.cell().offset(4, 0, 0)))), List.of(), List.of(), List.of()));
        Schedule repaired = tours.schedule(weave(chain, replanned, fed, other, added), crew, ways, 20, false);
        List<UUID> route = repaired.tours().get(resident.id()).nodes();
        helper.assertTrue(route.equals(List.of(feeds.id(), fed.id(), other.id(), added.id())),
            "a node planned anew, with what was timed after it, is moved back to where the walk is shortest: "
                + route);
        helper.succeed();
    }

    private static Weave weave(List<Before> links, Vertex... nodes) {
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        for (Vertex node : nodes) {
            vertices.put(node.id(), node);
        }
        return new Weave(vertices, links,  List.of());
    }

    private static Vertex node(WorldPos at, ResourceLocation domain) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), domain, new WorkSite.AtBlock(at),
            Stances.at(at), Workload.Once.of(1)).done();
        Node node = new Node() {
            public NodeSpec spec() {
                return spec;
            }
            public Outcome commit(ServerLevel level, Worker who) {
                throw new AssertionError("planning must not execute work");
            }
        };
        return new Vertex(node.id(), node, Placement.NOWHERE);
    }
}
