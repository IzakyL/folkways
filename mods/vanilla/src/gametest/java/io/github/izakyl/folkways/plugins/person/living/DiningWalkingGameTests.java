package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.walk.Walking;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
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
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiningWalkingGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(DiningWalkingGameTests.class); }

    @GameTest(template = "empty")
    public static void routesGoAroundTheTableInsteadOfClimbingAcrossIt(GameTestHelper helper) {
        BlockPos table = room(helper);
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
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void workFootingsStayBesideTheTableAndStillReachItsFood(GameTestHelper helper) {
        BlockPos table = room(helper);
        var feet = Reach.workableCells(helper.getLevel(), table.above());
        helper.assertFalse(feet.contains(table.above()), "tabletop cannot be selected as a work stance");
        helper.assertTrue(feet.contains(table.north()), "table food can still be delivered and eaten from beside it");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void aResidentPlacedOnATableWalksDownToOrdinaryGround(GameTestHelper helper) {
        BlockPos table = room(helper);
        ResidentEntity body = resident(helper, table.above());
        Vec3 target = Vec3.atBottomCenterOf(table.east(3));
        helper.startSequence().thenExecuteAfter(2, () -> walkTo(helper, body, target))
            .thenWaitUntil(() -> helper.assertTrue(body.position().distanceToSqr(target) < 0.5D,
                "resident actually leaves the table without teleporting; " + describe(body, target)))
            .thenExecute(body::discard).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void aResidentInTheMiddleOfALargeTableCanStillWalkOffItsEdge(GameTestHelper helper) {
        BlockPos table = room(helper);
        for (BlockPos at : BlockPos.betweenClosed(table.offset(-1, 0, -1), table.offset(1, 0, 1))) {
            helper.getLevel().setBlockAndUpdate(at,
                Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        }
        ResidentEntity body = resident(helper, table.above());
        Vec3 target = Vec3.atBottomCenterOf(table.east(3));
        helper.startSequence().thenExecuteAfter(2, () -> walkTo(helper, body, target))
            .thenWaitUntil(() -> helper.assertTrue(body.position().distanceToSqr(target) < 0.5D,
                "resident walks off the large tabletop without teleporting"))
            .thenExecute(body::discard).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void registeredTableAdaptersAlsoKeepTheirTopsOutOfWalkingAndWork(GameTestHelper helper) {
        AdapterFixture.install();
        BlockPos table = room(helper);
        helper.getLevel().setBlockAndUpdate(table, Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
        helper.assertTrue(DiningTables.table(helper.getLevel(), table), "integration adapter identifies its furniture");
        helper.assertTrue(Keepouts.forbidden(helper.getLevel(), table.above()), "adapter tabletop is forbidden");
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
        helper.getLevel().setBlockAndUpdate(table, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        return table;
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

    private static final class AdapterFixture {
        private static boolean installed;
        static synchronized void install() {
            if (installed) return;
            DiningTables.register(ResourceLocation.fromNamespaceAndPath("folkways", "test_walking_table"), new DiningTables.Table() {
                public boolean matches(ServerLevel level, BlockPos at) { return level.getBlockState(at).is(Blocks.CHISELED_QUARTZ_BLOCK); }
                public Container inventory(ServerLevel level, BlockPos at) { return null; }
            });
            installed = true;
        }
    }
}
