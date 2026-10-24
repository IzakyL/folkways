package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class GroundworkScene {

    private static final int PLATE = 9;

    private GroundworkScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(GroundworkContent.ID, "folkways.lessons.groundwork",
            ResourceLocation.withDefaultNamespace("iron_shovel"),
            Scenes.scene("groundwork", GroundworkScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("groundwork", "Groundwork: mark a box, and the ground in it is worked");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);
        Selection boulder = util.select().fromTo(1, 1, 1, 2, 2, 2);
        Selection pit = util.select().fromTo(1, 0, 5, 3, 0, 7);
        Selection weeds = util.select().fromTo(5, 1, 1, 7, 1, 2);
        scene.world().setBlocks(boulder, Blocks.STONE.defaultBlockState(), false);
        scene.world().setBlocks(pit, Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlocks(weeds, Blocks.SHORT_GRASS.defaultBlockState(), false);
        BlockPos trunk = util.grid().at(6, 1, 6);
        plantTree(scene, util, trunk);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 7, PLATE - 1), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(90)
            .text("A groundwork zone does whichever work is ticked in its settings, one or several: clear, fill, weed, fell, haul and forage. Cleared and filled together, the box is dug out and laid with the fill block; the fill block already there stays.")
            .pointAt(util.vector().topOf(4, 0, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showOutline(PonderPalette.RED, "clear", boulder, 90);
        scene.overlay().showText(90)
            .text("Clear takes out every block in the box, from the top down, and only in the box: mark it as deep as you mean to dig. Builders do it, with the tool each block wants, and leave the colony's own blocks alone.")
            .colored(PonderPalette.RED)
            .pointAt(util.vector().topOf(2, 0, 2))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(50);
        scene.world().setBlocks(util.select().fromTo(1, 2, 1, 2, 2, 2), Blocks.AIR.defaultBlockState(), true);
        scene.idle(20);
        scene.world().setBlocks(util.select().fromTo(1, 1, 1, 2, 1, 2), Blocks.AIR.defaultBlockState(), true);
        scene.idle(30);

        scene.overlay().showOutline(PonderPalette.GREEN, "fill", pit, 90);
        scene.overlay().showText(90)
            .text("Fill lays the block chosen in its settings into every open cell, from the bottom up, brought from the colony's stores.")
            .colored(PonderPalette.GREEN)
            .pointAt(util.vector().topOf(2, 0, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(50);
        scene.world().setBlocks(pit, Blocks.DIRT.defaultBlockState(), true);
        scene.idle(50);

        scene.overlay().showOutline(PonderPalette.GREEN, "weed", weeds, 80);
        scene.overlay().showText(80)
            .text("Weed pulls the grass and ferns in the box. Flowers are pulled only with Pull flowers ticked, and a fill never buries one. Farmers do it.")
            .pointAt(util.vector().topOf(6, 0, 1))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(50);
        scene.world().setBlocks(weeds, Blocks.AIR.defaultBlockState(), true);
        scene.idle(40);

        Selection tree = util.select().fromTo(4, 1, 4, 8, 6, 8);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "fell", util.select().fromTo(5, 1, 5, 7, 1, 7), 90);
        scene.overlay().showText(90)
            .text("Fell takes down trees whose trunks stand in the box, leaves and all. It also clears horizontal logs resting on the ground away from trees, with their moss and other small plants. Colony building blocks stay.")
            .colored(PonderPalette.OUTPUT)
            .pointAt(util.vector().topOf(trunk.below()))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(50);
        scene.world().setBlocks(tree, Blocks.AIR.defaultBlockState(), true);
        scene.idle(50);

        Selection yard = util.select().fromTo(4, 1, 4, 8, 1, 8);
        scene.overlay().showOutline(PonderPalette.BLUE, "haul", yard, 90);
        scene.world().createItemEntity(util.vector().topOf(5, 0, 5), Vec3.ZERO, new ItemStack(Items.OAK_LOG, 4));
        scene.world().createItemEntity(util.vector().topOf(7, 0, 6), Vec3.ZERO, new ItemStack(Items.APPLE));
        scene.overlay().showText(90)
            .text("Haul has the haulers pick up whatever lies loose in the box and carry it off to the colony's stores.")
            .colored(PonderPalette.BLUE)
            .pointAt(util.vector().topOf(6, 0, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        BlockPos berries = util.grid().at(6, 1, 1);
        scene.world().setBlock(berries, Blocks.SWEET_BERRY_BUSH.defaultBlockState()
            .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3), false);
        scene.overlay().showText(100)
            .text("Forage lets farmers gather wild food into colony stores. Ripe berry bushes and cave vines keep growing after picking; mushrooms and wild crops are picked whole. Clear takes priority when both switches are ticked.")
            .colored(PonderPalette.GREEN)
            .pointAt(util.vector().centerOf(berries))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(50);
        scene.world().setBlock(berries, Blocks.SWEET_BERRY_BUSH.defaultBlockState()
            .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 1), false);
        scene.world().createItemEntity(util.vector().centerOf(berries), Vec3.ZERO, new ItemStack(Items.SWEET_BERRIES, 2));
        scene.idle(60);
    }

    private static void plantTree(SceneBuilder scene, SceneBuildingUtil util, BlockPos trunk) {
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        scene.world().setBlocks(util.select().fromTo(trunk.getX() - 2, 3, trunk.getZ() - 2,
            trunk.getX() + 2, 4, trunk.getZ() + 2), leaves, false);
        scene.world().setBlocks(util.select().fromTo(trunk.getX() - 1, 5, trunk.getZ() - 1,
            trunk.getX() + 1, 5, trunk.getZ() + 1), leaves, false);
        scene.world().setBlocks(util.select().fromTo(trunk.getX(), 1, trunk.getZ(), trunk.getX(), 4, trunk.getZ()),
            Blocks.OAK_LOG.defaultBlockState(), false);
    }
}
