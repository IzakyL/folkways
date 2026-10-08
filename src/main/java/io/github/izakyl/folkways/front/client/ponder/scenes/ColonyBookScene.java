package io.github.izakyl.folkways.front.client.ponder.scenes;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ColonyBookScene {

    private static final int PLATE = 7;
    private static final float FACING_NORTH = 180.0F;

    private ColonyBookScene() {
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("colony_book", "A colony is the blocks you mark");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos chest = util.grid().at(1, 1, 1);
        BlockPos table = util.grid().at(3, 1, 1);
        BlockPos bedHead = util.grid().at(5, 1, 1);
        BlockPos bedFoot = util.grid().at(5, 1, 2);
        BlockPos log = util.grid().at(5, 1, 4);

        scene.world().setBlock(chest,
            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), false);
        scene.world().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        scene.world().setBlock(bedHead, bed.setValue(BedBlock.PART, BedPart.HEAD), false);
        scene.world().setBlock(bedFoot, bed.setValue(BedBlock.PART, BedPart.FOOT), false);
        scene.world().setBlock(log, Blocks.OAK_LOG.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 1, PLATE - 1), Direction.DOWN);
        scene.idle(20);

        ItemStack blank = SceneBooks.blank();
        ItemStack book = SceneBooks.bound();
        Selection chestArea = util.select().position(chest);
        Selection sites = util.select().position(table).add(util.select().fromTo(bedHead, bedFoot));
        Selection refused = util.select().position(log);
        Vec3 ground = util.vector().topOf(3, 0, 3);

        scene.overlay()
            .showText(70)
            .text("An empty colony book is crafted from a plain book. It belongs to no colony yet.")
            .pointAt(ground)
            .attachKeyFrame();
        scene.idle(80);

        scene.overlay().showControls(ground, Pointing.DOWN, 50).rightClick().withItem(blank);
        scene.idle(55);
        scene.overlay()
            .showText(100)
            .text("A right click opens the colony panel. With the empty book in your main hand it offers one button, New Colony. Press it, and the book is the new colony's deed.")
            .pointAt(ground)
            .attachKeyFrame();
        scene.idle(110);

        scene.overlay()
            .showText(60)
            .text("Craft that book together with a blank one to get a second deed for the same colony.")
            .pointAt(ground)
            .attachKeyFrame();
        scene.idle(70);

        scene.overlay().showControls(util.vector().topOf(chest), Pointing.DOWN, 30).leftClick().withItem(book);
        scene.idle(35);
        scene.overlay().showOutline(PonderPalette.GREEN, "storage", chestArea, 70);
        scene.overlay()
            .showText(70)
            .text("Left click a chest with the bound book and it joins the colony as storage. Click it again to take it back out.")
            .pointAt(util.vector().blockSurface(chest, Direction.SOUTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(60);
        scene.overlay().showControls(util.vector().topOf(chest), Pointing.DOWN, 20).leftClick().withItem(book);
        scene.idle(30);

        scene.overlay().showControls(util.vector().topOf(table), Pointing.DOWN, 25).leftClick().withItem(book);
        scene.idle(30);
        scene.overlay().showControls(util.vector().topOf(bedHead), Pointing.DOWN, 25).leftClick().withItem(book);
        scene.idle(30);
        scene.overlay().showOutline(PonderPalette.GREEN, "sites", sites, 90);
        scene.overlay()
            .showText(90)
            .text("Besides containers, only blocks some part of Folkways puts to use can join: a work station, a bed. What each is for is shown where it is used.")
            .pointAt(util.vector().topOf(table))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showControls(util.vector().topOf(log), Pointing.DOWN, 30).leftClick().withItem(book);
        scene.idle(35);
        scene.overlay().showOutline(PonderPalette.RED, "refused", refused, 70);
        scene.overlay()
            .showText(70)
            .text("Anything else is refused: the colony has no use for it.")
            .pointAt(util.vector().blockSurface(log, Direction.WEST))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        PonderScenery.Body resident =
            PonderScenery.body(scene, SceneBooks.resident(), util.vector().topOf(2, 0, 5), FACING_NORTH);
        scene.idle(15);
        scene.overlay().showControls(resident.at().add(0, 2.2, 0), Pointing.DOWN, 30)
            .leftClick().withItem(book);
        scene.idle(35);
        scene.overlay()
            .showText(80)
            .text("Bodies take the same click. Pointed at one of your own residents, it lets them go.")
            .pointAt(resident.at().add(0, 1.8, 0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showOutline(PonderPalette.GREEN, "storage", chestArea.copy().add(sites), 100);
        scene.overlay()
            .showText(100)
            .text("A colony is not a patch of ground you fence off. It is the blocks you marked yourself.")
            .pointAt(ground)
            .attachKeyFrame();
        scene.idle(110);
    }
}
