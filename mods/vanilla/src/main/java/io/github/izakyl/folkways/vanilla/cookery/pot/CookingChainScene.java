package io.github.izakyl.folkways.vanilla.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.MillstoneBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.NinePart;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StockpotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.vanilla.cookery.millstone.MillstoneScene;
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
public final class CookingChainScene {
    private CookingChainScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_chain", "One order, every step: wheat to noodles");
        PonderScenery.openWorld(scene, util, 9);
        PonderScenery.grassGround(scene, util, 9);
        BlockPos mill = util.grid().at(2, 1, 2);
        BlockPos table = util.grid().at(6, 1, 1);
        BlockPos board = util.grid().at(7, 1, 4);
        BlockPos stockpot = util.grid().at(6, 2, 7);
        BlockPos store = util.grid().at(1, 1, 7);
        for (NinePart part : NinePart.values()) {
            scene.world().setBlock(mill.offset(part.getPosX(), 0, part.getPosY()), ModBlocks.MILLSTONE.get().defaultBlockState()
                .setValue(MillstoneBlock.PART, part).setValue(MillstoneBlock.FACING, Direction.NORTH), false);
        }
        scene.world().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        scene.world().setBlock(board, ModBlocks.CHOPPING_BOARD.get().defaultBlockState(), false);
        scene.world().setBlock(stockpot.below(), HeatScene.litStove(), false);
        scene.world().setBlock(stockpot, ModBlocks.STOCKPOT.get().defaultBlockState(), false);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 3, 8), Direction.DOWN);
        scene.idle(15);
        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);

        scene.overlay().showControls(util.vector().topOf(stockpot), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showText(105).text("Order only the final dish, such as udon, and take in every station along the way. The colony plans the flour, dough and noodles itself.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(115);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(4, 0, 2), 90);
        resident.hold(scene, new ItemStack(Items.WHEAT));
        scene.overlay().showOutline(PonderPalette.GREEN, mill, util.select().fromTo(0, 0, 0, 4, 1, 4), 100);
        scene.overlay().showText(220).text("The millstone grinds wheat into flour. Leave room to walk around it and a clear way out.")
            .pointAt(util.vector().topOf(mill)).placeNearTarget().attachKeyFrame();
        scene.idle(15);
        resident.hold(scene, ItemStack.EMPTY);
        MillstoneScene.drive(scene, mill, resident, 90, 200);
        resident.walkTo(scene, util.vector().topOf(5, 0, 2));
        resident.hold(scene, new ItemStack(ModItems.FLOUR.get()));
        scene.idle(20);
        resident.walkTo(scene, util.vector().topOf(6, 0, 2));
        resident.lookAt(scene, util.vector().centerOf(table));
        resident.hold(scene, new ItemStack(Items.WATER_BUCKET));
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.RAW_DOUGH.get()));
        scene.overlay().showOutline(PonderPalette.GREEN, table, util.select().position(table), 90);
        scene.overlay().showText(95).text("At the crafting table, flour and a water bucket make dough. The empty bucket goes back to storage.")
            .pointAt(util.vector().topOf(table)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.walkTo(scene, util.vector().topOf(6, 0, 4));
        resident.lookAt(scene, util.vector().centerOf(board));
        resident.hold(scene, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        scene.idle(40);
        resident.hold(scene, new ItemStack(ModItems.RAW_NOODLES.get(), 3));
        scene.overlay().showOutline(PonderPalette.GREEN, board, util.select().position(board), 90);
        scene.overlay().showText(95).text("A borrowed kitchen knife cuts the dough into three noodles on the chopping board. Then the knife goes back.")
            .pointAt(util.vector().topOf(board)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.walkTo(scene, util.vector().topOf(6, 0, 6));
        resident.lookAt(scene, util.vector().centerOf(stockpot));
        resident.hold(scene, new ItemStack(ModItems.LETTUCE.get()));
        scene.idle(15);
        resident.hold(scene, new ItemStack(Items.EGG));
        scene.idle(15);
        resident.hold(scene, new ItemStack(ModItems.STOCKPOT_LID.get()));
        scene.world().modifyBlock(stockpot, state -> state.setValue(StockpotBlock.HAS_LID, true), false);
        scene.overlay().showText(100).text("The stockpot also needs its soup base, the other ingredients, a lid and a bowl.")
            .pointAt(util.vector().topOf(stockpot)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        resident.hold(scene, new ItemStack(Items.BOWL));
        scene.world().modifyBlock(stockpot, state -> state.setValue(StockpotBlock.HAS_LID, false), false);
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.UDON_NOODLE.get()));
        resident.walkTo(scene, util.vector().topOf(1, 0, 6));
        scene.overlay().showText(100).text("The order is done when the finished dish reaches storage. A missing ingredient or tool makes that step wait.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        resident.hold(scene, new ItemStack(ModItems.RAW_NOODLES.get(), 2));
        scene.idle(20);
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(105).text("Spare noodles stay for later orders. Drying, steaming and tea chains work the same way: give them stations, clear paths and raw ingredients.")
            .pointAt(util.vector().topOf(table)).placeNearTarget().attachKeyFrame();
        scene.idle(115);
        scene.markAsFinished();
    }
}
