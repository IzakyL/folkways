package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Trip;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.walk.Walking;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Same-server cache invalidation, with real native geometry and published route behaviour. */
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WaypointInvalidationGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(WaypointInvalidationGameTests.class); }

    @GameTest(template = "empty_large", batch = "waypoint_native_failure", timeoutTicks = 800)
    public static void aRealTripDropsABuriedWaypointInsteadOfTryingItsOtherIncomingEdges(GameTestHelper helper) throws Exception {
        Fixture rig = new Fixture(helper);
        boolean[] active = {false};
        helper.onEachTick(() -> {
            if (!active[0] || rig.closed) return;
            try {
                Going progress = rig.trip.step(rig.level, rig.body, Set.of(rig.goal));
                helper.assertTrue(!(progress instanceof Going.Arrived), "the stale intermediate point inside a solid island cannot be a successful trip");
                if (progress instanceof Going.Failed) {
                    active[0] = false;
                    helper.assertTrue(rig.body.walkDist > 0.5F, "the real resident physically walks toward the obsolete point before native failure");
                    helper.assertTrue(!rig.body.isInWall(), "native failure never puts the actual resident inside the filled island");
                    rig.trip.release(rig.level, rig.body);
                    rig.verifyRemovedAndRelearned();
                    rig.close(); helper.succeed();
                }
            } catch (RuntimeException | Error failure) { rig.close(); throw failure; }
            catch (ReflectiveOperationException failure) { rig.close(); throw new IllegalStateException(failure); }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.body.tickCount >= 5,
            "the actual resident must receive real entity ticks before native travel begins"))
            .thenExecute(() -> {
                for (BlockPos cell : BlockPos.betweenClosed(rig.bad.cell().offset(-3, 0, -3), rig.bad.cell().offset(3, 2, 3))) {
                    rig.level.setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
                }
                rig.trip = Trip.to(rig.roaming::ways, rig.who, Walking.INSTANCE, rig.body, Set.of(rig.goal), 1.0D,
                    () -> "folkways.test.no_way").orElseThrow();
                rig.trip.setOut(rig.level, rig.body, Set.of(rig.goal));
                active[0] = true;
            });
        helper.runAtTickTime(700, () -> {
            if (rig.closed) return;
            rig.close();
            helper.assertTrue(false, "the bounded real native trip must report failure and finish cache recovery");
        });
    }

    @GameTest(template = "empty_large", batch = "waypoint_blocked_start")
    public static void aBlockedSyntheticOriginCannotRegrowAndAnActuallyReopenedPointCanBeLearnedAgain(GameTestHelper helper) throws Exception {
        try (Fixture rig = new Fixture(helper)) {
            rig.level.setBlock(rig.bad.cell().above(), Blocks.STONE.defaultBlockState(), 3);
            helper.assertTrue(Look.blocked(rig.level, rig.bad, Walking.INSTANCE, rig.body), "the native body head space is really blocked");
            rig.roaming.wrongEdge(rig.who, Walking.INSTANCE, rig.body, rig.from, rig.bad);
            var blocked = Look.at(rig.level, rig.bad.realm(), rig.roamer, rig.bad.cell().asLong(), Set.of(rig.goal.cell()),
                rig.goal.cell(), true, 64).orElseThrow().swept();
            helper.assertTrue(blocked.nodes().isEmpty() && blocked.walked().isEmpty() && blocked.visited() == 0,
                "a forced BLOCKED start yields no positive path or walked cells to harvest");
            new Ramble(rig.level, rig.bad.realm(), rig.net, rig.roamer, rig.bad.cell().asLong()).run(rig.now(), 64);
            helper.assertTrue(rig.net.at(rig.bad.cell().asLong()) < 0, "normal rambling cannot re-admit that buried synthetic start");
            rig.level.setBlock(rig.bad.cell().above(), Blocks.AIR.defaultBlockState(), 3);
            rig.witness(rig.bad, rig.goal);
            int learned = new Ramble(rig.level, rig.bad.realm(), rig.net, rig.roamer, rig.bad.cell().asLong()).run(rig.now(), 512);
            helper.assertTrue(learned > 0 && rig.net.at(rig.bad.cell().asLong()) >= 0,
                "reopening actual head space allows a fresh native sweep to learn the same coordinate again");
            rig.publish();
            Faring reopened = rig.answer(rig.from, rig.bad);
            helper.assertTrue(reopened instanceof Faring.Yes, "a reopened real point is reachable again without a permanent tombstone");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "waypoint_congestion")
    public static void aLegalWaypointOccupiedByAnEntityKeepsItsOtherIncomingRoute(GameTestHelper helper) throws Exception {
        try (Fixture rig = new Fixture(helper)) {
            Mob crowd = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, helper.relativePos(rig.bad.cell()));
            try {
                helper.assertTrue(!Look.blocked(rig.level, rig.bad, Walking.INSTANCE, rig.body),
                    "an actual living occupant is congestion rather than a native BLOCKED terrain point");
                int epoch = rig.net.era();
                rig.roaming.wrongEdge(rig.who, Walking.INSTANCE, rig.body, rig.from, rig.bad);
                rig.publish();
                helper.assertTrue(rig.net.at(rig.bad.cell().asLong()) >= 0 && rig.net.era() == epoch,
                    "ordinary failure leaves the valid point and geometry epoch intact");
                helper.assertTrue(uses(rig.query(rig.other, rig.goal), rig.bad),
                    "only the failed incoming edge is suspended; another valid incoming route remains published");
                crowd.discard();
                rig.witness(rig.from, rig.bad);
                new Ramble(rig.level, rig.from.realm(), rig.net, rig.roamer,
                    rig.from.cell().asLong(), rig.bad.cell().asLong()).run(rig.now(), 512);
                rig.publish();
                helper.assertTrue(uses(rig.query(rig.from, rig.goal), rig.bad),
                    "fresh native sweep evidence can restore a legal congested edge before its backoff expires");
            } finally { crowd.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "waypoint_pending_epoch")
    public static void aBlockedPointRequeuesItsActiveQuestWithoutCreatingANewNegativeShelf(GameTestHelper helper) throws Exception {
        try (Fixture rig = new Fixture(helper)) {
            Urgency priority = Urgency.walking(-7);
            Ask ask = Ask.of(rig.who.kind(), rig.from, Set.of(rig.goal), priority).orElseThrow();
            Quest old = new Quest(rig.level, rig.net, rig.roamer, ask, rig.now());
            field(Roaming.class, "quest").set(rig.roaming, old);
            rig.level.setBlock(rig.bad.cell(), Blocks.DIRT.defaultBlockState(), 3);
            rig.roaming.wrongEdge(rig.who, Walking.INSTANCE, rig.body, rig.from, rig.bad);
            Object current = field(Roaming.class, "quest").get(rig.roaming);
            Pending waiting = (Pending) field(Roaming.class, "pending").get(rig.roaming);
            helper.assertTrue(current != old && old.stale() && waiting.contains(ask),
                "the exact obsolete quest is cancelled and its original request resumes through normal pending work");
            Ask resumed = waiting.iterator().next();
            helper.assertTrue(resumed.key().equals(ask.key()) && resumed.urgency().equals(priority),
                "requeue preserves the original request key and walking priority");
            rig.roaming.advance(List.of(rig.roamer), List.of(), List.of(), rig.now());
            Faring answer = rig.answer(rig.from, rig.goal);
            @SuppressWarnings("unchecked") Map<Ask.Key, ?> shelves = (Map<Ask.Key, ?>) field(Roaming.class, "shelved").get(rig.roaming);
            helper.assertTrue(answer instanceof Faring.Yes && !uses(answer, rig.bad) && !shelves.containsKey(ask.key()),
                "normal recovery finds the real alternate route without stale-epoch GAVE_UP becoming a1200-tick denial");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "waypoint_old_bad_goal")
    public static void anOldRequestForTheBuriedGoalCannotRepublishAPositiveRouteThroughIt(GameTestHelper helper) throws Exception {
        try (Fixture rig = new Fixture(helper)) {
            rig.level.setBlock(rig.bad.cell(), Blocks.DIRT.defaultBlockState(), 3);
            helper.assertTrue(Look.blocked(rig.level, rig.bad, Walking.INSTANCE, rig.body),
                "the obsolete requested goal is really inside native BLOCKED terrain");
            rig.roaming.wrongEdge(rig.who, Walking.INSTANCE, rig.body, rig.from, rig.bad);
            boolean reportedProposal = false;
            for (int step = 0; step < 8; step++) {
                long now = rig.now() + step;
                rig.roaming.advance(List.of(rig.roamer), List.of(), List.of(), now);
                Faring answer = rig.roaming.answered(ways -> ways.journey(rig.who, rig.from, Set.of(rig.bad), Urgency.WALKING),
                    List.of(rig.roamer), List.of(), List.of(), now);
                // A graph may propose a native final approach to an exact requested goal. That
                // proposal is not terrain evidence and must not turn into an intermediate edge.
                helper.assertTrue(!usesAsIntermediate(answer, rig.bad),
                    compact("an old buried goal cannot regrow a positive intermediate edge: " + describe(answer)));
                helper.assertTrue(!Look.at(rig.level, rig.from.realm(), rig.roamer, rig.from.cell().asLong(),
                    Set.of(rig.bad.cell()), rig.bad.cell(), false).orElseThrow().swept().reached(),
                    "native navigation still refuses to reach the exact filled goal behind an optimistic final proposal");
                if (!reportedProposal && answer instanceof Faring.Yes) {
                    System.out.println("WAYPOINT_OLD_BAD_PROPOSAL " + describe(answer));
                    reportedProposal = true;
                }
                rig.verifyNativeRoute(rig.from, rig.goal, rig.answer(rig.from, rig.goal, now));
                rig.verifyNativeRoute(rig.other, rig.goal, rig.answer(rig.other, rig.goal, now));
            }
        }
        helper.succeed();
    }

    private static boolean uses(Faring route, WorldPos waypoint) {
        return route instanceof Faring.Yes(Journey journey)
            && journey.legs().stream().anyMatch(leg -> leg.to().contains(waypoint));
    }

    private static boolean usesAsIntermediate(Faring route, WorldPos waypoint) {
        return route instanceof Faring.Yes(Journey journey)
            && journey.legs().stream().anyMatch(leg -> leg.from().filter(waypoint::equals).isPresent()
                || leg.passing() && leg.to().contains(waypoint));
    }

    private static String describe(Faring route) {
        if (!(route instanceof Faring.Yes(Journey journey))) return route.getClass().getSimpleName();
        StringBuilder out = new StringBuilder("Yes ");
        for (var leg : journey.legs()) {
            out.append(leg.passing() ? "via " : "final ");
            out.append(leg.from().map(at -> at.cell().toShortString()).orElse("native"));
            out.append(" -> ");
            for (WorldPos to : leg.to()) out.append(to.cell().toShortString()).append(';');
            out.append(' ');
            if (out.length() > 550) break;
        }
        return compact(out.toString());
    }

    private static String compact(String message) {
        return message.length() <= 690 ? message : message.substring(0, 687) + "...";
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }

    @SuppressWarnings("unchecked")
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final ResidentEntity body;
        final Resident who;
        final Roamer roamer;
        final Sort sort;
        final WorldPos from, other, bad, goal;
        final Roaming roaming;
        final Waypoints net = new Waypoints();
        final Waypoints.Net original;
        final Map<Object, Roaming> graphs;
        final Object track;
        Trip trip;
        boolean closed;

        Fixture(GameTestHelper helper) throws ReflectiveOperationException {
            this.helper = helper; level = helper.getLevel();
            for (int x = 0; x < 24; x++) for (int z = 0; z < 24; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 9, z)), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 10; y <= 14; y++) level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState(), 3);
            }
            from = at(4, 10, 12); other = at(4, 10, 16); bad = at(12, 10, 12); goal = at(20, 10, 12);
            body = PersonBody.RESIDENT.get().create(level);
            body.setPos(Vec3.atBottomCenterOf(from.cell())); body.setOnGround(true); body.setNoAi(false);
            Walking.INSTANCE.fit(body); level.addFreshEntity(body);
            who = body.resident(); roamer = new Roamer(who.kind(), Walking.INSTANCE, body);
            sort = new Sort(Walking.INSTANCE.id(), Bulk.of(body));
            roaming = new Roaming(level);
            // A test-owned graph track lets the real Trip feedback use ColonyWays without an idle
            // colony controller replacing the fixture's actual navigation intent.
            Class<?> type = Class.forName(ColonyWays.class.getName() + "$Track");
            var constructor = type.getDeclaredConstructor(MinecraftServer.class, ResourceKey.class, UUID.class);
            constructor.setAccessible(true); track = constructor.newInstance(level.getServer(), level.dimension(), UUID.randomUUID());
            graphs = (Map<Object, Roaming>) field(ColonyWays.class, "GRAPHS").get(null); graphs.put(track, roaming);
            ((Map<Tract, Waypoints>) field(Roaming.class, "nets").get(roaming)).put(new Tract(sort, from.realm()), net);
            witness(from, bad); witness(other, bad); witness(bad, goal);
            int a = net.pin(from.cell().asLong(), now()), b = net.pin(other.cell().asLong(), now());
            int middle = net.pin(bad.cell().asLong(), now()), end = net.pin(goal.cell().asLong(), now());
            net.link(a, middle, 20, now()); net.link(middle, a, 20, now());
            net.link(b, middle, 20, now()); net.link(middle, b, 20, now());
            net.link(middle, end, 20, now()); net.link(end, middle, 20, now());
            net.remember(bad.cell().north().asLong(), middle, now());
            original = net.freeze(now()); publish();
            helper.assertTrue(uses(query(from, goal), bad) && uses(query(other, goal), bad),
                "the same server first publishes two genuinely traversable incoming routes through the original point");
        }

        long now() { return level.getGameTime(); }
        WorldPos at(int x, int y, int z) { return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z))); }
        void witness(WorldPos start, WorldPos end) {
            helper.assertTrue(Look.at(level, start.realm(), roamer, start.cell().asLong(), Set.of(end.cell()), end.cell(), false)
                .orElseThrow().swept().reached(), "the real native evaluator witnesses the initial or reopened path");
        }
        void publish() throws ReflectiveOperationException {
            Method publish = Roaming.class.getDeclaredMethod("publish", Map.class, List.class, List.class, long.class, boolean.class);
            publish.setAccessible(true); publish.invoke(roaming, Map.of(who.kind(), sort), List.of(roamer), List.of(), now(), true);
        }
        Faring query(WorldPos start, WorldPos end) { return roaming.ways().journey(who, start, Set.of(end), Urgency.WALKING); }
        Faring answer(WorldPos start, WorldPos end) {
            return answer(start, end, now());
        }
        Faring answer(WorldPos start, WorldPos end, long now) {
            Faring answer = query(start, end);
            for (int step = 0; answer instanceof Faring.Later && step < 16; step++) {
                answer = roaming.answered(ways -> ways.journey(who, start, Set.of(end), Urgency.WALKING),
                    List.of(roamer), List.of(), List.of(), now);
            }
            return answer;
        }
        void verifyNativeRoute(WorldPos start, WorldPos end, Faring route) {
            helper.assertTrue(route instanceof Faring.Yes,
                compact("old goal requests must preserve a route to the real valid goal: " + describe(route)));
            Journey journey = ((Faring.Yes) route).by();
            WorldPos previous = start;
            for (var leg : journey.legs()) {
                helper.assertTrue(leg.hop().isEmpty() && leg.to().size() == 1,
                    "this walking-only fixture must retain an explicit singleton native leg");
                WorldPos origin = leg.from().orElse(previous);
                WorldPos destination = leg.to().iterator().next();
                helper.assertTrue(origin.equals(previous), "the recovered native legs form one continuous journey");
                helper.assertTrue(!origin.equals(bad) && !destination.equals(bad),
                    compact("a buried requested goal cannot poison another goal's route: " + describe(route)));
                helper.assertTrue(Look.at(level, origin.realm(), roamer, origin.cell().asLong(),
                    Set.of(destination.cell()), destination.cell(), false).orElseThrow().swept().reached(),
                    compact("each recovered graph leg must have a real native path: " + describe(route)));
                previous = destination;
            }
            helper.assertTrue(previous.equals(end), "the recovered native route still ends at the requested valid goal");
        }
        void verifyRemovedAndRelearned() throws ReflectiveOperationException {
            helper.assertTrue(net.at(bad.cell().asLong()) < 0 && net.reaching(bad.cell().north().asLong()) < 0,
                "definite native failure drops the exact buried point and its held path cells");
            helper.assertTrue(original.reaching(bad.cell()) >= 0 && original.reaching(bad.cell().north()) >= 0,
                "the old published snapshot remains immutable while the replacement is learned");
            publish();
            Faring first = answer(from, goal), second = answer(other, goal);
            helper.assertTrue(first instanceof Faring.Yes && second instanceof Faring.Yes && !uses(first, bad) && !uses(second, bad),
                "both incoming sources obtain new actual routes around the same filled point instead of alternating bad links");
        }
        @Override public void close() {
            if (closed) return; closed = true;
            if (trip != null) trip.release(level, body);
            graphs.remove(track); body.discard();
        }
    }
}
