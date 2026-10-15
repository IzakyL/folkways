package io.github.izakyl.folkways.plugins.cookery.stove;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class HeatScene {
    private HeatScene() { }
    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_heat", "Kitchen heat: light, shelter and relight");
        PonderScenery.openWorld(scene, util, 7);
        PonderScenery.grassGround(scene, util, 7);
        BlockPos stove = util.grid().at(2, 1, 3);
        BlockPos pot = stove.above();
        BlockPos magma = util.grid().at(5, 1, 3);
        scene.world().setBlock(stove, ModBlocks.STOVE.get().defaultBlockState().setValue(StoveBlock.FACING, Direction.SOUTH), false);
        scene.world().setBlock(pot, ModBlocks.STOCKPOT.get().defaultBlockState(), false);
        scene.world().setBlock(magma, Blocks.MAGMA_BLOCK.defaultBlockState(), false);
        scene.world().setBlock(magma.above(), ModBlocks.STOCKPOT.get().defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 2, 6), Direction.DOWN);
        scene.idle(15);
        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);
        scene.overlay().showControls(util.vector().topOf(stove), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showText(80).text("Mark the stove as a kitchen appliance. The colony tends its ignition separately from the food cooking above it.")
            .pointAt(util.vector().centerOf(stove)).placeNearTarget().attachKeyFrame();
        scene.idle(90);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(1, 0, 5), 180);
        resident.hold(scene, new ItemStack(Items.FLINT_AND_STEEL));
        resident.walkTo(scene, util.vector().topOf(2, 0, 4));
        scene.overlay().showControls(util.vector().centerOf(stove), Pointing.UP, 50).rightClick().withItem(new ItemStack(Items.FLINT_AND_STEEL));
        scene.world().modifyBlock(stove, state -> state.setValue(StoveBlock.LIT, true), false);
        scene.overlay().showText(90).text("Lighting spends one flint-and-steel durability, or one fire charge. A native stove keeps burning without a coal slot or repeated fuel deliveries.")
            .pointAt(util.vector().centerOf(stove)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.overlay().showOutline(PonderPalette.GREEN, magma, util.select().fromTo(magma, magma.above()), 80);
        scene.overlay().showText(90).text("Cooking follows the appliance's native heat rules: lit heat sources, or blocks in its heat-source tag such as magma, can supply heat.")
            .pointAt(util.vector().centerOf(magma)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.world().modifyBlock(stove, state -> state.setValue(StoveBlock.LIT, false), false);
        scene.world().setBlock(util.grid().at(2, 4, 3), Blocks.WATER.defaultBlockState(), false);
        scene.overlay().showText(90).text("Rain or water can put the stove out. Residents wait for a dry, reachable stove instead of wasting ignition items repeatedly.")
            .pointAt(util.vector().topOf(stove)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.world().setBlock(util.grid().at(2, 4, 3), Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(2, 4, 3), Blocks.STONE_SLAB.defaultBlockState(), false);
        scene.world().modifyBlock(stove, state -> state.setValue(StoveBlock.LIT, true), false);
        scene.overlay().showText(90).text("Keep the interaction side and a standing place clear. Once the shelter is dry, the colony can relight the stove and cooking can continue.")
            .pointAt(util.vector().topOf(stove)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.markAsFinished();
    }
}
