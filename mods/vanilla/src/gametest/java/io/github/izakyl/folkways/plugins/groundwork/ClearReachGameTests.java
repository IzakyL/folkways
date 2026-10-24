package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.labor.ColonyLabor;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClearReachGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ClearReachGameTests.class);
    }

    @GameTest(template = "empty_large")
    public static void clearingUsesTheActualLowerReachBoundaryAndRejectsOneBlockBeyond(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos feet = floor(helper);
        BlockPos target = feet.above(4);
        place(level, target, Blocks.WHITE_WOOL.defaultBlockState());
        helper.assertTrue(Reach.withinReach(feet, target) && Reach.inSight(level, feet, target),
            "the hand can reach exactly four and a half blocks upward from this safe floor");
        Job job = new Survey(level, Set.of(target)).clear(cell -> false).getFirst();
        helper.assertTrue(job.footings().cells().contains(WorldPos.of(level, feet)),
            "clearing must include the real lower reach boundary rather than only two levels below its target");
        place(level, target, Blocks.AIR.defaultBlockState());
        BlockPos tooHigh = target.above();
        place(level, tooHigh, Blocks.WHITE_WOOL.defaultBlockState());
        helper.assertTrue(!Reach.withinReach(feet, tooHigh), "one level beyond that boundary is out of reach");
        helper.assertTrue(new Survey(level, Set.of(tooHigh)).clear(cell -> false).isEmpty(),
            "a floating block beyond the real ground reach cannot invent a usable stance on itself");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void clearingAlsoFindsHigherLedgesWithinReachAndRejectsTheirFarSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = floor(helper);
        place(level, target, Blocks.WHITE_WOOL.defaultBlockState());
        BlockPos high = target.offset(2, 4, 0);
        BlockPos far = target.offset(3, 4, 0);
        place(level, high.below(), Blocks.STONE.defaultBlockState());
        place(level, far.below(), Blocks.STONE.defaultBlockState());
        helper.assertTrue(Footing.withFeetAt(level, high) && Reach.withinReach(high, target)
            && Reach.inSight(level, high, target), "a visible ledge above the target is also a legitimate hand position");
        Job job = new Survey(level, Set.of(target)).clear(cell -> false).getFirst();
        helper.assertTrue(job.footings().cells().contains(WorldPos.of(level, high)),
            "the search does not truncate legitimate higher ledges to one level above the target");
        helper.assertTrue(!Reach.withinReach(far, target)
            && !job.footings().cells().contains(WorldPos.of(level, far)), "a higher ledge outside the reach sphere stays excluded");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void clearingStillRejectsOccludedOccupiedUnsupportedAndRemovedFootings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = floor(helper);
        BlockPos target = origin.above();
        place(level, target, Blocks.WHITE_WOOL.defaultBlockState());
        BlockPos hidden = origin.west(3);
        for (int up = 0; up <= 3; up++) place(level, origin.west().above(up), Blocks.STONE.defaultBlockState());
        BlockPos occupied = origin.east(2);
        place(level, occupied, Blocks.STONE.defaultBlockState());
        BlockPos unsupported = origin.south(2);
        place(level, unsupported.below(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(Footing.withFeetAt(level, hidden) && Reach.withinReach(hidden, target)
            && !Reach.inSight(level, hidden, target), "the barrier separates a supported in-range floor from its target");
        Job job = new Survey(level, Set.of(target)).clear(cell -> false).getFirst();
        Set<WorldPos> stances = job.footings().cells();
        for (BlockPos rejected : List.of(hidden, occupied, unsupported, target, target.above())) {
            helper.assertTrue(!stances.contains(WorldPos.of(level, rejected)),
                "unsafe or self-removing work position remains excluded: " + rejected);
        }
        helper.assertTrue(stances.stream().allMatch(at -> Footing.withFeetAt(level, at.block(level))
            && Reach.withinReach(at.block(level), target) && Reach.inSight(level, at.block(level), target)),
            "every returned stance is supported, clear, within reach and visible");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void sharedClearFootingsMatchTheCompleteReachHelperForSeveralTargets(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = floor(helper);
        Set<BlockPos> targets = Set.of(origin.above(3), origin.east(2).above(2), origin.south(2).above(4));
        targets.forEach(target -> place(level, target, Blocks.WHITE_WOOL.defaultBlockState()));
        for (int up = 0; up <= 5; up++) {
            place(level, origin.west().above(up), Blocks.STONE.defaultBlockState());
        }
        BlockPos occupied = origin.east(2);
        BlockPos unsupported = origin.south(3);
        place(level, occupied, Blocks.STONE.defaultBlockState());
        place(level, unsupported.below(), Blocks.AIR.defaultBlockState());
        BlockPos hidden = origin.west(2);
        helper.assertTrue(Footing.withFeetAt(level, hidden) && Reach.withinReach(hidden, origin.above(3))
            && !Reach.inSight(level, hidden, origin.above(3)), "the equivalence fixture has an occluded in-range footing");
        List<Job> jobs = new Survey(level, targets).clear(cell -> false);
        helper.assertTrue(jobs.stream().map(Job::cell).collect(Collectors.toSet()).equals(targets),
            "one shared sweep offers all three independently reachable targets");
        for (Job job : jobs) {
            Set<BlockPos> barred = Set.of(job.cell(), job.cell().above());
            Set<WorldPos> expected = Reach.workableCells(level, job.cell()).stream()
                .filter(feet -> !barred.contains(feet)).map(feet -> WorldPos.of(level, feet)).collect(Collectors.toSet());
            helper.assertTrue(!expected.isEmpty() && job.footings().cells().equals(expected),
                "shared footing checks preserve the entire helper stance set, including reach boundaries: " + job.cell());
        }
        helper.assertTrue(new Survey(level, targets).discovering().clear(cell -> false).stream()
            .allMatch(job -> job.footings().equals(Stances.WHEREVER)), "discovery still records targets without footing proof");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void fourHighWallTorchesKeepTheirSupportAndOfferReachableGroundStances(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Column column = column(helper);
        List<Job> offered = new Survey(level, column.cells()).clear(cell -> false);
        helper.assertTrue(offered.stream().map(Job::cell).collect(Collectors.toSet()).equals(column.torches()),
            "all four side attachments are offered before the wool or any fence, even though they are high");
        helper.assertTrue(new ClearOrder(level).blocked(List.of(column.wool())),
            "the shared support remains blocked while any attached torch stands");
        for (Job job : offered) {
            BlockPos outside = job.cell().offset(job.cell().getX() - column.wool().getX(), -3,
                job.cell().getZ() - column.wool().getZ());
            helper.assertTrue(job.footings().cells().contains(WorldPos.of(level, outside)),
                "an outside ground stance is offered instead of requiring the isolated top of the column: " + job.cell());
            helper.assertTrue(job.footings().cells().stream().allMatch(at ->
                !at.cell().equals(job.cell()) && !at.cell().equals(job.cell().above())),
                "expanding reach does not permit breaking the block underneath the worker");
        }
        helper.succeed();
    }

    // GameTest servers do not run Labor.work automatically. Drive that same colony pipeline inline once per
    // real server tick; the resident still follows ordinary navigation and the actual BreakNodes do the work.
    @GameTest(template = "empty_large", timeoutTicks = 1200)
    public static void aRealGroundResidentIsAssignedAndClearsTheFourTorchColumnWithoutTeleporting(GameTestHelper helper) {
        Column column = column(helper);
        Dispatch dispatch = new Dispatch(helper, column);
        dispatch.tick(1100);
    }

    private record Column(BlockPos feet, BlockPos wool, Set<BlockPos> torches, Set<BlockPos> cells) {
    }

    private static Column column(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos feet = floor(helper);
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int up = 0; up < 3; up++) {
            BlockPos fence = feet.above(up);
            place(level, fence, Blocks.OAK_FENCE.defaultBlockState());
            cells.add(fence);
        }
        BlockPos wool = feet.above(3);
        place(level, wool, Blocks.WHITE_WOOL.defaultBlockState());
        cells.add(wool);
        Set<BlockPos> torches = new LinkedHashSet<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos torch = wool.relative(direction);
            place(level, torch, Blocks.WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, direction));
            torches.add(torch);
            cells.add(torch);
        }
        return new Column(feet, wool, Set.copyOf(torches), Set.copyOf(cells));
    }

    private static BlockPos floor(GameTestHelper helper) {
        BlockPos feet = helper.absolutePos(new BlockPos(10, 1, 10));
        for (BlockPos floor : BlockPos.betweenClosed(feet.offset(-7, -1, -7), feet.offset(7, -1, 7))) {
            place(helper.getLevel(), floor, Blocks.GRASS_BLOCK.defaultBlockState());
            for (int up = 1; up <= 9; up++) place(helper.getLevel(), floor.above(up), Blocks.AIR.defaultBlockState());
        }
        return feet;
    }

    private static void place(ServerLevel level, BlockPos cell, BlockState state) {
        level.setBlock(cell, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private static final class Dispatch {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final Column column;
        private final ColonyData colony;
        private final ColonyLabor labor;
        private final ResidentEntity body;
        private final Asking asking;
        private final Chores chores;
        private final Method run;
        private final Method closeLabor;
        private final Object inline;
        private boolean closed;

        Dispatch(GameTestHelper helper, Column column) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.column = column;
            try {
                run = java.util.Arrays.stream(ColonyLabor.class.getMethods())
                    .filter(method -> method.getName().equals("run") && method.getParameterCount() == 2).findFirst().orElseThrow();
                Field field = run.getParameterTypes()[1].getDeclaredField("INLINE");
                field.setAccessible(true);
                inline = field.get(null);
                closeLabor = ColonyLabor.class.getDeclaredMethod("close");
                closeLabor.setAccessible(true);
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException("cannot drive the actual colony test pipeline", broken);
            }
            colony = ColonyRegistry.get(level.getServer()).mint(level.getServer());
            labor = new ColonyLabor(colony, level.dimension());
            body = PersonBody.RESIDENT.get().create(level);
            asking = new Asking(colony.works(), GroundworkContent.ID, () -> { });
            chores = new Chores(UUID.randomUUID(), GroundworkContent.CLEAR,
                (at, chore) -> new BreakNode(chore, BreakNode.Way.CLEAR, at.getBlockState(chore.job().cell()),
                    state -> true, (cell, state) -> false), () -> { }, new PastDay());
            try {
                body.setPos(Vec3.atBottomCenterOf(column.feet().west(4)));
                helper.assertTrue(level.addFreshEntity(body), "the test builder is a real loaded resident");
                colony.works().hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
                body.joinColony(colony.colonyId());
                colony.works().entered(body);
                helper.assertTrue(body.licences().of(BuildContent.trade()).isPresent(), "the builder is licensed for native clearing");
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        void tick(int remaining) {
            try {
                helper.assertTrue(body.isAlive() && !body.isInWall(), "the builder stays alive outside solid blocks");
                boolean attachmentsRemain = column.torches().stream().anyMatch(cell -> !level.getBlockState(cell).isAir());
                if (attachmentsRemain) {
                    helper.assertTrue(level.getBlockState(column.wool()).is(Blocks.WHITE_WOOL),
                        "the original wool support cannot disappear before every attachment is deliberately removed");
                }
                if (column.cells().stream().allMatch(cell -> level.getBlockState(cell).isAir())) {
                    helper.assertTrue(Goods.countIn(body.pack(), Goods.specOf(Items.TORCH.getDefaultInstance())) == 4
                        && Goods.countIn(body.pack(), Goods.specOf(Items.WHITE_WOOL.getDefaultInstance())) == 1
                        && Goods.countIn(body.pack(), Goods.specOf(Items.OAK_FENCE.getDefaultInstance())) == 3,
                        "the assigned resident receives exactly four torches, one wool and three fence drops");
                    helper.assertTrue(level.getBlockState(column.feet().below()).is(Blocks.GRASS_BLOCK),
                        "the unmarked ground is preserved");
                    close();
                    helper.succeed();
                    return;
                }
                helper.assertTrue(remaining > 0, "the real dispatch must complete without climbing or teleporting; body="
                    + body.position() + "; remaining=" + column.cells().stream().filter(cell -> !level.getBlockState(cell).isAir()).toList());
                chores.settle(new Survey(level, column.cells()).clear(cell -> false));
                List<Grown> wanted = new ArrayList<>();
                chores.goals(level, wanted);
                asking.offer(Map.of(level.dimension(), wanted));
                ColonyWays.advance(colony, level);
                invoke(run, labor, level, inline);
                helper.runAfterDelay(1, () -> tick(remaining - 1));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void close() {
            if (closed) return;
            closed = true;
            try {
                invoke(closeLabor, labor);
            } finally {
                body.discard();
                colony.works().raze();
                ColonyWays.forget(colony.colonyId());
            }
        }

        private static void invoke(Method method, Object target, Object... arguments) {
            try {
                method.invoke(target, arguments);
            } catch (InvocationTargetException broken) {
                if (broken.getCause() instanceof RuntimeException failure) throw failure;
                if (broken.getCause() instanceof Error failure) throw failure;
                throw new IllegalStateException(broken.getCause());
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException(broken);
            }
        }
    }
}
