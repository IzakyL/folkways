package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.plugins.person.walk.Walking;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
public final class TravelRetryGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TravelRetryGameTests.class);
    }

    @GameTest(template = "empty")
    public static void multipleGoalsRespectDirectionAndSuspension(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        int start = net.pin(new BlockPos(0, 1, 0).asLong(), 0);
        int incoming = net.pin(new BlockPos(12, 1, 0).asLong(), 0);
        int reachable = net.pin(new BlockPos(24, 1, 0).asLong(), 0);
        int apart = net.pin(new BlockPos(36, 1, 0).asLong(), 0);
        net.link(incoming, start, 20, 0);
        net.link(start, reachable, 20, 0);
        helper.assertTrue(!net.joinsAny(start, IntArrayList.of(-1, apart, incoming), 0),
            "unknown, disconnected and reverse-only goals are not reachable");
        helper.assertTrue(net.joinsAny(start, IntArrayList.of(incoming, reachable), 0),
            "a later reachable goal must succeed despite an unreachable first goal");
        net.wrongAt(net.cellOf(reachable), 1);
        helper.assertTrue(!net.joinsAny(start, IntArrayList.of(incoming, reachable), 1),
            "weak connectivity must not bypass a suspended edge");
        net.link(start, reachable, 20, 2);
        helper.assertTrue(net.joinsAny(start, IntArrayList.of(reachable, reachable), 2),
            "a witnessed edge becomes reachable again, including duplicate goals");
        helper.assertTrue(net.joinsAny(start, IntArrayList.of(start), 2)
            && !net.joinsAny(start, new IntArrayList(), 2)
            && !net.joinsAny(-1, IntArrayList.of(start), 2),
            "same-cell, empty and unknown-start queries retain their semantics");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void promotingAPathCellPreservesItsRoute(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        BlockPos a = new BlockPos(0, 1, 0);
        BlockPos b = new BlockPos(12, 1, 0);
        BlockPos middle = new BlockPos(6, 1, 0);
        int one = net.pin(a.asLong(), 0);
        int two = net.pin(b.asLong(), 0);
        net.link(one, two, 20, 0);
        net.link(two, one, 20, 0);
        net.remember(middle.asLong(), two, 0);
        Waypoints.Net before = net.freeze(0);

        int promoted = net.pin(middle.asLong(), 1);
        helper.assertTrue(net.joins(promoted, one, 1) && net.joins(one, promoted, 1),
            "promoting a known cell must retain both directions of its old route");
        helper.assertTrue(before.reaching(middle) == two,
            "promotion must not mutate an already published snapshot");
        helper.assertTrue(net.freeze(1).reaching(middle) == promoted,
            "the new snapshot must address the promoted waypoint");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void crossingPathsJoinAtTheirSharedCell(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        BlockPos crossing = new BlockPos(6, 1, 0);
        int west = net.pin(new BlockPos(0, 1, 0).asLong(), 0);
        int east = net.pin(new BlockPos(12, 1, 0).asLong(), 0);
        int north = net.pin(new BlockPos(6, 1, -6).asLong(), 0);
        int south = net.pin(new BlockPos(6, 1, 6).asLong(), 0);
        net.link(west, east, 20, 0);
        net.link(east, west, 20, 0);
        net.link(north, south, 20, 0);
        net.link(south, north, 20, 0);
        net.remember(crossing.asLong(), east, 0);
        helper.assertTrue(!net.joins(west, north, 0),
            "nearby paths need a witnessed intersection before they can join");
        net.remember(crossing.asLong(), south, 1);
        helper.assertTrue(net.joins(west, north, 1) && net.joins(north, west, 1),
            "overlapping path attachments must connect both routes");

        int third = net.pin(new BlockPos(9, 1, 3).asLong(), 2);
        net.remember(crossing.asLong(), third, 2);
        helper.assertTrue(net.joins(third, west, 2) && net.joins(north, third, 2),
            "a path crossing an existing waypoint must also join it");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void walkingFailureSuspendsRoutesUntilTheyAreWitnessedAgain(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        BlockPos a = new BlockPos(0, 1, 0);
        BlockPos b = new BlockPos(12, 1, 0);
        BlockPos c = new BlockPos(24, 1, 0);
        int one = net.pin(a.asLong(), 0);
        int two = net.pin(b.asLong(), 0);
        int three = net.pin(c.asLong(), 0);
        net.link(one, two, 20, 0);
        net.link(two, one, 20, 0);
        net.link(two, three, 20, 0);
        net.link(three, two, 20, 0);
        net.remember(a.north().asLong(), one, 0);
        net.seal(one, Bounds.around(0, 1, 0));
        var before = net.freeze(0);
        net.wrongAt(a.asLong(), 1);
        var after = net.freeze(1);
        helper.assertTrue(before.joined(one, three), "published route was connected");
        helper.assertTrue(!after.joined(one, two) && after.joined(two, three),
            "actual failure must suspend incident routes and retain unrelated routes");
        helper.assertTrue(!after.shutBetween(a, c), "failure must reopen old denials");
        net.link(one, two, 20, 2);
        net.link(two, one, 20, 2);
        helper.assertTrue(net.joins(one, three, 2) && net.joins(three, one, 2),
            "a freshly witnessed route must restore connectivity before backoff expires");
        net.wrongAt(a.north().asLong(), 3);
        helper.assertTrue(net.reaching(a.north().asLong()) < 0 && net.joins(one, three, 3),
            "failure at an attached cell must discard that attachment without clearing the graph");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "long_travel", timeoutTicks = 100)
    public static void meetingSearchFrontsFinishThroughTheGraph(GameTestHelper helper) {
        for (int x = 0; x <= 98; x++) {
            for (int z = 0; z <= 2; z++) {
                helper.setBlock(x, 1, z, Blocks.STONE);
                helper.setBlock(x, 2, z, Blocks.AIR);
                helper.setBlock(x, 3, z, Blocks.AIR);
            }
        }
        Mob body = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        ResourceLocation kind = ResourceLocation.fromNamespaceAndPath("folkways", "resident");
        WorldPos from = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)));
        WorldPos to = from.at(helper.absolutePos(new BlockPos(97, 2, 1)));
        Waypoints net = new Waypoints();
        Quest quest = new Quest(helper.getLevel(), net, new Roamer(kind, Walking.INSTANCE, body),
            Ask.of(kind, from, Set.of(to)).orElseThrow(), 0);
        helper.assertTrue(quest.step(0) == Quest.State.GOING,
            "one local search cannot span the whole corridor");
        Quest.State result = quest.step(1);
        helper.assertTrue(net.joins(net.reaching(from.cell().asLong()),
            net.reaching(to.cell().asLong()), 1), "the two search fronts must meet in the graph");
        helper.assertTrue(result == Quest.State.FOUND,
            "a connected route must finish immediately instead of searching again: " + result);
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void expiredDenialReopensAndWaitsToBeAskedAgain(GameTestHelper helper)
            throws ReflectiveOperationException {
        Roaming roaming = new Roaming(helper.getLevel());
        ResourceLocation kind = ResourceLocation.fromNamespaceAndPath("folkways", "resident");
        Sort sort = new Sort(kind, new Bulk(1, 2));
        WorldPos from = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos to = from.at(from.cell().offset(20, 0, 0));
        Ask ask = Ask.of(kind, from, Set.of(to)).orElseThrow();
        Waypoints net = new Waypoints();
        int start = net.pin(from.cell().asLong(), 0);
        net.seal(start, Bounds.around(from.cell().getX(), from.cell().getY(), from.cell().getZ()));
        Waypoints.Net before = net.freeze(0);
        helper.assertTrue(before.shutBetween(from.cell(), to.cell()), "the initial result is denied");

        var netsField = Roaming.class.getDeclaredField("nets");
        netsField.setAccessible(true);
        ((Map<Tract, Waypoints>) netsField.get(roaming)).put(new Tract(sort, from.realm()), net);
        var shelve = Roaming.class.getDeclaredMethod("shelve", Ask.class, long.class);
        shelve.setAccessible(true);
        shelve.invoke(roaming, ask, 0L);
        var pendingField = Roaming.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        Collection<Ask> pending = (Collection<Ask>) pendingField.get(roaming);
        var reconsider = Roaming.class.getDeclaredMethod("reconsider", Map.class, long.class);
        reconsider.setAccessible(true);
        var receive = Roaming.class.getDeclaredMethod("receive", Ask.class, long.class);
        receive.setAccessible(true);
        reconsider.invoke(roaming, Map.of(kind, sort), 1199L);
        receive.invoke(roaming, ask, 1199L);
        helper.assertTrue(pending.isEmpty() && net.freeze(1199).shutBetween(from.cell(), to.cell()),
            "a shelved request is not searched again before its time");
        reconsider.invoke(roaming, Map.of(kind, sort), 1200L);
        helper.assertTrue(!net.freeze(1200).shutBetween(from.cell(), to.cell()),
            "expired denial must stop rejecting the route");
        helper.assertTrue(pending.isEmpty() && roaming.answered(),
            "expiry does not search for anyone; it tells the planner to ask again");
        receive.invoke(roaming, ask, 1201L);
        receive.invoke(roaming, ask, 1202L);
        helper.assertTrue(pending.size() == 1 && pending.contains(ask), "asked again, it is queued once");
        helper.assertTrue(before.shutBetween(from.cell(), to.cell()), "published snapshots stay immutable");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void answeringWorksAWayOutOrDeniesItWithoutLater(GameTestHelper helper) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 2; z++) {
                helper.setBlock(x, 1, z, Blocks.STONE);
            }
        }
        BlockPos shut = new BlockPos(4, 2, 6);
        for (BlockPos wall : BlockPos.betweenClosed(shut.offset(-1, -1, -1), shut.offset(1, 1, 1))) {
            if (!wall.equals(shut)) {
                helper.setBlock(wall, Blocks.STONE);
            }
        }
        Mob body = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        ResourceLocation kind = ResourceLocation.fromNamespaceAndPath("folkways", "resident");
        List<Roamer> roamers = List.of(new Roamer(kind, Walking.INSTANCE, body));
        Resident who = new Resident(body.getUUID(), kind);
        WorldPos from = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)));
        WorldPos open = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(7, 2, 1)));
        WorldPos walled = WorldPos.of(helper.getLevel(), helper.absolutePos(shut));
        Roaming roaming = new Roaming(helper.getLevel());
        long now = helper.getLevel().getGameTime();

        helper.assertTrue(roaming.ways().journey(who, from, Set.of(open)) instanceof Faring.Later,
            "unexplored ways cannot be known without working them out");
        Faring reached = roaming.answered(ways -> ways.journey(who, from, Set.of(open)), roamers,
            List.of(), now);
        helper.assertTrue(reached instanceof Faring.Yes, "an open floor must be worked out to a yes, not " + reached);
        Faring denied = roaming.answered(ways -> ways.journey(who, from, Set.of(walled)), roamers,
            List.of(), now);
        helper.assertTrue(denied instanceof Faring.No, "a walled-in cell must be answered no, not " + denied);
        helper.assertTrue(roaming.answered(ways -> ways.journey(new Resident(UUID.randomUUID(),
                ResourceLocation.fromNamespaceAndPath("folkways", "nobody")), from, Set.of(open)),
            roamers, List.of(), now) instanceof Faring.No,
            "a kind nobody here can walk for is a no");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void droppingAWaypointForgetsOnlyItsOwnAttachments(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        BlockPos a = new BlockPos(0, 1, 0);
        BlockPos b = new BlockPos(12, 1, 0);
        BlockPos beside = new BlockPos(11, 1, 2);
        BlockPos kept = new BlockPos(1, 1, 2);
        int one = net.admit(a.asLong(), 0);
        int two = net.admit(b.asLong(), 0);
        net.link(one, two, 20, 0);
        net.link(two, one, 20, 0);
        net.remember(beside.asLong(), two, 0);
        net.remember(kept.asLong(), one, 0);
        Waypoints.Net before = net.freeze(0);

        net.drop(two);
        Waypoints.Net after = net.freeze(1);
        helper.assertTrue(net.reaching(beside.asLong()) < 0 && net.at(b.asLong()) < 0,
            "dropping a waypoint must forget the cells it held");
        helper.assertTrue(net.reaching(kept.asLong()) == one,
            "another waypoint's attachments must survive");
        helper.assertTrue(after.reaching(beside) < 0 && after.reaching(kept) == one,
            "the next snapshot must show the same attachments");
        helper.assertTrue(before.reaching(beside) == two && before.reaching(b) == two,
            "a published snapshot must keep addressing the dropped waypoint");
        helper.assertTrue(net.linksFrom(one).stream().noneMatch(link -> link.to == two),
            "the route into a dropped waypoint must go with it");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void straighteningRehomesAttachmentsOfTheRemovedWaypoint(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        BlockPos a = new BlockPos(0, 1, 0);
        BlockPos middle = new BlockPos(12, 1, 0);
        BlockPos b = new BlockPos(24, 1, 0);
        BlockPos held = new BlockPos(10, 1, 1);
        int one = net.admit(a.asLong(), 0);
        int centre = net.admit(middle.asLong(), 0);
        int two = net.admit(b.asLong(), 0);
        int near = Waypoints.Net.ticks(a, middle);
        net.link(one, centre, near, 0);
        net.link(centre, one, near, 0);
        net.link(centre, two, near, 0);
        net.link(two, centre, near, 0);
        net.remember(held.asLong(), centre, 0);
        Waypoints.Net before = net.freeze(0);

        helper.assertTrue(net.straighten(1) == 1, "a straight three-point chain merges its middle");
        helper.assertTrue(net.at(middle.asLong()) < 0 && net.joins(one, two, 1) && net.joins(two, one, 1),
            "the merged route must keep both directions without the middle waypoint");
        helper.assertTrue(net.reaching(held.asLong()) == one && net.reaching(middle.asLong()) == one,
            "the removed waypoint's cells must re-home to the nearer survivor");
        helper.assertTrue(before.reaching(held) == centre && before.joined(one, two),
            "straightening must not reach into an already published snapshot");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void componentStatisticsExcludeDeletedSlots(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        helper.assertTrue(net.size() == 0 && net.freeze(0).apart() == 0,
            "an empty graph has no live points or components");
        int one = net.admit(new BlockPos(0, 1, 0).asLong(), 0);
        int middle = net.admit(new BlockPos(12, 1, 0).asLong(), 0);
        int two = net.admit(new BlockPos(24, 1, 0).asLong(), 0);
        int isolated = net.pin(new BlockPos(48, 1, 0).asLong(), 0);
        int cost = Waypoints.Net.ticks(BlockPos.of(net.cellOf(one)), BlockPos.of(net.cellOf(middle)));
        net.link(one, middle, cost, 0);
        net.link(middle, one, cost, 0);
        net.link(middle, two, cost, 0);
        net.link(two, middle, cost, 0);
        Waypoints.Net before = net.freeze(0);
        helper.assertTrue(net.straighten(1) == 1, "the middle is removed");
        Waypoints.Net after = net.freeze(1);
        helper.assertTrue(net.size() == 3 && after.cells().length == 4 && after.apart() == 2,
            "stable slots retain the tombstone, but statistics count only live points and components");
        helper.assertTrue(after.spellPieces(4).equals("[2@24,1,0 | 1@48,1,0]"),
            "component descriptions exclude the deleted singleton: " + after.spellPieces(4));
        helper.assertTrue(before.apart() == 2 && before.byCell().size() == 4,
            "old snapshots keep their own membership");
        net.wrongAt(net.cellOf(one), 2);
        helper.assertTrue(net.freeze(2).apart() == 3,
            "suspended edges still split live components");
        net.drop(isolated);
        net.drop(one);
        net.drop(two);
        helper.assertTrue(net.size() == 0 && net.freeze(3).apart() == 0
                && net.freeze(3).spellPieces(4).equals("[]"),
            "a graph with only deleted slots is empty in diagnostics");
        int again = net.admit(new BlockPos(0, 1, 0).asLong(), 4);
        helper.assertTrue(again > isolated && net.size() == 1 && net.freeze(4).apart() == 1,
            "re-admitting a coordinate must not count its former identifier");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void attachmentsStayBoundedAndSnapshotsStayApart(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        BlockPos anchor = new BlockPos(0, 1, 0);
        int one = net.admit(anchor.asLong(), 0);
        BlockPos oldest = new BlockPos(1, 1, 0);
        net.remember(oldest.asLong(), one, 0);
        Waypoints.Net before = net.freeze(0);
        BlockPos newest = oldest;
        for (int at = 1; at <= Waypoints.SPACING * 512; at++) {
            newest = new BlockPos(at % 2048 + 2, 1, at / 2048);
            net.remember(newest.asLong(), one, 0);
        }
        helper.assertTrue(net.reaching(oldest.asLong()) < 0 && net.reaching(newest.asLong()) == one,
            "the least recently reached cells give way to the newest ones");
        helper.assertTrue(before.reaching(oldest) == one,
            "eviction must not reach into an already published snapshot");

        Waypoints.Net full = net.freeze(1);
        net.drop(one);
        helper.assertTrue(net.reaching(newest.asLong()) < 0 && net.freeze(2).reaching(newest) < 0,
            "dropping the holder must forget every cell it still held");
        helper.assertTrue(full.reaching(newest) == one,
            "the snapshot taken before the drop must stay as it was published");
        helper.succeed();
    }
}
