package io.github.izakyl.folkways.vanilla.cookery.spit;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.ShawarmaSpitBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class SpitScene {
    private SpitScene() { }
    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_spit", "The shawarma spit: power, two halves, safe hands");
        PonderScenery.openWorld(scene, util, 7);
        PonderScenery.grassGround(scene, util, 7);
        BlockPos base = util.grid().at(3, 1, 3);
        BlockPos upper = base.above();
        BlockPos power = base.west();
        BlockPos store = util.grid().at(1, 1, 1);
        var lowerState = ModBlocks.SHAWARMA_SPIT.get().defaultBlockState().setValue(ShawarmaSpitBlock.POWERED, true);
        scene.world().setBlock(base, lowerState, false);
        scene.world().setBlock(upper, lowerState.setValue(ShawarmaSpitBlock.HALF, DoubleBlockHalf.UPPER), false);
        scene.world().setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 3, 6), Direction.DOWN);
        scene.idle(15);
        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);
        scene.overlay().showControls(util.vector().topOf(base), Pointing.DOWN, 60).leftClick().withItem(book);
        scene.overlay().showOutline(PonderPalette.GREEN, base, util.select().fromTo(base, upper), 80);
        scene.overlay().showText(90).text("Take in either half to add the whole spit as one kitchen station. Each half holds up to eight of the same ingredient.")
            .pointAt(util.vector().topOf(upper)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.world().modifyBlockEntity(base, ShawarmaSpitBlockEntity.class, spit -> {
            spit.cookingItem = new ItemStack(Items.PORKCHOP);
            spit.cookedItem = new ItemStack(Items.COOKED_PORKCHOP);
        });
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(5, 0, 3), 90);
        resident.hold(scene, new ItemStack(Items.BEEF, 3));
        scene.world().modifyBlockEntity(upper, ShawarmaSpitBlockEntity.class, spit -> {
            spit.cookingItem = new ItemStack(Items.BEEF, 3);
            spit.cookedItem = new ItemStack(Items.COOKED_BEEF, 3);
            spit.cookTime = 600;
        });
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(95).text("Orders use an empty half and leave your own food alone.")
            .pointAt(util.vector().centerOf(upper)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.world().setBlock(power, Blocks.AIR.defaultBlockState(), false);
        scene.world().modifyBlock(base, state -> state.setValue(ShawarmaSpitBlock.POWERED, false), false);
        scene.world().modifyBlock(upper, state -> state.setValue(ShawarmaSpitBlock.POWERED, false), false);
        scene.overlay().showText(90).text("A redstone signal turns the spit. Without power, cooking pauses.")
            .pointAt(util.vector().centerOf(power)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.world().setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), false);
        scene.world().modifyBlock(base, state -> state.setValue(ShawarmaSpitBlock.POWERED, true), false);
        scene.world().modifyBlock(upper, state -> state.setValue(ShawarmaSpitBlock.POWERED, true), false);
        scene.world().modifyBlockEntity(upper, ShawarmaSpitBlockEntity.class, spit -> {
            spit.cookingItem = ItemStack.EMPTY;
            spit.cookTime = 0;
        });
        resident.hold(scene, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        scene.overlay().showControls(util.vector().centerOf(upper), Pointing.RIGHT, 70).withItem(new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        scene.overlay().showText(100).text("When it is done, the booked resident takes the food safely with a kitchen knife. It does not make them fireproof or cut the power.")
            .pointAt(util.vector().centerOf(upper)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        scene.world().modifyBlockEntity(upper, ShawarmaSpitBlockEntity.class, spit -> spit.cookedItem = ItemStack.EMPTY);
        resident.hold(scene, new ItemStack(Items.COOKED_BEEF, 3));
        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        scene.overlay().showText(95).text("The cooked food goes to storage once. Cancelling or reloading recovers it without spending ingredients again.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.markAsFinished();
    }
}
