package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.walk.OnFoot;
import io.github.izakyl.folkways.plugins.person.walk.Walking;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SiteTravelGameTests {
    private static final RefusalKind LOST = () -> "folkways.refusal.labor.no_way_there";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SiteTravelGameTests.class); }

    @GameTest(template = "empty")
    public static void distantSiteMovesCanRepeatInTheSameTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos low = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos high = low.above(40);
        floor(level, low);
        floor(level, high);
        ResidentEntity body = body(level, low);
        Runnable reopen = Closures.close(level, BoundingBox.fromCorners(low.offset(-1, -1, -1), high.offset(1, 2, 1)));
        try {
            OnFoot movement = new OnFoot(1, LOST);
            for (BlockPos target : new BlockPos[] {high, low, high}) {
                Set<WorldPos> goals = Set.of(WorldPos.of(level, target));
                movement.setOut(level, body, goals);
                helper.assertTrue(movement.step(level, body, goals) instanceof Going.Arrived,
                    "a 40-block site move completes immediately, even after another move in the same tick");
                helper.assertValueEqual(body.blockPosition(), target, "the body reaches the actual requested standing cell");
                movement.release(level, body);
            }
        } finally { reopen.run(); body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ordinaryAndCrossSiteTravelCannotTeleportToTheDestination(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(1, 2, 2));
        BlockPos goal = start.offset(5, 0, 0);
        floor(level, start);
        floor(level, goal);
        ResidentEntity body = body(level, start);
        helper.assertFalse(new SiteTravel().toward(level, body, Set.of(goal)), "ordinary travel has no teleport permission");
        helper.assertValueEqual(body.blockPosition(), start, "ordinary travel does not move the body");
        Runnable one = Closures.close(level, BoundingBox.fromCorners(start.offset(-1, -1, -1), start.offset(1, 2, 1)));
        Runnable two = Closures.close(level, BoundingBox.fromCorners(goal.offset(-1, -1, -1), goal.offset(1, 2, 1)));
        try {
            helper.assertFalse(SiteTravel.place(level, body, goal), "two different work areas never authorize a direct jump");
            new SiteTravel().toward(level, body, Set.of(goal));
            helper.assertFalse(body.blockPosition().equals(goal), "an outside goal is never used as the landing cell");
            helper.assertTrue(Math.abs(body.getX() - start.getX()) < 2,
                "any departing jump stays within the original work area");
        } finally { one.run(); two.run(); body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unsafeLandingsAndPassengersStayPut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos goal = start.offset(2, 0, 0);
        floor(level, start);
        floor(level, goal);
        ResidentEntity body = body(level, start);
        Runnable reopen = Closures.close(level, BoundingBox.fromCorners(start.offset(-1, -1, -1), goal.offset(1, 3, 1)));
        var vehicle = EntityType.PIG.create(level);
        try {
            level.setBlockAndUpdate(goal.below(), Blocks.AIR.defaultBlockState());
            helper.assertFalse(new SiteTravel().toward(level, body, Set.of(goal)), "an unsupported landing is rejected");
            floor(level, goal);
            level.setBlockAndUpdate(goal.above(), Blocks.STONE.defaultBlockState());
            helper.assertFalse(new SiteTravel().toward(level, body, Set.of(goal)), "a head collision is rejected");
            level.setBlockAndUpdate(goal.above(), Blocks.AIR.defaultBlockState());
            vehicle.moveTo(body.position());
            helper.assertTrue(body.startRiding(vehicle, true), "the resident is mounted for the passenger check");
            helper.assertFalse(new SiteTravel().toward(level, body, Set.of(goal)), "a passenger cannot be teleported");
            helper.assertValueEqual(body.blockPosition(), start, "all rejected moves preserve the starting position");
        } finally { body.stopRiding(); reopen.run(); body.discard(); vehicle.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void leavingTheSiteJumpsOnlyToItsEdgeThenWalksToTheWarehouse(GameTestHelper helper) {
        leaveSite(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void leavingTheSiteCanDetourAlongItsBoundaryWithoutBeingPulledBack(GameTestHelper helper) {
        leaveSite(helper, true);
    }

    private static void leaveSite(GameTestHelper helper, boolean obstructed) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(1, 2, 2));
        BlockPos edge = start.offset(3, 0, 0);
        BlockPos warehouse = start.offset(7, 0, 0);
        for (BlockPos feet : BlockPos.betweenClosed(start.offset(0, 0, -1), warehouse.offset(1, 0, 3))) {
            floor(level, feet);
        }
        if (obstructed) {
            for (BlockPos wall : BlockPos.betweenClosed(edge.offset(1, 0, -1), edge.offset(1, 2, 1))) {
                level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            }
        }
        ResidentEntity body = body(level, start);
        level.addFreshEntity(body);
        Runnable reopen = Closures.close(level, BoundingBox.fromCorners(start.offset(-1, -1, -1), edge.offset(0, 2, 3)));
        Set<WorldPos> goals = Set.of(WorldPos.of(level, warehouse));
        OnFoot movement = new OnFoot(1, LOST);
        movement.setOut(level, body, goals);
        helper.assertTrue(movement.step(level, body, goals) instanceof Going.Underway,
            "leaving the site does not arrive at the distant warehouse immediately");
        helper.assertValueEqual(body.blockPosition(), edge, "the first step lands on the original site's boundary");
        float walked = body.walkDist;
        boolean[] arrived = {false};
        helper.runAfterDelay(190, reopen);
        helper.onEachTick(() -> {
            if (!arrived[0]) {
                Going progress = movement.step(level, body, goals);
                helper.assertFalse(progress instanceof Going.Failed, "the resident keeps walking after reaching the boundary");
                arrived[0] = progress instanceof Going.Arrived;
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(arrived[0], "the warehouse is reached"))
            .thenExecute(() -> {
                helper.assertTrue(body.walkDist - walked > 1, "physical walking covers the outside portion of the journey");
                movement.release(level, body);
                reopen.run();
                body.discard();
            }).thenSucceed();
    }

    private static ResidentEntity body(ServerLevel level, BlockPos feet) {
        ResidentEntity body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(Vec3.atBottomCenterOf(feet));
        Walking.INSTANCE.fit(body);
        return body;
    }

    private static void floor(ServerLevel level, BlockPos feet) {
        level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
    }
}
