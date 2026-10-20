package io.github.izakyl.folkways.cookery.steamer;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.SteamerBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.SteamerBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.cookery.stove.HeatScene;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class SteamerScene {
    private SteamerScene() { }
    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_steamer", "The steamer: layers, a top lid and real heat");
        PonderScenery.openWorld(scene, util, 7);
        PonderScenery.grassGround(scene, util, 7);
        BlockPos base = util.grid().at(3, 2, 3);
        BlockPos top = base.above();
        BlockPos store = util.grid().at(1, 1, 1);
        scene.world().setBlock(base.below(), HeatScene.litStove(), false);
        scene.world().setBlock(base, ModBlocks.STEAMER.get().defaultBlockState().setValue(SteamerBlock.HALF, false), false);
        scene.world().setBlock(top, ModBlocks.STEAMER.get().defaultBlockState(), false);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 4, 6), Direction.DOWN);
        scene.idle(15);
        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);
        scene.overlay().showControls(util.vector().topOf(top), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showOutline(PonderPalette.GREEN, base, util.select().fromTo(base, top), 80);
        scene.overlay().showText(95).text("A continuous column is one kitchen station. A half block holds four portions, a full block holds eight; heat passes upward only through full blocks, to at most four block levels.")
            .pointAt(util.vector().centerOf(base)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(5, 0, 3), 90);
        resident.lookAt(scene, util.vector().centerOf(top));
        resident.hold(scene, new ItemStack(ModItems.RAW_DOUGH.get(), 3));
        scene.world().modifyBlockEntity(top, SteamerBlockEntity.class, steamer -> {
            for (int slot = 0; slot < 3; slot++) { steamer.getItems().set(slot, new ItemStack(ModItems.RAW_DOUGH.get())); }
        });
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(95).text("The task reserves an entirely empty block and places several real ingredients in one native interaction. Food in other blocks stays with its original owner.")
            .pointAt(util.vector().centerOf(top)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.world().modifyBlock(top, state -> state.setValue(SteamerBlock.HAS_LID, true), false);
        scene.overlay().showControls(util.vector().topOf(top), Pointing.DOWN, 65).rightClick().whileSneaking();
        scene.overlay().showText(95).text("The reachable top lid closes using the native lid rule. Lower blocks are covered by the stack above them; a half block below another steamer cannot pass its heat upward.")
            .pointAt(util.vector().topOf(top)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.world().setBlock(base.below(), HeatScene.litStove().setValue(com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock.LIT, false), false);
        scene.overlay().showText(90).text("A real heat source is still required. When it disappears, an owned batch cools down through the native ticker and waits for heat to return.")
            .pointAt(util.vector().centerOf(base.below())).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.world().setBlock(base.below(), HeatScene.litStove(), false);
        scene.world().modifyBlockEntity(top, SteamerBlockEntity.class, steamer -> {
            for (int slot = 0; slot < 3; slot++) {
                steamer.getItems().set(slot, new ItemStack(ModItems.MANTOU.get()));
                steamer.getCookingTime()[slot] = -1;
            }
        });
        scene.overlay().showText(95).text("Collection waits until every owned portion is actually steamed. The native take-out moves all food in that block, so the task never mixes its ingredients with a player's slots.")
            .pointAt(util.vector().centerOf(top)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.world().modifyBlockEntity(top, SteamerBlockEntity.class, steamer -> steamer.getItems().clear());
        scene.world().modifyBlock(top, state -> state.setValue(SteamerBlock.HAS_LID, false), false);
        resident.hold(scene, new ItemStack(ModItems.MANTOU.get(), 3));
        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        scene.overlay().showText(100).text("The real portions are delivered once, and a lid changed by this task returns to its original state. Empty or changed batches are refused instead of accidentally dismantling a steamer layer.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        scene.markAsFinished();
    }
}
