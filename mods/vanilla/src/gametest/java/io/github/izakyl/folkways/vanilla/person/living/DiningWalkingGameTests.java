package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Keepouts;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.person.walk.Walking;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class DiningWalkingGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) {
        event.register(DiningWalkingGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 160)
    public static void aResidentWalksAcrossAnOrdinaryTopSlabFloor(GameTestHelper helper) {
        BlockPos start = slabFloor(helper, 9, 7).offset(1, 0, 3);
        BlockPos end = start.east(6);
        BlockPos chair = start.below().west(2);
        helper.getLevel().setBlockAndUpdate(chair, Blocks.OAK_STAIRS.defaultBlockState());
        helper.assertTrue(DiningTables.chair(helper.getLevel(), chair), "a stair touches the floor's edge");
        helper.assertFalse(DiningTables.table(helper.getLevel(), start.below()),
            "top slabs are never dining tables");
        for (BlockPos feet : BlockPos.betweenClosed(start, end)) {
            helper.assertFalse(Keepouts.forbidden(helper.getLevel(), feet), "ordinary top slabs are not furniture");
            helper.assertTrue(Footing.withFeetAt(helper.getLevel(), feet),
                "ordinary top slabs provide walking footing");
        }
        ResidentEntity body = resident(helper, start);
        Vec3 target = Vec3.atBottomCenterOf(end);
        helper.startSequence().thenExecuteAfter(2, () -> walkTo(helper, body, target))
            .thenWaitUntil(() -> helper.assertTrue(body.position().distanceToSqr(target) < 0.5D,
                "resident crosses the top-slab floor; " + describe(body, target)))
            .thenExecute(body::discard).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void topSlabsRemainWalkableInsideDiningAreas(GameTestHelper helper) {
        BlockPos table = room(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(table.east(),
            Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        Colony colony = Colonies.mint(level.getServer());
        try {
            var front = ColonyFront.of(colony);
            helper.assertTrue(Footing.withFeetAt(level, table.above()), "an unmarked slab is ordinary footing");
            var zone = front.addZone(level, LivingContent.DINING, Set.of(table),
                ColonySettings.empty()).orElseThrow();
            helper.assertFalse(DiningTables.table(level, table), "marking a slab does not make it a table");
            helper.assertFalse(Keepouts.forbidden(level, table.above()), "marked slabs remain ordinary footing");
            helper.assertTrue(Footing.withFeetAt(level, table.above()), "a slab inside the area stays walkable");
            helper.assertTrue(Footing.withFeetAt(level, table.east().above()),
                "a connected slab outside the area stays walkable");
            front.erase(zone.id());
            helper.assertTrue(Footing.withFeetAt(level, table.above()), "erasing the area preserves footing");
            zone = front.addZone(level, LivingContent.DINING, Set.of(table.below()),
                ColonySettings.empty()).orElseThrow();
            helper.assertFalse(DiningTables.table(level, table), "marking the floor does not make its slab a table");
            helper.assertFalse(Keepouts.forbidden(level, table.above()), "floor outlines do not forbid slabs");
            helper.assertTrue(Footing.withFeetAt(level, table.above()), "a slab above the area stays walkable");
            front.erase(zone.id());
            helper.assertTrue(Footing.withFeetAt(level, table.above()), "erasing the floor area preserves footing");
        } finally { colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void routesGoAroundTheTableInsteadOfClimbingAcrossIt(GameTestHelper helper) {
        try (var fixture = new TableFixture(helper)) {
            BlockPos table = fixture.table;
            helper.assertTrue(Keepouts.forbidden(helper.getLevel(), table.above()),
                "a declared table inside a dining area is furniture");
            ResidentEntity body = resident(helper, table.west(3));
            try {
                var path = body.getNavigation().createPath(table.east(3), 0);
                helper.assertTrue(path != null && path.canReach(), "a route remains around the dining table");
                for (int i = 0; i < path.getNodeCount(); i++) {
                    var node = path.getNode(i);
                    helper.assertFalse(Keepouts.forbidden(helper.getLevel(), node.x, node.y, node.z),
                        "a normal route never climbs onto dining furniture");
                }
            } finally { body.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void workFootingsStayBesideTheTableAndStillReachItsFood(GameTestHelper helper) {
        try (var fixture = new TableFixture(helper)) {
            BlockPos table = fixture.table;
            var feet = Reach.workableCells(helper.getLevel(), table.above());
            helper.assertFalse(feet.contains(table.above()), "tabletop cannot be selected as a work stance");
            helper.assertTrue(feet.contains(table.north()),
                "table food can still be delivered and eaten from beside it");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 160)
    public static void aResidentInTheMiddleOfALargeTableCanStillWalkOffItsEdge(GameTestHelper helper) {
        var fixture = new TableFixture(helper);
        BlockPos table = fixture.table;
        for (BlockPos at : BlockPos.betweenClosed(table.offset(-1, 0, -1), table.offset(1, 0, 1))) {
            helper.getLevel().setBlockAndUpdate(at, Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
        }
        helper.assertTrue(Keepouts.forbidden(helper.getLevel(), table.above()),
            "the middle of a table inside a dining area is furniture without chairs");
        ResidentEntity body = resident(helper, table.above());
        Vec3 target = Vec3.atBottomCenterOf(table.east(3));
        helper.startSequence().thenExecuteAfter(2, () -> walkTo(helper, body, target))
            .thenWaitUntil(() -> helper.assertTrue(body.position().distanceToSqr(target) < 0.5D,
                "resident walks off the large tabletop without teleporting"))
            .thenExecute(body::discard).thenExecute(fixture::close).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void registeredTableAdaptersAlsoKeepTheirTopsOutOfWalkingAndWork(GameTestHelper helper) {
        DiningGameTests.plainTable();
        BlockPos table = room(helper);
        helper.assertFalse(Keepouts.forbidden(helper.getLevel(), table.above()),
            "an unmarked plain slab is not furniture");
        helper.getLevel().setBlockAndUpdate(table, Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
        helper.assertTrue(DiningTables.table(helper.getLevel(), table), "integration adapter identifies its furniture");
        helper.assertTrue(Keepouts.forbidden(helper.getLevel(), table.above()), "adapter tabletop is forbidden");
        helper.assertFalse(Footing.withFeetAt(helper.getLevel(), table.above()), "declared tables are not standable");
        helper.assertFalse(Reach.workableCells(helper.getLevel(), table).contains(table.above()),
            "integration tabletop is absent from work stances");
        helper.succeed();
    }

    private static BlockPos room(GameTestHelper helper) {
        DiningTables.installKeepout();
        BlockPos table = helper.absolutePos(new BlockPos(5, 1, 4));
        for (BlockPos at : BlockPos.betweenClosed(table.offset(-4, -1, -3), table.offset(4, 3, 3))) {
            helper.getLevel().setBlockAndUpdate(at,
                (at.getY() == table.getY() - 1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        }
        helper.getLevel().setBlockAndUpdate(table,
            Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        return table;
    }

    private static BlockPos slabFloor(GameTestHelper helper, int width, int depth) {
        DiningTables.installKeepout();
        BlockPos start = helper.absolutePos(new BlockPos(1, 1, 1));
        for (BlockPos at : BlockPos.betweenClosed(start.below(), start.offset(width - 1, 3, depth - 1))) {
            helper.getLevel().setBlockAndUpdate(at, at.getY() < start.getY()
                ? Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
                : Blocks.AIR.defaultBlockState());
        }
        return start;
    }

    private static void walkTo(GameTestHelper helper, ResidentEntity body, Vec3 target) {
        var path = body.getNavigation().createPath(BlockPos.containing(target), 0);
        helper.assertTrue(path != null && path.canReach() && body.getNavigation().moveTo(path, 1.0D),
            "resident on furniture can plan a way down to the exact floor cell; " + describe(body, target));
    }

    private static String describe(ResidentEntity body, Vec3 target) {
        var path = body.getNavigation().getPath();
        var control = body.getMoveControl();
        return "feet=" + body.position() + ", target=" + target + ", ground=" + body.onGround()
            + ", path=" + (path == null ? "none" : path.getNextNodeIndex() + "/" + path.getNodeCount()
                + " end=" + path.getEndNode())
            + ", done=" + body.getNavigation().isDone() + ", moving=" + control.hasWanted()
            + ", controlTarget=" + control.getWantedX() + "," + control.getWantedY() + "," + control.getWantedZ();
    }

    private static ResidentEntity resident(GameTestHelper helper, BlockPos feet) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(feet));
        body.setOnGround(true);
        helper.getLevel().addFreshEntity(body);
        Walking.INSTANCE.fit(body);
        return body;
    }

    private static final class TableFixture implements AutoCloseable {
        final BlockPos table;
        final Colony colony;

        TableFixture(GameTestHelper helper) {
            DiningGameTests.plainTable();
            table = room(helper);
            helper.getLevel().setBlockAndUpdate(table, Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
            colony = Colonies.mint(helper.getLevel().getServer());
            var cells = new HashSet<BlockPos>();
            for (BlockPos at : BlockPos.betweenClosed(table.offset(-1, -1, -1), table.offset(1, -1, 1))) {
                cells.add(at.immutable());
            }
            ColonyFront.of(colony).addZone(helper.getLevel(), LivingContent.DINING, cells,
                ColonySettings.empty()).orElseThrow();
        }

        public void close() { colony.raze(); }
    }
}
