package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuildPlanningGameTests {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuildPlanningGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
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
        helper.assertValueEqual(rig.asked.size(), 1, "the whole wall is asked for in one go");
        helper.assertValueEqual(rig.asked.getFirst().nodes().size(), 9, "every block of it");
        for (int x = 6; x <= 8; x++) {
            helper.assertTrue(rig.free(at(helper, x, 1, 8)), "the course on the ground waits for nothing");
            for (int y = 2; y <= 3; y++) {
                int below = at(helper, x, y - 1, 8).getY();
                helper.assertTrue(rig.after(at(helper, x, y, 8)).stream().anyMatch(cell -> cell.getY() == below),
                    "each course goes up after the one under it");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aHighCellIsLaidFromAScaffoldRaisedBesideItAndLoweredAfter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos goal = at(helper, 10, 11, 10);
        BuildTarget target = target(level, Map.of(goal, STONE));
        SiteRig rig = SiteRig.start(level, target);
        List<BuildJob.Kind> kinds = rig.asked.getFirst().nodes().stream()
            .map(node -> rig.site.pieces().stream().filter(piece -> piece.node().id().equals(node.id())).findFirst()
                .orElseThrow().work().step().orElseThrow().kind()).toList();
        helper.assertValueEqual(kinds, List.of(BuildJob.Kind.RAISE, BuildJob.Kind.LAY, BuildJob.Kind.LOWER),
            "a scaffold goes up, the cell is laid from it, and it comes down");
        BuildSite.Piece piece = rig.pieceAt(goal);
        helper.assertTrue(!piece.work().stances().isEmpty()
            && piece.work().stances().stream().allMatch(feet -> Reach.withinReach(feet, goal)),
            "the cell is laid from within reach, on the scaffold");
        UUID raise = rig.asked.getFirst().nodes().getFirst().id();
        UUID lower = rig.asked.getFirst().nodes().getLast().id();
        helper.assertTrue(rig.ordered(raise, rig.node(goal).id()) && rig.ordered(rig.node(goal).id(), lower),
            "the scaffold goes up first and comes down last");
        Worker worker = worker(helper, at(helper, 10, 1, 9));
        rig.workAll(helper, worker);
        helper.assertTrue(level.getBlockState(goal).is(Blocks.STONE), "the high cell is laid");
        helper.assertTrue(BlockPos.betweenClosedStream(at(helper, 7, 1, 7), at(helper, 13, 10, 13))
            .noneMatch(cell -> level.getBlockState(cell).is(Blocks.SCAFFOLDING)), "and the scaffold is down again");
        helper.assertTrue(rig.site.complete(), "and the order is done");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aCellMetIsMetForGoodAndTheOrderIsDoneWhenAllAre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos first = at(helper, 6, 1, 6);
        BlockPos second = at(helper, 12, 1, 12);
        SiteRig rig = SiteRig.start(level, target(level, Map.of(first, STONE, second, STONE)));
        UUID firstAsked = rig.node(first).id();
        helper.assertValueEqual(rig.site.left(), 2, "both cells are still to meet");

        level.setBlock(first, STONE, 3);
        rig.site.tick(level);
        helper.assertValueEqual(rig.site.left(), 1, "a cell someone else set is met");
        helper.assertTrue(rig.withdrawn.contains(firstAsked) && rig.at(first).isEmpty(),
            "and the work asked for it is withdrawn");

        level.setBlock(first, AIR, 3);
        rig.site.tick(level);
        helper.assertValueEqual(rig.site.left(), 1, "a cell met stays met when it is broken after");
        helper.assertTrue(!rig.site.complete(), "the other cell is still to do");
        helper.assertTrue(rig.work(helper, second, worker(helper, at(helper, 12, 1, 14))) instanceof Outcome.Done,
            "the other cell is laid");
        helper.assertTrue(rig.site.complete(), "every cell met, the order is done");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aPieceThatEndsUndoneIsAskedAgainAndAtLastRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos cell = at(helper, 8, 1, 8);
        SiteRig rig = SiteRig.start(level, target(level, Map.of(cell, STONE)));
        Node node = rig.node(cell);
        for (int tried = 1; tried < BuildSite.MOST_TRIES; tried++) {
            rig.end(node, new Ending.Failed(node.id(), BuildRefusal.OCCUPIED));
            rig.site.tick(level);
            helper.assertValueEqual(rig.asked.size(), tried + 1, "the piece is asked for again");
            helper.assertValueEqual(rig.asked.getLast().nodes().getFirst().id(), node.id(), "as the same piece");
        }
        rig.end(node, new Ending.Failed(node.id(), BuildRefusal.OCCUPIED));
        rig.site.tick(level);
        helper.assertTrue(rig.site.pieces().isEmpty(), "at last it is given up");
        helper.assertValueEqual(rig.site.refusals().get(cell), BuildRefusal.OCCUPIED, "and the board says why");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void onlyThreeCoursesAreAskedAheadAndEachLaidAsksTheNext(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Map<BlockPos, BlockState> column = new LinkedHashMap<>();
        for (int y = 1; y <= 4; y++) {
            column.put(at(helper, 8, y, 8), STONE);
        }
        SiteRig rig = SiteRig.start(level, target(level, column));
        helper.assertValueEqual(rig.asked.size(), 1, "the column is asked for in one go at first");
        helper.assertValueEqual(rig.asked.getFirst().nodes().size(), BuildSite.DEPTH, "but only three courses deep");
        helper.assertTrue(rig.at(at(helper, 8, 4, 8)).isEmpty(), "the fourth waits to be asked");
        BlockPos third = at(helper, 8, 3, 8);
        UUID thirdAsked = rig.node(third).id();
        helper.assertTrue(rig.work(helper, at(helper, 8, 1, 8), worker(helper, at(helper, 9, 1, 8)))
            instanceof Outcome.Done, "the first course is laid");
        helper.assertValueEqual(rig.asked.size(), 2, "laying it asks for more");
        Grown next = rig.asked.getLast();
        BlockPos fourth = at(helper, 8, 4, 8);
        helper.assertTrue(next.nodes().size() == 1 && rig.pieceAt(fourth).node().id().equals(next.nodes().getFirst().id()),
            "the fourth course, and only it");
        helper.assertTrue(next.follows().contains(new Before(thirdAsked, next.nodes().getFirst().id())),
            "it goes up after the third, asked before it");
        rig.workAll(helper, worker(helper, at(helper, 9, 1, 8)));
        helper.assertTrue(rig.site.complete(), "working what is asked as it comes builds the whole column");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aColumnTallerThanReachIsBuiltToTheTopFromAScaffold(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Map<BlockPos, BlockState> column = new LinkedHashMap<>();
        for (int y = 1; y <= 6; y++) {
            column.put(at(helper, 8, y, 8), STONE);
        }
        SiteRig rig = SiteRig.start(level, target(level, column));
        helper.assertTrue(rig.site.refusals().isEmpty(), "every course has somewhere to be laid from: "
            + rig.site.refusals());
        rig.workAll(helper, worker(helper, at(helper, 9, 1, 8)));
        helper.assertTrue(column.keySet().stream().allMatch(cell -> level.getBlockState(cell).is(Blocks.STONE)),
            "the column stands to the top");
        helper.assertTrue(BlockPos.betweenClosedStream(at(helper, 5, 1, 5), at(helper, 11, 8, 11))
            .noneMatch(cell -> level.getBlockState(cell).is(Blocks.SCAFFOLDING)), "with no scaffold left up");
        helper.assertTrue(rig.site.complete(), "and the order is done");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void fluidIsPouredFromABucketAndClearedForNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        Worker worker = worker(helper, at(helper, 3, 1, 3));

        BlockPos pour = at(helper, 6, 1, 6);
        SiteRig pouring = SiteRig.start(level, target(level, Map.of(pour, Blocks.WATER.defaultBlockState())));
        Node poured = pouring.node(pour);
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
        Node drain = draining.node(at(helper, 12, 1, 12));
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aLeftoverScaffoldIsTakenDownByItsOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos players = at(helper, 18, 1, 18);
        level.setBlock(players, ScaffoldNode.state(), 3);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        List<WorldPos> column = new ArrayList<>();
        for (int y = 1; y <= 3; y++) {
            BlockPos cell = at(helper, 10, y, 10);
            level.setBlock(cell, ScaffoldNode.state(), 3);
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
        helper.assertTrue(temporary.isEmpty() && column.stream().allMatch(cell -> level.getBlockState(cell.block(level)).isAir()),
            "nothing of the column is left");
        helper.assertTrue(level.getBlockState(players).is(Blocks.SCAFFOLDING), "a player's scaffolding is left alone");
        helper.assertTrue(rig.site.complete(), "the order has nothing left to do");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
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

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void cellsMetAreKeptWithTheOrderAcrossAReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos anchor = at(helper, 10, 1, 10);
        Asked asked = new Asked();
        Colony colony = colony(helper, asked);
        BuildPresence presence = new BuildPresence(colony, level.getServer());
        presence.file(level, blueprint(BlockPos.ZERO, new BlockPos(2, 0, 0)), anchor, "", Optional.empty(), "")
            .orElseThrow();
        presence.settle(level.getServer());
        helper.assertValueEqual(asked.works.getLast().nodes().size(), 2, "both cells are asked for");

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

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aDoorIsLaidAndTakenDownWholeForOneDoor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos lower = at(helper, 8, 1, 8);
        BlockPos upper = lower.above();
        Worker worker = worker(helper, at(helper, 5, 1, 8));
        BuildTarget target = target(level, Map.of(lower, Blocks.OAK_DOOR.defaultBlockState()));
        helper.assertTrue(target.cells().get(upper) != null && target.cells().get(upper).is(Blocks.OAK_DOOR),
            "the blueprint's door is read whole from its lower half");

        SiteRig building = SiteRig.start(level, target);
        helper.assertTrue(building.step(upper).after().keySet().containsAll(List.of(lower, upper)),
            "both halves are one step");
        Node lay = building.node(upper);
        helper.assertValueEqual(lay.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_door")), 1)), "one door is asked for");
        long doors = Goods.countIn(worker.pack(), ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_door")));
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Done, "the door is laid");
        helper.assertValueEqual(Goods.countIn(worker.pack(), ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_door"))),
            doors - 1, "one door is spent");
        helper.assertTrue(level.getBlockState(lower).is(Blocks.OAK_DOOR) && level.getBlockState(upper).is(Blocks.OAK_DOOR),
            "both halves stand");
        helper.assertTrue(BuildPlanner.complete(level, target), "the blueprint is complete");

        for (BlockPos half : List.of(lower, upper)) {
            level.setBlock(half, level.getBlockState(half).setValue(BlockStateProperties.OPEN, true), 2);
        }
        helper.assertTrue(BuildPlanner.complete(level, target), "an opened door is still the door the blueprint asked for");

        SiteRig clearing = SiteRig.start(level, target(level, ordered(upper, AIR, lower, AIR)));
        helper.assertTrue(clearing.step(lower).after().keySet().containsAll(List.of(lower, upper)),
            "both halves come down in one step");
        Outcome taken = Commit.run(level, clearing.node(lower), worker);
        helper.assertTrue(taken instanceof Outcome.Done done && done.made().size() == 1
            && done.made().getFirst().is(Items.OAK_DOOR) && done.made().getFirst().getCount() == 1,
            "the worker gets exactly one door back");
        helper.assertTrue(level.getBlockState(lower).isAir() && level.getBlockState(upper).isAir(), "the door is gone");
        helper.assertTrue(loose(helper, lower).isEmpty(), "nothing drops on the ground");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
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
        Outcome taken = Commit.run(level, clearing.node(head), worker);
        helper.assertTrue(taken instanceof Outcome.Done done && done.made().size() == 1
            && done.made().getFirst().is(Items.RED_BED), "the worker gets one bed");
        helper.assertTrue(loose(helper, foot).isEmpty(), "nothing drops on the ground");

        BuildTarget target = target(level, Map.of(head, bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
        SiteRig building = SiteRig.start(level, target);
        Node lay = building.node(foot);
        helper.assertValueEqual(lay.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("red_bed")), 1)), "one bed is asked for");
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Done, "the bed is laid");
        helper.assertTrue(BuildPlanner.complete(level, target), "both halves stand");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void onlyWhatPlacingDecidesIsHeldToTheBlueprint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos base = at(helper, 8, 1, 8);
        BlockState extended = Blocks.PISTON.defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.UP).setValue(BlockStateProperties.EXTENDED, true);
        BlockState head = Blocks.PISTON_HEAD.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
        BuildTarget piston = target(level, ordered(base, extended, base.above(), head));
        helper.assertValueEqual(piston.cells().keySet(), Set.of(base), "only the piston is built, not its head");
        level.setBlock(base, extended, 2 | 16);
        level.setBlock(base.above(), head, 2 | 16);
        helper.assertTrue(BuildPlanner.complete(level, piston), "a pushed-out piston is the piston asked for");
        level.setBlock(base, Blocks.PISTON.defaultBlockState(), 2 | 16);
        helper.assertTrue(!BuildPlanner.complete(level, piston), "one facing the wrong way is not");

        BlockPos slab = at(helper, 12, 1, 8);
        BlockPos candles = at(helper, 14, 1, 8);
        SiteRig costs = SiteRig.start(level, target(level, ordered(
            slab, Blocks.STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE),
            candles, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 3)
                .setValue(BlockStateProperties.LIT, true))));
        helper.assertValueEqual(costs.node(slab).spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("stone_slab")), 2)),
            "a double slab takes two slabs");
        helper.assertValueEqual(costs.node(candles).spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("candle")), 3)),
            "three candles take three");

        BlockPos left = at(helper, 8, 1, 14);
        BlockState chest = Blocks.CHEST.defaultBlockState();
        BlockPos right = left.relative(ChestBlock.getConnectedDirection(chest.setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT)));
        BuildTarget chests = target(level, Map.of(left, chest.setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT)));
        helper.assertValueEqual(chests.cells().get(right), chest.setValue(BlockStateProperties.CHEST_TYPE, ChestType.RIGHT),
            "a double chest is read whole from one half");
        Worker worker = worker(helper, at(helper, 8, 1, 11));
        SiteRig pairing = SiteRig.start(level, chests);
        Node layPair = pairing.node(right);
        helper.assertValueEqual(layPair.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("chest")), 2)), "both halves take two chests");
        helper.assertTrue(Commit.run(level, layPair, worker) instanceof Outcome.Done, "the double chest is laid");
        helper.assertTrue(BuildPlanner.complete(level, chests), "both halves stand paired");
        SiteRig unpairing = SiteRig.start(level, target(level, ordered(right, AIR, left, AIR)));
        helper.assertTrue(Commit.run(level, unpairing.node(left), worker)
            instanceof Outcome.Done done && done.made().stream().mapToInt(ItemStack::getCount).sum() == 2,
            "taking it down gives both chests back");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aWaterloggedBlockIsLaidDryThenPoured(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos cell = at(helper, 8, 1, 8);
        Worker worker = worker(helper, at(helper, 5, 1, 8));
        BlockState wet = Blocks.STONE_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        BuildTarget target = target(level, Map.of(cell, wet));

        SiteRig laying = SiteRig.start(level, target);
        Node lay = laying.node(cell);
        helper.assertValueEqual(lay.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("stone_stairs")), 1)),
            "the block itself is laid first");
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Done, "the stairs are laid");
        helper.assertTrue(!level.getBlockState(cell).getValue(BlockStateProperties.WATERLOGGED), "dry");
        helper.assertTrue(!BuildPlanner.complete(level, target), "dry stairs are not the wet stairs asked for");

        SiteRig pouring = SiteRig.start(level, target);
        helper.assertValueEqual(pouring.step(cell).kind(), BuildJob.Kind.POUR, "then the water");
        Node pour = pouring.node(cell);
        helper.assertValueEqual(pour.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("water_bucket")), 1)), "from a bucket");
        helper.assertTrue(Commit.run(level, pour, worker) instanceof Outcome.Done done
            && done.made().stream().anyMatch(stack -> stack.is(Items.BUCKET)), "the empty bucket comes back");
        helper.assertTrue(BuildPlanner.complete(level, target), "the stairs hold water");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void theRightBlockPlacedWrongIsTurnedForNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos lower = at(helper, 8, 1, 8);
        Worker worker = worker(helper, at(helper, 5, 1, 8));
        BlockState door = Blocks.OAK_DOOR.defaultBlockState();
        BlockState wrong = door.setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT);
        level.setBlock(lower, wrong, 3);
        level.setBlock(lower.above(), wrong.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);

        BuildTarget target = target(level, Map.of(lower, door));
        SiteRig site = SiteRig.start(level, target);
        Node turn = site.node(lower);
        helper.assertTrue(turn.spec().needs().isEmpty() && turn.spec().tools().isEmpty(), "turning needs nothing");
        helper.assertTrue(Commit.run(level, turn, worker) instanceof Outcome.Done done && done.made().isEmpty(),
            "and gives nothing");
        helper.assertTrue(BuildPlanner.complete(level, target), "the door hangs as the blueprint asks");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aKelpColumnGoesUpAndComesDownWholeLeavingItsWater(GameTestHelper helper) {
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
        Worker worker = worker(helper, at(helper, 10, 3, 12));
        BuildTarget target = target(level, ordered(root, Blocks.KELP_PLANT.defaultBlockState(), tip, Blocks.KELP.defaultBlockState()));

        SiteRig laying = SiteRig.start(level, target);
        helper.assertTrue(laying.pieceAt(root) == laying.pieceAt(tip), "the whole column is one piece");
        Node lay = laying.node(tip);
        helper.assertValueEqual(lay.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("kelp")), 2)), "one kelp for each cell");
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Done, "the column is laid");
        helper.assertTrue(BuildPlanner.complete(level, target), "body below, tip above");

        SiteRig clearing = SiteRig.start(level, target(level, ordered(tip, AIR, root, AIR)));
        helper.assertTrue(clearing.pieceAt(root) == clearing.pieceAt(tip), "it comes down as one");
        helper.assertTrue(Commit.run(level, clearing.node(root), worker) instanceof Outcome.Done done
            && done.made().stream().filter(stack -> stack.is(Items.KELP)).mapToInt(ItemStack::getCount).sum() == 2,
            "the worker gets both kelp back");
        helper.assertTrue(level.getBlockState(root).is(Blocks.WATER) && level.getBlockState(tip).is(Blocks.WATER),
            "the water it grew in is left");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aDripleafStemGoesUpWithItsLeaf(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos stem = at(helper, 8, 1, 16);
        level.setBlock(stem.below(), Blocks.DIRT.defaultBlockState(), 3);
        Worker worker = worker(helper, at(helper, 5, 1, 16));
        BuildTarget target = target(level, ordered(stem, Blocks.BIG_DRIPLEAF_STEM.defaultBlockState(),
            stem.above(), Blocks.BIG_DRIPLEAF.defaultBlockState()));

        SiteRig site = SiteRig.start(level, target);
        Node lay = site.node(stem);
        helper.assertValueEqual(lay.spec().needs(),
            List.of(new Need(ItemSpec.of(ResourceLocation.withDefaultNamespace("big_dripleaf")), 2)),
            "a stem and its leaf take two dripleaves");
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Done, "a stem that needs its leaf goes up with it");
        helper.assertTrue(BuildPlanner.complete(level, target), "both stand");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
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
        helper.assertValueEqual(site.site.refusals().get(dry), BuildRefusal.NO_WAY_TO_BUILD, "seagrass waits for water");
        helper.assertValueEqual(site.site.refusals().get(cut), BuildRefusal.NO_WAY_TO_BUILD,
            "a column cut short of its tip cannot be laid");
        helper.assertValueEqual(site.site.refusals().get(fire), BuildRefusal.NO_WAY_TO_BUILD,
            "a known block nothing knows how to lay has no way to be built");
        helper.assertValueEqual(site.site.refusals().get(modded), BuildRefusal.UNKNOWN_BLOCK,
            "a block from a mod this instance has not loaded is unknown");
        helper.assertTrue(site.site.pieces().isEmpty(), "none of them is asked for");
        helper.assertTrue(!site.site.complete(), "and the order is not done");

        level.setBlock(dry, Blocks.STONE.defaultBlockState(), 3);
        BuildTarget onlyUnloaded = new BuildTarget(WorldPos.of(level, dry).realm(),
            Map.of(dry, Blocks.STONE.defaultBlockState()), Set.of(modded), BlockPos.ZERO);
        helper.assertTrue(!BuildPlanner.complete(level, onlyUnloaded), "an order waiting on an unloaded block is not done");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void comingUpShortFailsAndLosesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper);
        BlockPos left = at(helper, 8, 1, 8);
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.CHEST_TYPE, ChestType.LEFT);
        Worker worker = worker(helper, at(helper, 8, 1, 5));
        ItemSpec chests = ItemSpec.of(ResourceLocation.withDefaultNamespace("chest"));
        Containers.extract(worker.pack(), stack -> Goods.matches(stack, chests), 1);

        SiteRig site = SiteRig.start(level, target(level, Map.of(left, chest)));
        Node lay = site.node(left);
        helper.assertTrue(Commit.run(level, lay, worker) instanceof Outcome.Failed(var noises, var why)
            && why == LaborRefusal.GOODS_GONE, "one chest for a double chest is goods gone, and the core notices");
        helper.assertTrue(level.getBlockState(left).isAir(), "nothing is laid");
        helper.assertValueEqual(Goods.countIn(worker.pack(), chests), 1L, "the chest that was taken goes back");
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

            public List<Holding> holdings() {
                return List.of();
            }

            public Set<BlockPos> blocks(ResourceLocation owner) {
                return Set.of();
            }

            public List<Resident> residents() {
                return List.of();
            }

            public int rankOf(Resident resident, String perk) {
                return 0;
            }
        };
    }

    private static Colony colony(GameTestHelper helper, Asked asked) {
        ColonyView view = view(helper.getLevel());
        return (Colony) Proxy.newProxyInstance(BuildPlanningGameTests.class.getClassLoader(),
            new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
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

    private static BuildTarget target(ServerLevel level, Map<BlockPos, BlockState> cells) {
        return new BuildTarget(WorldPos.of(level, cells.keySet().iterator().next()).realm(), cells);
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 1; x <= 21; x++) {
            for (int z = 1; z <= 21; z++) {
                helper.getLevel().setBlock(at(helper, x, 0, z), STONE, 3);
            }
        }
    }

    private static Mob walker(GameTestHelper helper, BlockPos feet) {
        Mob body = EntityType.PIG.create(helper.getLevel());
        body.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
        return body;
    }

    private static Worker worker(GameTestHelper helper, BlockPos feet) {
        Mob body = walker(helper, feet);
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.SCAFFOLDING, 64),
            new ItemStack(Items.STONE, 64), new ItemStack(Items.TORCH, 16), new ItemStack(Items.WATER_BUCKET),
            new ItemStack(Items.OAK_DOOR, 4), new ItemStack(Items.RED_BED, 2), new ItemStack(Items.CHEST, 2), new ItemStack(Items.STONE_STAIRS, 4),
            new ItemStack(Items.KELP, 4), new ItemStack(Items.BIG_DRIPLEAF, 4));
        return new Worker() {
            public Resident resident() {
                return new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig"));
            }

            public Mob body() {
                return body;
            }

            public Container pack() {
                return pack;
            }

            public List<Store> within() {
                return List.of();
            }

            public ItemStack held(ToolNeed need) {
                return ItemStack.EMPTY;
            }

            public void spill(ItemStack stack) {
            }

            public int rankOf(String perk) {
                return 0;
            }

            public List<Node> route() {
                return List.of();
            }

            public Placement placement() {
                return Placement.NOWHERE;
            }
        };
    }
}
