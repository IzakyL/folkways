package io.github.izakyl.folkways.vanilla.cookery.stove;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class HeatScene {
    private HeatScene() { }
    public static BlockState litStove() {
        return ModBlocks.STOVE.get().defaultBlockState().setValue(StoveBlock.FACING, Direction.SOUTH)
            .setValue(StoveBlock.LIT, true);
    }

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
        scene.overlay().showText(80).text("Take in the stove as a kitchen station. The colony keeps it lit; the pot on top does the cooking.")
            .pointAt(util.vector().centerOf(stove)).placeNearTarget().attachKeyFrame();
        scene.idle(90);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(1, 0, 5), 180);
        resident.hold(scene, new ItemStack(Items.FLINT_AND_STEEL));
        resident.walkTo(scene, util.vector().topOf(2, 0, 4));
        resident.lookAt(scene, util.vector().centerOf(stove));
        scene.overlay().showControls(util.vector().centerOf(stove), Pointing.UP, 50).rightClick().withItem(new ItemStack(Items.FLINT_AND_STEEL));
        scene.world().modifyBlock(stove, state -> state.setValue(StoveBlock.LIT, true), false);
        scene.overlay().showText(90).text("Lighting uses one flint and steel use or one fire charge. A lit stove needs no more fuel.")
            .pointAt(util.vector().centerOf(stove)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.overlay().showOutline(PonderPalette.GREEN, magma, util.select().fromTo(magma, magma.above()), 80);
        scene.overlay().showText(90).text("Any lit heat source works, and so do heat blocks such as magma.")
            .pointAt(util.vector().centerOf(magma)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.world().modifyBlock(stove, state -> state.setValue(StoveBlock.LIT, false), false);
        scene.world().setBlock(util.grid().at(2, 4, 3), Blocks.WATER.defaultBlockState(), false);
        scene.world().showSection(util.select().position(util.grid().at(2, 4, 3)), Direction.DOWN);
        scene.overlay().showText(90).text("Rain or water can put the stove out. Residents wait until it is dry instead of wasting fire starters.")
            .pointAt(util.vector().topOf(stove)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.world().setBlock(util.grid().at(2, 4, 3), Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(2, 4, 3), Blocks.STONE_SLAB.defaultBlockState(), false);
        scene.world().modifyBlock(stove, state -> state.setValue(StoveBlock.LIT, true), false);
        scene.overlay().showText(90).text("Keep the stove's front and a standing spot clear. Once it is dry under shelter, residents relight it.")
            .pointAt(util.vector().topOf(stove)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.markAsFinished();
    }
}
