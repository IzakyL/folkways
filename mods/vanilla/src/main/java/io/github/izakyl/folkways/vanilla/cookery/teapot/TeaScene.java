package io.github.izakyl.folkways.vanilla.cookery.teapot;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.TeacupRegistry;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.vanilla.cookery.stove.HeatScene;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
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
        scene.title("cookery_teapot", "Tea: a bucket, a tea bag and twelve cups");
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
        scene.overlay().showText(95).text("Take in a teapot and order the finished tea. Each brew makes twelve cups, so supply twelve empty cups and room to set them out.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame(); scene.idle(105);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 5), 180);
        resident.lookAt(scene, util.vector().centerOf(pot));
        resident.hold(scene, new ItemStack(Items.WATER_BUCKET));
        scene.overlay().showText(95).text("The pot is filled with a bucket of the recipe's liquid: water for most teas, milk for milk tea. The empty bucket goes back.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame(); scene.idle(105);
        resident.hold(scene, new ItemStack(ModItems.BARLEY_TEA_BAG.get()));
        scene.overlay().showText(100).text("Then the tea goes in. Brewing needs heat; without it, it pauses. Reloading never spends another bag or bucket.")
            .pointAt(util.vector().centerOf(pot.below())).placeNearTarget().attachKeyFrame(); scene.idle(110);
        resident.lookAt(scene, util.vector().centerOf(cup));
        resident.hold(scene, new ItemStack(ModItems.TEAPOT.get()));
        scene.world().setBlock(pot, Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(cup, ModBlocks.EMPTY_CUP.get().defaultBlockState(), false);
        scene.overlay().showControls(util.vector().topOf(cup), Pointing.DOWN, 65).rightClick().withItem(new ItemStack(ModItems.TEAPOT.get()));
        scene.overlay().showText(100).text("Once brewed, the resident picks up the pot, sets out a cup, pours one and takes it. Cups you placed are left alone.")
            .pointAt(util.vector().topOf(cup)).placeNearTarget().attachKeyFrame(); scene.idle(110);
        BlockItem tea = (BlockItem) TeacupRegistry.getItem(TeacupRegistry.BARLEY_TEA);
        scene.world().setBlock(cup, tea.getBlock().defaultBlockState(), false);
        resident.hold(scene, new ItemStack(tea));
        scene.overlay().showText(100).text("This repeats for all twelve. If serving fails, the pot is restored and only unused cups go back.")
            .pointAt(util.vector().topOf(cup)).placeNearTarget().attachKeyFrame(); scene.idle(110);
        scene.world().setBlock(cup, Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(pot, ModBlocks.TEAPOT.get().defaultBlockState(), false);
        resident.hold(scene, new ItemStack(tea, 12)); resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        scene.overlay().showText(95).text("The empty pot goes back on its station. The twelve cups and the empty bucket go to storage once.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame(); scene.idle(105);
        resident.walkTo(scene, util.vector().topOf(3, 0, 5));
        resident.lookAt(scene, util.vector().centerOf(pot));
        resident.hold(scene, TeacupRegistry.getItem(TeacupRegistry.MYSTERY_TEA).getDefaultInstance().copyWithCount(4));
        scene.overlay().showOutline(PonderPalette.RED, pot, util.select().position(pot), 145);
        scene.overlay().showText(145).text("Normal orders use matching recipes. Only a Mystery Tea order uses unmatched ingredients. It brews longer and makes four cups, not twelve, so prepare four.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame(); scene.idle(155); scene.markAsFinished();
    }
}
