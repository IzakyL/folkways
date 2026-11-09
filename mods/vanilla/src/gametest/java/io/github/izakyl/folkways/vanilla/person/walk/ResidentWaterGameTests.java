package io.github.izakyl.folkways.vanilla.person.walk;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.graph.Roamer;
import io.github.izakyl.folkways.core.travel.internal.graph.Roaming;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class ResidentWaterGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ResidentWaterGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void theColonyPlansAndTheResidentCompletesTheSameShallowWaterExit(GameTestHelper helper) {
        leaveWater(helper, 1);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void theColonyPlansAndTheResidentCompletesTheSameTwoDeepWaterExit(GameTestHelper helper) {
        leaveWater(helper, 2);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void theColonyPlansFromDeepWaterWhileItsSizingResidentIsDryOnTheBank(GameTestHelper helper) {
        Rig rig = new Rig(helper, 2);
        rig.body.moveTo(Vec3.atBottomCenterOf(rig.bank));
        helper.startSequence().thenExecuteAfter(10, () -> {
            helper.assertTrue(!rig.body.isInWater() && rig.body.onGround(), "the sizing resident stands on dry land");
            Faring route = route(rig.level, rig.body, rig.submergedStart, rig.goals());
            helper.assertTrue(route instanceof Faring.Yes,
                "the explicit underwater start is independent of the sizing resident's position and water state: " + route);
            rig.body.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 450)
    public static void aResidentUnderAFloodedFloorFindsAirBeforeDrowning(GameTestHelper helper) {
        Rig rig = new Rig(helper, 2);
        rig.roof();
        int[] firstDamage = {-1};
        helper.onEachTick(() -> {
            if (firstDamage[0] < 0 && rig.body.getHealth() < rig.body.getMaxHealth()) {
                firstDamage[0] = rig.body.tickCount;
            }
        });
        helper.startSequence().thenExecuteAfter(380, () -> {
            helper.assertTrue(firstDamage[0] < 0, "resident drowned at entity tick " + firstDamage[0]
                + ", air=" + rig.body.getAirSupply() + ", " + rig.describe());
            helper.assertTrue(!rig.body.isUnderWater() && rig.body.getAirSupply() == rig.body.getMaxAirSupply(),
                "the resident reaches air and recovers its breath: " + rig.describe());
            rig.body.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void aResidentDoesNotDrownFollowingAPathThroughEnclosedWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 1, 0));
        for (int x = 0; x <= 22; x++) {
            for (int z = 0; z <= 10; z++) {
                level.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 1; y <= 3; y++) {
                    level.setBlock(base.offset(x, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        for (int x = 1; x <= 20; x++) {
            for (int z = 2; z <= 8; z++) {
                boolean tunnel = z % 2 == 0 || x == 20 && (z == 3 || z == 7) || x == 2 && z == 5;
                boolean dryEnd = x == 1 && (z == 2 || z == 8);
                if (tunnel && (x >= 2 || dryEnd)) {
                    for (int y = 1; y <= 2; y++) {
                        level.setBlock(base.offset(x, y, z), (dryEnd ? Blocks.AIR : Blocks.WATER)
                            .defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        ResidentEntity body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(Vec3.atBottomCenterOf(base.offset(1, 1, 2)));
        level.addFreshEntity(body);
        Walking.INSTANCE.fit(body);
        Set<WorldPos> goals = Set.of(WorldPos.of(level, base.offset(1, 1, 8)));
        OnFoot movement = new OnFoot(1.0, WaterLabor.LOST);
        int[] firstDamage = {-1};
        boolean[] entered = {false};
        boolean[] failed = {false};
        helper.startSequence().thenExecuteAfter(5, () -> {
            Faring route = route(level, body, goals);
            helper.assertFalse(route instanceof Faring.Yes, "the colony must not plan the enclosed water maze: " + route);
            movement.setOut(level, body, goals);
        });
        helper.onEachTick(() -> {
            if (body.tickCount >= 5 && !failed[0]) {
                failed[0] = movement.step(level, body, goals) instanceof Going.Failed;
            }
            entered[0] |= body.isUnderWater();
            if (firstDamage[0] < 0 && body.getHealth() < body.getMaxHealth()) {
                firstDamage[0] = body.tickCount;
            }
        });
        helper.startSequence().thenExecuteAfter(520, () -> {
            helper.assertTrue(entered[0] || failed[0], "route neither entered water nor refused: feet=" + body.position());
            helper.assertTrue(firstDamage[0] < 0, "enclosed route drowned at entity tick " + firstDamage[0]
                + ", entered=" + entered[0] + ", refused=" + failed[0] + ", feet=" + body.position());
            movement.release(level, body);
            body.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void separateGraphSweepsCannotJoinAnUnsafeWaterTunnel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 4));
        WaterLabor.tunnel(level, base, 15);
        ResidentEntity body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(Vec3.atBottomCenterOf(base.offset(0, 1, 1)));
        level.addFreshEntity(body);
        Walking.INSTANCE.fit(body);
        Set<WorldPos> goals = Set.of(WorldPos.of(level, base.offset(17, 1, 1)));
        helper.startSequence().thenExecuteAfter(5, () -> {
            helper.assertFalse(route(level, body, goals) instanceof Faring.Yes,
                "the graph refuses a long enclosed water route");
            body.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 600));
            Faring protectedRoute = route(level, body, goals);
            helper.assertTrue(protectedRoute instanceof Faring.Yes,
                "water breathing permits the same graph route: " + protectedRoute);
            body.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 450)
    public static void aResidentCanSurviveAnAttemptedRouteOutFromUnderAFloodedFloor(GameTestHelper helper) {
        Rig rig = new Rig(helper, 2);
        rig.roof();
        OnFoot movement = new OnFoot(1.0, WaterLabor.LOST);
        boolean[] started = {false};
        boolean[] failed = {false};
        boolean[] reached = {false};
        boolean[] route = {false};
        int[] firstDamage = {-1};
        helper.startSequence().thenExecuteAfter(10, () -> {
            movement.setOut(rig.level, rig.body, rig.goals());
            var path = rig.body.getNavigation().getPath();
            route[0] = path != null && path.canReach();
            started[0] = true;
        });
        helper.onEachTick(() -> {
            if (started[0] && !failed[0] && !reached[0]) {
                Going going = movement.step(rig.level, rig.body, rig.goals());
                failed[0] = going instanceof Going.Failed;
                reached[0] = going instanceof Going.Arrived;
            }
            if (firstDamage[0] < 0 && rig.body.getHealth() < rig.body.getMaxHealth()) {
                firstDamage[0] = rig.body.tickCount;
            }
        });
        helper.startSequence().thenExecuteAfter(380, () -> {
            helper.assertTrue(firstDamage[0] < 0, "attempted route drowned at " + firstDamage[0]
                + ", path accepted=" + route[0] + ", failed=" + failed[0] + ", reached=" + reached[0]
                + ", " + rig.describe());
            movement.release(rig.level, rig.body);
            rig.body.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void aColonyWorkerResumesAfterFindingAir(GameTestHelper helper) {
        resumesAfterAir(helper, false);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void aHoldingReturnsToItsPrivateActionAfterFindingAir(GameTestHelper helper) {
        resumesAfterAir(helper, true);
    }

    private static void resumesAfterAir(GameTestHelper helper, boolean delegated) {
        Rig rig = new Rig(helper, 2, false);
        rig.roof();
        rig.body.moveTo(Vec3.atBottomCenterOf(rig.bank));
        var colony = WaterLabor.enlist(helper, rig.body).colony();
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_test", "breathing");
        boolean[] pieceDone = {false};
        boolean[] suspended = {false};
        boolean[] resumed = {false};
        boolean[] dropped = {false};
        int[] acquired = {0};
        NodeSpec pieceSpec = NodeSpec.of(UUID.randomUUID(), owner, WorkSite.at(rig.level, rig.bank),
            Stances.at(rig.level, rig.bank), Workload.Once.of(180)).doing(owner).done();
        Node piece = new Node() {
            public NodeSpec spec() { return pieceSpec; }

            public Outcome commit(ServerLevel level, Worker worker) {
                helper.assertTrue(WorkStances.standingIn(worker.body(), pieceSpec.stances()),
                    "the private action returns to its work cell before committing");
                pieceDone[0] = true;
                return Outcome.done();
            }

            public void released(ServerLevel level, Worker worker, Ending how) {
                dropped[0] |= how instanceof Ending.Dropped;
            }
        };
        Workload load = delegated ? new Workload.Custom() : Workload.Once.of(180);
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner, WorkSite.at(rig.level, rig.bank),
            Stances.WHEREVER, load).doing(owner).done();
        boolean[] committed = {false};
        boolean[] escaped = {false};
        Node node = new Node() {
            public WorkHolding acquire(HoldingContext context) {
                acquired[0]++;
                WorkHolding action = delegated ? context.action(piece) : Node.super.acquire(context);
                return new WorkHolding() {
                    public HoldingStep tick(HoldingContext at) {
                        HoldingStep step = action.tick(at);
                        if (delegated && step instanceof HoldingStep.Done) {
                            committed[0] = true;
                        }
                        return step;
                    }

                    public void requestSoftPreempt(HoldingContext at, SoftPreempt request) {
                        action.requestSoftPreempt(at, request);
                    }

                    public void hardRelease(HoldingContext at, Disposition disposition, PreemptReason reason) {
                        action.hardRelease(at, disposition, reason);
                    }

                    public void suspended(HoldingContext at, PreemptReason reason) {
                        suspended[0] = true;
                        action.suspended(at, reason);
                    }

                    public void resumed(HoldingContext at) {
                        resumed[0] = true;
                        action.resumed(at);
                    }
                };
            }

            public NodeSpec spec() { return spec; }

            public Outcome commit(ServerLevel level, Worker worker) {
                committed[0] = true;
                return Outcome.done();
            }

            public void released(ServerLevel level, Worker worker, Ending how) {
                dropped[0] |= how instanceof Ending.Dropped;
            }
        };
        colony.submit(owner, rig.level.dimension(), Grown.of(node), (id, how) -> { });
        helper.onEachTick(() -> escaped[0] |= WaterLabor.escaping(rig.body));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.body.doing()
                .filter(doing -> doing.what().equals(owner) && doing.toward().isEmpty()).isPresent(),
                "the colony starts the submitted work, rather than an idle urge"))
            .thenExecute(() -> {
                rig.body.moveTo(Vec3.atBottomCenterOf(rig.submergedStart));
                rig.body.setAirSupply(100);
            })
            .thenWaitUntil(() -> helper.assertTrue(escaped[0] && suspended[0],
                "the air emergency suspends the running holding"))
            .thenWaitUntil(() -> helper.assertTrue(committed[0], "the colony resumes and finishes the work"))
            .thenExecute(() -> {
                helper.assertTrue(resumed[0], "the suspended holding is resumed");
                helper.assertValueEqual(acquired[0], 1, "the same holding resumes without reacquiring work");
                helper.assertFalse(dropped[0], "finding air never drops the suspended work");
                helper.assertTrue(!delegated || pieceDone[0], "the delegated private action commits");
                helper.assertTrue(rig.body.getHealth() == rig.body.getMaxHealth(), "the worker survives");
                rig.body.discard();
            }).thenSucceed();
    }

    private static void leaveWater(GameTestHelper helper, int depth) {
        Rig rig = new Rig(helper, depth);
        boolean[] travelling = {false};
        boolean[] arrived = {false};
        boolean[] enteredWater = {false};
        OnFoot movement = new OnFoot(1.0, WaterLabor.LOST);
        helper.onEachTick(() -> {
            enteredWater[0] |= rig.body.isInWater();
            if (travelling[0] && !arrived[0]) {
                Going progress = movement.step(rig.level, rig.body, rig.goals());
                helper.assertFalse(progress instanceof Going.Failed,
                    "real water movement cannot stall before reaching its bank: " + rig.describe());
                arrived[0] = progress instanceof Going.Arrived;
            }
        });
        helper.startSequence().thenExecuteAfter(10, () -> {
            helper.assertTrue(enteredWater[0], "the actual resident first falls into real water");
            Faring route = route(rig.level, rig.body, rig.goals());
            helper.assertTrue(route instanceof Faring.Yes,
                "the actual colony path graph must accept an exit its resident can swim: " + route + "; " + rig.describe());
            movement.setOut(rig.level, rig.body, rig.goals());
            travelling[0] = true;
        }).thenWaitUntil(() -> helper.assertTrue(arrived[0] && !rig.body.isInWater() && rig.body.onGround(),
            "the actual resident reaches and stands on the dry bank: " + rig.describe()))
            .thenExecute(() -> {
                movement.release(rig.level, rig.body);
                helper.assertTrue(rig.body.walkDist > 0.5F, "native physical travel, rather than teleporting, reaches the bank");
                helper.assertTrue(rig.body.getHealth() == rig.body.getMaxHealth(), "the short real swim does not drown the resident");
                rig.body.discard();
            }).thenSucceed();
    }

    private static Faring route(ServerLevel level, ResidentEntity body, Set<WorldPos> goals) {
        return route(level, body, body.blockPosition(), goals);
    }

    private static Faring route(ServerLevel level, ResidentEntity body, BlockPos from, Set<WorldPos> goals) {
        return new Roaming(level).answered(known -> known.journey(body.resident(), WorldPos.of(level, from), goals),
            List.of(new Roamer(PersonBody.ID, Walking.INSTANCE, body)), List.of(), level.getGameTime());
    }

    private static final class Rig {
        final ServerLevel level;
        final ResidentEntity body;
        final BlockPos bank;
        final BlockPos submergedStart;
        Rig(GameTestHelper helper, int depth) {
            this(helper, depth, true);
        }

        Rig(GameTestHelper helper, int depth, boolean enroll) {
            level = helper.getLevel();
            BlockPos base = helper.absolutePos(new BlockPos(0, 1, 0));
            for (BlockPos at : BlockPos.betweenClosed(base, base.offset(9, depth + 4, 4))) {
                level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (BlockPos at : BlockPos.betweenClosed(base, base.offset(9, 0, 4))) {
                level.setBlock(at, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int y = 1; y <= depth; y++) for (int x = 0; x <= 9; x++) for (int z = 0; z <= 4; z++) {
                BlockPos at = base.offset(x, y, z);
                boolean pond = x >= 1 && x <= 5 && z >= 1 && z <= 3;
                level.setBlock(at, (pond ? Blocks.WATER : Blocks.STONE).defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            bank = base.offset(7, depth + 1, 2);
            submergedStart = base.offset(2, 1, 2);
            body = PersonBody.RESIDENT.get().create(level);
            body.moveTo(Vec3.atBottomCenterOf(submergedStart));
            level.addFreshEntity(body);
            Walking.INSTANCE.fit(body);
            if (enroll) {
                WaterLabor.enlist(helper, body);
            }
        }
        // A stone ceiling over the pond, open at one corner.
        void roof() {
            for (int x = -1; x <= 3; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 3 || z != 0) {
                        level.setBlock(submergedStart.above(2).offset(x, 0, z), Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                    }
                }
            }
        }

        Set<WorldPos> goals() { return Set.of(WorldPos.of(level, bank)); }
        String describe() {
            var path = body.getNavigation().getPath();
            return "feet=" + body.position() + ", bank=" + bank + ", water=" + body.isInWater()
                + ", ground=" + body.onGround() + ", nativeFloat=" + body.getNavigation().canFloat()
                + ", graphFloat=" + Walking.INSTANCE.newEvaluator(io.github.izakyl.folkways.core.resident.api.Search.from(body.blockPosition())).canFloat()
                + ", path=" + (path == null ? "none" : path.getNextNodeIndex() + "/" + path.getNodeCount());
        }
    }
}
