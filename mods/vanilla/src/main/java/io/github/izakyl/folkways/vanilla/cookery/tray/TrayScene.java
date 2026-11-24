package io.github.izakyl.folkways.vanilla.cookery.tray;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class TrayScene {
    private TrayScene() { }
    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_tray", "The bamboo tray: weather and timing");
        PonderScenery.openWorld(scene, util, 7); PonderScenery.grassGround(scene, util, 7);
        BlockPos dry = util.grid().at(2, 1, 3); BlockPos wet = util.grid().at(4, 1, 3);
        scene.world().setBlock(dry, ModBlocks.BAMBOO_TRAY.get().defaultBlockState(), false);
        scene.world().setBlock(wet, ModBlocks.BAMBOO_TRAY.get().defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 1, 6), Direction.DOWN); scene.idle(15);
        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);
        scene.overlay().showControls(util.vector().topOf(dry), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showOutline(PonderPalette.GREEN, dry, util.select().position(dry), 80);
        scene.overlay().showText(95).text("Take in a bamboo tray and order something to dry or wet. A task uses one empty slot; your items in other slots stay put.")
            .pointAt(util.vector().topOf(dry)).placeNearTarget().attachKeyFrame(); scene.idle(105);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(2, 0, 5), 90);
        resident.lookAt(scene, util.vector().centerOf(dry));
        resident.hold(scene, new ItemStack(ModItems.FRESH_TEA_LEAVES.get(), 8));
        scene.world().modifyBlockEntity(dry, com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity.class,
            tray -> tray.setItem(0, new ItemStack(ModItems.FRESH_TEA_LEAVES.get(), 8))); resident.hold(scene, ItemStack.EMPTY);
        scene.world().setBlock(dry.above(2), Blocks.STONE.defaultBlockState(), false);
        scene.world().showSection(util.select().position(dry.above(2)), Direction.DOWN);
        scene.overlay().showText(95).text("Drying needs open sky and dry weather. A solid roof pauses it; open air or clear glass lets it go on.")
            .pointAt(util.vector().centerOf(dry.above(2))).placeNearTarget().attachKeyFrame(); scene.idle(105);
        scene.world().setBlock(dry.above(2), Blocks.GLASS.defaultBlockState(), false);
        scene.world().showSection(util.select().position(dry.above(2)), Direction.DOWN);
        scene.overlay().showText(90).text("The tray runs on its own timer, and residents wait for it to finish.")
            .pointAt(util.vector().topOf(dry)).placeNearTarget().attachKeyFrame(); scene.idle(100);
        scene.world().setBlock(wet.above(3), Blocks.DRIPSTONE_BLOCK.defaultBlockState(), false);
        scene.world().setBlock(wet.above(4), Blocks.WATER.defaultBlockState(), false);
        scene.world().setBlock(wet.above(2), Blocks.POINTED_DRIPSTONE.defaultBlockState()
            .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN).setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP), false);
        scene.world().showSection(util.select().fromTo(wet.above(2), wet.above(4)), Direction.DOWN);
        scene.overlay().showText(100).text("Wetting needs rain or water dripping from a dripstone. Many changes reverse in other weather, so finished items are collected promptly.")
            .pointAt(util.vector().centerOf(wet.above(2))).placeNearTarget().attachKeyFrame(); scene.idle(110);
        scene.world().modifyBlockEntity(dry, com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity.class,
            tray -> tray.setItem(0, new ItemStack(ModItems.DRIED_TEA_LEAVES.get(), 8)));
        resident.hold(scene, new ItemStack(ModItems.DRIED_TEA_LEAVES.get(), 8));
        scene.world().modifyBlockEntity(dry, com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity.class, tray -> tray.removeItemNoUpdate(0));
        scene.overlay().showText(100).text("Only the task's own result is taken; changed contents are refused. Dried leaves can then go into tea bags and brewing.")
            .pointAt(util.vector().topOf(dry)).placeNearTarget().attachKeyFrame(); scene.idle(110); scene.markAsFinished();
    }
}
