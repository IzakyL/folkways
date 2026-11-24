package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.front.api.GhostRecipes;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.vanilla.build.draft.client.OrderPreviews;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
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
        event.enqueueWork(() -> GhostRecipes.install(
            new OrderPreviews()));
        event.enqueueWork(() -> Scenes.folder(Scenes.Category.BUILDING, BuildContent.ID, "folkways.page.build",
            ResourceLocation.withDefaultNamespace("bricks"), Scenes.scene("folkways_vanilla", "building", BuildScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("building", "Building: draw it, and the colony builds it");
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

        ItemStack book = PonderScenery.book(Shape.Gesture.BOX);
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
            .text("Mark out a box or draw a line with the book, then right-click and choose Pattern.")
            .pointAt(util.vector().topOf(MIDDLE, 0, LOW))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(90)
            .text("Choose a pattern and its settings, down to the blocks it uses. Add your own patterns in the folkways/pattern folder or a data pack.")
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
            .text("Builders take the order. First they clear whatever is in the way, from the top down. Containers are emptied into storage first.")
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
            .text("Then they build from the bottom up, with blocks from the colony's storage.")
            .pointAt(util.vector().topOf(LOW, 1, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        layer(scene, util, resident, 2);
        Selection owed = util.select().fromTo(LOW, WALL_TOP, LOW, HIGH, ROOF, HIGH);
        scene.overlay().showOutline(PonderPalette.WHITE, owed, owed, 90);
        scene.overlay().showText(90)
            .text("Hold the book to see the missing blocks as ghosts. A card above the site shows progress and what it Still needs.")
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
            .text("Where a block is out of reach, they put up scaffolding from storage and take it down afterwards.")
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
            .text("Build orders are listed under Facilities. Cancel on an order's card stops the work and removes its scaffolding.")
            .pointAt(util.vector().topOf(MIDDLE, ROOF, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(90)
            .text("A mine is planned all at once from the ground as it stands, as one ordinary build order.")
            .pointAt(util.vector().topOf(MIDDLE, ROOF, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(70)
            .text("In creative mode, Build now places it instantly.")
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
