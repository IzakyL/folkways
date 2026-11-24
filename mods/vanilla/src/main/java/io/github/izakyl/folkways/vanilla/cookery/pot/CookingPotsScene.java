package io.github.izakyl.folkways.vanilla.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.PotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StockpotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class CookingPotsScene {
    private CookingPotsScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_pots", "Pots and stockpots: ingredients, heat and bowls");
        PonderScenery.openWorld(scene, util, 9);
        PonderScenery.grassGround(scene, util, 9);
        BlockPos pot = util.grid().at(2, 2, 2);
        BlockPos stockpot = util.grid().at(6, 2, 2);
        BlockPos store = util.grid().at(4, 1, 6);
        scene.world().setBlock(pot.below(), HeatScene.litStove(), false);
        scene.world().setBlock(stockpot.below(), HeatScene.litStove(), false);
        scene.world().setBlock(pot, ModBlocks.POT.get().defaultBlockState().setValue(PotBlock.FACING, Direction.SOUTH), false);
        scene.world().setBlock(stockpot, ModBlocks.STOCKPOT.get().defaultBlockState(), false);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 3, 8), Direction.DOWN);
        scene.idle(15);
        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);

        scene.overlay().showControls(util.vector().topOf(pot), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showText(95).text("Take in each pot as a kitchen station and keep its heat lit. Leave the stir-fry pot's handle side clear; residents stand there. Food you put in yourself is left alone.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(4, 0, 4), 180);
        resident.walkTo(scene, util.vector().topOf(2, 0, 3));
        resident.lookAt(scene, util.vector().centerOf(pot));
        resident.hold(scene, new ItemStack(ModItems.OIL.get()));
        scene.world().modifyBlock(pot, state -> state.setValue(PotBlock.HAS_OIL, true).setValue(PotBlock.SHOW_OIL, true), false);
        scene.idle(20);
        resident.hold(scene, new ItemStack(Items.BEEF, 3));
        scene.overlay().showText(95).text("A stir-fry first checks it has all the food, one oil and any bowls. Then the resident adds the oil and ingredients.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.hold(scene, new ItemStack(ModItems.KITCHEN_SHOVEL.get()));
        for (int stir = 0; stir < 3; stir++) scene.idle(20);
        scene.overlay().showText(95).text("The resident borrows a kitchen shovel and stirs as the recipe asks. Dishes that need bowls wait for all of them; others come out with the shovel.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.hold(scene, new ItemStack(Items.COOKED_BEEF, 3));
        scene.world().modifyBlock(pot, state -> state.setValue(PotBlock.HAS_OIL, false).setValue(PotBlock.SHOW_OIL, false), false);
        scene.idle(30);
        resident.hold(scene, FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE).getDefaultInstance());
        scene.overlay().showOutline(PonderPalette.RED, pot, util.select().position(pot), 100);
        scene.overlay().showText(110).text("Collect stir-fry within 40 seconds or it burns; 20 seconds later it turns to charcoal. Burnt ingredients are not refunded.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        resident.hold(scene, ItemStack.EMPTY);
        resident.walkTo(scene, util.vector().topOf(6, 0, 3));
        resident.lookAt(scene, util.vector().centerOf(stockpot));
        resident.hold(scene, new ItemStack(Items.WATER_BUCKET));
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.RICE_SEED.get(), 3));
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.STOCKPOT_LID.get()));
        scene.world().modifyBlock(stockpot, state -> state.setValue(StockpotBlock.HAS_LID, true), false);
        scene.overlay().showText(95).text("A stockpot checks the soup base, food, lid and bowls first. The resident pours the base, adds the ingredients and closes the lid. It cooks only with heat and the lid on.")
            .pointAt(util.vector().topOf(stockpot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.hold(scene, new ItemStack(Items.BOWL, 3));
        scene.world().modifyBlock(stockpot, state -> state.setValue(StockpotBlock.HAS_LID, false), false);
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.COOKED_RICE.get(), 3));
        scene.overlay().showText(100).text("Open the lid and serve each portion into its bowl: three rice portions take three bowls. The next batch starts once the pot is empty.")
            .pointAt(util.vector().topOf(stockpot)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        resident.walkTo(scene, util.vector().topOf(4, 0, 5));
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(100).text("Recipes match as they do in Cookery, and flexible dishes keep their quality. A cancelled or reloaded batch is recovered without spending ingredients again.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        resident.walkTo(scene, util.vector().topOf(2, 0, 3));
        resident.lookAt(scene, util.vector().centerOf(pot));
        resident.hold(scene, FoodBiteRegistry.getItem(FoodBiteRegistry.SUSPICIOUS_STIR_FRY).getDefaultInstance());
        scene.overlay().showOutline(PonderPalette.RED, pot, util.select().position(pot), 150);
        scene.overlay().showText(150).text("Normal orders use matching recipes. Only a Suspicious Stir-Fry or Dark Cuisine order uses unmatched ingredients, and only Dark Cuisine waits for burning. A normal dish that burns does not fill its order.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(80);
        resident.hold(scene, FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE).getDefaultInstance());
        scene.idle(80);
        scene.markAsFinished();
    }
}
