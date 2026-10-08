package io.github.izakyl.folkways.front.client.ponder.scenes;

import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import java.util.List;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class BookModesScene {

    private static final int PLATE = 7;
    private static final String DRAFT = "draft";

    private BookModesScene() {
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("book_modes", "One book, three things a click can mean");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos chest = util.grid().at(1, 1, 1);
        scene.world().setBlock(chest,
            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 1, PLATE - 1), Direction.DOWN);
        scene.idle(20);

        ItemStack pointBook = SceneBooks.bound(Shape.Gesture.POINT);
        ItemStack boxBook = SceneBooks.bound(Shape.Gesture.BOX);
        ItemStack lineBook = SceneBooks.bound(Shape.Gesture.LINE);

        Selection chestArea = util.select().position(chest);
        Selection framed = util.select().fromTo(3, 1, 1, 5, 2, 3);
        Selection pushed = util.select().fromTo(3, 1, 1, 6, 2, 3);
        Vec3 middle = util.vector().topOf(3, 0, 3);
        List<Vec3> stops = List.of(
            util.vector().topOf(0, 0, 5),
            util.vector().topOf(2, 0, 6),
            util.vector().topOf(4, 0, 5),
            util.vector().topOf(6, 0, 6));

        scene.overlay()
            .showText(70)
            .text("A left click with a bound book acts on the world. Which of three things it does shows on the book itself.")
            .pointAt(middle)
            .attachKeyFrame();
        scene.idle(80);

        scene.overlay().showControls(util.vector().topOf(chest), Pointing.DOWN, 110)
            .leftClick()
            .withItem(pointBook);
        scene.idle(30);
        scene.overlay().showOutline(PonderPalette.GREEN, "member", chestArea, 80);
        scene.overlay()
            .showText(80)
            .text("Pointing: a left click takes the block or the body under your crosshair into the colony, or back out of it.")
            .pointAt(util.vector().blockSurface(chest, Direction.SOUTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showControls(middle, Pointing.DOWN, 110)
            .scroll()
            .whileSneaking()
            .withItem(pointBook);
        scene.idle(30);
        scene.overlay()
            .showText(80)
            .text("Sneak and scroll turns the book to the next gesture. A second book keeps whatever it was left in.")
            .pointAt(middle)
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showControls(util.vector().topOf(3, 0, 1), Pointing.DOWN, 30)
            .leftClick()
            .withItem(boxBook);
        scene.idle(35);
        scene.overlay().showControls(util.vector().topOf(5, 0, 3), Pointing.DOWN, 40)
            .leftClick()
            .withItem(boxBook);
        scene.idle(35);
        scene.overlay().showOutline(PonderPalette.WHITE, DRAFT, framed, 100);
        scene.overlay()
            .showText(80)
            .text("Marking out: that same click draws a box instead, one click on each of two opposite corners.")
            .pointAt(util.vector().topOf(4, 0, 2))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        Vec3 eastFace = util.vector().blockSurface(util.grid().at(5, 1, 2), Direction.EAST);
        scene.overlay().showControls(eastFace, Pointing.LEFT, 60)
            .scroll()
            .whileCTRL()
            .withItem(boxBook);
        scene.idle(25);
        scene.overlay().showOutline(PonderPalette.WHITE, DRAFT, pushed, 110);
        scene.overlay()
            .showText(100)
            .text("Hold Ctrl (Cmd on a Mac) and scroll. Before the box is framed this sets a reach, and a Ctrl click drops a corner that far out, in open air. Once it is framed, it pushes the face you aim at in or out.")
            .pointAt(eastFace)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        scene.overlay().showOutline(PonderPalette.WHITE, DRAFT, pushed, 100);
        scene.overlay().showControls(util.vector().topOf(4, 1, 2), Pointing.DOWN, 40)
            .rightClick()
            .withItem(boxBook);
        scene.idle(45);
        scene.overlay()
            .showText(90)
            .text("Then right click. The panel asks what the ground is for, and the answers come from the parts of Folkways you have installed: a farm, a pasture, a building, and so on.")
            .pointAt(util.vector().topOf(4, 1, 2))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        int lineHold = 170;
        for (int at = 0; at < stops.size(); at++) {
            Vec3 stop = stops.get(at);
            scene.overlay().showControls(stop, Pointing.DOWN, 20).leftClick().withItem(lineBook);
            scene.idle(12);
            if (at > 0) {
                scene.overlay().showLine(PonderPalette.WHITE, stops.get(at - 1), stop, lineHold);
            }
            scene.idle(8);
            lineHold -= 20;
        }
        scene.overlay()
            .showText(80)
            .text("Drawing a line: each left click adds a stop, joined to the one before, up to 4096 of them. A right click asks what the path is for.")
            .pointAt(stops.get(2))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        scene.overlay().showControls(stops.get(3), Pointing.DOWN, 40)
            .leftClick()
            .whileSneaking()
            .withItem(lineBook);
        scene.idle(30);
        scene.overlay()
            .showText(70)
            .text("Sneak and left click throws the box or line you are drawing away.")
            .pointAt(stops.get(3))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        scene.overlay().showOutline(PonderPalette.BLUE, "zone", framed, 110);
        scene.overlay().showControls(util.vector().topOf(4, 1, 2), Pointing.DOWN, 40)
            .rightClick()
            .withItem(pointBook);
        scene.idle(45);
        scene.overlay()
            .showText(90)
            .text("Aimed at a zone or path already marked out, right click opens its settings, whichever gesture the book is in. Anywhere else, it opens the colony panel.")
            .pointAt(util.vector().topOf(4, 1, 2))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
    }
}
