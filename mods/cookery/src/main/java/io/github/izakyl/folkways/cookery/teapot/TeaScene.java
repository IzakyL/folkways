package io.github.izakyl.folkways.cookery.teapot;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.TeacupRegistry;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.cookery.stove.HeatScene;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class TeaScene {
    private TeaScene() { }
    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_teapot", "Brewing tea: a real bucket, a tea bag and twelve cups");
        PonderScenery.openWorld(scene, util, 7); PonderScenery.grassGround(scene, util, 7);
        BlockPos pot = util.grid().at(3, 2, 3); BlockPos cup = pot.east(); BlockPos store = util.grid().at(1, 1, 1);
        scene.world().setBlock(pot.below(), HeatScene.litStove(), false);
        scene.world().setBlock(pot, ModBlocks.TEAPOT.get().defaultBlockState(), false);
        scene.world().setBlock(cup.below(), Blocks.SMOOTH_STONE.defaultBlockState(), false);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 3, 6), Direction.DOWN); scene.idle(15);
        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);
        scene.overlay().showControls(util.vector().topOf(pot), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showOutline(PonderPalette.GREEN, pot, util.select().position(pot), 80);
        scene.overlay().showText(95).text("Mark a teapot and order the finished tea. Each native recipe brews twelve portions, so the plan also supplies twelve real empty cups and leaves an accessible place to serve them.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame(); scene.idle(105);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 5), 180);
        resident.lookAt(scene, util.vector().centerOf(pot));
        resident.hold(scene, new ItemStack(Items.WATER_BUCKET));
        scene.overlay().showText(95).text("Native filling transfers a real bucket of the recipe's fluid and returns its empty container. Water teas and milk tea use their actual different fluids; the pot is not filled by changing a display value.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame(); scene.idle(105);
        resident.hold(scene, new ItemStack(ModItems.BARLEY_TEA_BAG.get()));
        scene.overlay().showText(100).text("The actual ingredient then enters the pot. Its native grace period and brewing timer both require real heat. Removing heat pauses the same accepted batch, and reload does not charge another bag or bucket.")
            .pointAt(util.vector().centerOf(pot.below())).placeNearTarget().attachKeyFrame(); scene.idle(110);
        resident.lookAt(scene, util.vector().centerOf(cup));
        resident.hold(scene, new ItemStack(ModItems.TEAPOT.get()));
        scene.world().setBlock(pot, Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(cup, ModBlocks.EMPTY_CUP.get().defaultBlockState(), false);
        scene.overlay().showControls(util.vector().topOf(cup), Pointing.DOWN, 65).rightClick().withItem(new ItemStack(ModItems.TEAPOT.get()));
        scene.overlay().showText(100).text("After brewing finishes, the real filled pot is picked up. The resident places one of its own cups, pours one portion from the pot's actual stored result, and takes the real filled cup. Player cup positions are never overwritten.")
            .pointAt(util.vector().topOf(cup)).placeNearTarget().attachKeyFrame(); scene.idle(110);
        BlockItem tea = (BlockItem) TeacupRegistry.getItem(TeacupRegistry.BARLEY_TEA);
        scene.world().setBlock(cup, tea.getBlock().defaultBlockState(), false);
        resident.hold(scene, new ItemStack(tea));
        scene.overlay().showText(100).text("This repeats for all twelve actual portions. A failed serving transaction restores the finished pot and returns only its unspent cups; it never leaves extra filled cups while also refunding their containers.")
            .pointAt(util.vector().topOf(cup)).placeNearTarget().attachKeyFrame(); scene.idle(110);
        scene.world().setBlock(cup, Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(pot, ModBlocks.TEAPOT.get().defaultBlockState(), false);
        resident.hold(scene, new ItemStack(tea, 12)); resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        scene.overlay().showText(95).text("The one real, now-empty pot returns to its original station. The twelve filled cups and the empty fluid bucket enter logistics once; neither a duplicate pot nor twelve leftover empty cups are created.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame(); scene.idle(105);
        resident.walkTo(scene, util.vector().topOf(3, 0, 5));
        resident.lookAt(scene, util.vector().centerOf(pot));
        resident.hold(scene, TeacupRegistry.getItem(TeacupRegistry.MYSTERY_TEA).getDefaultInstance().copyWithCount(4));
        scene.overlay().showOutline(PonderPalette.RED, pot, util.select().position(pot), 145);
        scene.overlay().showText(145).text("Normal tea orders use matching native recipes. Only an explicit Mystery Tea order brews an unmatched ingredient: 200 ticks of input waiting, then 2,400 ticks of native brewing with heat. It yields four real cups instead of twelve, so prepare four empty cups.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame(); scene.idle(155); scene.markAsFinished();
    }
}
