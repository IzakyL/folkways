package io.github.izakyl.folkways.vanilla.cookery.steamer;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.SteamerBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.SteamerBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
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
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class SteamerScene {
    private SteamerScene() { }
    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_steamer", "The steamer: layers, lid and heat");
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
        scene.overlay().showText(95).text("A stack of steamers is one kitchen station. A half block holds four portions, a full block eight. Heat rises only through full blocks, up to four high.")
            .pointAt(util.vector().centerOf(base)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(5, 0, 3), 90);
        resident.lookAt(scene, util.vector().centerOf(top));
        resident.hold(scene, new ItemStack(ModItems.RAW_DOUGH.get(), 3));
        scene.world().modifyBlockEntity(top, SteamerBlockEntity.class, steamer -> {
            for (int slot = 0; slot < 3; slot++) { steamer.getItems().set(slot, new ItemStack(ModItems.RAW_DOUGH.get())); }
        });
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(95).text("A task takes one fully empty block and fills it in one go. Food in other blocks is left alone.")
            .pointAt(util.vector().centerOf(top)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.world().modifyBlock(top, state -> state.setValue(SteamerBlock.HAS_LID, true), false);
        scene.overlay().showControls(util.vector().topOf(top), Pointing.DOWN, 65).rightClick().whileSneaking();
        scene.overlay().showText(95).text("The resident closes the top lid. Lower layers are covered by the ones above; a half block under another steamer blocks the heat.")
            .pointAt(util.vector().topOf(top)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.world().setBlock(base.below(), HeatScene.litStove().setValue(com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock.LIT, false), false);
        scene.overlay().showText(90).text("It still needs heat below. Without heat, the batch cools and waits.")
            .pointAt(util.vector().centerOf(base.below())).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.world().setBlock(base.below(), HeatScene.litStove(), false);
        scene.world().modifyBlockEntity(top, SteamerBlockEntity.class, steamer -> {
            for (int slot = 0; slot < 3; slot++) {
                steamer.getItems().set(slot, new ItemStack(ModItems.MANTOU.get()));
                steamer.getCookingTime()[slot] = -1;
            }
        });
        scene.overlay().showText(95).text("Residents collect only when all their portions are steamed. Taking out empties the whole block, so a task never shares a block with you.")
            .pointAt(util.vector().centerOf(top)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.world().modifyBlockEntity(top, SteamerBlockEntity.class, steamer -> steamer.getItems().clear());
        scene.world().modifyBlock(top, state -> state.setValue(SteamerBlock.HAS_LID, false), false);
        resident.hold(scene, new ItemStack(ModItems.MANTOU.get(), 3));
        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        scene.overlay().showText(100).text("The food goes to storage once, and the lid is put back as it was. A changed batch is refused rather than breaking the steamer.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        scene.markAsFinished();
    }
}
