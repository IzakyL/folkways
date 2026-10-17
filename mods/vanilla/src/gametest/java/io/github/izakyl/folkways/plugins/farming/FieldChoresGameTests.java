package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FieldChoresGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FieldChoresGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void changingTheCropWithdrawsTheOldChoresAndSubmitsTheNewOnes(GameTestHelper helper)
            throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        for (BlockPos floor : BlockPos.betweenClosed(origin, origin.offset(4, 0, 4))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
        }
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int x = 1; x <= 3; x++) {
            BlockPos cell = origin.offset(x, 0, 2);
            level.setBlockAndUpdate(cell, Blocks.FARMLAND.defaultBlockState());
            cells.add(cell);
        }
        ZoneView zone = zone(level, cells);

        Set<UUID> live = new LinkedHashSet<>();
        Set<UUID> withdrawn = new HashSet<>();
        Colony colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(),
            new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
                case "submit" -> { live.add(idOf((Grown) args[2])); yield null; }
                case "withdraw" -> { withdrawn.add((UUID) args[1]); yield null; }
                default -> throw new AssertionError(method.getName());
            });
        Asking asking = new Asking(colony, FarmingContent.ID, () -> { });

        List<Grown> wheat = sweep(level, zone, cells, "wheat", 1);
        asking.offer(Map.of(level.dimension(), wheat));
        Set<UUID> wheatIds = idsOf(wheat);
        helper.assertTrue(!wheat.isEmpty() && live.containsAll(wheatIds), "wheat chores must be submitted");
        helper.assertTrue(cellsOf(wheat).equals(cells), "wheat chores must cover every cell: " + cellsOf(wheat));

        List<Grown> carrots = sweep(level, zone, cells, "carrots", 2);
        asking.offer(Map.of(level.dimension(), carrots));
        Set<UUID> carrotIds = idsOf(carrots);
        helper.assertTrue(carrotIds.stream().noneMatch(wheatIds::contains),
            "a crop change must not reuse the old crop's chore ids");
        helper.assertTrue(withdrawn.containsAll(wheatIds), "the wheat chores must be withdrawn");
        helper.assertTrue(live.containsAll(carrotIds) && asking.live().containsAll(carrotIds),
            "the carrot chores must be submitted");
        helper.assertTrue(cellsOf(carrots).equals(cells), "carrot chores must cover every cell: " + cellsOf(carrots));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aGrassyFieldIsPlannedWithoutChangingTheWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        for (BlockPos floor : BlockPos.betweenClosed(origin, origin.offset(5, 0, 2))) {
            place(level, floor, Blocks.DIRT.defaultBlockState());
            place(level, floor.above(), Blocks.AIR.defaultBlockState());
            place(level, floor.above(2), Blocks.AIR.defaultBlockState());
        }
        List<Block> grass = List.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN);
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int i = 0; i < grass.size(); i++) {
            BlockPos ground = origin.offset(i + 1, 0, 1);
            Block block = grass.get(i);
            place(level, ground.above(), block.defaultBlockState());
            if (block instanceof DoublePlantBlock) {
                place(level, ground.above(2), block.defaultBlockState()
                    .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
            }
            cells.add(ground);
        }
        List<Grown> goals = sweep(level, zone(level, cells), cells, "wheat", 1);
        Set<BlockPos> tilled = new LinkedHashSet<>();
        goals.forEach(goal -> goal.nodes().forEach(node -> {
            helper.assertTrue(node instanceof TillNode, "grass-covered dirt needs tilling before sowing");
            tilled.add(node.spec().focus().orElseThrow());
        }));
        helper.assertTrue(tilled.equals(cells), "every grass-covered cell needs a tilling job: " + tilled);
        int i = 0;
        for (BlockPos ground : cells) {
            helper.assertTrue(level.getBlockState(ground).is(Blocks.DIRT), "planning does not turn the soil");
            helper.assertTrue(level.getBlockState(ground.above()).is(grass.get(i++)),
                "planning does not remove the grass");
        }
        helper.succeed();
    }

    // Farmland stops short of a full block. The middle of a wide field has nothing but farmland round it, and must
    // still offer somewhere to stand: sown from the edge alone, it would be left bare once the edge was done.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theMiddleOfAWideFieldIsSownToo(GameTestHelper helper) throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(origin, origin.offset(6, 0, 6))) {
            level.setBlockAndUpdate(cell, Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(cell.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(cell.above(2), Blocks.AIR.defaultBlockState());
            cells.add(cell.immutable());
        }

        Set<BlockPos> covered = cellsOf(sweep(level, zone(level, cells), cells, "wheat", 1));
        helper.assertTrue(covered.contains(origin.offset(3, 0, 3)),
            "the middle of a 7x7 field must be sown, got " + covered.size() + " of " + cells.size() + " cells");
        helper.assertTrue(covered.equals(cells), "every cell of the field must be sown: " + covered);
        helper.succeed();
    }

    // A melon grows beside its vine: vines on the edge of the plot set fruit outside it, where nobody harvests. The
    // vines keep a cell in, and the ring round them is left for the fruit.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void melonVinesKeepACellInFromTheEdge(GameTestHelper helper) throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        Set<BlockPos> cells = new LinkedHashSet<>();
        Set<BlockPos> inner = new LinkedHashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(origin, origin.offset(4, 0, 3))) {
            level.setBlockAndUpdate(cell, Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(cell.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(cell.above(2), Blocks.AIR.defaultBlockState());
            cells.add(cell.immutable());
            int x = cell.getX() - origin.getX();
            int z = cell.getZ() - origin.getZ();
            if (x > 0 && x < 4 && z > 0 && z < 3) {
                inner.add(cell.immutable());
            }
        }

        Set<BlockPos> sown = cellsOf(sweep(level, zone(level, cells), cells, "melon_stem", 1));
        helper.assertTrue(sown.equals(inner), "melons are sown a cell in from the edge, got " + sown);
        helper.succeed();
    }

    // Bamboo is a stalk nobody walks through. A plot of it is sown two rows at a time with an aisle between, and
    // never in the cell the sower stands in: a plot sown wall to wall shut in whoever stood in it.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void bambooIsSownWithAislesToWalkOutBy(GameTestHelper helper) throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(origin, origin.offset(5, 0, 7))) {
            level.setBlockAndUpdate(cell, Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(cell.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(cell.above(2), Blocks.AIR.defaultBlockState());
            cells.add(cell.immutable());
        }

        List<Job> jobs = jobsOf(sweep(level, zone(level, cells), cells, "bamboo", 1));
        Set<BlockPos> sown = new LinkedHashSet<>();
        for (Job job : jobs) {
            sown.addAll(job.cells());
            for (BlockPos ground : job.cells()) {
                helper.assertFalse(job.footings().cells().contains(WorldPos.of(level, ground.above())),
                    "nobody sows bamboo where they stand: " + ground);
            }
        }
        for (BlockPos cell : cells) {
            boolean aisle = (cell.getX() - origin.getX()) % 3 == 1;
            helper.assertTrue(sown.contains(cell) != aisle,
                (aisle ? "an aisle is left bare: " : "a row beside an aisle is sown: ") + cell);
        }

        Set<BlockPos> sugar = new LinkedHashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(origin, origin.offset(2, 0, 0))) {
            sugar.add(cell.immutable());
        }
        level.setBlockAndUpdate(origin.offset(0, 0, -1), Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(1, 0, -1), Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(origin.offset(2, 0, -1), Blocks.WATER.defaultBlockState());
        Set<BlockPos> cane = cellsOf(sweep(level, zone(level, sugar), sugar, "sugar_cane", 2));
        helper.assertTrue(cane.equals(sugar), "cane is walked through and wants no aisle, got " + cane);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aFelledTreeTakesTheLeavesItHoldsUpAndNoOthers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (BlockPos floor : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 0, 0)),
                helper.absolutePos(new BlockPos(6, 0, 4)))) {
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        List<BlockPos> trunk = new ArrayList<>();
        for (int y = 1; y <= 4; y++) {
            BlockPos log = helper.absolutePos(new BlockPos(2, y, 2));
            place(level, log, Blocks.OAK_LOG.defaultBlockState());
            trunk.add(log);
        }
        for (int y = 1; y <= 3; y++) {
            place(level, helper.absolutePos(new BlockPos(5, y, 2)), Blocks.OAK_LOG.defaultBlockState());
        }
        Set<BlockPos> own = new LinkedHashSet<>();
        for (BlockPos at : List.of(new BlockPos(1, 3, 2), new BlockPos(3, 3, 2), new BlockPos(2, 3, 1),
                new BlockPos(2, 3, 3), new BlockPos(2, 5, 2))) {
            own.add(leaf(level, helper.absolutePos(at), 1, false));
        }
        own.add(leaf(level, helper.absolutePos(new BlockPos(1, 3, 1)), 2, false));
        BlockPos placed = leaf(level, helper.absolutePos(new BlockPos(1, 4, 2)), 1, true);
        BlockPos neighbours = leaf(level, helper.absolutePos(new BlockPos(4, 3, 2)), 1, false);

        Set<BlockPos> canopy = new LinkedHashSet<>(new Habit.Tree(2, 9).canopy(level, trunk));
        helper.assertTrue(canopy.equals(own), "the canopy is the tree's own leaves, got " + canopy);
        helper.assertFalse(canopy.contains(placed), "a placed leaf belongs to no tree");
        helper.assertFalse(canopy.contains(neighbours), "a leaf nearer another tree's log is that tree's");
        helper.succeed();
    }

    // A dark oak grows from four saplings in a square and from no fewer: a wood lot of them is sown a square at a
    // time, each square one piece of work, the squares apart from each other, and a square half sown is finished.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void darkOakIsSownFourToASquare(GameTestHelper helper) throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(origin, origin.offset(5, 0, 1))) {
            level.setBlockAndUpdate(cell, Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(cell.above(), Blocks.AIR.defaultBlockState());
            cells.add(cell.immutable());
        }
        Set<BlockPos> squares = new LinkedHashSet<>();
        for (int x : List.of(0, 1, 4, 5)) {
            squares.add(origin.offset(x, 0, 0));
            squares.add(origin.offset(x, 0, 1));
        }

        List<Job> sown = jobsOf(sweep(level, zone(level, cells), cells, "dark_oak_sapling", 1));
        helper.assertTrue(sown.size() == 2, "a square is one piece of work, got " + sown.size());
        Set<BlockPos> covered = new LinkedHashSet<>();
        for (Job job : sown) {
            helper.assertTrue(job.cells().size() == 4, "a square is sown four saplings at once: " + job.cells());
            covered.addAll(job.cells());
        }
        helper.assertTrue(covered.equals(squares), "two squares of four, two cells apart, got " + covered);

        Set<BlockPos> strip = new LinkedHashSet<>();
        for (int x = 0; x <= 5; x++) {
            strip.add(origin.offset(x, 0, 0));
        }
        List<Job> alone = jobsOf(sweep(level, zone(level, strip), strip, "dark_oak_sapling", 2));
        helper.assertTrue(alone.isEmpty(), "a plot one cell wide holds no square, got " + alone);

        BlockPos last = origin.offset(1, 0, 1);
        for (BlockPos cell : squares) {
            if (!cell.equals(last)) {
                level.setBlockAndUpdate(cell.above(), Blocks.DARK_OAK_SAPLING.defaultBlockState());
            }
        }
        List<Job> left = jobsOf(sweep(level, zone(level, cells), cells, "dark_oak_sapling", 3));
        helper.assertTrue(left.size() == 1 && left.getFirst().cells().equals(List.of(last)),
            "a square short of a sapling wants that one alone, got " + left);

        Crop crop = Crop.of(level, origin, ResourceLocation.withDefaultNamespace("dark_oak_sapling")).orElseThrow();
        helper.assertTrue(crop.roomAt(level, crop.standAt(origin)), "the sower finds room beside its own square");

        level.setBlockAndUpdate(last.above(), Blocks.DARK_OAK_SAPLING.defaultBlockState());
        List<Job> done = jobsOf(sweep(level, zone(level, cells), cells, "dark_oak_sapling", 4));
        helper.assertTrue(done.isEmpty(), "full squares want nothing more, got " + done);
        helper.succeed();
    }

    // Jungle and spruce grow alone or four to a square, and a plot says which: the two are different crops with different
    // chores, the square one sown like dark oak.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void jungleIsSownEitherWay(GameTestHelper helper) throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(origin, origin.offset(1, 0, 1))) {
            level.setBlockAndUpdate(cell, Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(cell.above(), Blocks.AIR.defaultBlockState());
            cells.add(cell.immutable());
        }
        helper.assertTrue(Crop.sowable().contains(ResourceLocation.withDefaultNamespace("jungle_sapling/square")),
            "the square way of jungle is a crop to choose");
        helper.assertTrue(jobsOf(sweep(level, zone(level, cells), cells, "spruce_sapling/square", 3))
            .getFirst().cells().size() == 4, "spruce is sown in a square the same way");
        helper.assertFalse(Crop.of(level, origin, ResourceLocation.withDefaultNamespace("oak_sapling/square"))
            .isPresent(), "oak grows no way but alone");

        List<Grown> alone = sweep(level, zone(level, cells), cells, "jungle_sapling", 1);
        List<Grown> square = sweep(level, zone(level, cells), cells, "jungle_sapling/square", 2);
        helper.assertTrue(jobsOf(alone).size() == 1 && jobsOf(alone).getFirst().cells().size() == 1,
            "jungle alone is a sapling to itself, with room round it: " + jobsOf(alone));
        helper.assertTrue(jobsOf(square).size() == 1
                && new LinkedHashSet<>(jobsOf(square).getFirst().cells()).equals(cells),
            "jungle in a square is four saplings at once: " + jobsOf(square));
        helper.assertTrue(idsOf(alone).stream().noneMatch(idsOf(square)::contains),
            "the two ways do not share chore ids");
        helper.succeed();
    }

    // A tree is felled whole: taller than the room a sapling asks for, and out along its boughs.
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aTallTreeIsFelledToTheTop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(3, 1, 3));
        Set<BlockPos> tree = new LinkedHashSet<>();
        for (int up = 1; up <= 24; up++) {
            tree.add(ground.above(up));
        }
        for (int out = 1; out <= 5; out++) {
            tree.add(ground.offset(out, 20 + out, 0));
        }
        BlockPos apart = ground.offset(3, 2, 3);
        try {
            tree.forEach(log -> place(level, log, Blocks.JUNGLE_LOG.defaultBlockState()));
            place(level, apart, Blocks.JUNGLE_LOG.defaultBlockState());
            Crop crop = Crop.of(level, ground, ResourceLocation.withDefaultNamespace("jungle_sapling"))
                .orElseThrow();
            Set<BlockPos> felled = new LinkedHashSet<>(crop.harvestFrom(level, ground));
            helper.assertTrue(felled.equals(tree), "every log joined to the trunk, got " + felled.size()
                + " of " + tree.size());
        } finally {
            tree.forEach(log -> place(level, log, Blocks.AIR.defaultBlockState()));
            place(level, apart, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    // A big tree is slower to fell than a small one, but nothing like as many times slower as it is bigger.
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void workGrowsSlowerThanWhatThereIsOfIt(GameTestHelper helper) {
        int unit = FarmingContent.FELL_TICKS_PER_LOG;
        helper.assertTrue(FarmingContent.ticksFor(unit, 1) == unit, "one log takes a log's ticks");
        helper.assertTrue(FarmingContent.ticksFor(unit, 3) == 2 * unit, "each doubling takes as many again");
        int small = FarmingContent.ticksFor(unit, 8);
        int great = FarmingContent.ticksFor(unit, 160);
        helper.assertTrue(great > small && great < 3 * small,
            "twenty times the tree is not three times the work: " + small + " then " + great);
        helper.succeed();
    }

    private static void place(ServerLevel level, BlockPos at, BlockState state) {
        level.setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private static BlockPos leaf(ServerLevel level, BlockPos at, int distance, boolean persistent) {
        place(level, at, Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.DISTANCE, distance).setValue(LeavesBlock.PERSISTENT, persistent));
        return at;
    }

    private static List<Grown> sweep(ServerLevel level, ZoneView zone, Set<BlockPos> cells, String crop,
            long stamp) {
        Crop growing = Crop.of(level, cells.iterator().next(), ResourceLocation.withDefaultNamespace(crop))
            .orElseThrow();
        Field field = new Field(growing, Tillage.Clearing.ALL, WorldPos.of(level, cells.iterator().next()), () -> { }, new PastDay());
        field.scan(level, zone, growing, stamp);
        field.settle(stamp);
        List<Grown> goals = new ArrayList<>();
        field.chores().forEach(chores -> chores.goals(level, goals));
        return goals;
    }

    private static ZoneView zone(ServerLevel level, Set<BlockPos> cells) {
        UUID id = UUID.randomUUID();
        return new ZoneView() {
            @Override
            public UUID id() {
                return id;
            }

            @Override
            public ResourceLocation delegation() {
                return FarmingContent.PLOT;
            }

            @Override
            public ResourceKey<Level> dimension() {
                return level.dimension();
            }

            @Override
            public WorldPos at(BlockPos cell) {
                return WorldPos.of(level, cell);
            }

            @Override
            public Set<BlockPos> cells() {
                return cells;
            }

            @Override
            public Settings settings() {
                throw new AssertionError("the field reads its crop from the sweep");
            }
        };
    }

    private static Set<UUID> idsOf(List<Grown> goals) {
        Set<UUID> ids = new LinkedHashSet<>();
        goals.forEach(goal -> ids.add(idOf(goal)));
        return ids;
    }

    private static Set<BlockPos> cellsOf(List<Grown> goals) throws ReflectiveOperationException {
        var held = FarmNode.class.getDeclaredField("chore");
        held.setAccessible(true);
        Set<BlockPos> covered = new LinkedHashSet<>();
        for (Grown goal : goals) {
            for (Node cell : goal.nodes()) {
                covered.add(((FarmNode.Chore) held.get(cell)).job().cell());
            }
        }
        return covered;
    }

    private static List<Job> jobsOf(List<Grown> goals) throws ReflectiveOperationException {
        var held = FarmNode.class.getDeclaredField("chore");
        held.setAccessible(true);
        List<Job> jobs = new ArrayList<>();
        for (Grown goal : goals) {
            for (Node cell : goal.nodes()) {
                jobs.add(((FarmNode.Chore) held.get(cell)).job());
            }
        }
        return jobs;
    }

    private static UUID idOf(Grown goal) {
        return goal.nodes().getFirst().id();
    }
}
