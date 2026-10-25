package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
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
public final class ResidentTrafficGameTests {
    private static final RefusalKind LOST = () -> "folkways.refusal.labor.no_way_there";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ResidentTrafficGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void alignedOncomingResidentsPassInAOneWideCorridor(GameTestHelper helper) {
        oncoming(helper, 1, true);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void alignedOncomingResidentsPassInATwoWideCorridor(GameTestHelper helper) {
        oncoming(helper, 2, true);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void keepingRightDoesNotStepOffAOneWideBridge(GameTestHelper helper) {
        oncoming(helper, 1, false);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aFasterFollowerKeepsAGapAndBothResidentsArrive(GameTestHelper helper) {
        BlockPos base = corridor(helper, 1, true);
        Walker leader = new Walker(helper, base.offset(4, 0, 1), base.offset(14, 0, 1), 0.55D);
        Walker follower = new Walker(helper, base.offset(2, 0, 1), base.offset(12, 0, 1), 1.0D);
        int[] yielded = {0};
        helper.onEachTick(() -> {
            leader.tick(false);
            follower.tick(false);
            if (leader.started && !leader.finished && follower.started && !follower.finished) {
                double gap = leader.body.getX() - follower.body.getX() - leader.body.getBbWidth();
                helper.assertTrue(gap >= 0.45D, "the follower keeps space between full bodies: gap=" + gap);
                if (((ResidentMoveControl) follower.body.getMoveControl()).yielding()) { yielded[0]++; }
            }
        });
        helper.startSequence().thenExecuteAfter(5, () -> { leader.start(); follower.start(); })
            .thenWaitUntil(() -> helper.assertTrue(leader.arrived() && follower.arrived(), "both queued walkers arrive"))
            .thenExecute(() -> {
                helper.assertTrue(yielded[0] > 20, "the faster follower actually eases behind the leader");
                leader.close();
                follower.close();
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aLongSlowQueueDoesNotExpireEitherProgressWatchdog(GameTestHelper helper) {
        BlockPos base = corridor(helper, 1, true);
        Walker leader = new Walker(helper, base.offset(4, 0, 1), base.offset(14, 0, 1), 0.08D);
        Walker follower = new Walker(helper, base.offset(2, 0, 1), base.offset(12, 0, 1), 1.0D);
        boolean[] slow = {true};
        int[] yielded = {0};
        double[] queuedFrom = {0};
        helper.onEachTick(() -> {
            // Only the follower owns a real OnFoot leg here; the leader supplies slow native navigation.
            follower.tick(false);
            if (slow[0] && follower.started) {
                helper.assertFalse(follower.body.getNavigation().isDone(),
                    "a deliberate queue retains its native path: " + follower.describe() + "; leader=" + leader.describe()
                        + " leaderDone=" + leader.body.getNavigation().isDone() + " yielded=" + yielded[0]);
                helper.assertFalse(follower.body.getNavigation().isStuck(), "queueing is not native navigation stuckness");
                if (((ResidentMoveControl) follower.body.getMoveControl()).yielding()) { yielded[0]++; }
            }
        });
        helper.startSequence().thenExecuteAfter(5, () -> {
            leader.start();
            follower.start();
        }).thenExecuteAfter(40, () -> queuedFrom[0] = follower.body.getX())
            .thenExecuteAfter(220, () -> {
                helper.assertTrue(yielded[0] > 200, "the queue outlasts both watchdog windows: " + yielded[0]);
                helper.assertTrue(follower.body.getX() - queuedFrom[0] < 0.5D,
                    "the real queued leg stays below OnFoot's progress threshold for over 200 ticks");
                slow[0] = false;
                leader.body.getNavigation().setSpeedModifier(1.0D);
            }).thenWaitUntil(() -> helper.assertTrue(follower.arrived(), "the long queue eventually reaches its destination"))
            .thenExecute(() -> { leader.close(); follower.close(); }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void aLeaderThatStopsPermanentlyCannotHoldAFollowerForever(GameTestHelper helper) {
        BlockPos base = corridor(helper, 1, true);
        Walker leader = new Walker(helper, base.offset(4, 0, 1), base.offset(14, 0, 1), 0.55D);
        Walker follower = new Walker(helper, base.offset(2, 0, 1), base.offset(12, 0, 1), 1.0D);
        Vec3[] stopped = {null};
        int[] ticksStopped = {0};
        helper.onEachTick(() -> {
            if (stopped[0] != null) {
                leader.body.setPos(stopped[0]);
                leader.body.setDeltaMovement(Vec3.ZERO);
                if (++ticksStopped[0] > 30) {
                    helper.assertFalse(((ResidentMoveControl) follower.body.getMoveControl()).yielding(),
                        "a stopped resident with a remaining path is no longer a moving leader");
                }
            }
            follower.tick(true);
        });
        helper.startSequence().thenExecuteAfter(5, () -> { leader.start(); follower.start(); })
            .thenExecuteAfter(30, () -> {
                stopped[0] = leader.body.position();
                leader.body.setNoAi(true);
            }).thenWaitUntil(() -> helper.assertTrue(follower.finished,
                "the follower passes or fails normally instead of waiting forever"))
            .thenExecute(() -> {
                helper.assertTrue(ticksStopped[0] > 30, "the follower encountered the stopped leader");
                leader.close();
                follower.close();
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aConveyanceKeepsItsStandingWalkingPassengerCentred(GameTestHelper helper) {
        BlockPos base = corridor(helper, 1, true);
        Walker passenger = new Walker(helper, base.offset(2, 0, 1), base.offset(13, 0, 1), 1.0D);
        double centre = passenger.body.getZ();
        helper.onEachTick(() -> {
            ((ResidentMoveControl) passenger.body.getMoveControl()).centreForTransport();
            passenger.tick(false);
            helper.assertTrue(Math.abs(passenger.body.getZ() - centre) < 0.05D,
                "a standing conveyance passenger stays on its original steering line: " + passenger.describe()
                    + " lateral=" + (passenger.body.getZ() - centre) + " tick=" + passenger.body.tickCount
                    + " wantedZ=" + passenger.body.getMoveControl().getWantedZ() + " centre=" + centre);
        });
        helper.startSequence().thenExecuteAfter(5, passenger::start)
            .thenWaitUntil(() -> helper.assertTrue(passenger.arrived(), "the centred passenger still walks normally"))
            .thenExecute(passenger::close).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void keepingRightEasesAroundABridgeCorner(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(0, 2, 1));
        for (int x = 0; x <= 13; x++) for (int z = 0; z <= 13; z++) for (int y = -1; y <= 3; y++) {
            boolean floor = y == -1 && (z == 2 && x >= 1 && x <= 10 || x == 10 && z >= 2 && z <= 12);
            helper.getLevel().setBlock(base.offset(x, y, z),
                (floor ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Walker walker = new Walker(helper, base.offset(2, 0, 2), base.offset(10, 0, 11), 1.0D);
        helper.onEachTick(() -> {
            walker.tick(false);
            helper.assertTrue(Math.abs(walker.body.getY() - base.getY()) < 0.05D,
                "the offset stays on the bridge through a right angle: " + walker.describe());
        });
        helper.startSequence().thenExecuteAfter(5, walker::start)
            .thenWaitUntil(() -> helper.assertTrue(walker.arrived(), "the resident walks around the bridge corner"))
            .thenExecute(walker::close).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void onlyResidentPairsUseTheNarrowPushBox(GameTestHelper helper) {
        BlockPos base = corridor(helper, 1, true);
        Walker first = new Walker(helper, base.offset(4, 0, 1), base.offset(12, 0, 1), 1);
        Walker second = new Walker(helper, base.offset(4, 0, 1), base.offset(12, 0, 1), 1);
        second.body.setPos(first.body.position().add(0, 0, 0.38D));
        first.body.push(second.body);
        helper.assertTrue(first.body.getDeltaMovement().equals(Vec3.ZERO)
            && second.body.getDeltaMovement().equals(Vec3.ZERO), "passing residents do not push across their lanes");
        second.body.setPos(first.body.position().add(0, 0, 0.3D));
        first.body.push(second.body);
        helper.assertTrue(first.body.getDeltaMovement().horizontalDistanceSqr() > 0
            && second.body.getDeltaMovement().horizontalDistanceSqr() > 0, "closer resident pairs still push each other");
        var sheep = EntityType.SHEEP.create(helper.getLevel());
        sheep.setPos(first.body.position().add(0, 0, 0.5D));
        helper.getLevel().addFreshEntity(sheep);
        first.body.setDeltaMovement(Vec3.ZERO);
        first.body.push(sheep);
        helper.assertTrue(first.body.getDeltaMovement().horizontalDistanceSqr() > 0
            && sheep.getDeltaMovement().horizontalDistanceSqr() > 0, "resident and animal pushing stays reciprocal");
        first.body.setDeltaMovement(Vec3.ZERO);
        sheep.setDeltaMovement(Vec3.ZERO);
        sheep.push(first.body);
        helper.assertTrue(first.body.getDeltaMovement().horizontalDistanceSqr() > 0
            && sheep.getDeltaMovement().horizontalDistanceSqr() > 0, "an animal still pushes a resident normally");
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(first.body.position().add(0, 0, 0.5D));
        first.body.setDeltaMovement(Vec3.ZERO);
        first.body.push(player);
        helper.assertTrue(first.body.getDeltaMovement().horizontalDistanceSqr() > 0
            && player.getDeltaMovement().horizontalDistanceSqr() > 0, "resident and player pushing stays reciprocal");
        first.body.setDeltaMovement(Vec3.ZERO);
        player.setDeltaMovement(Vec3.ZERO);
        player.push(first.body);
        helper.assertTrue(first.body.getDeltaMovement().horizontalDistanceSqr() > 0
            && player.getDeltaMovement().horizontalDistanceSqr() > 0, "a player still pushes a resident normally");
        helper.assertTrue(Math.abs(first.body.getBoundingBox().getXsize() - 0.6D) < 0.0001D,
            "block collision and route sizing keep the full body width");
        first.close();
        second.close();
        sheep.discard();
        helper.succeed();
    }

    private static void oncoming(GameTestHelper helper, int width, boolean walls) {
        BlockPos base = corridor(helper, width, walls);
        Walker east = new Walker(helper, base.offset(2, 0, 1), base.offset(13, 0, 1), 1.0D);
        Walker west = new Walker(helper, base.offset(13, 0, 1), base.offset(2, 0, 1), 1.0D);
        int[] encounter = {0};
        helper.onEachTick(() -> {
            boolean meeting = east.started && west.started && Math.abs(east.body.getX() - west.body.getX()) < 3;
            if (meeting) { encounter[0]++; }
            east.tick(false);
            west.tick(false);
            if (meeting && !east.finished && !west.finished) {
                helper.assertTrue(east.stalled < 30 && west.stalled < 30,
                    "oncoming walkers keep progressing: east=" + east.describe() + "; west=" + west.describe());
            }
            if (east.started) {
                for (Walker walker : new Walker[] {east, west}) {
                    helper.assertTrue(Math.abs(walker.body.getY() - base.getY()) < 0.05D,
                        "keeping right neither jumps at walls nor falls off the bridge: " + walker.describe());
                    helper.assertTrue(walker.body.getZ() >= base.getZ() + 1.3D - 0.001D
                        && walker.body.getZ() <= base.getZ() + width + 0.7D + 0.001D,
                        "the full body stays inside the corridor: " + walker.describe());
                }
            }
        });
        helper.startSequence().thenExecuteAfter(5, () -> { east.start(); west.start(); })
            .thenWaitUntil(() -> helper.assertTrue(east.arrived() && west.arrived(),
                "both aligned oncoming walkers arrive: east=" + east.describe() + "; west=" + west.describe()))
            .thenExecute(() -> {
                helper.assertTrue(encounter[0] > 0, "the residents actually meet on their shared line");
                helper.assertTrue(east.longest < 30 && west.longest < 30,
                    "the maximum no-progress stretch stays short: " + east.longest + ", " + west.longest);
                east.close();
                west.close();
            }).thenSucceed();
    }

    private static BlockPos corridor(GameTestHelper helper, int width, boolean walls) {
        BlockPos base = helper.absolutePos(new BlockPos(0, 2, 1));
        for (int x = 0; x <= 15; x++) for (int z = 0; z <= width + 1; z++) {
            boolean side = z == 0 || z == width + 1;
            for (int y = -1; y <= 3; y++) {
                boolean solid = y == -1 && (!side || walls) || walls && (side || x == 0 || x == 15);
                helper.getLevel().setBlock(base.offset(x, y, z),
                    (solid ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        return base;
    }

    private static final class Walker {
        final GameTestHelper helper;
        final ResidentEntity body;
        final Set<WorldPos> goals;
        final OnFoot movement;
        final double sign;
        boolean started;
        boolean finished;
        Going going = Going.UNDERWAY;
        double best;
        int stalled;
        int longest;

        Walker(GameTestHelper helper, BlockPos start, BlockPos end, double speed) {
            this.helper = helper;
            body = PersonBody.RESIDENT.get().create(helper.getLevel());
            body.setPos(Vec3.atBottomCenterOf(start));
            body.setOnGround(true);
            helper.getLevel().addFreshEntity(body);
            Walking.INSTANCE.fit(body);
            goals = Set.of(WorldPos.of(helper.getLevel(), end));
            movement = new OnFoot(speed, LOST);
            sign = Math.signum(end.getX() - start.getX());
            best = sign * body.getX();
        }

        void start() {
            movement.setOut(helper.getLevel(), body, goals);
            started = true;
            best = sign * body.getX();
        }

        void tick(boolean mayFail) {
            if (!started || finished) { return; }
            going = movement.step(helper.getLevel(), body, goals);
            helper.assertTrue(mayFail || !(going instanceof Going.Failed), "real OnFoot leg failed: " + describe());
            finished = going instanceof Going.Arrived || going instanceof Going.Failed;
            if (sign * body.getX() > best + 0.05D) {
                best = sign * body.getX();
                stalled = 0;
            } else {
                longest = Math.max(longest, ++stalled);
            }
            if (finished) { movement.release(helper.getLevel(), body); }
        }

        boolean arrived() { return going instanceof Going.Arrived; }
        void close() { movement.release(helper.getLevel(), body); body.discard(); }
        String describe() { return body.position() + " stalled=" + stalled + " going=" + going; }
    }
}
