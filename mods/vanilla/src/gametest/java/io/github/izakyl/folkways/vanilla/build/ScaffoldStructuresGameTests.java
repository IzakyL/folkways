package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class ScaffoldStructuresGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ScaffoldStructuresGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 50)
    public static void horizontalDeckSurvivesTicksAndReturnsEveryMaterial(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = deck(helper, 6);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        Worker worker = worker();
        helper.assertTrue(node(level, Step.raise(cells), temporary).work(level, worker) instanceof Outcome.Done,
            "supported column and six horizontal cells can be built together");
        helper.startSequence().thenExecuteAfter(10, () -> {
            for (int distance = 0; distance <= 6; distance++) {
                var state = level.getBlockState(cells.get(2).east(distance));
                helper.assertTrue(state.is(Blocks.SCAFFOLDING)
                    && state.getValue(ScaffoldingBlock.DISTANCE) == distance,
                    "horizontal scaffolds retain their true support distance after physics ticks");
            }
            Outcome result = node(level, Step.lower(temporary.structures().getFirst().stream()
                .map(cell -> cell.block(level)).toList()), temporary).work(level, worker);
            helper.assertTrue(result instanceof Outcome.Done done
                && done.made().stream().mapToInt(ItemStack::getCount).sum() == cells.size(),
                "one connected structure returns every column and deck item");
            helper.assertTrue(temporary.isEmpty() && cells.stream().allMatch(cell -> level.getBlockState(cell).isAir()),
                "cleanup clears the whole connected structure");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void seventhHorizontalCellRejectsTheWholeRaise(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = deck(helper, 7);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        helper.assertTrue(node(level, Step.raise(cells), temporary).work(level, worker()) instanceof Outcome.Failed,
            "a seventh horizontal cell cannot survive vanilla scaffolding support rules");
        helper.assertTrue(temporary.isEmpty() && cells.stream().allMatch(cell -> level.getBlockState(cell).isAir()),
            "invalid support leaves no partial construction or ownership");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void obstructedDeckNeverOverwritesTheWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = deck(helper, 3);
        BlockPos obstacle = cells.getLast();
        level.setBlock(obstacle, Blocks.CHEST.defaultBlockState(), 3);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        helper.assertTrue(node(level, Step.raise(cells), temporary).work(level, worker()) instanceof Outcome.Failed,
            "a new obstruction invalidates the entire access structure");
        helper.assertTrue(level.getBlockState(obstacle).is(Blocks.CHEST) && level.getBlockState(cells.getFirst())
            .isAir(),
            "the chest and all earlier unbuilt cells remain untouched");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void cleanupPreservesReplacementAndOnlyReturnsRemainingScaffolds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = deck(helper, 3);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        node(level, Step.raise(cells), temporary).work(level, worker());
        BlockPos replacement = cells.getLast();
        level.setBlock(replacement, Blocks.CHEST.defaultBlockState(), 3);
        Outcome result = node(level, Step.lower(cells), temporary).work(level, worker());
        helper.assertTrue(result instanceof Outcome.Done done
            && done.made().stream().mapToInt(ItemStack::getCount).sum() == cells.size() - 1,
            "cleanup only refunds scaffold blocks it actually removes");
        helper.assertTrue(temporary.isEmpty() && level.getBlockState(replacement).is(Blocks.CHEST),
            "cleanup releases ownership while preserving a replacement block and its contents");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void committingCleanupAfterExternalRemovalStillClearsOwnership(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = deck(helper, 3);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        Worker worker = worker();
        helper.assertTrue(node(level, Step.raise(cells), temporary).commit(level, worker) instanceof Outcome.Done,
            "the structure is first built through the normal commit path");
        cells.forEach(cell -> level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3));
        Outcome result = node(level, Step.lower(cells), temporary).commit(level, worker);
        helper.assertTrue(result instanceof Outcome.Done done && done.made().isEmpty(),
            "externally removed scaffolds are never refunded a second time");
        helper.assertTrue(temporary.isEmpty(), "normal commit still clears stale scaffold ownership");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void committingAnOwnedHorizontalDeckRecognizesItsPhysicsStates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = deck(helper, 3);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        Worker worker = worker();
        ScaffoldAction raise = node(level, Step.raise(cells), temporary);
        helper.assertTrue(raise.commit(level, worker) instanceof Outcome.Done, "the first raise builds the deck");
        helper.assertTrue(level.getBlockState(cells.getLast()).getValue(ScaffoldingBlock.DISTANCE) == 3,
            "the final horizontal cell has its actual physics distance");
        helper.assertTrue(raise.commit(level, worker) instanceof Outcome.Done,
            "retrying an already owned deck recognizes completion without requiring distance zero");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void connectedDeckPersistsAsOneCleanupStructure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> cells = deck(helper, 4);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        temporary.added(cells.stream().map(cell -> WorldPos.of(level, cell)).toList());
        BlockPos detached = cells.getFirst().south(3);
        temporary.added(List.of(WorldPos.of(level, detached)));
        TemporaryScaffolds loaded = new TemporaryScaffolds(() -> { });
        loaded.load(Reader.of(temporary.save()));
        helper.assertTrue(loaded.structures().size() == 2 && loaded.structures().getFirst().size() == cells.size(),
            "saved cell records retain the whole six-direction connected deck and separate detached columns");
        helper.assertTrue(loaded.structures().getFirst().getFirst().cell().equals(cells.getFirst()),
            "cleanup ordering begins with the lowest support cell");
        helper.succeed();
    }

    private static List<BlockPos> deck(GameTestHelper helper, int length) {
        BlockPos base = helper.absolutePos(new BlockPos(5, 2, 5));
        helper.getLevel().setBlock(base.below(), Blocks.STONE.defaultBlockState(), 3);
        List<BlockPos> cells = new ArrayList<>(List.of(base, base.above(), base.above(2)));
        for (int distance = 1; distance <= length; distance++) {
            cells.add(base.above(2).east(distance));
        }
        return List.copyOf(cells);
    }

    private static ScaffoldAction node(ServerLevel level, Step step, TemporaryScaffolds temporary) {
        BuildTarget target = new BuildTarget(WorldPos.of(level, step.focus()).realm(), Map.of());
        return new ScaffoldAction(step.job(level, target, Stances.WHEREVER), temporary);
    }

    private static Worker worker() {
        return Proxies.of(Worker.class, (proxy, method, args) -> method.getName().equals("supplied") ? List.of() : null);
    }
}
