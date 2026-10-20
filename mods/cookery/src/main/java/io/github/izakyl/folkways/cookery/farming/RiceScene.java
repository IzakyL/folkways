package io.github.izakyl.folkways.cookery.farming;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.RiceCropBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.MillstoneBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.NinePart;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.cookery.stove.HeatScene;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class RiceScene {
    private RiceScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_rice", "Rice: from a flooded plot to a bowl");
        PonderScenery.openWorld(scene, util, 9);
        PonderScenery.ground(scene, util, 9, Blocks.DIRT);
        scene.world().setBlocks(util.select().fromTo(2, 0, 2, 4, 0, 3),
            Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7), false);
        scene.world().setBlocks(util.select().fromTo(2, 1, 2, 4, 1, 3), Blocks.WATER.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(1, 1, 6), Blocks.CHEST.defaultBlockState(), false);
        for (NinePart part : NinePart.values()) {
            scene.world().setBlock(util.grid().at(4 + part.getPosX(), 1, 6 + part.getPosY()),
                ModBlocks.MILLSTONE.get().defaultBlockState().setValue(MillstoneBlock.PART, part)
                    .setValue(MillstoneBlock.FACING, Direction.NORTH), false);
        }
        scene.world().setBlock(util.grid().at(7, 1, 6), HeatScene.litStove(), false);
        scene.world().setBlock(util.grid().at(7, 2, 6), ModBlocks.STOCKPOT.get().defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 4, 8), Direction.DOWN);
        var paddy = util.select().fromTo(2, 0, 2, 4, 3, 3);
        scene.overlay().showOutline(PonderPalette.GREEN, paddy, paddy, 100);
        scene.overlay().showText(90).text("Mark a flooded dirt plot with the colony book and supply a hoe. Residents till its soil before sowing; suitable existing farmland also works. Keep water in the bottom crop space and two clear spaces above.")
            .pointAt(util.vector().topOf(3, 0, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(100);

        var farmer = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 4), 180);
        farmer.hold(scene, new ItemStack(ModItems.WILD_RICE_SEED.get()));
        scene.overlay().showText(90).text("One physical wild rice seed plants all three parts. Residents prefer wild seeds; edible rice can plant the same crop when it is the available fallback.")
            .pointAt(util.vector().topOf(3, 1, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(45);
        rice(scene, util, 0);
        farmer.hold(scene, ItemStack.EMPTY);
        scene.idle(55);
        rice(scene, util, 3);
        scene.idle(25);
        rice(scene, util, 7);
        scene.idle(25);

        scene.overlay().showText(90).text("A mature plant is one harvest. Only its bottom part yields panicles and wild seeds; the two upper parts are claimed with it, so they never pay the harvest again.")
            .pointAt(util.vector().topOf(3, 2, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(35);
        for (int x = 2; x <= 4; x++) {
            scene.world().setBlock(util.grid().at(x, 1, 2), Blocks.WATER.defaultBlockState(), false);
            scene.world().setBlocks(util.select().fromTo(x, 2, 2, x, 3, 2), Blocks.AIR.defaultBlockState(), false);
        }
        farmer.hold(scene, new ItemStack(ModItems.RICE_PANICLE.get(), 3));
        scene.idle(65);

        farmer.hold(scene, new ItemStack(ModItems.WILD_RICE_SEED.get()));
        rice(scene, util, 0);
        scene.overlay().showText(90).text("Ordinary harvesting leaves water for the next planting. Replanting spends a real harvested seed. Wild seeds and edible rice share one planting reserve, with wild seeds kept first.")
            .pointAt(util.vector().topOf(3, 1, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(100);

        farmer.walkTo(scene, util.vector().topOf(4, 0, 4));
        farmer.lookAt(scene, util.vector().centerOf(4, 1, 6));
        farmer.hold(scene, new ItemStack(ModItems.RICE_PANICLE.get()));
        scene.overlay().showText(90).text("Mark a reachable millstone as a kitchen station. A rice order can send a resident to drive the real mill: one panicle supplies three grains, and the resident walks safely out when finished.")
            .pointAt(util.vector().topOf(4, 1, 6)).placeNearTarget().attachKeyFrame();
        scene.idle(45);
        farmer.hold(scene, new ItemStack(ModItems.RICE_SEED.get(), 3));
        scene.idle(55);

        farmer.walkTo(scene, util.vector().topOf(6, 0, 4));
        farmer.walkTo(scene, util.vector().topOf(6, 0, 6));
        farmer.lookAt(scene, util.vector().centerOf(7, 2, 6));
        farmer.hold(scene, new ItemStack(Items.BOWL, 3));
        scene.overlay().showText(100).text("Order the finished rice meal. Residents connect the paddy, millstone and heated stockpot themselves. Supply the recipe's bowls and lid; missing carriers wait instead of spending the next batch.")
            .pointAt(util.vector().topOf(7, 2, 6)).placeNearTarget().attachKeyFrame();
        scene.idle(55);
        farmer.hold(scene, new ItemStack(ModItems.COOKED_RICE.get(), 3));
        scene.idle(55);
    }

    private static void rice(SceneBuilder scene, SceneBuildingUtil util, int age) {
        for (int x = 3; x <= 3; x++) for (int part = 0; part < 3; part++) {
            scene.world().setBlock(util.grid().at(x, part + 1, 2), ModBlocks.RICE_CROP.get().defaultBlockState()
                .setValue(CropBlock.AGE, age).setValue(RiceCropBlock.LOCATION, part)
                .setValue(BlockStateProperties.WATERLOGGED, part == 0), false);
        }
    }
}
