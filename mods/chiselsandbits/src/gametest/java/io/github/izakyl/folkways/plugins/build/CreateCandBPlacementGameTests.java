package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Commission;
import io.github.izakyl.folkways.plugins.build.draft.Draft;
import io.github.izakyl.folkways.plugins.build.draft.Drafting;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.World;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
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
import org.slf4j.LoggerFactory;

@GameTestHolder("folkways_create_candb")
@PrefixGameTestTemplate(false)
public final class CreateCandBPlacementGameTests {

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void createPrintingKeepsEveryCloisterDraftVoxel(GameTestHelper helper) {
        helper.assertValueEqual(SchematicPlacement.adapter().getClass().getName(),
            "io.github.izakyl.folkways.create.build.CreateSchematicPlacement", "the real Create adapter is installed");
        var pattern = Patterns.find(ResourceLocation.parse("folkways_chiselsandbits:gothic_cloister")).orElseThrow();
        CompoundTag settings = new CompoundTag();
        for (var setting : pattern.knobs().settings()) {
            if (setting instanceof Schema.Setting.Items items) {
                settings.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                    .map(filter -> ItemSpec.of(filter.id())).toList()).save());
            }
        }
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
        int states = 0;
        int blocks = 0;
        int voxels = 0;
        int payloads = 0;
        for (var cell : draft.cells()) {
            BlockPos at = anchor.offset(cell.offset());
            boolean changed = false;
            if (!helper.getLevel().getBlockState(at).equals(cell.state())) {
                states++;
                changed = true;
            }
            if (cell.payload().present()) {
                payloads++;
                var expected = CandBStructureCodec.decode(cell.payload().tag());
                var entity = helper.getLevel().getBlockEntity(at);
                var actual = entity instanceof ChiseledBlockEntity bits ? bits.payload().storage() : null;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            if (actual == null || !expected.getBlockInformation(x, y, z)
                                    .equals(actual.getBlockInformation(x, y, z))) {
                                voxels++;
                                changed = true;
                            }
                        }
                    }
                }
            }
            if (changed) {
                blocks++;
            }
        }
        LoggerFactory.getLogger("folkways-patterns").info(
            "CREATE_CANDB stage={} cells={} payloads={} wrongStates={} wrongBlocks={} wrongVoxels={}",
            stage, draft.cells().size(), payloads, states, blocks, voxels);
        helper.assertTrue(states == 0 && blocks == 0 && voxels == 0,
            stage + ": wrongStates=" + states + " wrongBlocks=" + blocks + " wrongVoxels=" + voxels);
    }
}
