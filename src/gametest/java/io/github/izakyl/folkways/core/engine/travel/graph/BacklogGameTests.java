package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import io.github.izakyl.folkways.plugins.person.walk.Walking;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BacklogGameTests {

    private static final ResourceLocation KIND = ResourceLocation.fromNamespaceAndPath("folkways", "resident");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BacklogGameTests.class);
    }

    @GameTest(template = "empty")
    public static void representativesSurviveDeletionAndAttachmentEviction(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        int a = net.admit(new BlockPos(0, 1, 0).asLong(), 0);
        int middle = net.admit(new BlockPos(12, 1, 0).asLong(), 0);
        int b = net.admit(new BlockPos(24, 1, 0).asLong(), 0);
        int cost = Waypoints.Net.ticks(BlockPos.of(net.cellOf(a)), BlockPos.of(net.cellOf(middle)));
        net.link(a, middle, cost, 0);
        net.link(middle, a, cost, 0);
        net.link(middle, b, cost, 0);
        net.link(b, middle, cost, 0);
        Waypoints.Net before = net.freeze(0);
        helper.assertTrue(net.straighten(1) == 1, "the old union root is simplified away");
        for (int z = 1; z <= 2049; z++) {
            net.remember(new BlockPos(0, 1, z).asLong(), a, 2);
        }
        Waypoints.Net after = net.freeze(2);
        helper.assertTrue(after.reaching(new BlockPos(12, 1, 0)) < 0, "old root attachment was evicted");
        int representative = after.reaching(after.pieceCell(b));
        helper.assertTrue(representative >= 0 && net.joins(b, representative, 2)
            && net.joins(representative, b, 2), "normalization uses a live, mutually reachable point");
        helper.assertTrue(before.reaching(before.pieceCell(b)) >= 0 && before.cells().length == 3,
            "publishing new representatives leaves old snapshots intact");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void representativesRespectDirectionAndResumeExpiredEdges(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        int a = net.pin(new BlockPos(0, 1, 0).asLong(), 0);
        int b = net.pin(new BlockPos(1, 1, 0).asLong(), 0);
        int c = net.pin(new BlockPos(2, 1, 0).asLong(), 0);
        int d = net.pin(new BlockPos(3, 1, 0).asLong(), 0);
        net.link(a, b, 1, 0);
        net.link(b, c, 1, 0);
        net.link(c, a, 1, 0);
        net.link(c, d, 1, 0);
        Waypoints.Net before = net.freeze(0);
        helper.assertTrue(before.mutual()[a] == before.mutual()[c]
            && before.mutual()[c] != before.mutual()[d], "one-way exit is outside the mutual group");
        net.wrongAt(net.cellOf(b), 10);
        Waypoints.Net paused = net.freeze(10);
        helper.assertTrue(paused.mutual()[a] != paused.mutual()[c]
            && paused.pieceCell(d).equals(BlockPos.of(net.cellOf(d))),
            "paused or reverse-only paths cannot normalize together");
        helper.assertTrue(!net.changed(1209) && net.changed(1210), "edge expiry requires a fresh published snapshot");
        helper.assertTrue(net.freeze(1210).mutual()[a] == before.mutual()[c], "expired suspension restores the cycle");
        helper.assertTrue(paused.mutual()[a] != paused.mutual()[c], "expiry leaves the old snapshot unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pendingServesUrgencyFirstAndKeysByRequester(GameTestHelper helper) {
        Pending queue = new Pending();
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        Ask planned = ask(helper, one, 1, 10, Urgency.PLANNING);
        Ask walking = ask(helper, two, 2, 10, Urgency.WALKING);
        Ask background = ask(helper, null, 3, 10, Urgency.BACKGROUND);
        helper.assertTrue(queue.offer(background, 0) && queue.offer(planned, 0) && queue.offer(walking, 0),
            "different requesters are different requests");
        Ask moved = ask(helper, one, 5, 10, Urgency.PLANNING);
        helper.assertTrue(!queue.offer(moved, 1) && queue.size() == 3,
            "the same requester asking from somewhere else is the same request");
        helper.assertTrue(queue.pollFirst().equals(walking), "a resident waiting on its feet goes first");
        Ask polled = queue.pollFirst();
        helper.assertTrue(polled.equals(moved) && polled.from() == moved.from(),
            "a re-asked request keeps its place and takes the latest origin");
        helper.assertTrue(queue.pollFirst().equals(background) && queue.pollFirst() == null,
            "background work waits for the rest");
        queue.offer(planned, 0);
        queue.offer(walking, 0);
        queue.offer(ask(helper, two, 2, 10, Urgency.WALKING), 50);
        List<Ask> gone = queue.expire(100, ask -> ask.urgency().band() == Urgency.Band.WALKING ? 60 : 1200);
        helper.assertTrue(gone.isEmpty() && queue.size() == 2, "asking again keeps a request alive");
        gone = queue.expire(200, ask -> ask.urgency().band() == Urgency.Band.WALKING ? 60 : 1200);
        helper.assertTrue(gone.size() == 1 && gone.getFirst().urgency().band() == Urgency.Band.WALKING
            && queue.size() == 1, "a request nobody repeats expires on its own clock");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void snapshotsAnswerOnTheirOwnTermsUntilFreezingAgain(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        Ask ask = ask(helper, 1, 2);
        int a = net.pin(ask.from(), 0), b = net.pin(ask.goals().getLong(0), 0);
        net.link(a, b, 1, 0);
        helper.assertTrue(!net.freeze(0).answers(ask) && net.joins(a, b, 0),
            "one-way routes remain for full checking, not a mutual answer");
        net.link(b, a, 1, 0);
        Waypoints.Net known = net.freeze(0);
        helper.assertTrue(known.answers(ask), "mutual connectivity answers the ask");
        net.wrongAt(ask.from(), 2);
        helper.assertTrue(known.answers(ask), "a published snapshot is stale, not revised, after a failure");
        helper.assertTrue(!net.freeze(2).answers(ask), "the next snapshot reflects the failure");
        net.link(a, b, 1, 3);
        net.link(b, a, 1, 3);
        Waypoints.Net restored = net.freeze(3);
        net.drop(b);
        net.pin(ask.goals().getLong(0), 4);
        helper.assertTrue(restored.answers(ask), "a snapshot keeps its own numbering after the graph changes");
        helper.assertTrue(!net.freeze(4).answers(ask), "a re-admitted end is unlinked in the next snapshot");
        helper.succeed();
    }

    @GameTest(template = "empty")
    @SuppressWarnings("unchecked")
    public static void knownWaysAreAnsweredWithoutSearching(GameTestHelper helper) throws Exception {
        Mob body = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        Roamer roamer = new Roamer(KIND, Walking.INSTANCE, body);
        Sort sort = new Sort(Walking.INSTANCE.id(), Bulk.of(body));
        Roaming roaming = new Roaming(helper.getLevel());
        Waypoints net = new Waypoints();
        Ask first = ask(helper, 1, 30);
        int a = net.pin(first.from(), 0), b = net.pin(first.goals().getLong(0), 0);
        net.link(a, b, 1, 0);
        net.link(b, a, 1, 0);
        Pending queue = (Pending) field(roaming, "pending");
        for (int x = 31; x < 131; x++) {
            Ask known = ask(helper, UUID.randomUUID(), x, 30, Urgency.PLANNING);
            net.remember(known.from(), a, 0);
            queue.offer(known, 1);
        }
        ((Map<Tract, Waypoints>) field(roaming, "nets")).put(new Tract(sort, first.realm()), net);
        Method work = Roaming.class.getDeclaredMethod("work", Map.class, List.class, long.class, Slice.class);
        work.setAccessible(true);
        work.invoke(roaming, Map.of(KIND, sort), List.of(roamer), 1L, (Slice) spent -> true);
        helper.assertTrue(queue.size() == 99 && (long) field(roaming, "sweptRequests") == 1,
            "a spent budget still serves one request, and only one");
        work.invoke(roaming, Map.of(KIND, sort), List.of(roamer), 1L, Slice.UNTIL_ANSWERED);
        helper.assertTrue(queue.isEmpty() && (long) field(roaming, "sweptRequests") == 100
                && (long) field(roaming, "startedRequests") == 0 && field(roaming, "quest") == null,
            "requests the graph already answers never start a search");
        helper.assertTrue(roaming.answered(), "answering tells the planner to ask again");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nearbyCellsSnapOntoTheGraphUnlessWalkingThereFailed(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        BlockPos at = new BlockPos(10, 1, 10);
        int point = net.pin(at.asLong(), 0);
        helper.assertTrue(net.near(at.east(2).asLong(), 0) == point && net.near(at.east(2).above().asLong(), 0) == point,
            "a cell two blocks off or a step up snaps onto the waypoint");
        helper.assertTrue(net.near(at.east(3).asLong(), 0) < 0, "further away is off the graph");
        helper.assertTrue(net.freeze(0).near(at.north(2).asLong()) == point, "snapshots snap the same way");
        helper.assertTrue(net.wrongEdge(at.east(2).asLong(), at.asLong(), 5), "walking from a snapped cell can fail");
        helper.assertTrue(net.near(at.east(2).asLong(), 6) < 0 && net.freeze(6).near(at.east(2).asLong()) < 0,
            "after failing, that cell stops snapping");
        helper.assertTrue(net.freeze(6).near(at.south(2).asLong()) == point, "other cells still snap");
        net.remember(at.east(2).asLong(), point, 7);
        helper.assertTrue(net.near(at.east(2).asLong(), 8) == point, "a walked trail restores it");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void walkingAnEdgeTeachesItsCostAndFailingStrikesOnlyThatEdge(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        long a = new BlockPos(0, 1, 0).asLong(), b = new BlockPos(12, 1, 0).asLong(), c = new BlockPos(24, 1, 0).asLong();
        int one = net.pin(a, 0), two = net.pin(b, 0), three = net.pin(c, 0);
        for (int[] edge : new int[][] {{one, two}, {two, one}, {two, three}, {three, two}}) {
            net.link(edge[0], edge[1], 40, 0);
        }
        net.walked(a, b, 120, 1);
        helper.assertTrue(net.linksFrom(one).getFirst().ticks == 60, "a walked edge moves a quarter of the way to what it took");
        helper.assertTrue(net.wrongEdge(b, c, 2), "a failed edge between waypoints is struck");
        helper.assertTrue(!net.joins(two, three, 3) && net.joins(three, two, 3) && net.joins(one, two, 3),
            "only the edge that failed, in the direction it failed, is suspended");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "backlog_exploration")
    @SuppressWarnings("unchecked")
    public static void explorationGetsATurnWhileAQuestAndBacklogRemain(GameTestHelper helper) throws Exception {
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                helper.setBlock(x, 1, z, Blocks.STONE);
                helper.setBlock(x, 2, z, Blocks.AIR);
                helper.setBlock(x, 3, z, Blocks.AIR);
            }
        }
        Mob body = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(12, 2, 12));
        Roamer roamer = new Roamer(KIND, Walking.INSTANCE, body);
        Sort sort = new Sort(Walking.INSTANCE.id(), Bulk.of(body));
        Roaming roaming = new Roaming(helper.getLevel());
        WorldPos feet = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(12, 2, 12)));
        Ask far = Ask.of(KIND, feet, Set.of(feet.at(feet.cell().offset(200, 0, 0)))).orElseThrow();
        Waypoints net = new Waypoints();
        net.pin(feet.cell().asLong(), 0);
        ((Map<Tract, Waypoints>) field(roaming, "nets")).put(new Tract(sort, feet.realm()), net);
        ((Pending) field(roaming, "pending")).add(ask(helper, 999, 1000));
        Quest active = new Quest(helper.getLevel(), net, roamer, far, 0);
        Field quest = Roaming.class.getDeclaredField("quest");
        quest.setAccessible(true);
        quest.set(roaming, active);
        ((Deque<Trod>) field(roaming, "walked")).add(
            new Trod(KIND, feet.realm(), LongArrayList.of(feet.cell().asLong())));
        Method advance = Roaming.class.getDeclaredMethod("advance",
            List.class, List.class, long.class, Slice.class, boolean.class);
        advance.setAccessible(true);
        advance.invoke(roaming, List.of(roamer), List.of(), 100L, (Slice) spent -> true, true);
        helper.assertTrue(field(roaming, "quest") == active && !(boolean) field(active, "opened"),
            "reserved exploration replaces search when the time budget is consumed");
        helper.assertTrue(net.reaching(feet.cell().east().asLong()) >= 0,
            "neighbor coverage grows despite the active quest and non-empty backlog");
        helper.assertTrue((long) field(roaming, "nextRamble") == 120L, "busy exploration is rate limited");
        helper.assertTrue(((Pending) field(roaming, "pending")).size() == 1, "unanswered work remains queued");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void strongComponentsMatchIndependentReachability(GameTestHelper helper) {
        Random random = new Random(47017);
        for (int trial = 0; trial < 20; trial++) {
            int count = 20;
            boolean[][] reach = new boolean[count][count];
            int[] head = new int[count + 1];
            it.unimi.dsi.fastutil.ints.IntArrayList edges = new it.unimi.dsi.fastutil.ints.IntArrayList();
            long[] cells = new long[count];
            for (int a = 0; a < count; a++) {
                cells[a] = count - a;
                head[a] = edges.size();
                reach[a][a] = true;
                for (int b = 0; b < count; b++) {
                    if (random.nextInt(10) == 0) {
                        edges.add(b);
                        reach[a][b] = true;
                    }
                }
            }
            head[count] = edges.size();
            for (int via = 0; via < count; via++) {
                for (int a = 0; a < count; a++) {
                    for (int b = 0; b < count; b++) {
                        reach[a][b] |= reach[a][via] && reach[via][b];
                    }
                }
            }
            int[] group = StrongComponents.of(cells, head, edges.toIntArray());
            for (int a = 0; a < count; a++) {
                for (int b = 0; b < count; b++) {
                    helper.assertTrue((group[a] == group[b]) == (reach[a][b] && reach[b][a]),
                        "mutual groups must agree with directed transitive closure");
                }
            }
        }
        int count = 10000;
        long[] cells = new long[count];
        int[] head = new int[count + 1], to = new int[count];
        for (int at = 0; at < count; at++) {
            cells[at] = at;
            head[at] = at;
            to[at] = (at + 1) % count;
        }
        head[count] = count;
        int[] group = StrongComponents.of(cells, head, to);
        helper.assertTrue(group[count - 1] == 0, "a deep directed cycle does not use the Java call stack");
        helper.succeed();
    }

    private static Ask ask(GameTestHelper helper, int from, int to) {
        WorldPos start = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(from, 2, 0)));
        return Ask.of(KIND, start, Set.of(start.at(helper.absolutePos(new BlockPos(to, 2, 0))))).orElseThrow();
    }

    private static Ask ask(GameTestHelper helper, UUID requester, int from, int to, Urgency urgency) {
        WorldPos start = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(from, 2, 0)));
        return Ask.of(KIND, requester, start, Set.of(start.at(helper.absolutePos(new BlockPos(to, 2, 0)))), urgency)
            .orElseThrow();
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
