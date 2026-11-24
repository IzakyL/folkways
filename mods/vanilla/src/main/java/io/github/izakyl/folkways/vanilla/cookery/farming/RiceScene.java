package io.github.izakyl.folkways.vanilla.cookery.farming;

import com.github.ysbbbbbb.kaleidoscopecookery.block.crop.RiceCropBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.MillstoneBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.NinePart;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.vanilla.cookery.stove.HeatScene;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
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
        scene.overlay().showText(90).text("Mark out a farm on flooded dirt and supply a hoe. Residents till under the water, then sow. Keep water in the crop's block and two clear blocks above.")
            .pointAt(util.vector().topOf(3, 0, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(100);

        var farmer = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 4), 180);
        farmer.hold(scene, new ItemStack(ModItems.WILD_RICE_SEED.get()));
        scene.overlay().showText(90).text("One wild rice seed plants a whole three-block rice plant. Residents prefer wild seeds but can plant edible rice instead.")
            .pointAt(util.vector().topOf(3, 1, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(45);
        rice(scene, util, 0);
        farmer.hold(scene, ItemStack.EMPTY);
        scene.idle(55);
        rice(scene, util, 3);
        scene.idle(25);
        rice(scene, util, 7);
        scene.idle(25);

        scene.overlay().showText(90).text("A ripe plant is harvested once. The bottom part gives the panicles and seeds; the top two give nothing extra.")
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
        scene.overlay().showText(90).text("Harvesting leaves the water for replanting. Wild seeds and rice share one seed reserve, with wild seeds kept first.")
            .pointAt(util.vector().topOf(3, 1, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(100);

        farmer.walkTo(scene, util.vector().topOf(4, 0, 4));
        farmer.lookAt(scene, util.vector().centerOf(4, 1, 6));
        farmer.hold(scene, new ItemStack(ModItems.RICE_PANICLE.get()));
        scene.overlay().showText(90).text("Take in a reachable millstone. A rice order sends a resident to grind: one panicle gives three rice.")
            .pointAt(util.vector().topOf(4, 1, 6)).placeNearTarget().attachKeyFrame();
        scene.idle(45);
        farmer.hold(scene, new ItemStack(ModItems.RICE_SEED.get(), 3));
        scene.idle(55);

        farmer.walkTo(scene, util.vector().topOf(6, 0, 4));
        farmer.walkTo(scene, util.vector().topOf(6, 0, 6));
        farmer.lookAt(scene, util.vector().centerOf(7, 2, 6));
        farmer.hold(scene, new ItemStack(Items.BOWL, 3));
        scene.overlay().showText(100).text("Order the finished rice meal. Residents link the paddy, millstone and heated stockpot. Supply bowls and a lid; without them the batch waits.")
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
