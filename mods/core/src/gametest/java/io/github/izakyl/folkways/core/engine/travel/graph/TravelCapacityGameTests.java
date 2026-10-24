package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TravelCapacityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TravelCapacityGameTests.class);
    }

    @GameTest(template = "empty")
    public static void overlappingStoreysHaveSeparateFootprints(GameTestHelper helper) {
        TravelExtent extent = new TravelExtent();
        extent.roll(0);
        for (int y : new int[] {-59, -49, -39}) {
            extent.covers(new BlockPos(-16, y, -5));
            extent.covers(new BlockPos(15, y, 8));
        }
        helper.assertTrue(extent.layers() == 3 && extent.ground() == 3L * 32 * 14,
            "overlapping balconies need the area of all three floors");
        TravelExtent tower = new TravelExtent();
        tower.covers(new BlockPos(0, -60, 0));
        tower.covers(new BlockPos(0, 200, 0));
        helper.assertTrue(tower.layers() == 2 && tower.ground() == 2,
            "unoccupied air between destinations must not allocate extra storeys");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void abandonedStoreysLeaveTheBudget(GameTestHelper helper) {
        TravelExtent extent = new TravelExtent();
        extent.roll(0);
        extent.covers(new BlockPos(0, 1, 0));
        extent.covers(new BlockPos(9, 1, 9));
        extent.covers(new BlockPos(0, 17, 0));
        extent.wants(new BlockPos(0, 17, 0));
        extent.roll(TravelExtent.WINDOW_TICKS);
        extent.covers(new BlockPos(0, 1, 0));
        extent.covers(new BlockPos(9, 1, 9));
        helper.assertTrue(extent.layers() == 2 && extent.wanted() == 1,
            "a recently visited floor remains available during one quiet window");
        extent.roll(TravelExtent.WINDOW_TICKS * 2);
        helper.assertTrue(extent.layers() == 1 && extent.ground() == 100 && extent.wanted() == 0,
            "an abandoned floor and its requests expire without raising the flat-map budget");
        extent.roll(TravelExtent.WINDOW_TICKS * 4);
        helper.assertTrue(extent.layers() == 0 && extent.ground() == 0,
            "a long gap expires both windows");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void threeStoreysKeepTheirWalkedConnections(GameTestHelper helper) {
        Waypoints layered = new Waypoints();
        Waypoints flat = new Waypoints();
        layered.room(3L * 32 * 14, 12, 3);
        flat.room(32L * 14, 12, 1);
        for (int floor = 0; floor < 3; floor++) {
            int previous = -1;
            for (int x = 0; x < 400; x++) {
                long cell = BlockPos.asLong(x, floor * 10 + 1, 0);
                int point = layered.admit(cell, floor * 400 + x);
                flat.admit(cell, floor * 400 + x);
                if (previous >= 0) {
                    layered.link(previous, point, 1, floor * 400 + x);
                    layered.link(point, previous, 1, floor * 400 + x);
                }
                previous = point;
            }
        }
        layered.trim(1200);
        helper.assertTrue(flat.trim(1200) && flat.freeze(1200).cells().length < 512,
            "a flat map retains the existing compact budget");
        for (int floor = 0; floor < 3; floor++) {
            int first = layered.at(BlockPos.asLong(0, floor * 10 + 1, 0));
            int last = layered.at(BlockPos.asLong(399, floor * 10 + 1, 0));
            helper.assertTrue(first >= 0 && last >= 0 && layered.joins(first, last, 1200)
                && layered.joins(last, first, 1200),
                "newer work on other floors must not erase this floor's entire walking route");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void coldPinnedConnectionsSurviveTrimming(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        long platform = BlockPos.asLong(0, 21, 0);
        long station = BlockPos.asLong(12, 21, 0);
        int a = net.pin(platform, 0), b = net.pin(station, 0);
        net.link(a, b, 20, 0);
        net.link(b, a, 20, 0);
        var before = net.freeze(0);
        for (int i = 0; i < 700; i++) {
            net.admit(BlockPos.asLong(i, 1, 10), i + 1);
        }
        helper.assertTrue(net.trim(701), "ground-floor exploration causes actual capacity pressure");
        a = net.at(platform);
        b = net.at(station);
        helper.assertTrue(a >= 0 && b >= 0 && net.joins(a, b, 701) && net.joins(b, a, 701),
            "a cold platform and station retain their witnessed connection after eviction");
        helper.assertTrue(before.cells().length == 2 && before.joined(0, 1),
            "trimming must leave already published journeys immutable");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void excessivePinsStillRespectTheHardCeiling(GameTestHelper helper) {
        Waypoints net = new Waypoints();
        net.room(Long.MAX_VALUE / 8, Integer.MAX_VALUE, Integer.MAX_VALUE);
        for (int i = 0; i <= 65_536; i++) {
            net.pin(BlockPos.asLong(i, 1, 0), i);
        }
        helper.assertTrue(net.trim(65_537) && net.freeze(65_537).cells().length == 65_536,
            "even an excessive number of pinned sites cannot exceed the global memory ceiling");
        helper.assertTrue(net.at(BlockPos.asLong(0, 1, 0)) < 0
            && net.at(BlockPos.asLong(65_536, 1, 0)) >= 0,
            "the hard ceiling falls back to recency between pins");
        helper.succeed();
    }
}
