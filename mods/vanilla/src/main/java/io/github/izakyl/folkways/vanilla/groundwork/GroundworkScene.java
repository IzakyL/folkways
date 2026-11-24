package io.github.izakyl.folkways.vanilla.groundwork;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
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
        event.enqueueWork(() -> Scenes.folder(Scenes.Category.BUILDING, GroundworkContent.ID, "folkways.lessons.groundwork",
            ResourceLocation.withDefaultNamespace("iron_shovel"),
            Scenes.scene("folkways_vanilla", "groundwork", GroundworkScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("groundwork", "Groundwork: clear, fill and tidy an area");
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
            .text("With Clear or Fill ticked, the area becomes one build order and then disappears. Other ticked jobs are ignored.")
            .pointAt(util.vector().topOf(4, 0, 4))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showOutline(PonderPalette.RED, "clear", boulder, 30);
        scene.overlay().showText(90)
            .text("Clear alone empties every block in the area. Builders dig it out like any building site.")
            .colored(PonderPalette.RED)
            .pointAt(util.vector().topOf(2, 0, 2))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(30);
        scene.overlay().showOutline(PonderPalette.BLUE, "clear_order", boulder, 60);
        scene.idle(20);
        scene.world().setBlocks(util.select().fromTo(1, 2, 1, 2, 2, 2), Blocks.AIR.defaultBlockState(), true);
        scene.idle(20);
        scene.world().setBlocks(util.select().fromTo(1, 1, 1, 2, 1, 2), Blocks.AIR.defaultBlockState(), true);
        scene.idle(30);

        scene.overlay().showOutline(PonderPalette.GREEN, "fill", pit, 30);
        scene.overlay().showText(90)
            .text("Fill replaces every block in the area with the block you chose. Builders bring it from storage.")
            .colored(PonderPalette.GREEN)
            .pointAt(util.vector().topOf(2, 0, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(30);
        scene.overlay().showOutline(PonderPalette.BLUE, "fill_order", pit, 60);
        scene.idle(20);
        scene.world().setBlocks(pit, Blocks.DIRT.defaultBlockState(), true);
        scene.idle(50);

        scene.overlay().showOutline(PonderPalette.GREEN, "weed", weeds, 80);
        scene.overlay().showText(80)
            .text("With Clear and Fill off, Weed has farmers pull grass and ferns. Flowers go only if Pull flowers is ticked.")
            .pointAt(util.vector().topOf(6, 0, 1))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(50);
        scene.world().setBlocks(weeds, Blocks.AIR.defaultBlockState(), true);
        scene.idle(40);

        Selection tree = util.select().fromTo(4, 1, 4, 8, 6, 8);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "fell", util.select().fromTo(5, 1, 5, 7, 1, 7), 90);
        scene.overlay().showText(90)
            .text("Fell trees cuts down trees whose trunks stand inside, leaves and all, plus fallen logs on the ground. Colony buildings are left alone.")
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
            .text("Haul has haulers pick up loose items in the area and take them to storage.")
            .colored(PonderPalette.BLUE)
            .pointAt(util.vector().topOf(6, 0, 6))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        BlockPos berries = util.grid().at(6, 1, 1);
        scene.world().setBlock(berries, Blocks.SWEET_BERRY_BUSH.defaultBlockState()
            .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3), false);
        scene.overlay().showText(100)
            .text("Forage has farmers harvest food plants and keep every drop. Young berry bushes and cave vines are cleared too and do not regrow. If Clear is also ticked, Clear wins.")
            .colored(PonderPalette.GREEN)
            .pointAt(util.vector().centerOf(berries))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(50);
        scene.world().setBlock(berries, Blocks.AIR.defaultBlockState(), true);
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
