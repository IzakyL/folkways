package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.world.api.Closures;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Settings;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.build.draft.Chosen;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import io.github.izakyl.folkways.vanilla.build.draft.Pattern;
import io.github.izakyl.folkways.vanilla.build.draft.Patterns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static io.github.izakyl.folkways.vanilla.build.SiteRig.floor;
import static io.github.izakyl.folkways.vanilla.build.SiteRig.target;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class BuildPlanningGameTests {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuildPlanningGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void everythingLeftIsAskedForOnceInLayersOutFromThePlane(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Map<BlockPos, BlockState> wall = new LinkedHashMap<>();
        for (int x = 6; x <= 8; x++) {
            for (int y = 1; y <= 3; y++) {
                wall.put(at(helper, x, y, 8), STONE);
            }
        }
        SiteRig rig = SiteRig.start(level, target(level, wall));
        helper.assertValueEqual(rig.site.pieces().size(), 9, "every block of the wall comes up at once");
        helper.assertTrue(rig.site.turns() > 0 && rig.asked.size() == rig.site.turns(),
            "and the colony is asked for hands to it, not for the blocks");
        for (int x = 6; x <= 8; x++) {
            helper.assertTrue(rig.free(at(helper, x, 1, 8)), "the course on the ground waits for nothing");
            for (int y = 2; y <= 3; y++) {
                var lower = rig.pieceAt(at(helper, x, y - 1, 8)).work();
                var upper = rig.pieceAt(at(helper, x, y, 8)).work();
                helper.assertTrue(lower.key() < upper.key(), "the suggested order favours lower courses");
                helper.assertTrue(rig.free(at(helper, x, y, 8)),
                    "solid upper courses do not require an unrelated lower block to finish");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void runtimeAccessExtendsHorizontallyWhenNearbyShaftsAreBlocked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos goal = at(helper, 10, 11, 10);
        // Water blocks every nearby air shaft without offering an upper face to support scaffolding.
        for (BlockPos cell : BlockPos.betweenClosed(at(helper, 8, 3, 8), at(helper, 12, 3, 12))) {
            level.setBlock(cell, Blocks.WATER.defaultBlockState(), 3);
        }
        SiteRig rig = SiteRig.start(level, target(level, Map.of(goal, STONE)));
        helper.assertValueEqual(rig.site.pieces().size(), 1, "planning contains only the blueprint target");
        rig.site.tick(level);
        BuildSite.Piece raising = rig.site.pieces().stream()
            .filter(piece -> piece.work().step().orElseThrow().kind() == BuildJob.Kind.RAISE)
            .findFirst().orElseThrow();
        List<BlockPos> access = List.copyOf(raising.work().step().orElseThrow().after().keySet());
        helper.assertTrue(access.stream().anyMatch(cell -> access.stream().anyMatch(other ->
                cell.getY() == other.getY() && !cell.equals(other))),
            "access includes horizontal scaffolding from a clear shaft outside the blocked area");
        Worker worker = worker(helper, at(helper, 7, 1, 9));
        helper.assertTrue(Commit.run(level, raising.action(), worker) instanceof Outcome.Done,
            "the vertical shaft and horizontal extension can be built together");
        helper.assertTrue(access.stream().allMatch(cell -> level.getBlockState(cell).is(Blocks.SCAFFOLDING)),
            "every scaffold cell is placed in the world");
        helper.assertTrue(access.stream().allMatch(cell -> level.getBlockState(cell)
                .getValue(ScaffoldingBlock.DISTANCE) <= 6),
            "the horizontal extension remains within the scaffolding support limit");
        rig.end(raising.action(), Ending.DONE);
        rig.site.tick(level);
        helper.assertTrue(rig.work(helper, goal, worker) instanceof Outcome.Done,
            "work proceeds once runtime access is raised");
        helper.startSequence().thenExecuteAfter(41, () -> {
            rig.site.tick(level);
            rig.workAll(helper, worker);
            helper.assertTrue(access.stream().noneMatch(cell -> level.getBlockState(cell).is(Blocks.SCAFFOLDING)),
                "cleanup removes both the vertical shaft and horizontal extension");
            helper.assertTrue(rig.site.complete(), "horizontal access cleanup releases final completion");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void completedAirDoesNotSkipTemporaryScaffoldWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos goal = at(helper, 10, 11, 10);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (BlockPos cell : BlockPos.betweenClosed(at(helper, 7, 1, 7), at(helper, 13, 10, 13))) {
            cells.put(cell.immutable(), AIR);
        }
        cells.put(goal, STONE);
        SiteRig rig = SiteRig.start(level, target(level, cells));
        helper.assertTrue(rig.site.pieces().stream().noneMatch(piece ->
                piece.work().step().orElseThrow().kind() == BuildJob.Kind.RAISE),
            "completed air does not introduce scaffolds during planning");
        rig.site.tick(level);
        BuildSite.Piece raising = rig.site.pieces().stream()
            .filter(piece -> piece.work().step().orElseThrow().kind() == BuildJob.Kind.RAISE)
            .findFirst().orElseThrow();
        Worker worker = worker(helper, at(helper, 10, 1, 9));
        helper.assertTrue(Commit.run(level, raising.action(), worker) instanceof Outcome.Done,
            "completed air does not stand in for the scaffold operation");
        helper.assertTrue(raising.work().step().orElseThrow().after().keySet().stream()
            .allMatch(cell -> level.getBlockState(cell).is(Blocks.SCAFFOLDING)),
            "the temporary scaffold is physically raised over completed air targets");
        rig.end(raising.action(), Ending.DONE);
        rig.site.tick(level);
        helper.assertTrue(rig.work(helper, goal, worker) instanceof Outcome.Done, "the remaining target is laid");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void attachedBlocksComeAfterTheirSupportsAndGoBeforeThemComingDown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos support = at(helper, 8, 1, 8);
        BlockPos torch = support.east();
        BlockState hung = Blocks.WALL_TORCH.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);

        SiteRig building = SiteRig.start(level, target(level, ordered(support, STONE, torch, hung)));
        helper.assertTrue(building.before(support, torch), "the torch goes up after its wall");

        level.setBlock(support, STONE, 3);
        level.setBlock(torch, hung, 3);
        SiteRig clearing = SiteRig.start(level, target(level, ordered(support, AIR, torch, AIR)));
        helper.assertTrue(clearing.before(torch, support), "the torch comes down before its wall");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void separateWorkIsAskedTogetherAndTakesTheWorldAsItFindsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos west = at(helper, 4, 1, 4);
        BlockPos east = at(helper, 18, 1, 18);
        SiteRig rig = SiteRig.start(level, target(level, Map.of(west, STONE, east, STONE)));
        helper.assertTrue(rig.free(west) && rig.free(east), "two unrelated cells are asked for at once");

        level.setBlock(west, Blocks.DIRT.defaultBlockState(), 3);
        rig.site.tick(level);
        helper.assertTrue(rig.at(west).isPresent() && rig.withdrawn.isEmpty(),
            "a cell changed underneath keeps the work asked for it");
        helper.assertTrue(rig.work(helper, west, worker(helper, at(helper, 4, 1, 6))) instanceof Outcome.Done,
            "the work takes the world as it finds it");
        helper.assertTrue(level.getBlockState(west).is(Blocks.STONE), "the dirt in the way gives way to the stone");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void whatIsBrokenIsMadeRoomForAndAFullPackLeavesToPutItAway(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos rock = at(helper, 5, 1, 5);
        level.setBlock(rock, STONE, 3);
        SiteRig rig = SiteRig.start(level, target(level, Map.of(rock, Blocks.AIR.defaultBlockState())));
        helper.assertTrue(rig.asked.size() == 1, "one turn is asked for the stone, not " + rig.asked.size());
        Node stay = rig.asked.getFirst().nodes().getFirst();
        helper.assertTrue(stay.spec().leaves().equals(List.of(new Amount(ItemSpec.of(
                ResourceLocation.withDefaultNamespace("stone")), 1))),
            "the turn keeps room for what the stone breaks into, not " + stay.spec().leaves());
        SiteRig.Shift shift = new SiteRig.Shift(stay);
        BlockPos feet = at(helper, 5, 1, 7);

        // Room on a stack of stone is no room for cobble.
        SimpleContainer full = new SimpleContainer(2);
        full.setItem(0, new ItemStack(Items.DIRT, 64));
        full.setItem(1, new ItemStack(Items.STONE, 63));
        helper.assertTrue(shift.tick(level, worker(helper, feet, full)) instanceof HoldingStep.Done,
            "a pack with no room for the rubble leaves to put it away");
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(rock).inflate(3.0D)).isEmpty()
            && level.getBlockState(rock).is(Blocks.STONE), "nothing is broken for want of room, and nothing dropped");

        SimpleContainer topped = new SimpleContainer(2);
        topped.setItem(0, new ItemStack(Items.STONE, 63));
        helper.assertTrue(shift.step(level, worker(helper, feet, topped)) instanceof Outcome.Done,
            "an empty cell is room enough");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aWaterloggedBlockTakesInTheWaterItIsLaidIntoOrHasABucketLetInAfter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockState wet = Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        BlockPos pond = at(helper, 3, 1, 5);
        BlockPos dry = at(helper, 18, 1, 5);
        level.setBlock(pond, Blocks.WATER.defaultBlockState(), 3);
        SiteRig rig = SiteRig.start(level, target(level, ordered(pond, wet, dry, wet)));
        List<BlockPos> soaked = rig.site.pieces().stream()
            .filter(piece -> piece.work().kind() == Layering.Kind.SOAK).map(piece -> piece.work().focus()).toList();
        helper.assertTrue(soaked.equals(List.of(dry)),
            "only the block laid where no water stands has water let in after, not " + soaked);
        UUID laid = rig.site.pieces().stream().filter(piece -> piece.work().kind() == Layering.Kind.LAY
            && piece.work().focus().equals(dry)).findFirst().orElseThrow().id();
        UUID soak = rig.site.pieces().stream().filter(piece -> piece.work().kind() == Layering.Kind.SOAK)
            .findFirst().orElseThrow().id();
        helper.assertTrue(rig.ordered(laid, soak), "the water is let in after the block is laid");

        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.STONE_STAIRS, 2),
            new ItemStack(Items.WATER_BUCKET));
        rig.workAll(helper, worker(helper, at(helper, 10, 1, 7), pack));
        helper.assertTrue(level.getBlockState(pond) == wet, "laid into water, the block takes it in: "
            + level.getBlockState(pond));
        helper.assertTrue(level.getBlockState(dry) == wet, "laid dry, the block has water let in: "
            + level.getBlockState(dry));
        helper.assertTrue(pack.countItem(Items.WATER_BUCKET) == 0 && pack.countItem(Items.STONE_STAIRS) == 0,
            "two stairs and one bucket, for the dry one alone, are spent");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void turnsAreSmallOfOneKindOfGoodsAndAHandDoesWhatItCarriesFor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (int x = 1; x <= 10; x++) {
            for (int z = 1; z <= 10; z++) {
                cells.put(at(helper, x, 1, z), STONE);
            }
        }
        for (int x = 13; x <= 16; x++) {
            for (int z = 1; z <= 5; z++) {
                cells.put(at(helper, x, 1, z), Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        SiteRig rig = SiteRig.start(level, target(level, cells));
        List<Set<ItemSpec>> kinds = rig.asked.stream()
            .map(work -> work.nodes().getFirst().spec().needs().stream().map(Need::spec)
                .collect(java.util.stream.Collectors.toSet()))
            .toList();
        helper.assertTrue(kinds.stream().allMatch(one -> one.size() == 1),
            "no turn is asked for two kinds of goods, one of which the colony may be short of: " + kinds);
        ItemSpec stone = ItemSpec.of(ResourceLocation.withDefaultNamespace("stone"));
        helper.assertTrue(kinds.stream().filter(one -> one.contains(stone)).count() >= 2,
            "a hundred blocks of floor are more than one turn's work: " + kinds);

        SiteRig.Shift shift = new SiteRig.Shift(rig.asked.stream()
            .map(work -> work.nodes().getFirst())
            .filter(stay -> stay.spec().needs().stream().anyMatch(need -> need.spec().equals(stone)))
            .findFirst().orElseThrow());
        SimpleContainer planks = new SimpleContainer(new ItemStack(Items.OAK_PLANKS, 20));
        helper.assertTrue(shift.step(level, worker(helper, at(helper, 14, 1, 7), planks))
                instanceof Outcome.Done,
            "a hand on one turn does the work of another it brought the goods for");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aCellMetIsMetForGoodAndTheOrderIsDoneWhenAllAre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos first = at(helper, 6, 1, 6);
        BlockPos second = at(helper, 12, 1, 12);
        SiteRig rig = SiteRig.start(level, target(level, Map.of(first, STONE, second, STONE)));
        helper.assertValueEqual(rig.site.left(), 2, "both cells are still to meet");

        level.setBlock(first, STONE, 3);
        rig.site.tick(level);
        helper.assertValueEqual(rig.site.left(), 1, "a cell someone else set is met");
        helper.assertTrue(rig.at(first).isEmpty(), "and its piece is no longer to do");

        level.setBlock(first, AIR, 3);
        rig.site.tick(level);
        helper.assertValueEqual(rig.site.left(), 1, "a cell met stays met when it is broken after");
        helper.assertTrue(!rig.site.complete(), "the other cell is still to do");
        BuildTarget frozen = rig.site.target();
        var progress = BuildOrderView.of(UUID.randomUUID(), frozen, rig.site.metCells(), rig.site.metJoints())
            .orElseThrow();
        helper.assertValueEqual(progress.placed(), 1,
            "the board reads completion history after a completed block breaks");
        helper.assertTrue(BuildOrderView.pending(level, frozen, rig.site.metCells(), rig.site.metJoints()).stream()
            .noneMatch(ghost -> ghost.pos().equals(first)), "a completed block never returns as a ghost");
        helper.assertValueEqual(
            BuildOrderView.owed(level, frozen, rig.site.metCells()),
            Map.of(ItemSpec.of(Items.STONE), 1L),
            "broken completed blocks never return to the material bill");
        Set<BlockPos> saved = rig.site.met();
        rig.site.close();
        rig = SiteRig.start(level, frozen, new TemporaryScaffolds(() -> { }), saved);
        helper.assertValueEqual(rig.site.left(), 1,
            "restoring the site preserves completed cells without checking them again");
        helper.assertTrue(rig.at(first).isEmpty(), "restoring does not plan repair work for completed cells");
        helper.assertTrue(rig.work(helper, second, worker(helper, at(helper, 12, 1, 14))) instanceof Outcome.Done,
            "the other cell is laid");
        helper.assertTrue(rig.site.complete(), "every cell met, the order is done");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aPieceThatEndsUndoneIsAskedAgainAndAtLastRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos cell = at(helper, 8, 1, 8);
        SiteRig rig = SiteRig.start(level, target(level, Map.of(cell, STONE)));
        BuildAction node = rig.action(cell);
        for (int hitch = 0; hitch <= BuildSite.MOST_TRIES; hitch++) {
            rig.end(node, new Ending.Failed(rig.pieceAt(cell).id(), BuildRefusal.OCCUPIED));
            rig.site.tick(level);
        }
        helper.assertTrue(rig.at(cell).isPresent(), "a hitch that passes is never counted against the piece");
        for (int tried = 1; tried < BuildSite.MOST_TRIES; tried++) {
            rig.end(node, new Ending.Failed(rig.pieceAt(cell).id(), BuildRefusal.NO_WAY_TO_BUILD));
            rig.site.tick(level);
            helper.assertTrue(rig.at(cell).isPresent() && rig.action(cell) == node,
                "the piece comes up again as it was");
        }
        rig.end(node, new Ending.Failed(rig.pieceAt(cell).id(), BuildRefusal.NO_WAY_TO_BUILD));
        rig.site.tick(level);
        helper.assertTrue(rig.site.pieces().isEmpty(), "at last it is given up");
        helper.assertValueEqual(rig.site.refusals().get(cell), BuildRefusal.NO_WAY_TO_BUILD, "and the board says why");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void onlyDepthPhysicalDependenciesAreAskedAheadAndEachLaidAsksTheNext(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        int top = BuildSite.DEPTH + 1;
        Map<BlockPos, BlockState> column = new LinkedHashMap<>();
        for (int y = 1; y <= top; y++) {
            column.put(at(helper, 8, y, 8), Blocks.SAND.defaultBlockState());
        }
        SiteRig rig = SiteRig.start(level, target(level, column));
        long released = column.keySet().stream().filter(cell -> rig.at(cell).isPresent()).count();
        helper.assertTrue(released > 0 && released <= BuildSite.DEPTH,
            "only a bounded depth of physical supports is released ahead");
        helper.assertTrue(rig.at(at(helper, 8, top, 8)).isEmpty(), "the top course waits for supports to finish");
        helper.assertTrue(rig.before(at(helper, 8, 1, 8), at(helper, 8, 2, 8)),
            "falling blocks retain their genuine support dependency");
        rig.workAll(helper, worker(helper, at(helper, 9, 1, 8),
            new SimpleContainer(new ItemStack(Items.SAND, 64), new ItemStack(Items.SCAFFOLDING, 64))));
        helper.assertTrue(rig.site.complete(), "finishing the released work brings out the rest of the column");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aColumnTallerThanReachHasNoPlannedAccessDependency(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Map<BlockPos, BlockState> column = new LinkedHashMap<>();
        for (int y = 1; y <= 6; y++) {
            column.put(at(helper, 8, y, 8), STONE);
        }
        SiteRig rig = SiteRig.start(level, target(level, column));
        helper.assertTrue(rig.site.refusals().isEmpty(), "the plan does not reject work for missing access: "
            + rig.site.refusals());
        rig.workAll(helper, worker(helper, at(helper, 9, 1, 8)));
        helper.assertTrue(column.keySet().stream().allMatch(cell -> level.getBlockState(cell).is(Blocks.STONE)),
            "the column stands to the top");
        helper.assertTrue(BlockPos.betweenClosedStream(at(helper, 5, 1, 5), at(helper, 11, 8, 11))
            .noneMatch(cell -> level.getBlockState(cell).is(Blocks.SCAFFOLDING)), "with no scaffold left up");
        helper.assertTrue(rig.site.complete(), "and the order is done");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void theWaysAreClosedOverASiteWhileItStands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos cell = at(helper, 8, 1, 8);
        SiteRig rig = SiteRig.start(level, target(level, Map.of(cell, STONE)));
        helper.assertTrue(closing(level, cell).isPresent(), "the ways are closed over the site");
        helper.assertTrue(closing(level, cell).get().box().isInside(cell.offset(2, 0, 2)),
            "and a little past it");
        rig.site.close();
        helper.assertTrue(closing(level, cell).isEmpty(), "and opened again when it is given up");
        helper.succeed();
    }

    private static Optional<Closures.Closed> closing(ServerLevel level, BlockPos cell) {
        return Closures.in(level).stream().filter(one -> one.holds(cell)).findFirst();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void fluidIsPouredFromABucketAndClearedForNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Worker worker = worker(helper, at(helper, 3, 1, 3));

        BlockPos pour = at(helper, 6, 1, 6);
        SiteRig pouring = SiteRig.start(level, target(level, Map.of(pour, Blocks.WATER.defaultBlockState())));
        BuildAction poured = pouring.action(pour);
        helper.assertValueEqual(poured.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("water_bucket")), 1)),
            "pouring water asks for a water bucket");
        Outcome outcome = Commit.run(level, poured, worker);
        helper.assertTrue(outcome instanceof Outcome.Done done
            && done.made().stream().anyMatch(stack -> stack.is(Items.BUCKET)), "the empty bucket comes back");
        helper.assertTrue(level.getBlockState(pour).is(Blocks.WATER), "the water is poured");

        for (int x = 10; x <= 14; x++) {
            for (int z = 10; z <= 14; z++) {
                boolean rim = x == 10 || x == 14 || z == 10 || z == 14;
                level.setBlock(at(helper, x, 1, z), rim ? STONE : Blocks.WATER.defaultBlockState(), 2);
            }
        }
        Map<BlockPos, BlockState> dry = new LinkedHashMap<>();
        for (int x = 11; x <= 13; x++) {
            for (int z = 11; z <= 13; z++) {
                dry.put(at(helper, x, 1, z), AIR);
            }
        }
        SiteRig draining = SiteRig.start(level, target(level, dry));
        helper.assertValueEqual(draining.step(at(helper, 12, 1, 12)).after().size(), 9, "the pool drains as one layer");
        BuildAction drain = draining.action(at(helper, 12, 1, 12));
        helper.assertTrue(drain.spec().needs().isEmpty(), "clearing fluid needs nothing");
        helper.assertTrue(Commit.run(level, drain, worker) instanceof Outcome.Done done && done.made().isEmpty(),
            "and gives nothing");
        helper.assertTrue(dry.keySet().stream().allMatch(cell -> level.getBlockState(cell).isAir()), "the pool is dry");

        level.setBlock(at(helper, 14, 1, 12), Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(at(helper, 13, 1, 12), Blocks.WATER.defaultBlockState(), 2);
        SiteRig fed = SiteRig.start(level, target(level, Map.of(at(helper, 13, 1, 12), AIR)));
        helper.assertValueEqual(fed.site.refusals().get(at(helper, 13, 1, 12)), BuildRefusal.NO_WAY_TO_BUILD,
            "water with a source beside it outside the blueprint is not cleared");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void plannedWallsComeBeforeDrainingWaterAndLava(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        for (int z : new int[] {7, 15}) {
            BlockState fluid = z == 7 ? Blocks.WATER.defaultBlockState() : Blocks.LAVA.defaultBlockState();
            BlockPos source = at(helper, 10, 1, z);
            BlockPos flowing = source.east();
            BlockPos wall = flowing.east();
            BlockPos rock = wall.north();
            for (int x = 9; x <= 13; x++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(at(helper, x, 1, z + dz), STONE, 2);
                }
            }
            level.setBlock(source, fluid, 2);
            level.setBlock(flowing, fluid.setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3), 2);
            level.setBlock(wall, fluid, 2);
            SiteRig rig = SiteRig.start(level, target(level, Map.of(source, AIR, flowing, AIR, rock, AIR,
                wall, Blocks.COBBLESTONE.defaultBlockState())));
            helper.assertTrue(rig.site.refusals().isEmpty(), "a planned wall makes the drain possible: "
                + rig.site.refusals());
            helper.assertTrue(rig.before(wall, source), "the wall precedes the drain");
            helper.assertTrue(rig.before(wall, rock), "the wall precedes excavation beside fluid");
            Worker worker = worker(helper, at(helper, 3, 1, 3),
                new SimpleContainer(new ItemStack(Items.COBBLESTONE, 64)));
            helper.assertTrue(rig.work(helper, wall, worker) instanceof Outcome.Done, "the wall is laid");
            helper.assertTrue(rig.work(helper, source, worker) instanceof Outcome.Done, "the fluid is drained");
            helper.assertTrue(level.getBlockState(source).isAir() && level.getBlockState(flowing).isAir(),
                "source and flowing fluid are gone");
            helper.assertTrue(level.getBlockState(wall).is(Blocks.COBBLESTONE), "the wall stands");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aLeftoverScaffoldIsTakenDownByItsOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos players = at(helper, 18, 1, 18);
        level.setBlock(players, ScaffoldAction.state(), 3);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        List<WorldPos> column = new ArrayList<>();
        for (int y = 1; y <= 3; y++) {
            BlockPos cell = at(helper, 10, y, 10);
            level.setBlock(cell, ScaffoldAction.state(), 3);
            column.add(WorldPos.of(level, cell));
        }
        temporary.added(column);
        TemporaryScaffolds restored = new TemporaryScaffolds(() -> { });
        restored.load(Reader.of(temporary.save()));
        helper.assertValueEqual(restored.cells(), temporary.cells(), "ownership survives a reload");

        SiteRig rig = SiteRig.start(level, new BuildTarget(WorldPos.of(level, players).realm(), Map.of()), temporary,
            Set.of());
        BlockPos foot = at(helper, 10, 1, 10);
        helper.assertValueEqual(rig.step(foot).kind(), BuildJob.Kind.LOWER, "the column is taken down");
        helper.assertTrue(rig.work(helper, foot, worker(helper, at(helper, 8, 1, 10))) instanceof Outcome.Done,
            "in one go");
        helper.assertTrue(temporary.isEmpty() && column.stream()
            .allMatch(cell -> level.getBlockState(cell.block(level)).isAir()),
            "nothing of the column is left");
        helper.assertTrue(level.getBlockState(players).is(Blocks.SCAFFOLDING), "a player's scaffolding is left alone");
        helper.assertTrue(rig.site.complete(), "the order has nothing left to do");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void cancellingSurvivesReloadAndRetiresTheOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos goal = at(helper, 10, 1, 10);
        Asked asked = new Asked();
        Colony colony = colony(helper, asked);
        BuildPresence presence = new BuildPresence(colony, level.getServer());
        BlueprintBuildOrder order = presence.file(level, blueprint(BlockPos.ZERO), goal, "", Optional.empty(), "")
            .orElseThrow();
        presence.settle(level.getServer());
        helper.assertValueEqual(asked.works.size(), 1, "the order's work is asked for");
        UUID node = asked.works.getFirst().nodes().getFirst().id();
        presence.act(BuildContent.PAGE.id(), BuildAct.cancelling(order.id()), Optional.empty(), view(level));
        helper.assertTrue(asked.withdrawn.contains(node), "calling the order off withdraws its work");

        BuildPresence restored = new BuildPresence(colony, level.getServer());
        restored.settle(level.getServer());
        helper.assertValueEqual(asked.works.size(), 1, "cancelled work is not asked for again after a reload");
        helper.assertTrue(level.getBlockState(goal).isAir(), "nor built");
        helper.assertTrue(asked.saved.get().getCompound("book").getList("buildOrders", 10).isEmpty(),
            "the order is removed with nothing left to take down");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void aPatternDrawnOnTheServerIsFiledWholeFarPastFiveHundredCells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BuildPresence presence = new BuildPresence(colony(helper, new Asked()), level.getServer());
        Pattern pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_slab"))
            .orElseThrow();
        BlockPos start = at(helper, 1, 2, 1);
        Hint hint = new Hint.Zone(start, start.offset(39, 0, 39));
        CompletableFuture<Rounds.Once> drawing = Rounds.drawOnce(level, pattern, hint,
            Chosen.of(pattern.knobs(), ColonySettings.byDefault(pattern.knobs())), Direction.NORTH);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(drawing.isDone(), "the drawing comes back"))
            .thenExecute(() -> {
                Rounds.Once once = drawing.join();
                helper.assertTrue(once instanceof Rounds.Once.Drafted, "the slab is drawn: " + once);
                Rounds.Once.Drafted drafted = (Rounds.Once.Drafted) once;
                helper.assertValueEqual(drafted.blueprint().blocks().size(), 1600, "every cell of the slab is kept");
                helper.assertValueEqual(drafted.corner(), start, "the slab lies where it was marked");
                helper.assertTrue(presence.file(level, drafted.blueprint(), drafted.corner(), "", Optional.empty(),
                    "").isPresent(), "the slab is filed as one order");
                helper.assertTrue(level.getBlockState(start).isAir(), "filing builds nothing for free");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void aCommissionCrossesTheWireIntact(GameTestHelper helper) {
        Hint hint = new Hint.Path(List.of(new BlockPos(1, 2, 3), new BlockPos(40, 5, -6)));
        CompoundTag settings = new CompoundTag();
        settings.putInt("width", 7);
        CommissionPacket sent = new CommissionPacket(UUID.randomUUID(),
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "arch_bridge"), hint.save(), settings,
            Direction.EAST, "西桥", true);
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
            helper.getLevel().registryAccess());
        try {
            CommissionPacket.STREAM_CODEC.encode(buffer, sent);
            CommissionPacket read = CommissionPacket.STREAM_CODEC.decode(buffer);
            helper.assertValueEqual(read.colonyId(), sent.colonyId(), "the colony");
            helper.assertValueEqual(read.pattern(), sent.pattern(), "the pattern");
            helper.assertValueEqual(Hint.load(read.hint()).orElseThrow(), hint, "the mark");
            helper.assertValueEqual(read.settings().getInt("width"), 7, "the settings");
            helper.assertValueEqual(read.facing(), Direction.EAST, "the facing");
            helper.assertValueEqual(read.siteName(), "西桥", "the site's name");
            helper.assertTrue(read.raiseInCreative(), "whether to raise at once");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    private static Chosen mineSettings(Pattern pattern, int y) {
        CompoundTag values = Chosen.of(pattern.knobs(), ColonySettings.byDefault(pattern.knobs())).save();
        values.putInt("y", y);
        values.putInt("main_east", 6);
        values.putInt("branch_east", 3);
        values.putBoolean("chase", false);
        Chosen loaded = Chosen.load(pattern.knobs(), values);
        return Chosen.of(pattern.knobs(), new Settings() {
            public boolean flag(String key) {
                return loaded.flag(key);
            }

            public int count(String key) {
                return loaded.count(key);
            }

            public ResourceLocation choice(String key) {
                return loaded.choice(key);
            }

            public Optional<ItemSpec> items(String key) {
                for (Schema.Setting setting : pattern.knobs().settings()) {
                    if (setting instanceof Schema.Setting.Items slot && slot.key().equals(key)) {
                        return Optional.of(ItemSpec.of(slot.byDefault().getFirst().id()));
                    }
                }
                return Optional.empty();
            }
        });
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 2000)
    public static void aWholeMineOrderCanBeBuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = at(helper, 10, 40, 10);
        for (int x = -16; x <= 22; x++) {
            for (int z = -16; z <= 16; z++) {
                for (int y = -18; y <= 0; y++) {
                    level.setBlock(ground.offset(x, y, z), STONE, 2);
                }
            }
        }
        BlockPos centre = ground.offset(1, -16, 1);
        BlockPos inside = centre.east(12).above();
        BlockPos flowing = inside.south();
        BlockPos outside = flowing.south();
        level.setBlock(inside, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(flowing, Blocks.WATER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, 3), 2);
        level.setBlock(outside, Blocks.WATER.defaultBlockState(), 2);
        Pattern pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "mine"))
            .orElseThrow();
        BlockPos mark = ground.above();
        Hint hint = new Hint.Zone(mark, mark.offset(2, 0, 2));
        Chosen settings = mineSettings(pattern, ground.getY() - 16);
        BuildPresence presence = new BuildPresence(colony(helper, new Asked()), level.getServer());
        CompletableFuture<Rounds.Once> drawing = Rounds.drawOnce(level, pattern, hint, settings, Direction.NORTH);
        AtomicReference<SiteRig> active = new AtomicReference<>();
        AtomicReference<Worker> builder = new AtomicReference<>();
        AtomicReference<SimpleContainer> supplies = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(drawing.isDone(), "the mine drawing comes back"))
            .thenExecute(() -> {
                Rounds.Once once = drawing.join();
                helper.assertTrue(once instanceof Rounds.Once.Drafted, "the mine is drawn: " + once);
                Rounds.Once.Drafted drafted = (Rounds.Once.Drafted) once;
                BlueprintBuildOrder order = presence.file(level, drafted.blueprint(), drafted.corner(), "",
                    Optional.empty(), "").orElseThrow();
                BuildTarget target = BuildTarget.of(order, drafted.blueprint().confirm(level)).in(level);
                active.set(SiteRig.start(level, order.id(), target));
                SimpleContainer pack = new SimpleContainer(128);
                supplies.set(pack);
                Mob body = walker(helper, mark.above());
                builder.set(TestWorker.of(body, new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig")), pack)
                    .held(need -> new ItemStack(Items.DIAMOND_PICKAXE)).spill(stack -> { }));
            }).thenWaitUntil(() -> {
                BuildSite site = active.get().site;
                SimpleContainer pack = supplies.get();
                for (BuildSite.Piece piece : site.pieces()) {
                    if (!site.before(piece).isEmpty()) {
                        continue;
                    }
                    pack.clearContent();
                    int slot = 0;
                    for (var need : piece.action().spec().needs()) {
                        var item = Goods.members(need.spec()).stream().findFirst().orElseThrow();
                        long left = need.count();
                        while (left > 0) {
                            int count = (int) Math.min(left, item.getDefaultMaxStackSize());
                            pack.setItem(slot++, new ItemStack(item, count));
                            left -= count;
                        }
                    }
                    Outcome outcome = Commit.run(level, piece.action(), builder.get());
                    if (!(outcome instanceof Outcome.Done)) {
                        failure.set("mine piece " + piece.work().focus() + " failed: " + outcome
                            + " refusals=" + site.refusals());
                        break;
                    }
                    site.ended(piece.id(), Ending.DONE);
                }
                site.tick(level);
                helper.assertTrue(site.complete() || failure.get() != null, "mine incomplete: left=" + site.left()
                    + " turns=" + site.turns() + " refusals=" + site.refusals() + " pieces=" + site.pieces().stream()
                        .map(piece -> piece.work().focus() + " before=" + site.before(piece)).toList());
            }).thenExecute(() -> {
                helper.assertTrue(failure.get() == null, String.valueOf(failure.get()));
                helper.assertTrue(level.getBlockState(inside).isAir() && level.getBlockState(flowing).isAir(),
                    "water inside the drift is drained");
                helper.assertTrue(level.getBlockState(outside).is(Blocks.COBBLESTONE), "the pocket is sealed");
                helper.assertTrue(level.getBlockState(centre.east(14).above()).isAir(), "main drift is excavated");
                helper.assertTrue(level.getBlockState(centre.north(7).above()).isAir(),
                    "junction reaches the stair bottom");
                boolean stairBottom = false;
                for (int x = -4; x <= 4; x++) {
                    for (int z = -4; z <= 4; z++) {
                        BlockPos floor = centre.offset(x, 0, z);
                        if (level.getBlockState(floor).is(Blocks.SPRUCE_PLANKS)
                                && level.getBlockState(floor.above()).isAir()) {
                            stairBottom = true;
                        }
                    }
                }
                helper.assertTrue(stairBottom, "the stair bottom has a landing and air above it");
                helper.assertTrue(level.getBlockState(ground.offset(-4, 0, -4)).is(Blocks.SPRUCE_PLANKS),
                    "the headframe deck stands");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aSiteIsCalledWhatItWasGivenOrTheNextNumber(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Colony colony = colony(helper, new Asked());
        BuildPresence presence = new BuildPresence(colony, level.getServer());
        helper.assertValueEqual(presence.file(level, blueprint(BlockPos.ZERO), at(helper, 2, 1, 2), " ",
            Optional.empty(), "").orElseThrow().name(), "#1", "an unnamed site is numbered");
        helper.assertValueEqual(presence.file(level, blueprint(BlockPos.ZERO), at(helper, 4, 1, 2), "  粮仓 ",
            Optional.empty(), "").orElseThrow().name(), "粮仓", "a named one keeps the name it was given");
        helper.assertValueEqual(presence.file(level, blueprint(BlockPos.ZERO), at(helper, 6, 1, 2), "",
            Optional.empty(), "").orElseThrow().name(), "#2", "and spends no number");

        BuildPresence restored = new BuildPresence(colony, level.getServer());
        helper.assertValueEqual(restored.file(level, blueprint(BlockPos.ZERO), at(helper, 8, 1, 2), "",
            Optional.empty(), "").orElseThrow().name(), "#3", "the count goes on after a reload");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void cellsMetAreKeptWithTheOrderAcrossAReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos anchor = at(helper, 10, 1, 10);
        Asked asked = new Asked();
        Colony colony = colony(helper, asked);
        BuildPresence presence = new BuildPresence(colony, level.getServer());
        BlueprintBuildOrder filed = presence.file(level, blueprint(BlockPos.ZERO, new BlockPos(2, 0, 0)), anchor, "",
            Optional.empty(), "").orElseThrow();
        presence.settle(level.getServer());
        helper.assertValueEqual(presence.pieces(filed.id()).size(), 2, "both cells are to do");

        level.setBlock(anchor, STONE, 3);
        presence.tick(level.getServer());
        helper.assertTrue(!asked.saved.get().getList("met", 10).isEmpty(), "the cell met is kept with the order");
        level.setBlock(anchor, AIR, 3);

        int before = asked.works.size();
        BuildPresence restored = new BuildPresence(colony, level.getServer());
        restored.settle(level.getServer());
        helper.assertValueEqual(asked.works.size(), before + 1, "the order is asked for again after a reload");
        helper.assertValueEqual(asked.works.getLast().nodes().size(), 1,
            "only the cell never met, though the met one was broken since");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aDoorIsLaidAndTakenDownWholeForOneDoor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos lower = at(helper, 8, 1, 8);
        BlockPos upper = lower.above();
        Worker worker = worker(helper, at(helper, 5, 1, 8));
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        BuildTarget target = printed(level, ordered(lower, door,
            upper, door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        level.setBlock(upper.above(), STONE, 3);
        helper.assertValueEqual(target.launch(lower).orElseThrow().produces(), Set.of(lower, upper),
            "the lower half's shot makes both halves");
        helper.assertTrue(target.launch(upper).isEmpty(), "the upper half is never aimed at");

        SiteRig building = SiteRig.start(level, target);
        helper.assertValueEqual(building.site.pieces().size(), 1, "one piece of work");
        helper.assertTrue(building.step(upper).after().keySet().containsAll(List.of(lower, upper)),
            "both halves are one step");
        BuildAction lay = building.action(upper);
        helper.assertValueEqual(lay.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_door")), 1)),
                "one door is asked for");
        long doors = Goods.countIn(worker.pack(), ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_door")));
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Done, "the door is laid");
        helper.assertValueEqual(Goods.countIn(worker.pack(),
            ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_door"))),
            doors - 1, "one door is spent");
        helper.assertTrue(level.getBlockState(lower).is(Blocks.OAK_DOOR) && level.getBlockState(upper)
            .is(Blocks.OAK_DOOR),
            "both halves stand");
        helper.assertTrue(level.getBlockState(upper.above()).is(Blocks.STONE), "nothing above the door is touched");
        helper.assertTrue(BuildPlanner.complete(level, target), "the blueprint is complete");
        building.end(lay, Ending.DONE);
        building.site.tick(level);
        helper.assertTrue(building.site.complete(), "the built door is latched as complete");

        for (BlockPos half : List.of(lower, upper)) {
            level.setBlock(half, level.getBlockState(half).setValue(BlockStateProperties.OPEN, true), 2);
        }
        building.site.tick(level);
        helper.assertTrue(!BuildPlanner.complete(level, target),
            "the opened door differs from the confirmed closed state");
        helper.assertTrue(building.site.complete(), "opening a completed door does not undo its latched progress");

        SiteRig clearing = SiteRig.start(level, target(level, ordered(upper, AIR, lower, AIR)));
        helper.assertTrue(clearing.step(lower).after().keySet().containsAll(List.of(lower, upper)),
            "both halves come down in one step");
        Outcome taken = Commit.run(level, clearing.action(lower), worker);
        helper.assertTrue(taken instanceof Outcome.Done done && done.made().size() == 1
            && done.made().getFirst().is(Items.OAK_DOOR) && done.made().getFirst().getCount() == 1,
            "the worker gets exactly one door back");
        helper.assertTrue(level.getBlockState(lower).isAir() && level.getBlockState(upper).isAir(), "the door is gone");
        helper.assertTrue(loose(helper, lower).isEmpty(), "nothing drops on the ground");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aBedComesDownHeadFirstOrNotForOneBed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos foot = at(helper, 8, 1, 8);
        BlockPos head = foot.north();
        Worker worker = worker(helper, at(helper, 5, 1, 8));
        BlockState bed = Blocks.RED_BED.defaultBlockState();
        level.setBlock(foot, bed, 3);
        level.setBlock(head, bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), 3);

        SiteRig clearing = SiteRig.start(level, target(level, ordered(foot, AIR, head, AIR)));
        Outcome taken = Commit.run(level, clearing.action(head), worker);
        helper.assertTrue(taken instanceof Outcome.Done done && done.made().size() == 1
            && done.made().getFirst().is(Items.RED_BED), "the worker gets one bed");
        helper.assertTrue(loose(helper, foot).isEmpty(), "nothing drops on the ground");

        BuildTarget target = printed(level, ordered(foot, bed, head, bed.setValue(BlockStateProperties.BED_PART,
            BedPart.HEAD)));
        SiteRig building = SiteRig.start(level, target);
        BuildAction lay = building.action(foot);
        helper.assertValueEqual(lay.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("red_bed")), 1)),
                "one bed is asked for");
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Done, "the bed is laid");
        helper.assertTrue(BuildPlanner.complete(level, target), "both halves stand");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void placementAndCompletionUseTheSameConfirmedState(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos base = at(helper, 8, 1, 8);
        BlockState extended = Blocks.PISTON.defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.UP).setValue(BlockStateProperties.EXTENDED, true);
        BlockState head = Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
        BuildTarget piston = printed(level, ordered(base, extended, base.above(), head));
        helper.assertValueEqual(piston.cells().keySet(), Set.of(base), "only the piston is built, not its head");
        level.setBlock(base, extended, 2 | 16);
        level.setBlock(base.above(), head, 2 | 16);
        helper.assertTrue(!BuildPlanner.complete(level, piston), "the confirmed piston target is retracted");
        level.setBlock(base, piston.wanted(base), 2 | 16);
        helper.assertTrue(BuildPlanner.complete(level, piston),
            "the exact confirmed piston state satisfies the target");
        level.setBlock(base, Blocks.PISTON.defaultBlockState(), 2 | 16);
        helper.assertTrue(!BuildPlanner.complete(level, piston), "one facing the wrong way is not");

        BlockPos slab = at(helper, 12, 1, 8);
        BlockPos candles = at(helper, 14, 1, 8);
        SiteRig costs = SiteRig.start(level, target(level, ordered(
            slab, Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
            candles, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 3)
                .setValue(BlockStateProperties.LIT, true))));
        helper.assertValueEqual(costs.action(slab).spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("stone_slab")), 2)),
            "a double slab takes two slabs");
        helper.assertValueEqual(costs.action(candles).spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("candle")), 1)),
            "the cannon asks one candle for a cluster of three");

        BlockPos left = at(helper, 8, 1, 14);
        BlockState chest = Blocks.CHEST.defaultBlockState();
        BlockPos right = left.relative(ChestBlock.getConnectedDirection(
            chest.setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT)));
        BuildTarget chests = printed(level, ordered(left, chest.setValue(BlockStateProperties.CHEST_TYPE,
            ChestType.LEFT),
            right, chest.setValue(BlockStateProperties.CHEST_TYPE, ChestType.RIGHT)));
        Worker worker = worker(helper, at(helper, 8, 1, 11));
        SiteRig pairing = SiteRig.start(level, chests);
        for (BlockPos half : List.of(left, right)) {
            BuildAction layHalf = pairing.action(half);
            helper.assertValueEqual(layHalf.spec().needs(),
                List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("chest")), 1)),
                "each half is a shot of its own, one chest each");
            helper.assertTrue(Commit.run(level, layHalf, worker) instanceof Outcome.Done, "the half is laid");
        }
        helper.assertTrue(BuildPlanner.complete(level, chests), "both halves stand paired");
        SiteRig unpairing = SiteRig.start(level, target(level, ordered(right, AIR, left, AIR)));
        helper.assertTrue(Commit.run(level, unpairing.action(left), worker)
            instanceof Outcome.Done done && done.made().stream().mapToInt(ItemStack::getCount).sum() == 2,
            "taking it down gives both chests back");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aWaterloggedBlockWithNoWaterUnderItIsPlacedDryAsTheCannonPlacesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos cell = at(helper, 8, 1, 8);
        Worker worker = worker(helper, at(helper, 5, 1, 8));
        BlockState wet = Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        BuildTarget target = target(level, Map.of(cell, wet));

        SiteRig laying = SiteRig.start(level, target);
        BuildAction lay = laying.action(cell);
        helper.assertValueEqual(lay.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("stone_stairs")), 1)),
            "only the block is paid for: the cannon brings no water");
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Done, "the stairs are laid");
        laying.end(lay, io.github.izakyl.folkways.core.work.api.Ending.DONE);
        laying.site.tick(level);
        helper.assertTrue(!level.getBlockState(cell).getValue(BlockStateProperties.WATERLOGGED), "dry");
        helper.assertTrue(!laying.site.metCells().contains(cell),
            "the cell waits for its water, let in by a step of its own");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void theRightBlockPlacedWrongIsTurnedForNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos lower = at(helper, 8, 1, 8);
        Worker worker = worker(helper, at(helper, 5, 1, 8));
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        BlockState wrong = door.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT);
        level.setBlock(lower, wrong, 3);
        level.setBlock(lower.above(), wrong.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);

        BuildTarget target = printed(level, ordered(lower, door,
            lower.above(), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        SiteRig site = SiteRig.start(level, target);
        BuildAction turn = site.action(lower);
        helper.assertTrue(turn.spec().needs().isEmpty() && turn.spec().tools().isEmpty(), "turning needs nothing");
        helper.assertTrue(Commit.run(level, turn, worker) instanceof Outcome.Done done && done.made().isEmpty(),
            "and gives nothing");
        helper.assertTrue(BuildPlanner.complete(level, target), "the door hangs as the blueprint asks");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aKelpColumnComesDownWholeLeavingItsWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        for (int x = 10; x <= 14; x++) {
            for (int z = 10; z <= 14; z++) {
                boolean rim = x == 10 || x == 14 || z == 10 || z == 14;
                for (int y = 1; y <= 2; y++) {
                    level.setBlock(at(helper, x, y, z), rim ? STONE : Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
        BlockPos root = at(helper, 12, 1, 12);
        BlockPos tip = root.above();
        level.setBlock(root, Blocks.KELP_PLANT.defaultBlockState(), 2);
        level.setBlock(tip, Blocks.KELP.defaultBlockState(), 2);
        Worker worker = worker(helper, at(helper, 10, 3, 12));
        BuildTarget kelp = printed(level, ordered(root, Blocks.KELP_PLANT.defaultBlockState(),
            tip, Blocks.KELP.defaultBlockState()));
        helper.assertValueEqual(kelp.cells(), Map.of(tip, Blocks.KELP.defaultBlockState()),
            "the cannon prints the kelp tip, young, and skips the plant body that has no item");

        SiteRig clearing = SiteRig.start(level, target(level, ordered(tip, AIR, root, AIR)));
        helper.assertTrue(clearing.pieceAt(root) == clearing.pieceAt(tip), "it comes down as one");
        helper.assertTrue(Commit.run(level, clearing.action(root), worker) instanceof Outcome.Done done
            && done.made().stream().filter(stack -> stack.is(Items.KELP)).mapToInt(ItemStack::getCount).sum() == 2,
            "the worker gets both kelp back");
        helper.assertTrue(level.getBlockState(root).is(Blocks.WATER) && level.getBlockState(tip).is(Blocks.WATER),
            "the water it grew in is left");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aDripleafStemAndItsLeafAreShotsOfTheirOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos stem = at(helper, 8, 1, 16);
        BuildTarget target = printed(level, ordered(stem, Blocks.BIG_DRIPLEAF_STEM.defaultBlockState(),
            stem.above(), Blocks.BIG_DRIPLEAF.defaultBlockState()));
        SiteRig site = SiteRig.start(level, target);
        for (BlockPos cell : List.of(stem, stem.above())) {
            helper.assertValueEqual(site.action(cell).spec().needs(),
                List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("big_dripleaf")), 1)),
                "one dripleaf for each, as the cannon reckons it");
        }
        helper.assertTrue(site.pieceAt(stem) != site.pieceAt(stem.above()), "two pieces of work");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aCellIsRefusedAsUnknownOrAsHavingNoWayToBuild(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos dry = at(helper, 4, 1, 4);
        BlockPos cut = at(helper, 8, 1, 4);
        BlockPos fire = at(helper, 12, 1, 4);
        BlockPos modded = at(helper, 16, 1, 4);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        cells.put(dry, Blocks.SEAGRASS.defaultBlockState());
        cells.put(cut, Blocks.KELP_PLANT.defaultBlockState());
        cells.put(fire, Blocks.FIRE.defaultBlockState());
        BuildTarget target = new BuildTarget(WorldPos.of(level, dry).realm(), cells, Set.of(modded), BlockPos.ZERO);
        SiteRig site = SiteRig.start(level, target);
        helper.assertTrue(!site.site.refusals().containsKey(dry),
            "seagrass is placed as the cannon places it, water or not");
        helper.assertValueEqual(site.site.refusals().get(cut), BuildRefusal.NO_WAY_TO_BUILD,
            "a block with no item has no way to be built");
        helper.assertValueEqual(site.site.refusals().get(fire), BuildRefusal.NO_WAY_TO_BUILD,
            "a known block nothing knows how to lay has no way to be built");
        helper.assertValueEqual(site.site.refusals().get(modded), BuildRefusal.UNKNOWN_BLOCK,
            "a block from a mod this instance has not loaded is unknown");
        helper.assertValueEqual(site.site.pieces().size(), 1, "only the seagrass is asked for");
        helper.assertTrue(!site.site.complete(), "and the order is not done");

        level.setBlock(dry, Blocks.STONE.defaultBlockState(), 3);
        BuildTarget onlyUnloaded = new BuildTarget(WorldPos.of(level, dry).realm(),
            Map.of(dry, Blocks.STONE.defaultBlockState()), Set.of(modded), BlockPos.ZERO);
        helper.assertTrue(BuildPlanner.complete(level, onlyUnloaded), "a block no longer known is skipped, as the cannon skips it");
        helper.assertTrue(SiteRig.start(level, onlyUnloaded).site.complete(), "and does not hold the order open");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void comingUpShortFailsAndLosesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos cell = at(helper, 8, 1, 8);
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,
            SlabType.DOUBLE);
        Worker worker = worker(helper, at(helper, 8, 1, 5), new SimpleContainer(new ItemStack(Items.STONE_SLAB)));
        ItemSpec slabs = ItemSpec.of(ResourceLocation.withDefaultNamespace("stone_slab"));

        SiteRig site = SiteRig.start(level, target(level, Map.of(cell, slab)));
        BuildAction lay = site.action(cell);
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Failed(var noises, var why)
            && why == LaborRefusal.GOODS_GONE, "one slab for a double slab is goods gone, and the core notices");
        helper.assertTrue(level.getBlockState(cell).isAir(), "nothing is laid");
        helper.assertValueEqual(Goods.countIn(worker.pack(), slabs), 1L, "the slab that was taken goes back");
        helper.succeed();
    }

    /** What a colony was asked: the work submitted, the nodes withdrawn, and what it kept last. */
    private static final class Asked {
        final List<Grown> works = new ArrayList<>();
        final List<UUID> withdrawn = new ArrayList<>();
        final AtomicReference<CompoundTag> saved = new AtomicReference<>(new CompoundTag());
    }

    private static ColonyView view(ServerLevel level) {
        return new ColonyView() {
            public ServerLevel level() {
                return level;
            }

            public ResourceKey<Level> dimension() {
                return level.dimension();
            }

            public List<Claim> claims() {
                return List.of();
            }

            public Set<BlockPos> blocks(ResourceLocation owner) {
                return Set.of();
            }

            public List<Resident> residents() {
                return List.of();
            }

            public Effects effectsOf(Resident resident) {
                return Effects.NONE;
            }
        };
    }

    private static Colony colony(GameTestHelper helper, Asked asked) {
        ColonyView view = view(helper.getLevel());
        var inventory = new io.github.izakyl.folkways.core.inventory.internal.InventoryIndex(UUID.randomUUID());
        return Proxies.of(Colony.class, (proxy, method, args) -> switch (method.getName()) {
            case "kept" -> asked.saved.get();
            case "keep" -> {
                asked.saved.set(((CompoundTag) args[1]).copy());
                yield null;
            }
            case "submit" -> {
                asked.works.add((Grown) args[2]);
                yield null;
            }
            case "withdraw" -> {
                asked.withdrawn.add((UUID) args[1]);
                yield null;
            }
            case "inventory" -> inventory;
            case "claimAt" -> Optional.empty();
            case "views" -> List.of(view);
            case "view" -> view;
            default -> null;
        });
    }

    private static Blueprint blueprint(BlockPos... stones) {
        List<BlueprintBlock> blocks = new ArrayList<>();
        int wide = 1;
        for (BlockPos stone : stones) {
            blocks.add(new BlueprintBlock(stone, STONE));
            wide = Math.max(wide, stone.getX() + 1);
        }
        return new Blueprint(UUID.randomUUID(), "plan-test", blocks, BlueprintVolume.of(wide, 1, 1).orElseThrow(), 0);
    }

    private static List<ItemEntity> loose(GameTestHelper helper, BlockPos around) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(3.0D));
    }

    private static Map<BlockPos, BlockState> ordered(BlockPos first, BlockState firstState, BlockPos second,
            BlockState secondState) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        cells.put(first, firstState);
        cells.put(second, secondState);
        return cells;
    }

    /** An order's target as it really comes to be: the cells filed as a blueprint and resolved by the printer. */
    private static BuildTarget printed(ServerLevel level, Map<BlockPos, BlockState> cells) {
        BlockPos min = cells.keySet().stream().reduce(BlockPos::min).orElseThrow();
        BlockPos max = cells.keySet().stream().reduce(BlockPos::max).orElseThrow();
        List<BlueprintBlock> blocks = new ArrayList<>();
        cells.forEach((cell, state) -> blocks.add(new BlueprintBlock(cell.subtract(min), state)));
        Blueprint blueprint = new Blueprint(UUID.randomUUID(), "printed", blocks, BlueprintVolume.of(
            max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1).orElseThrow(), 0L);
        return BuildTarget.of(BlueprintBuildOrder.create(blueprint.id(), "printed", WorldPos.of(level, min),
            java.util.Optional.empty(), "", 0L), blueprint.confirm(level));
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static Mob walker(GameTestHelper helper, BlockPos feet) {
        Mob body = EntityType.PIG.create(helper.getLevel());
        body.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
        return body;
    }

    private static Worker worker(GameTestHelper helper, BlockPos feet) {
        return worker(helper, feet, new SimpleContainer(new ItemStack(Items.SCAFFOLDING, 64),
            new ItemStack(Items.STONE, 64), new ItemStack(Items.TORCH, 16), new ItemStack(Items.WATER_BUCKET),
            new ItemStack(Items.OAK_DOOR, 4), new ItemStack(Items.RED_BED, 2), new ItemStack(Items.CHEST, 2),
                new ItemStack(Items.STONE_STAIRS, 4),
            new ItemStack(Items.KELP, 4), new ItemStack(Items.BIG_DRIPLEAF, 4)));
    }

    private static Worker worker(GameTestHelper helper, BlockPos feet, Container pack) {
        Mob body = walker(helper, feet);
        return TestWorker.of(body, new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig")), pack).spill(stack -> { });
    }
}
