package io.github.izakyl.folkways.core.front.client.ponder.scenes;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

// The panel itself cannot be drawn in a ponder world, so each page is named in text and pointed at
// the things in the world it is about.
@OnlyIn(Dist.CLIENT)
public final class ColonyPanelScene {

    private static final int PLATE = 7;
    private static final float FACING_NORTH = 180.0F;
    private static final float FACING_WEST = 90.0F;

    private ColonyPanelScene() {
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("colony_panel", "The Colony Book's pages");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos chest = util.grid().at(1, 1, 1);
        BlockPos table = util.grid().at(3, 1, 1);
        BlockPos furnace = util.grid().at(4, 1, 1);
        BlockPos bedHead = util.grid().at(6, 1, 3);
        BlockPos bedFoot = util.grid().at(6, 1, 4);

        scene.world().setBlock(chest,
            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), false);
        scene.world().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        scene.world().setBlock(furnace,
            Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, Direction.SOUTH), false);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        scene.world().setBlock(bedHead, bed.setValue(BedBlock.PART, BedPart.HEAD), false);
        scene.world().setBlock(bedFoot, bed.setValue(BedBlock.PART, BedPart.FOOT), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 1, PLATE - 1), Direction.DOWN);
        scene.idle(15);

        PonderScenery.Body miner =
            PonderScenery.body(scene, SceneBooks.resident(), util.vector().topOf(2, 0, 4), FACING_NORTH);
        PonderScenery.Body cook =
            PonderScenery.body(scene, SceneBooks.resident(), util.vector().topOf(4, 0, 3), FACING_WEST);
        scene.idle(15);

        ItemStack book = SceneBooks.book();
        Selection chestArea = util.select().position(chest);
        Selection stations = util.select().fromTo(table, furnace);
        Selection beds = util.select().fromTo(bedHead, bedFoot);
        Vec3 middle = util.vector().topOf(3, 0, 5);
        Vec3 minerHead = miner.at().add(0, 1.8, 0);
        Vec3 cookHead = cook.at().add(0, 1.8, 0);

        scene.overlay().showControls(middle, Pointing.DOWN, 50).rightClick().withItem(book);
        scene.idle(55);
        scene.overlay()
            .showText(80)
            .text("Right-click with the book to open it. Its pages are listed on the left.")
            .pointAt(middle)
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay()
            .showText(90)
            .text("Overview shows what needs attention. Workforce sets each resident's trades.")
            .pointAt(minerHead)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showOutline(PonderPalette.GREEN, "stock", chestArea, 100);
        scene.overlay()
            .showText(100)
            .text("Stock & orders lists what the colony's containers hold. Click Refresh to count again.")
            .pointAt(util.vector().blockSurface(chest, Direction.SOUTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        scene.overlay().showOutline(PonderPalette.RED, "stock", chestArea, 90);
        scene.overlay()
            .showText(90)
            .text("Delete colony, under Settings → Advanced, ends the colony for good. You must type a phrase to confirm.")
            .pointAt(util.vector().blockSurface(chest, Direction.SOUTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showOutline(PonderPalette.GREEN, "stations", stations, 110);
        scene.overlay().showOutline(PonderPalette.GREEN, "beds", beds, 110);
        scene.overlay()
            .showText(110)
            .text("Facilities lists everything the colony runs, such as stations and beds. Click one to open its card. Colony-wide lists, like fuel and food, are in Settings.")
            .pointAt(util.vector().topOf(furnace))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(120);

        miner.hold(scene, new ItemStack(Items.IRON_PICKAXE));
        cook.hold(scene, new ItemStack(Items.IRON_SHOVEL));
        scene.idle(10);
        scene.overlay()
            .showText(100)
            .text("Settings → Trade tools sets which tools each trade may use. A list left alone uses the trade's defaults; an empty list allows none.")
            .pointAt(minerHead)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        scene.overlay()
            .showText(90)
            .text("Lessons groups every lesson by topic. Watch one, or click Watch all.")
            .pointAt(cookHead)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay()
            .showText(100)
            .text("Hover a page's tab and hold the ponder key to play that page's lesson.")
            .pointAt(middle)
            .attachKeyFrame();
        scene.idle(110);
    }
}
