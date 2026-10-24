package io.github.izakyl.folkways.plugins.groundwork.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Box;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Capability;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.Cell;
import io.github.izakyl.folkways.plugins.groundwork.plan.Construction.State;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Exercises real Level#setBlock notifications; never invokes the change hook directly. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConstructionSnapshotGameTests {
    private ConstructionSnapshotGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ConstructionSnapshotGameTests.class);
    }

    private record Fixture(ConstructionSnapshot.Captured captured, BlockPos target, BlockPos noise) {
    }

    private static Cell cell(BlockPos at) {
        return new Cell(at.getX(), at.getY(), at.getZ());
    }

    private static Construction.Budget steps(int maximum) {
        return new Construction.Budget() {
            private int used;
            public boolean tryStep() { if (exhausted()) return false; used++; return true; }
            public boolean exhausted() { return used >= maximum; }
            public int used() { return used; }
        };
    }

    private static Fixture fixture(GameTestHelper helper) {
        for (BlockPos at : BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(8, 7, 8))) {
            boolean air = at.getX() >= 1 && at.getX() <= 7 && at.getZ() >= 1 && at.getZ() <= 7
                && at.getY() >= 1 && at.getY() <= 6;
            helper.setBlock(at, air ? Blocks.AIR : Blocks.STONE);
        }
        BlockPos target = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos noise = helper.absolutePos(new BlockPos(6, 0, 6));
        helper.getLevel().setBlock(noise, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), Block.UPDATE_ALL);
        Cell start = cell(helper.absolutePos(new BlockPos(2, 1, 2)));
        Box bounds = new Box(cell(helper.absolutePos(BlockPos.ZERO)), cell(helper.absolutePos(new BlockPos(8, 7, 8))));
        try (var capture = new ConstructionSnapshot.Capture(helper.getLevel(), bounds, List.of(cell(target)), Set.of(),
                Set.of(start), start, 1, new Capability(0.6D, 1.8D, 1, 3, 4.5D), Blocks.DIRT.defaultBlockState())) {
            for (int calls = 0; calls < 10000 && capture.status() == ConstructionSnapshot.Status.PENDING; calls++) {
                capture.advance(steps(1));
            }
            helper.assertTrue(capture.status() == ConstructionSnapshot.Status.READY, "one-step capture must finish: " + capture.reason());
            return new Fixture(capture.result().orElseThrow(), target, noise);
        }
    }

    private static void finishValidation(GameTestHelper helper, ConstructionSnapshot.Validation validation) {
        for (int calls = 0; calls < 10000 && validation.status() == ConstructionSnapshot.Status.PENDING; calls++) {
            validation.advance(steps(1));
        }
        helper.assertTrue(validation.status() == ConstructionSnapshot.Status.VALID, "coherent live validation: " + validation.reason());
        helper.assertTrue(validation.validNow(), "permission is fresh in this actual server tick");
    }

    @GameTest(template = "empty_large", batch = "construction_snapshot")
    public static void unchangedEvidenceCanRenewAfterARealWorkerWindup(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        var p = fixture.captured().problem();
        var validation = fixture.captured().validate(helper.getLevel(), p.initialState(), ConstructionReference.wholeDomain(p));
        finishValidation(helper, validation);
        helper.runAfterDelay(12, () -> {
            try (validation) {
                helper.assertTrue(!validation.validNow(), "the old tick alone is never permission");
                validation.advance(steps(1));
                helper.assertTrue(validation.validNow(), "unchanged evidence renews without restarting the entire world pass");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty_large", batch = "construction_snapshot")
    public static void moistureChurnIsRecheckedWithoutDiscardingEquivalentBarrier(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        var p = fixture.captured().problem();
        try (var validation = fixture.captured().validate(helper.getLevel(), p.initialState(), ConstructionReference.wholeDomain(p))) {
            finishValidation(helper, validation);
            helper.getLevel().setBlock(fixture.noise(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 3), Block.UPDATE_ALL);
            helper.assertTrue(!validation.validNow(), "a dirty dependency needs rechecking even for a known equivalent property");
            validation.advance(steps(1));
            finishValidation(helper, validation);
            helper.succeed();
        }
    }

    @GameTest(template = "empty_large", batch = "construction_snapshot")
    public static void realNewObstacleRevokesTheOldPermission(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        var p = fixture.captured().problem();
        try (var validation = fixture.captured().validate(helper.getLevel(), p.initialState(), ConstructionReference.wholeDomain(p))) {
            finishValidation(helper, validation);
            helper.getLevel().setBlock(fixture.target(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(!validation.validNow(), "new world terrain synchronously marks the proof dirty");
            validation.advance(steps(8192));
            helper.assertTrue(validation.status() == ConstructionSnapshot.Status.INVALID && !validation.validNow(), "the altered target must invalidate the old model");
            helper.succeed();
        }
    }

    @GameTest(template = "empty_large", batch = "construction_snapshot")
    public static void unknownBlockReplacementCannotBorrowTheMoistureException(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        var p = fixture.captured().problem();
        try (var validation = fixture.captured().validate(helper.getLevel(), p.initialState(), ConstructionReference.wholeDomain(p))) {
            finishValidation(helper, validation);
            helper.getLevel().setBlock(fixture.noise(), Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
            validation.advance(steps(8192));
            helper.assertTrue(validation.status() == ConstructionSnapshot.Status.INVALID, "two UNKNOWN projections are not interchangeable block behavior");
            helper.succeed();
        }
    }

    @GameTest(template = "empty_large", batch = "construction_snapshot")
    public static void anExecutedTargetMustStillMatchTheExactMaterialReceipt(GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        var p = fixture.captured().problem();
        helper.getLevel().setBlock(fixture.target(), Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        State executed = new State(Set.of(cell(fixture.target())), p.start(), 1);
        try (var validation = fixture.captured().validate(helper.getLevel(), executed, ConstructionReference.wholeDomain(p))) {
            finishValidation(helper, validation);
            helper.getLevel().setBlock(fixture.target(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            validation.advance(steps(8192));
            helper.assertTrue(validation.status() == ConstructionSnapshot.Status.INVALID, "another FULL block cannot impersonate this project's material result");
            helper.succeed();
        }
    }
}
