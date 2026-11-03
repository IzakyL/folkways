package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
public final class WorkSurfaceGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WorkSurfaceGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void aResidentClimbsAnOrdinaryStairInsteadOfStoppingUnderItsWork(GameTestHelper helper) {
        floor(helper);
        // No building metadata or scaffold: an ordinary platform reached by three solid steps.
        for (int x = 4; x <= 13; x++) {
            int height = Math.min(3, x - 3);
            for (int z = 7; z <= 10; z++) {
                for (int y = 1; y <= height; y++) {
                    stone(helper, x, y, z);
                }
            }
        }
        BlockPos goal = helper.absolutePos(new BlockPos(10, 4, 8));
        SiteRig rig = site(helper, goal);
        Mob body = body(helper, new BlockPos(8, 1, 6));
        Worker worker = worker(body);
        Workload.Within shift = shift(rig);
        Workload.Within.Turn turn = shift.next(helper.getLevel(), worker);
        helper.assertTrue(turn instanceof Workload.Within.Turn.Piece,
            "work starts while the resident is still on the ground");
        BlockPos aim = body.getNavigation().getTargetPos();
        helper.assertTrue(aim != null && aim.getY() >= goal.getY() - 1,
            "the route selects the upper working surface even when ground is already within work reach");
        helper.assertTrue(Commit.run(helper.getLevel(), ((Workload.Within.Turn.Piece) turn).node(), worker)
                instanceof Outcome.Done,
            "taking the longer staircase does not postpone the construction operation");
        helper.onEachTick(() -> shift.tick(helper.getLevel(), worker));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(body.getY() >= goal.getY() - 0.25D,
                "the resident physically climbs the ordinary staircase to the platform"))
            .thenExecute(() -> {
                helper.assertTrue(helper.getLevel().getBlockState(goal).is(Blocks.STONE), "the operation built its block");
                body.discard();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anUnreachableUpperSurfaceKeepsAReachableGroundAlternative(GameTestHelper helper) {
        floor(helper);
        // The upper platform has no connecting steps. A preferred height must not consume every path attempt.
        for (int x = 8; x <= 12; x++) {
            for (int z = 8; z <= 12; z++) {
                stone(helper, x, 3, z);
            }
        }
        BlockPos goal = helper.absolutePos(new BlockPos(10, 4, 10));
        SiteRig rig = site(helper, goal);
        Mob body = body(helper, new BlockPos(10, 1, 7));
        Workload.Within.Turn turn = shift(rig).next(helper.getLevel(), worker(body));
        helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "unreachable upper geometry does not gate work");
        var path = body.getNavigation().getPath();
        helper.assertTrue(path != null && path.canReach(), "the bounded route search retains a fully reachable fallback");
        helper.assertTrue(body.getNavigation().getTargetPos().getY() == helper.absolutePos(new BlockPos(0, 1, 0)).getY(),
            "the fallback is on the connected ground rather than the isolated platform");
        body.discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void inaccessibleUpperWorkGetsAUsefulScaffoldWithoutWaitingForIt(GameTestHelper helper) {
        floor(helper);
        for (int x = 8; x <= 12; x++) {
            for (int z = 8; z <= 12; z++) {
                stone(helper, x, 3, z);
            }
        }
        // Four pending cells on an inaccessible platform, with the next layer visible to the access heuristic.
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> cells = new LinkedHashMap<>();
        for (int z = 9; z <= 10; z++) {
            for (int y = 4; y <= 5; y++) {
                cells.put(helper.absolutePos(new BlockPos(8, y, z)), Blocks.STONE.defaultBlockState());
            }
        }
        SiteRig rig = SiteRig.start(helper.getLevel(), new BuildTarget(
            WorldPos.of(helper.getLevel(), cells.keySet().iterator().next()).realm(), cells));
        helper.assertTrue(rig.site.pieces().stream().noneMatch(piece ->
                piece.work().step().orElseThrow().kind() == BuildJob.Kind.RAISE),
            "the blueprint plan contains no temporary access");
        Mob body = body(helper, new BlockPos(6, 1, 9));
        Worker worker = worker(body);
        Workload.Within shift = shift(rig);
        Workload.Within.Turn turn = shift.next(helper.getLevel(), worker);
        helper.assertTrue(turn instanceof Workload.Within.Turn.Piece,
            "ordinary construction starts before the new access column is built");
        var operation = ((Workload.Within.Turn.Piece) turn).node();
        var raise = rig.site.pieces().stream().filter(piece ->
            piece.work().step().orElseThrow().kind() == BuildJob.Kind.RAISE).findFirst();
        helper.assertTrue(raise.isPresent(), "failed upper access creates a physical scaffold alongside the platform");
        helper.assertTrue(Commit.run(helper.getLevel(), operation, worker) instanceof Outcome.Done,
            "improving access never becomes a prerequisite of the current operation");
        var column = raise.orElseThrow();
        BlockPos top = column.work().step().orElseThrow().after().keySet().stream()
            .max(java.util.Comparator.comparingInt(BlockPos::getY)).orElseThrow().above();
        helper.assertTrue(top.getY() >= helper.absolutePos(new BlockPos(0, 4, 0)).getY(),
            "the column anticipates the next layer rather than stopping below the useful platform height");
        helper.assertTrue(Commit.run(helper.getLevel(), column.node(), worker) instanceof Outcome.Done,
            "the access column can actually be raised");
        rig.end(column.node(), Ending.DONE);
        rig.site.tick(helper.getLevel());
        helper.onEachTick(() -> shift.tick(helper.getLevel(), worker));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(body.getY() >= helper.absolutePos(new BlockPos(0, 4, 0)).getY() - 0.25D,
                "the resident reconsiders the ground fallback and physically reaches the working platform"))
            .thenExecute(body::discard).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 340)
    public static void cleanupBringsAnIdleResidentBackFromAnAdjacentPermanentRoof(GameTestHelper helper) {
        floor(helper);
        BlockPos base = helper.absolutePos(new BlockPos(10, 1, 10));
        List<BlockPos> column = java.util.stream.IntStream.range(0, 7).mapToObj(base::above).toList();
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        column.forEach(cell -> helper.getLevel().setBlock(cell, ScaffoldNode.state(), 3));
        temporary.added(column.stream().map(cell -> WorldPos.of(helper.getLevel(), cell)).toList());
        for (int x = 11; x <= 15; x++) {
            for (int z = 8; z <= 12; z++) {
                stone(helper, x, 7, z);
            }
        }
        Mob resident = body(helper, new BlockPos(13, 8, 10));
        Mob hand = body(helper, new BlockPos(8, 1, 10));
        Worker worker = worker(hand);
        var lower = BuildNode.of(Step.lower(column).job(helper.getLevel(), new BuildTarget(
            WorldPos.of(helper.getLevel(), base).realm(), Map.of(base.east(6), Blocks.STONE.defaultBlockState())),
            Stances.WHEREVER), (ended, how) -> { }, temporary);
        float health = resident.getHealth();
        helper.assertTrue(Commit.run(helper.getLevel(), lower, worker) instanceof Outcome.Failed,
            "cleanup keeps the only access column while a resident is on an adjacent permanent roof");
        resident.getNavigation().moveTo(base.getX() + 0.5D, column.getLast().getY() + 1.0D,
            base.getZ() + 0.5D, 1.0D);
        var retry = BuildNode.of(lower.job(), (ended, how) -> { }, temporary);
        helper.assertTrue(!retry.id().equals(lower.id()) && !resident.getNavigation().isDone(),
            "a subsequent Within attempt has a fresh node while the returner is walking across the roof");
        helper.assertTrue(Commit.run(helper.getLevel(), retry, worker) instanceof Outcome.Failed,
            "a fresh cleanup node still protects the pending returner before it reaches the column");
        boolean[] removed = {false};
        boolean[] enteredColumn = {false};
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (resident.getY() >= base.getY() + 3.0D
                && Math.abs(resident.getX() - (base.getX() + 0.5D)) < 0.65D
                && Math.abs(resident.getZ() - (base.getZ() + 0.5D)) < 0.65D) {
                enteredColumn[0] = true;
            }
            if (!removed[0] && ++ticks[0] % 20 == 0) {
                var nextAttempt = BuildNode.of(lower.job(), (ended, how) -> { }, temporary);
                removed[0] = Commit.run(helper.getLevel(), nextAttempt, worker) instanceof Outcome.Done;
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(removed[0],
                "the roof resident returns through the column before cleanup: y=" + resident.getY()
                    + " pos=" + resident.blockPosition()))
            .thenExecute(() -> {
                helper.assertTrue(enteredColumn[0], "the resident walked to the upper entry before descending");
                helper.assertTrue(resident.getY() <= base.getY() + 1.25D, "cleanup waited until the resident reached ground");
                helper.assertTrue(resident.getHealth() >= health && resident.fallDistance < 2.0F,
                    "the return caused no fall damage or drop off the roof");
                helper.assertTrue(temporary.isEmpty(), "the access column is fully removed after the return");
                resident.discard();
                hand.discard();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void anUnrelatedInaccessibleRoofResidentDoesNotBlockCleanup(GameTestHelper helper) {
        floor(helper);
        BlockPos base = helper.absolutePos(new BlockPos(10, 1, 10));
        List<BlockPos> column = java.util.stream.IntStream.range(0, 7).mapToObj(base::above).toList();
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        column.forEach(cell -> helper.getLevel().setBlock(cell, ScaffoldNode.state(), 3));
        temporary.added(column.stream().map(cell -> WorldPos.of(helper.getLevel(), cell)).toList());
        for (int x = 14; x <= 16; x++) {
            for (int z = 9; z <= 11; z++) {
                stone(helper, x, 7, z);
            }
        }
        Mob resident = body(helper, new BlockPos(15, 8, 10));
        Mob hand = body(helper, new BlockPos(8, 1, 10));
        var lower = BuildNode.of(Step.lower(column).job(helper.getLevel(), new BuildTarget(
            WorldPos.of(helper.getLevel(), base).realm(), Map.of(base.east(7), Blocks.STONE.defaultBlockState())),
            Stances.WHEREVER), (ended, how) -> { }, temporary);
        helper.assertTrue(Commit.run(helper.getLevel(), lower, worker(hand)) instanceof Outcome.Done,
            "a resident on an unrelated roof with no route to the column does not hold cleanup");
        helper.assertTrue(temporary.isEmpty(), "the unused column is removed immediately");
        resident.discard();
        hand.discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 450)
    public static void aBuilderInFloodedWorkDoesNotDrown(GameTestHelper helper) {
        floor(helper);
        for (int x = 5; x <= 11; x++) {
            for (int z = 5; z <= 11; z++) {
                for (int y = 1; y <= 2; y++) {
                    helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)),
                        Blocks.WATER.defaultBlockState(), 3);
                }
                if ((x != 11 || z != 8) && (x != 8 || z != 8)) {
                    stone(helper, x, 3, z);
                }
            }
        }
        Mob body = body(helper, new BlockPos(8, 1, 8));
        Worker worker = worker(body);
        BlockPos roof = helper.absolutePos(new BlockPos(8, 3, 8));
        SiteRig roofing = site(helper, roof);
        helper.assertTrue(roofing.work(helper, roof, worker) instanceof Outcome.Done,
            "the builder places the overhead block");
        helper.assertTrue(helper.getLevel().getBlockState(roof).is(Blocks.STONE), "the roof is now closed above the builder");
        BlockPos goal = helper.absolutePos(new BlockPos(8, 1, 9));
        SiteRig rig = site(helper, goal);
        Workload.Within shift = shift(rig);
        Workload.Within.Turn turn = shift.next(helper.getLevel(), worker);
        helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "the builder starts a real site turn: " + turn);
        int[] firstDamage = {-1};
        helper.onEachTick(() -> {
            shift.tick(helper.getLevel(), worker);
            if (firstDamage[0] < 0 && body.getHealth() < body.getMaxHealth()) {
                firstDamage[0] = body.tickCount;
            }
        });
        helper.startSequence().thenExecuteAfter(380, () -> {
            helper.assertTrue(firstDamage[0] < 0, "builder drowned at entity tick " + firstDamage[0]
                + ", air=" + body.getAirSupply() + ", feet=" + body.position()
                + ", path=" + body.getNavigation().getPath());
            helper.assertFalse(body.isUnderWater(), "the builder reaches breathable air");
            helper.assertTrue(body.getAirSupply() == body.getMaxAirSupply(), "the builder recovers its breath");
            body.discard();
        }).thenSucceed();
    }

    private static SiteRig site(GameTestHelper helper, BlockPos goal) {
        return SiteRig.start(helper.getLevel(), new BuildTarget(WorldPos.of(helper.getLevel(), goal).realm(),
            Map.of(goal, Blocks.STONE.defaultBlockState())));
    }

    private static Mob body(GameTestHelper helper, BlockPos feet) {
        Mob body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(feet)));
        body.setOnGround(true);
        helper.getLevel().addFreshEntity(body);
        return body;
    }

    private static Workload.Within shift(SiteRig rig) {
        return (Workload.Within) rig.asked.getFirst().nodes().getFirst().spec().workload();
    }

    private static Worker worker(Mob body) {
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.STONE, 64), new ItemStack(Items.SCAFFOLDING, 64));
        return (Worker) Proxy.newProxyInstance(WorkSurfaceGameTests.class.getClassLoader(), new Class<?>[] {Worker.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "body" -> body;
                case "pack" -> pack;
                case "resident" -> new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("villager"));
                case "effects" -> Effects.NONE;
                case "placement" -> Placement.NOWHERE;
                case "held" -> ItemStack.EMPTY;
                case "within", "route", "supplied" -> List.of();
                default -> null;
            });
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 1; x <= 21; x++) {
            for (int z = 1; z <= 21; z++) {
                stone(helper, x, 0, z);
            }
        }
    }

    private static void stone(GameTestHelper helper, int x, int y, int z) {
        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.STONE.defaultBlockState(), 3);
    }
}
