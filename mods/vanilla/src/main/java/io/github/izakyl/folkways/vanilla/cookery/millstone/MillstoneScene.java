package io.github.izakyl.folkways.vanilla.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.MillstoneBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.NinePart;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
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
        scene.title("cookery_millstone", "The millstone: turn it, then walk clear");
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
        scene.overlay().showText(85).text("The whole millstone is one kitchen station. A flour order brings wheat and sends a resident to grind it.")
            .pointAt(util.vector().topOf(center)).placeNearTarget().attachKeyFrame();
        scene.idle(95);
        scene.overlay().showOutline(PonderPalette.GREEN, exit, util.select().position(exit), 80);
        scene.overlay().showText(90).text("Leave room to walk around the mill and a clear way out. Residents check the exit before loading.")
            .pointAt(util.vector().topOf(exit)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(7, 0, 4), 90);
        resident.hold(scene, new ItemStack(Items.WHEAT, 3));
        scene.world().modifyBlockEntity(center, MillstoneBlockEntity.class, mill -> {
            var level = Minecraft.getInstance().level;
            if (level != null) { mill.onPutItem(level, new ItemStack(Items.WHEAT, 3)); }
        });
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(215).text("Passers-by are not caught. Only the assigned resident is hitched to turn the mill.")
            .pointAt(util.vector().topOf(center)).placeNearTarget().attachKeyFrame();
        resident.walkTo(scene, util.vector().topOf(6, 0, 4));
        drive(scene, center, resident, 90, 200);
        scene.world().modifyBlockEntity(center, MillstoneBlockEntity.class, mill -> {
            mill.unbindEntity();
            mill.getInput().copyAndClear();
            mill.getOutputs().insertItem(0, new ItemStack(ModItems.FLOUR.get(), 3), false);
        });
        scene.overlay().showText(95).text("When grinding ends, the resident is let go, and the task waits until they walk clear.")
            .pointAt(util.vector().topOf(exit)).placeNearTarget().attachKeyFrame();
        resident.walkTo(scene, util.vector().topOf(7, 0, 4));
        scene.idle(105);
        scene.world().modifyBlockEntity(center, MillstoneBlockEntity.class, MillstoneBlockEntity::resetWhenTakeout);
        resident.hold(scene, new ItemStack(ModItems.FLOUR.get(), 3));
        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        scene.overlay().showText(95).text("Only then is the flour collected into storage.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.overlay().showText(100).text("Cancelling or reloading frees the resident safely and keeps the ingredients. A blocked exit waits for a path. Animal-driven mills work as usual.")
            .pointAt(util.vector().topOf(center)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        scene.markAsFinished();
    }
}
