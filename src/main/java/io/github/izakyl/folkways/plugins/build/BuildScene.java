package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.plugins.CreateMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import net.createmod.catnip.math.Pointing;
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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class BuildScene {

    private static final int PLATE = 9;
    private static final float FACING_NORTH = 180.0F;
    private static final float FACING_SOUTH = 0.0F;
    private static final float FACING_WEST = 90.0F;
    private static final float FACING_EAST = 270.0F;

    private static final int LOW = 2;
    private static final int HIGH = 6;
    private static final int MIDDLE = 4;
    private static final int WALL_TOP = 3;
    private static final int ROOF = 4;
    private static final int SCAFFOLD_X = 7;

    private BuildScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        List<Scenes.Scene> scenes = new ArrayList<>();
        scenes.add(Scenes.scene("building", BuildScene::program));
        if (CreateMod.loaded()) {
            scenes.add(Scenes.scene("create_handoff", CreateHandoffScene::program));
        }
        event.enqueueWork(() -> Scenes.folder(BuildContent.ID, "folkways.page.build",
            ResourceLocation.withDefaultNamespace("bricks"), scenes.toArray(Scenes.Scene[]::new)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("building", "Building: draw it, and the colony raises it");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos chest = util.grid().at(1, 1, 0);
        BlockPos grass = util.grid().at(MIDDLE, 1, MIDDLE);
        BlockPos flower = util.grid().at(MIDDLE + 1, 1, MIDDLE - 1);
        scene.world().setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH),
            false);
        scene.world().setBlock(grass, Blocks.SHORT_GRASS.defaultBlockState(), false);
        scene.world().setBlock(flower, Blocks.POPPY.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 5, PLATE - 1), Direction.DOWN);
        scene.idle(15);

        ItemStack book = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        ColonyBookItem.setGesture(book, Shape.Gesture.BOX);
        Selection site = util.select().fromTo(LOW, 1, LOW, HIGH, ROOF, HIGH);

        scene.overlay().showControls(util.vector().topOf(LOW, 0, LOW), Pointing.DOWN, 30)
            .leftClick()
            .withItem(book);
        scene.idle(35);
        scene.overlay().showControls(util.vector().topOf(HIGH, 0, HIGH), Pointing.DOWN, 30)
            .leftClick()
            .withItem(book);
        scene.idle(20);
        scene.overlay().showOutline(PonderPalette.GREEN, site, site, 200);
        scene.overlay().showText(90)
            .text("With the book marking out, frame the ground and pick Raise a building from what the box can become. A line drawn with the book offers it too.")
            .pointAt(util.vector().topOf(MIDDLE, 0, LOW))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(90)
            .text("Then choose a pattern and its few settings, down to the blocks it is made of. Folkways ships a few, data packs add more under folkways/pattern, and your own go in the folkways/pattern folder of your game.")
            .pointAt(util.vector().topOf(MIDDLE, 0, LOW))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        Body resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(1, 0, 3), FACING_EAST);
        scene.idle(10);
        resident.hold(scene, new ItemStack(Items.IRON_SHOVEL));
        resident.walkTo(scene, util.vector().topOf(MIDDLE, 0, LOW - 1));
        resident.walkTo(scene, util.vector().topOf(MIDDLE, 0, MIDDLE - 1));
        resident.turnTo(scene, FACING_SOUTH);
        scene.idle(10);
        scene.world().destroyBlock(grass);
        scene.idle(10);
        scene.world().destroyBlock(flower);
        scene.overlay().showText(80)
            .text("Residents with the building trade take the order. First they clear what stands where the drawing wants empty space, working down from the top.")
            .pointAt(util.vector().topOf(grass.below()))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);
        resident.walkTo(scene, util.vector().topOf(MIDDLE, 0, LOW - 1));
        resident.walkTo(scene, util.vector().topOf(1, 0, 1));
        resident.turnTo(scene, FACING_NORTH);
        resident.hold(scene, new ItemStack(Items.COBBLESTONE, 32));
        scene.idle(10);

        layer(scene, util, resident, 1);
        scene.overlay().showText(80)
            .text("Then they lay it from the bottom up, a cell at a time, with blocks fetched from the colony's stores.")
            .pointAt(util.vector().topOf(LOW, 1, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        layer(scene, util, resident, 2);
        Selection owed = util.select().fromTo(LOW, WALL_TOP, LOW, HIGH, ROOF, HIGH);
        scene.overlay().showOutline(PonderPalette.WHITE, owed, owed, 90);
        scene.overlay().showText(90)
            .text("While you hold the book, the blocks still to go show as ghosts, and a card over the site counts the cells laid and lists, under Still needs, what the rest will take.")
            .pointAt(util.vector().topOf(MIDDLE, ROOF, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        layer(scene, util, resident, WALL_TOP);

        resident.walkTo(scene, util.vector().topOf(1, 0, 1));
        resident.hold(scene, new ItemStack(Items.SCAFFOLDING, 2));
        scene.idle(10);
        resident.walkTo(scene, util.vector().topOf(SCAFFOLD_X + 1, 0, 1));
        resident.walkTo(scene, util.vector().topOf(SCAFFOLD_X + 1, 0, MIDDLE));
        resident.turnTo(scene, FACING_WEST);
        scene.idle(10);
        Selection scaffold = util.select().fromTo(SCAFFOLD_X, 1, MIDDLE, SCAFFOLD_X, 2, MIDDLE);
        scene.world().setBlocks(scaffold, scaffolding(), false);
        resident.hold(scene, ItemStack.EMPTY);
        scene.idle(10);
        resident.walkTo(scene, util.vector().topOf(SCAFFOLD_X, 2, MIDDLE));
        resident.hold(scene, new ItemStack(Items.OAK_PLANKS, 25));
        scene.overlay().showOutline(PonderPalette.OUTPUT, scaffold, scaffold, 90);
        scene.overlay().showText(90)
            .text("A cell with nowhere in reach to stand gets a column of scaffolding beside it, taken from the stores. Once that part is laid it comes down, and the scaffolding goes back.")
            .pointAt(util.vector().topOf(SCAFFOLD_X, 2, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(40);
        for (int x = HIGH; x >= LOW; x--) {
            scene.world().setBlocks(util.select().fromTo(x, ROOF, LOW, x, ROOF, HIGH),
                Blocks.OAK_PLANKS.defaultBlockState(), false);
            scene.idle(8);
        }
        scene.idle(20);
        resident.hold(scene, ItemStack.EMPTY);
        resident.walkTo(scene, util.vector().topOf(SCAFFOLD_X + 1, 0, MIDDLE));
        resident.turnTo(scene, FACING_WEST);
        scene.idle(10);
        scene.world().setBlocks(scaffold, Blocks.AIR.defaultBlockState(), false);
        resident.hold(scene, new ItemStack(Items.SCAFFOLDING, 2));
        scene.idle(30);
        resident.walkTo(scene, util.vector().topOf(SCAFFOLD_X + 1, 0, 1));
        resident.walkTo(scene, util.vector().topOf(1, 0, 1));
        resident.hold(scene, ItemStack.EMPTY);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.GREEN, site, site, 90);
        scene.overlay().showText(90)
            .text("The Building page lists every build order with its cells done out of the total. Deleting one stops the work and takes any scaffolding it put up back down.")
            .pointAt(util.vector().topOf(MIDDLE, ROOF, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(90)
            .text("A pattern such as the mine grows in rounds: once one round is built, the next is drawn onto what stands now. The page says which round is coming up.")
            .pointAt(util.vector().topOf(MIDDLE, ROOF, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(70)
            .text("In creative mode, a drawing you confirm stands up at once.")
            .pointAt(util.vector().topOf(MIDDLE, ROOF, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);
    }

    private static void layer(SceneBuilder scene, SceneBuildingUtil util, Body resident, int y) {
        BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
        resident.walkTo(scene, util.vector().topOf(LOW - 1, 0, LOW - 1));
        resident.turnTo(scene, FACING_SOUTH);
        scene.world().setBlocks(util.select().fromTo(LOW, y, LOW, LOW, y, HIGH), stone, false);
        scene.idle(6);
        resident.walkTo(scene, util.vector().topOf(LOW - 1, 0, HIGH + 1));
        resident.turnTo(scene, FACING_EAST);
        scene.world().setBlocks(util.select().fromTo(LOW, y, HIGH, HIGH, y, HIGH), stone, false);
        scene.idle(6);
        resident.walkTo(scene, util.vector().topOf(HIGH + 1, 0, HIGH + 1));
        resident.turnTo(scene, FACING_NORTH);
        scene.world().setBlocks(util.select().fromTo(HIGH, y, LOW, HIGH, y, HIGH), stone, false);
        scene.idle(6);
        resident.walkTo(scene, util.vector().topOf(HIGH + 1, 0, LOW - 1));
        resident.turnTo(scene, FACING_WEST);
        scene.world().setBlocks(util.select().fromTo(LOW, y, LOW, HIGH, y, LOW), stone, false);
        if (y < WALL_TOP) {
            scene.world().setBlock(util.grid().at(MIDDLE, y, LOW), Blocks.AIR.defaultBlockState(), false);
        }
        scene.idle(6);
        resident.walkTo(scene, util.vector().topOf(LOW - 1, 0, LOW - 1));
        scene.idle(4);
    }

    private static BlockState scaffolding() {
        return Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0);
    }
}
