package io.github.izakyl.folkways.vanilla.farming;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class FarmingScene {

    private static final int PLATE = 9;
    private static final float FACING_NORTH = 180.0F;
    private static final float FACING_SOUTH = 0.0F;
    private static final float FACING_WEST = 90.0F;
    private static final float FACING_EAST = 270.0F;

    private static final int TRUNK = 7;
    private static final int TRUNK_Z = 6;

    private FarmingScene() {
    }
    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(Scenes.Category.PRODUCTION, FarmingContent.ID, "folkways.lessons.farming",
            ResourceLocation.withDefaultNamespace("wheat"),
            Scenes.scene("folkways_vanilla", "farming", FarmingScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("farming", "Farming: mark a plot, and it keeps going");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);
        scene.world().setBlocks(util.select().fromTo(0, 0, 0, 0, 0, PLATE - 1),
            Blocks.WATER.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 5, PLATE - 1), Direction.DOWN);
        scene.idle(10);

        Selection plot = util.select().fromTo(1, 0, 1, 5, 2, 4);
        Selection wheatSoil = util.select().fromTo(1, 0, 1, 4, 0, 1);
        Selection wheatCrop = util.select().fromTo(1, 1, 1, 4, 1, 1);

        scene.overlay().showOutline(PonderPalette.GREEN, plot, plot, 100);
        scene.overlay().showText(90)
            .text("Click two opposite corners, then right-click and choose Farm. On its card, choose the Crop, wheat here. Clear grass and Clear flowers start on.")
            .pointAt(util.vector().topOf(3, 0, 2))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        Body resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(2, 0, 3), FACING_NORTH);
        scene.idle(10);

        walk(scene, util, resident, 2, 2);
        resident.hold(scene, new ItemStack(Items.IRON_HOE));
        scene.idle(10);
        scene.world().setBlocks(wheatSoil,
            Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7), false);
        scene.overlay().showOutline(PonderPalette.GREEN, wheatSoil, wheatSoil, 90);
        scene.overlay().showText(90)
            .text("Residents hoe the ground inside. Farm must allow a hoe under Settings → Trade tools.")
            .pointAt(util.vector().topOf(2, 0, 1))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.world().setBlocks(wheatCrop, wheat(0), false);
        resident.hold(scene, new ItemStack(Items.WHEAT_SEEDS, 4));
        scene.overlay().showOutline(PonderPalette.GREEN, wheatCrop, wheatCrop, 90);
        scene.overlay().showText(90)
            .text("Then they sow, and come back when the crop is ripe. Crops that need no farmland, like cane or saplings, are planted without hoeing.")
            .pointAt(util.vector().topOf(2, 1, 1))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(40);
        resident.hold(scene, ItemStack.EMPTY);
        scene.world().setBlocks(wheatCrop, wheat(3), false);
        scene.idle(25);
        scene.world().setBlocks(wheatCrop, wheat(7), false);
        scene.idle(35);

        for (int x = 1; x <= 4; x++) {
            scene.world().destroyBlock(util.grid().at(x, 1, 1));
            scene.idle(6);
        }
        resident.hold(scene, new ItemStack(Items.WHEAT, 4));
        scene.overlay().showText(90)
            .text("Most crops leave bare soil when harvested, and the next round is sown in place.")
            .pointAt(util.vector().topOf(2, 0, 1))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(60);
        resident.hold(scene, ItemStack.EMPTY);
        scene.world().setBlocks(wheatCrop, wheat(0), false);
        scene.idle(30);

        BlockPos caneBase = util.grid().at(1, 1, 4);
        scene.world().setBlock(util.grid().at(1, 0, 4), Blocks.SAND.defaultBlockState(), false);
        for (int y = 1; y <= 3; y++) {
            scene.world().setBlock(util.grid().at(1, y, 4), Blocks.SUGAR_CANE.defaultBlockState(), false);
        }
        scene.idle(10);
        walk(scene, util, resident, 2, 4);
        resident.turnTo(scene, FACING_WEST);
        scene.idle(10);
        scene.world().destroyBlock(util.grid().at(1, 3, 4));
        scene.idle(8);
        scene.world().destroyBlock(util.grid().at(1, 2, 4));
        resident.hold(scene, new ItemStack(Items.SUGAR_CANE, 2));
        scene.overlay().showOutline(PonderPalette.BLUE, caneBase, util.select().position(caneBase), 90);
        scene.overlay().showText(90)
            .text("Sugar cane is cut at three tall and bamboo at four, leaving the bottom stalk to regrow. Bamboo goes in two rows with an aisle between.")
            .pointAt(util.vector().topOf(caneBase))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
        resident.hold(scene, ItemStack.EMPTY);

        BlockPos stem = util.grid().at(3, 1, 4);
        BlockPos melon = util.grid().at(4, 1, 4);
        scene.world().setBlock(util.grid().at(3, 0, 4),
            Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7), false);
        scene.world().setBlock(stem,
            Blocks.ATTACHED_MELON_STEM.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST),
            false);
        scene.world().setBlock(melon, Blocks.MELON.defaultBlockState(), false);
        scene.idle(15);
        walk(scene, util, resident, 4, 3);
        resident.turnTo(scene, FACING_SOUTH);
        scene.idle(10);
        scene.world().destroyBlock(melon);
        scene.world().setBlock(stem, Blocks.MELON_STEM.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7),
            false);
        resident.hold(scene, new ItemStack(Items.MELON_SLICE, 5));
        scene.overlay().showOutline(PonderPalette.BLUE, stem, util.select().position(stem), 90);
        scene.overlay().showText(90)
            .text("For melons and pumpkins, only the fruit is taken and the stem grows more. Stems go one block in from the edge, leaving room for fruit.")
            .pointAt(util.vector().topOf(stem))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
        resident.hold(scene, ItemStack.EMPTY);

        Selection wood = util.select().fromTo(TRUNK - 1, 1, TRUNK_Z - 1, TRUNK + 1, 5, TRUNK_Z + 1);
        growTree(scene, util);
        scene.idle(20);
        scene.overlay().showOutline(PonderPalette.OUTPUT, wood, wood, 120);
        scene.overlay().showText(90)
            .text("A farm of saplings is a wood lot. Whole trees are felled with their leaves, and saplings are replanted. Dark oak, jungle and spruce can go in 2×2.")
            .pointAt(util.vector().topOf(TRUNK, 3, TRUNK_Z))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        walk(scene, util, resident, TRUNK - 1, TRUNK_Z);
        resident.turnTo(scene, FACING_EAST);
        scene.idle(10);
        for (int y = 4; y >= 1; y--) {
            scene.world().destroyBlock(util.grid().at(TRUNK, y, TRUNK_Z));
            scene.idle(6);
        }
        scene.world().setBlocks(util.select().fromTo(TRUNK - 1, 4, TRUNK_Z - 1, TRUNK + 1, 5, TRUNK_Z + 1),
            Blocks.AIR.defaultBlockState(), true);
        resident.hold(scene, new ItemStack(Items.OAK_LOG, 4));
        scene.idle(35);
        resident.hold(scene, ItemStack.EMPTY);
        scene.world().setBlock(util.grid().at(TRUNK, 1, TRUNK_Z), Blocks.OAK_SAPLING.defaultBlockState(), false);
        scene.idle(20);

        walk(scene, util, resident, 2, 2);
        resident.turnTo(scene, FACING_NORTH);
        scene.world().setBlocks(wheatCrop, wheat(7), false);
        scene.idle(15);
        for (int x = 1; x <= 4; x++) {
            scene.world().destroyBlock(util.grid().at(x, 1, 1));
            scene.world().setBlock(util.grid().at(x, 1, 1), wheat(0), false);
            scene.idle(6);
        }
        resident.hold(scene, new ItemStack(Items.WHEAT, 4));
        scene.overlay().showOutline(PonderPalette.GREEN, wheatCrop, wheatCrop, 90);
        scene.overlay().showText(90)
            .text("A farmer with the Replant perk resows with seeds from the harvest. This only works for crops cut whole, like wheat.")
            .pointAt(util.vector().topOf(2, 1, 1))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
        resident.hold(scene, ItemStack.EMPTY);
    }

    private static void growTree(SceneBuilder scene, SceneBuildingUtil util) {
        for (int y = 1; y <= 4; y++) {
            scene.world().setBlock(util.grid().at(TRUNK, y, TRUNK_Z), Blocks.OAK_LOG.defaultBlockState(), false);
        }
        for (int x = TRUNK - 1; x <= TRUNK + 1; x++) {
            for (int z = TRUNK_Z - 1; z <= TRUNK_Z + 1; z++) {
                if (x != TRUNK || z != TRUNK_Z) {
                    scene.world().setBlock(util.grid().at(x, 4, z), Blocks.OAK_LEAVES.defaultBlockState(), false);
                }
            }
        }
        scene.world().setBlock(util.grid().at(TRUNK, 5, TRUNK_Z), Blocks.OAK_LEAVES.defaultBlockState(), false);
    }

    private static BlockState wheat(int age) {
        return Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, age);
    }

    private static void walk(SceneBuilder scene, SceneBuildingUtil util, Body resident, int x, int z) {
        resident.walkTo(scene, util.vector().topOf(x, 0, z));
        scene.idle(8);
    }
}
