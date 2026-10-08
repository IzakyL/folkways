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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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
        Field field = new Field(growing, WorldPos.of(level, cells.iterator().next()), () -> { }, new PastDay());
        field.scan(level, zone, growing, stamp);
        field.settle(stamp, 0);
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

    private static UUID idOf(Grown goal) {
        return goal.nodes().getFirst().id();
    }
}
