package io.github.izakyl.folkways.plugins.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.MillstoneBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.NinePart;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StockpotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
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
public final class CookingChainScene {
    private CookingChainScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_chain", "A meal order: flour, dough, noodles and the finished dish");
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
        scene.world().setBlock(stockpot.below(), Blocks.CAMPFIRE.defaultBlockState(), false);
        scene.world().setBlock(stockpot, ModBlocks.STOCKPOT.get().defaultBlockState(), false);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 3, 8), Direction.DOWN);
        scene.idle(15);
        ItemStack book = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        ColonyBookItem.setGesture(book, Shape.Gesture.POINT);
        scene.overlay().showControls(util.vector().topOf(stockpot), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showText(105).text("Order the final dish, such as udon noodles, and register every station along its route. The colony plans the needed intermediate foods instead of asking you to order each one by hand.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(115);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(4, 0, 2), 90);
        resident.hold(scene, new ItemStack(Items.WHEAT));
        scene.overlay().showOutline(PonderPalette.GREEN, mill, util.select().fromTo(0, 0, 0, 4, 1, 4), 100);
        scene.overlay().showText(95).text("Wheat becomes flour in the real millstone. Keep the turning path and a clear exit open: the resident must finish driving the mill and walk free before another step can use the output.")
            .pointAt(util.vector().topOf(mill)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.hold(scene, ItemStack.EMPTY);
        int[][] around = {{4, 4}, {2, 4}, {0, 4}, {0, 2}, {0, 0}, {2, 0}, {4, 0}, {4, 2}};
        for (int lap = 0; lap < 4; lap++) {
            for (int point = 0; point < around.length; point++) {
                final int angle = (lap * around.length + point + 1) * 45;
                scene.world().modifyBlockEntity(mill, MillstoneBlockEntity.class, tile -> tile.setCacheRot(angle));
                resident.walkTo(scene, util.vector().topOf(around[point][0], 0, around[point][1]));
            }
        }
        resident.walkTo(scene, util.vector().topOf(5, 0, 2));
        resident.hold(scene, new ItemStack(ModItems.FLOUR.get()));
        scene.idle(20);
        resident.walkTo(scene, util.vector().topOf(6, 0, 2));
        resident.hold(scene, new ItemStack(Items.WATER_BUCKET));
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.RAW_DOUGH.get()));
        scene.overlay().showOutline(PonderPalette.GREEN, table, util.select().position(table), 90);
        scene.overlay().showText(95).text("The normal crafting recipe combines flour and a water bucket into raw dough. That intermediate is real inventory, and the empty bucket returns through the same ordinary crafting logistics.")
            .pointAt(util.vector().topOf(table)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.walkTo(scene, util.vector().topOf(6, 0, 4));
        resident.hold(scene, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        scene.idle(40);
        resident.hold(scene, new ItemStack(ModItems.RAW_NOODLES.get(), 3));
        scene.overlay().showOutline(PonderPalette.GREEN, board, util.select().position(board), 90);
        scene.overlay().showText(95).text("A borrowed kitchen knife cuts that dough on the actual chopping board. The native recipe gives three raw noodles; the knife returns for another task, and the real surplus noodles remain available for later orders.")
            .pointAt(util.vector().topOf(board)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.walkTo(scene, util.vector().topOf(6, 0, 6));
        resident.hold(scene, new ItemStack(ModItems.LETTUCE.get()));
        scene.idle(15);
        resident.hold(scene, new ItemStack(Items.EGG));
        scene.idle(15);
        resident.hold(scene, new ItemStack(ModItems.STOCKPOT_LID.get()));
        scene.world().modifyBlock(stockpot, state -> state.setValue(StockpotBlock.HAS_LID, true), false);
        scene.overlay().showText(100).text("The stockpot route also needs its real soup base, lettuce, egg, lid and bowl. Fixed and flexible recipes may require different quantities, so a shorter ingredient list still follows native matching and quality rules.")
            .pointAt(util.vector().topOf(stockpot)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        resident.hold(scene, new ItemStack(Items.BOWL));
        scene.world().modifyBlock(stockpot, state -> state.setValue(StockpotBlock.HAS_LID, false), false);
        scene.idle(20);
        resident.hold(scene, new ItemStack(ModItems.UDON_NOODLE.get()));
        resident.walkTo(scene, util.vector().topOf(1, 0, 6));
        scene.overlay().showText(100).text("The final order completes with the actual served dish. No station is skipped, and missing ingredients, a borrowed tool or an unfinished native timer keep the dependent step waiting.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        resident.hold(scene, new ItemStack(ModItems.RAW_NOODLES.get(), 2));
        scene.idle(20);
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(105).text("Other chains obey their own native conditions too: drying needs the right exposure, steaming needs heat and fluid, and tea needs real brewing and serving. Provide the stations, safe paths and base materials; the colony carries each real intermediate between them.")
            .pointAt(util.vector().topOf(table)).placeNearTarget().attachKeyFrame();
        scene.idle(115);
        scene.markAsFinished();
    }
}
