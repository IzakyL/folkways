package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.labor.ColonyLabor;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.graph.Roamer;
import io.github.izakyl.folkways.core.engine.travel.graph.Roaming;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
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
public final class ResidentWaterGameTests {
    private static final RefusalKind LOST = () -> "folkways.refusal.labor.no_way_there";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ResidentWaterGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aResidentActuallyWalksOutOfShallowWaterOntoAOneBlockBank(GameTestHelper helper) {
        leaveWater(helper, 1, false);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aResidentActuallySwimsOutOfTwoDeepWaterOntoAOneBlockBank(GameTestHelper helper) {
        leaveWater(helper, 2, false);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theColonyPlansAndTheResidentCompletesTheSameShallowWaterExit(GameTestHelper helper) {
        leaveWater(helper, 1, true);
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void theColonyPlansAndTheResidentCompletesTheSameTwoDeepWaterExit(GameTestHelper helper) {
        leaveWater(helper, 2, true);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void theColonyPlansFromDeepWaterWhileItsSizingResidentIsDryOnTheBank(GameTestHelper helper) {
        Rig rig = new Rig(helper, 2);
        rig.body.moveTo(Vec3.atBottomCenterOf(rig.bank));
        helper.startSequence().thenExecuteAfter(10, () -> {
            helper.assertTrue(!rig.body.isInWater() && rig.body.onGround(), "the sizing resident stands on dry land");
            WorldPos from = WorldPos.of(rig.level, rig.submergedStart);
            Roaming ways = new Roaming(rig.level);
            Faring route = ways.answered(known -> known.journey(rig.body.resident(), from, rig.goals()),
                List.of(new Roamer(PersonBody.ID, Walking.INSTANCE, rig.body)), List.of(),
                rig.level.getGameTime());
            helper.assertTrue(route instanceof Faring.Yes,
                "the explicit underwater start is independent of the sizing resident's position and water state: " + route);
            rig.body.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void aResidentUnderAFloodedFloorFindsAirBeforeDrowning(GameTestHelper helper) {
        Rig rig = new Rig(helper, 2);
        BlockPos roof = rig.submergedStart.above(2);
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 3 || z != 0) {
                    rig.level.setBlock(roof.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
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

    @GameTest(template = "empty_large", timeoutTicks = 600)
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
        OnFoot movement = new OnFoot(1.0, LOST);
        int[] firstDamage = {-1};
        boolean[] entered = {false};
        boolean[] failed = {false};
        helper.startSequence().thenExecuteAfter(5, () -> {
            Roaming ways = new Roaming(level);
            Faring route = ways.answered(known -> known.journey(body.resident(),
                    WorldPos.of(level, body.blockPosition()), goals),
                List.of(new Roamer(PersonBody.ID, Walking.INSTANCE, body)), List.of(), level.getGameTime());
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

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void theColonyDoesNotPlanALongEnclosedWaterTunnel(GameTestHelper helper) {
        enclosedTunnel(helper, 19);
    }

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void separateGraphSweepsCannotJoinAnUnsafeWaterTunnel(GameTestHelper helper) {
        enclosedTunnel(helper, 15);
    }

    private static void enclosedTunnel(GameTestHelper helper, int lastWater) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 4));
        for (int x = 0; x <= lastWater + 2; x++) {
            for (int z = 0; z <= 2; z++) {
                level.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 1; y <= 3; y++) {
                    boolean wall = z != 1 || y == 3;
                    boolean flooded = x >= 2 && x <= lastWater;
                    level.setBlock(base.offset(x, y, z), (wall ? Blocks.STONE
                        : flooded ? Blocks.WATER : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
        ResidentEntity body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(Vec3.atBottomCenterOf(base.offset(0, 1, 1)));
        level.addFreshEntity(body);
        Walking.INSTANCE.fit(body);
        Set<WorldPos> goals = Set.of(WorldPos.of(level, base.offset(lastWater + 2, 1, 1)));
        OnFoot movement = new OnFoot(1.0D, LOST);
        boolean[] accepted = {false};
        boolean[] failed = {false};
        int[] leastAir = {body.getAirSupply()};
        helper.startSequence().thenExecuteAfter(5, () -> {
            Roaming ways = new Roaming(level);
            Faring route = ways.answered(known -> known.journey(body.resident(),
                    WorldPos.of(level, body.blockPosition()), goals),
                List.of(new Roamer(PersonBody.ID, Walking.INSTANCE, body)), List.of(), level.getGameTime());
            accepted[0] = route instanceof Faring.Yes;
            body.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.WATER_BREATHING, 600));
            Roaming protectedWays = new Roaming(level);
            Faring protectedRoute = protectedWays.answered(known -> known.journey(body.resident(),
                    WorldPos.of(level, body.blockPosition()), goals),
                List.of(new Roamer(PersonBody.ID, Walking.INSTANCE, body)), List.of(), level.getGameTime());
            helper.assertTrue(protectedRoute instanceof Faring.Yes,
                "water breathing permits the same graph route: " + protectedRoute);
            body.removeEffect(net.minecraft.world.effect.MobEffects.WATER_BREATHING);
            movement.setOut(level, body, goals);
        });
        helper.onEachTick(() -> {
            leastAir[0] = Math.min(leastAir[0], body.getAirSupply());
            if (body.tickCount >= 5 && !failed[0]) {
                failed[0] = movement.step(level, body, goals) instanceof Going.Failed;
            }
        });
        helper.startSequence().thenExecuteAfter(380, () -> {
            movement.release(level, body);
            body.discard();
            helper.assertFalse(accepted[0], "the graph accepted a long enclosed water route; minimum air="
                + leastAir[0] + ", movement refused=" + failed[0]);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void aResidentCanSurviveAnAttemptedRouteOutFromUnderAFloodedFloor(GameTestHelper helper) {
        Rig rig = new Rig(helper, 2);
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 3 || z != 0) {
                    rig.level.setBlock(rig.submergedStart.above(2).offset(x, 0, z),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        OnFoot movement = new OnFoot(1.0, LOST);
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

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void aColonyWorkerResumesAfterFindingAir(GameTestHelper helper) throws ReflectiveOperationException {
        resumesAfterAir(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void aWithinWorkerReturnsToItsPieceAfterFindingAir(GameTestHelper helper)
            throws ReflectiveOperationException {
        resumesAfterAir(helper, true);
    }

    private static void resumesAfterAir(GameTestHelper helper, boolean within) throws ReflectiveOperationException {
        Rig rig = new Rig(helper, 2, false);
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 3 || z != 0) {
                    rig.level.setBlock(rig.submergedStart.above(2).offset(x, 0, z),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
        rig.body.moveTo(Vec3.atBottomCenterOf(rig.bank));
        var colony = Colonies.mint(rig.level.getServer());
        colony.hold(PersonContent.ID, Held.Entity.of(rig.body)).orElseThrow();
        rig.body.joinColony(colony.id());
        colony.entered(rig.body);
        ColonyData data = ColonyData.find(rig.level.getServer(), colony.id()).orElseThrow();
        ColonyLabor labor = new ColonyLabor(data, rig.level.dimension());
        Class<?> lanes = Class.forName("io.github.izakyl.folkways.core.engine.labor.Lanes");
        var inline = lanes.getDeclaredField("INLINE");
        inline.setAccessible(true);
        Object executor = inline.get(null);
        var run = ColonyLabor.class.getMethod("run", ServerLevel.class, lanes);
        var stop = ColonyLabor.class.getDeclaredMethod("close");
        stop.setAccessible(true);
        boolean[] closed = {false};
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_test", "breathing");
        boolean[] pieceDone = {false};
        boolean[] interrupted = {false};
        NodeSpec pieceSpec = NodeSpec.of(UUID.randomUUID(), owner, WorkSite.at(rig.level, rig.bank),
            Stances.WHEREVER, Workload.Once.of(180)).doing(owner).done();
        Node piece = new Node() {
            public NodeSpec spec() { return pieceSpec; }

            public Outcome commit(ServerLevel level, Worker worker) {
                pieceDone[0] = true;
                return Outcome.done();
            }

            public void released(ServerLevel level, Worker worker, Ending how) {
                interrupted[0] |= how instanceof Ending.Dropped;
            }
        };
        Workload load = within ? (Workload.Within) (level, who) -> pieceDone[0]
            ? Workload.Within.LEAVE : new Workload.Within.Turn.Piece(piece) : Workload.Once.of(180);
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner, WorkSite.at(rig.level, rig.bank),
            Stances.WHEREVER, load).doing(owner).done();
        boolean[] committed = {false};
        boolean[] escaped = {false};
        Node node = new Node() {
            public NodeSpec spec() { return spec; }

            public Outcome commit(ServerLevel level, Worker worker) {
                committed[0] = true;
                return Outcome.done();
            }

            public void released(ServerLevel level, Worker worker, Ending how) {
                interrupted[0] |= !within && how instanceof Ending.Dropped;
            }
        };
        colony.submit(owner, rig.level.dimension(), Grown.of(node), (id, how) -> { });
        helper.onEachTick(() -> {
            if (closed[0]) {
                return;
            }
            ColonyWays.advance(data, rig.level);
            try {
                run.invoke(labor, rig.level, executor);
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException(broken);
            }
            escaped[0] |= WaterLabor.escaping(rig.body);
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.body.doing()
                .filter(doing -> doing.what().equals(owner) && doing.toward().isEmpty()).isPresent(),
                "the colony starts the submitted work, rather than an idle urge"))
            .thenExecute(() -> {
                rig.body.moveTo(Vec3.atBottomCenterOf(rig.submergedStart));
                rig.body.setAirSupply(100);
            })
            .thenWaitUntil(() -> helper.assertTrue(escaped[0] && interrupted[0],
                "the air emergency interrupts the running work"))
            .thenWaitUntil(() -> helper.assertTrue(committed[0], "the colony resumes and finishes the work"))
            .thenExecute(() -> {
                helper.assertTrue(rig.body.getHealth() == rig.body.getMaxHealth(), "the worker survives");
                closed[0] = true;
                try {
                    stop.invoke(labor);
                } catch (ReflectiveOperationException broken) {
                    throw new IllegalStateException(broken);
                }
                rig.body.discard();
                colony.raze();
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void waterBreathingDoesNotInterruptAResidentUnderwater(GameTestHelper helper) {
        Rig rig = new Rig(helper, 2);
        for (int x = -1; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) {
                rig.level.setBlock(rig.submergedStart.above(2).offset(x, 0, z),
                    Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        rig.body.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.WATER_BREATHING, 600));
        helper.startSequence().thenExecuteAfter(380, () -> {
            helper.assertTrue(rig.body.isUnderWater(), "the protected resident remains underwater");
            helper.assertTrue(rig.body.getAirSupply() == rig.body.getMaxAirSupply(),
                "vanilla water breathing preserves air");
            helper.assertFalse(WaterLabor.escaping(rig.body), "water breathing does not trigger an escape");
            helper.assertTrue(rig.body.getHealth() == rig.body.getMaxHealth(), "the resident does not drown");
            rig.body.discard();
        }).thenSucceed();
    }

    private static void leaveWater(GameTestHelper helper, int depth, boolean planned) {
        Rig rig = new Rig(helper, depth);
        boolean[] travelling = {false};
        boolean[] arrived = {false};
        boolean[] enteredWater = {false};
        OnFoot movement = new OnFoot(1.0, LOST);
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
            if (planned) {
                WorldPos from = WorldPos.of(rig.level, rig.body.blockPosition());
                Roaming ways = new Roaming(rig.level);
                Faring route = ways.answered(known -> known.journey(rig.body.resident(), from, rig.goals()),
                    List.of(new Roamer(PersonBody.ID, Walking.INSTANCE, rig.body)), List.of(),
                    rig.level.getGameTime());
                helper.assertTrue(route instanceof Faring.Yes,
                    "the actual colony path graph must accept an exit its resident can swim: " + route + "; " + rig.describe());
            }
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
                WaterLabor.enroll(helper, body);
            }
        }
        Set<WorldPos> goals() { return Set.of(WorldPos.of(level, bank)); }
        String describe() {
            var path = body.getNavigation().getPath();
            return "feet=" + body.position() + ", bank=" + bank + ", water=" + body.isInWater()
                + ", ground=" + body.onGround() + ", nativeFloat=" + body.getNavigation().canFloat()
                + ", graphFloat=" + Walking.INSTANCE.newEvaluator(io.github.izakyl.folkways.core.api.resident.Search.from(body.blockPosition())).canFloat()
                + ", path=" + (path == null ? "none" : path.getNextNodeIndex() + "/" + path.getNodeCount());
        }
    }
}
