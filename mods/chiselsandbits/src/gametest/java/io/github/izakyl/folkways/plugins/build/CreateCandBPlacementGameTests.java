package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Commission;
import io.github.izakyl.folkways.plugins.build.draft.Draft;
import io.github.izakyl.folkways.plugins.build.draft.Drafting;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.PatternChecks;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.World;
import java.util.List;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_create_candb")
@PrefixGameTestTemplate(false)
public final class CreateCandBPlacementGameTests {

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void createPrintingKeepsEveryCloisterDraftVoxel(GameTestHelper helper) {
        helper.assertValueEqual(SchematicPlacement.adapter().getClass().getName(),
            "io.github.izakyl.folkways.create.build.CreateSchematicPlacement", "the real Create adapter is installed");
        var pattern = Patterns.find(ResourceLocation.parse("folkways_chiselsandbits:gothic_cloister")).orElseThrow();
        CompoundTag settings = PatternChecks.defaults(pattern);
        settings.putInt("width", 5);
        var hint = new Hint.Path(List.of(new BlockPos(156, 76, -172), new BlockPos(184, 76, -172)));
        var drawn = Drafting.draw(pattern, new Commission(hint, Chosen.load(pattern.knobs(), settings),
            World.NONE, Direction.NORTH, 73)).join();
        helper.assertTrue(drawn instanceof Drawn.Ready, "the asymmetric multi-cell cloister draws");
        Draft draft = ((Drawn.Ready) drawn).draft();
        var level = helper.getLevel();
        Blueprint blueprint = Rounds.blueprintOf(level, "create-cloister", draft).orElseThrow().confirm(level);
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 2, 2));
        var raised = SchematicPlacement.print(level, blueprint, anchor);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(raised.isDone(), "the real printing queue finishes");
            raised.join();
        }).thenExecute(() -> compare(helper, draft, anchor, "queue-finished"))
            .thenIdle(40).thenExecute(() -> compare(helper, draft, anchor, "after-40-ticks")).thenSucceed();
    }

    private static void compare(GameTestHelper helper, Draft draft, BlockPos anchor, String stage) {
        for (var cell : draft.cells()) {
            BlockPos at = anchor.offset(cell.offset());
            helper.assertValueEqual(helper.getLevel().getBlockState(at), cell.state(), stage + ": state at " + at);
            if (cell.payload().present()) {
                helper.assertTrue(helper.getLevel().getBlockEntity(at) instanceof ChiseledBlockEntity,
                    stage + ": carving at " + at);
                CandBTestData.sameVoxels(helper, CandBStructureCodec.decode(cell.payload().tag()),
                    ((ChiseledBlockEntity) helper.getLevel().getBlockEntity(at)).payload().storage());
            }
        }
    }
}
