package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentArrivalGameTests {
    private static final RefusalKind LOST = () -> "folkways.refusal.labor.no_way_there";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentArrivalGameTests.class);
    }

    @GameTest(template = "empty_large")
    public static void ordinaryArrivalKeepsItsExistingStandingTolerance(GameTestHelper helper) {
        BlockPos goal = ground(helper).offset(7, 0, 3);
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(goal).add(-0.8D, 0, 0));
        var goals = Set.of(WorldPos.of(helper.getLevel(), goal));
        helper.assertTrue(new OnFoot(1, LOST).step(helper.getLevel(), body, goals) instanceof Going.Arrived,
            "the original 0.9-block arrival accepts a body 0.8 blocks from center");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void aPartialPathNeverReportsWorkArrival(GameTestHelper helper) {
        BlockPos start = ground(helper).offset(1, 0, 3);
        BlockPos goal = start.east(7);
        var level = helper.getLevel();
        for (BlockPos at : BlockPos.betweenClosed(goal.offset(-1, 0, -1), goal.offset(1, 3, 1))) {
            if (at.getX() != goal.getX() || at.getZ() != goal.getZ()) {
                level.setBlock(at, Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        var body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(start));
        body.setOnGround(true);
        level.addFreshEntity(body);
        var goals = Set.of(WorldPos.of(level, goal));
        var walk = Walking.INSTANCE.along(1, LOST);
        walk.setOut(level, body, goals);
        var path = body.getNavigation().getPath();
        helper.assertTrue(path != null && !path.canReach(), "the sealed goal produces an actual partial path");
        Going[] result = {Going.UNDERWAY};
        helper.onEachTick(() -> {
            if (result[0] instanceof Going.Underway) {
                result[0] = walk.step(level, body, goals);
                helper.assertFalse(result[0] instanceof Going.Arrived, "the partial endpoint is never the work site");
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(result[0] instanceof Going.Failed,
            "an unreachable job fails within the ordinary travel bound"))
            .thenExecute(() -> {
                walk.release(level, body);
                body.discard();
            }).thenSucceed();
    }

    private static BlockPos ground(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(1, 3, 1));
        for (BlockPos at : BlockPos.betweenClosed(base.offset(0, -1, 0), base.offset(12, 4, 7))) {
            helper.getLevel().setBlock(at,
                (at.getY() < base.getY() ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
        }
        return base;
    }
}
