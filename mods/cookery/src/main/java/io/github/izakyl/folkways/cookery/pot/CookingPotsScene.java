package io.github.izakyl.folkways.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.PotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StockpotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry;
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
public final class CookingPotsScene {
    private CookingPotsScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_pots", "Pots and stockpots: real ingredients, heat and serving vessels");
        PonderScenery.openWorld(scene, util, 9);
        PonderScenery.grassGround(scene, util, 9);
        BlockPos pot = util.grid().at(2, 2, 2);
        BlockPos stockpot = util.grid().at(6, 2, 2);
        BlockPos store = util.grid().at(4, 1, 6);
        scene.world().setBlock(pot.below(), Blocks.CAMPFIRE.defaultBlockState(), false);
        scene.world().setBlock(stockpot.below(), Blocks.CAMPFIRE.defaultBlockState(), false);
        scene.world().setBlock(pot, ModBlocks.POT.get().defaultBlockState(), false);
        scene.world().setBlock(stockpot, ModBlocks.STOCKPOT.get().defaultBlockState(), false);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 3, 8), Direction.DOWN);
        scene.idle(15);
        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);

        scene.overlay().showControls(util.vector().topOf(pot), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showText(95).text("Register each pot or stockpot as a kitchen station, keep its heat source lit, and leave a reachable working position. Players' existing ingredients and dishes are left alone.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(4, 0, 4), 180);
        resident.walkTo(scene, util.vector().topOf(2, 0, 3));
        resident.hold(scene, new ItemStack(ModItems.OIL.get()));
        scene.world().modifyBlock(pot, state -> state.setValue(PotBlock.HAS_OIL, true).setValue(PotBlock.SHOW_OIL, true), false);
        scene.idle(20);
        resident.hold(scene, new ItemStack(Items.BEEF, 3));
        scene.overlay().showText(95).text("A stir-fry batch checks all its food, one oil and any serving vessels before loading. It then uses the real pot to add oil and ingredients; containers returned by those ingredients rejoin storage.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.hold(scene, new ItemStack(ModItems.KITCHEN_SHOVEL.get()));
        for (int stir = 0; stir < 3; stir++) scene.idle(20);
        scene.overlay().showText(95).text("The resident borrows a real kitchen shovel and performs the recipe's native stirs. Cooking runs on the block's own timer. A dish with serving vessels needs its complete vessel count; other products are taken with the shovel.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.hold(scene, new ItemStack(Items.COOKED_BEEF, 3));
        scene.world().modifyBlock(pot, state -> state.setValue(PotBlock.HAS_OIL, false).setValue(PotBlock.SHOW_OIL, false), false);
        scene.idle(30);
        resident.hold(scene, FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE).getDefaultInstance());
        scene.overlay().showOutline(PonderPalette.RED, pot, util.select().position(pot), 100);
        scene.overlay().showText(110).text("Stir-fry must be collected within its native 40-second window. It then burns, and after another 20 seconds becomes real charcoal. A long interruption can lose the requested dish: spent ingredients are not refunded and the original food is not recreated.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        resident.hold(scene, ItemStack.EMPTY);
        resident.walkTo(scene, util.vector().topOf(6, 0, 3));
        resident.hold(scene, new ItemStack(Items.WATER_BUCKET));
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.RICE_SEED.get(), 3));
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.STOCKPOT_LID.get()));
        scene.world().modifyBlock(stockpot, state -> state.setValue(StockpotBlock.HAS_LID, true), false);
        scene.overlay().showText(95).text("A stockpot batch checks its actual soup base, food, lid and vessels together. The resident fills the base, adds ingredients and closes the real lid; the native cooking timer then advances while heat and lid are present.")
            .pointAt(util.vector().topOf(stockpot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.hold(scene, new ItemStack(Items.BOWL, 3));
        scene.world().modifyBlock(stockpot, state -> state.setValue(StockpotBlock.HAS_LID, false), false);
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.COOKED_RICE.get(), 3));
        scene.overlay().showText(100).text("Open the stockpot and serve each actual portion with its required vessel. Three rice portions spend three bowls, not six. The same real lid can serve later batches, and the next batch begins only after the previous one is fully collected.")
            .pointAt(util.vector().topOf(stockpot)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        resident.walkTo(scene, util.vector().topOf(4, 0, 5));
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(100).text("Fixed recipes and flexible recipes use the native matching rules. Flexible dishes keep the quality calculated by the actual appliance. Accepted batches survive cancellation and reload; recovery takes the owned result rather than spending the ingredients again.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        resident.walkTo(scene, util.vector().topOf(2, 0, 3));
        resident.hold(scene, FoodBiteRegistry.getItem(FoodBiteRegistry.SUSPICIOUS_STIR_FRY).getDefaultInstance());
        scene.overlay().showOutline(PonderPalette.RED, pot, util.select().position(pot), 150);
        scene.overlay().showText(150).text("Normal orders use matching native recipes. Only an explicit Suspicious Stir-Fry or Dark Cuisine order chooses unmatched ingredients. Suspicious Stir-Fry is served after cooking; only a Dark Cuisine order waits for the real burnt state. An accidentally burnt normal dish does not complete its original order.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(80);
        resident.hold(scene, FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE).getDefaultInstance());
        scene.idle(80);
        scene.markAsFinished();
    }
}
