package io.github.izakyl.folkways.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.MillstoneBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.NinePart;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class MillstoneScene {
    private MillstoneScene() { }
    // The native motor follows (0, 0, 2).yRot(angle) and faces -angle - 90 degrees.
    // The unbound scene renderer reads cacheRot directly, so advance both by the same angle each frame.
    public static void drive(SceneBuilder scene, BlockPos mill, PonderScenery.Body resident, float start, int ticks) {
        Vec3 center = Vec3.atBottomCenterOf(mill);
        for (int tick = 1; tick <= ticks; tick++) {
            float angle = start + 360.0F * tick / ticks;
            double radians = Math.toRadians(angle);
            scene.world().modifyBlockEntity(mill, MillstoneBlockEntity.class, tile -> tile.setCacheRot(angle));
            resident.moveTo(scene, center.add(2 * Math.sin(radians), 0, 2 * Math.cos(radians)), -angle - 90);
            scene.idle(1);
        }
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_millstone", "The millstone: drive it, release it, walk clear");
        PonderScenery.openWorld(scene, util, 9);
        PonderScenery.grassGround(scene, util, 9);
        BlockPos center = util.grid().at(4, 1, 4);
        BlockPos store = util.grid().at(1, 1, 1);
        BlockPos exit = util.grid().at(7, 1, 4);
        for (NinePart part : NinePart.values()) {
            scene.world().setBlock(center.offset(part.getPosX(), 0, part.getPosY()), ModBlocks.MILLSTONE.get().defaultBlockState()
                .setValue(MillstoneBlock.PART, part).setValue(MillstoneBlock.FACING, Direction.NORTH), false);
        }
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 1, 8), Direction.DOWN);
        scene.idle(15);
        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);
        scene.overlay().showControls(util.vector().topOf(center), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showOutline(PonderPalette.GREEN, center, util.select().fromTo(center.offset(-1, 0, -1), center.offset(1, 0, 1)), 80);
        scene.overlay().showText(85).text("The nine millstone parts belong to one kitchen station. A flour order can fetch wheat and arrange a real grinding task.")
            .pointAt(util.vector().topOf(center)).placeNearTarget().attachKeyFrame();
        scene.idle(95);
        scene.overlay().showOutline(PonderPalette.GREEN, exit, util.select().position(exit), 80);
        scene.overlay().showText(90).text("Leave a reachable exit and room for the resident to circle the mill. The task checks its way out before loading any ingredients.")
            .pointAt(util.vector().topOf(exit)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(7, 0, 4), 90);
        resident.hold(scene, new ItemStack(Items.WHEAT, 3));
        scene.world().modifyBlockEntity(center, MillstoneBlockEntity.class, mill -> {
            var level = Minecraft.getInstance().level;
            if (level != null) { mill.onPutItem(level, new ItemStack(Items.WHEAT, 3)); }
        });
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(215).text("Passing residents are not captured. Only the assigned resident may bind as the motor; the native mill then turns and processes the actual input.")
            .pointAt(util.vector().topOf(center)).placeNearTarget().attachKeyFrame();
        resident.walkTo(scene, util.vector().topOf(6, 0, 4));
        drive(scene, center, resident, 90, 200);
        scene.world().modifyBlockEntity(center, MillstoneBlockEntity.class, mill -> {
            mill.unbindEntity();
            mill.getInput().copyAndClear();
            mill.getOutputs().insertItem(0, new ItemStack(ModItems.FLOUR.get(), 3), false);
        });
        scene.overlay().showText(95).text("Finished grinding releases the motor immediately. The task still waits until the resident really walks clear; unbinding in place is not a completed exit.")
            .pointAt(util.vector().topOf(exit)).placeNearTarget().attachKeyFrame();
        resident.walkTo(scene, util.vector().topOf(7, 0, 4));
        scene.idle(105);
        scene.world().modifyBlockEntity(center, MillstoneBlockEntity.class, MillstoneBlockEntity::resetWhenTakeout);
        resident.hold(scene, new ItemStack(ModItems.FLOUR.get(), 3));
        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        scene.overlay().showText(95).text("Only then are the real output slots emptied into colony logistics. The same flour is never also left as a loose duplicate.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.overlay().showText(100).text("Cancellation and reload release the resident safely and preserve accepted ingredients for recovery. A blocked exit waits for a path; ordinary animal-driven mills keep their native behavior.")
            .pointAt(util.vector().topOf(center)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        scene.markAsFinished();
    }
}
