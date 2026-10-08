package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.plugins.person.PersonBody;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class WorkshopScene {

    private static final int PLATE = 7;
    private static final float FACING_NORTH = 180.0F;
    private static final float FACING_SOUTH = 0.0F;

    private WorkshopScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(WaresContent.ID, "folkways.page.wares",
            ResourceLocation.withDefaultNamespace("crafting_table"),
            Scenes.scene("workshop", WorkshopScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("workshop", "The workshop: stations the colony works");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos chest = util.grid().at(1, 1, 1);
        BlockPos table = util.grid().at(3, 1, 1);
        BlockPos cutter = util.grid().at(5, 1, 1);
        BlockPos furnace = util.grid().at(2, 1, 5);
        BlockPos second = util.grid().at(4, 1, 5);

        scene.world().setBlock(chest, facing(Blocks.CHEST, Direction.SOUTH), false);
        scene.world().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        scene.world().setBlock(cutter, facing(Blocks.STONECUTTER, Direction.SOUTH), false);
        scene.world().setBlock(furnace, facing(Blocks.FURNACE, Direction.NORTH), false);
        scene.world().setBlock(second, facing(Blocks.FURNACE, Direction.NORTH), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 2, PLATE - 1), Direction.DOWN);
        scene.idle(15);

        ItemStack book = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        ColonyBookItem.setGesture(book, Shape.Gesture.POINT);
        Selection stations = util.select().position(table).add(util.select().position(cutter))
            .add(util.select().position(furnace)).add(util.select().position(second));

        scene.overlay().showControls(util.vector().topOf(table), Pointing.DOWN, 80)
            .leftClick()
            .withItem(book);
        scene.idle(20);
        scene.overlay().showOutline(PonderPalette.GREEN, stations, stations, 90);
        scene.overlay().showText(90)
            .text("Point the book at a crafting table, a stonecutter or a smithing table and it joins the colony as a station. A furnace, blast furnace or smoker joins the same way.")
            .pointAt(util.vector().topOf(table))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(80)
            .text("A station needs a spot within reach and in sight of it for a resident to stand on. One walled in on every side is left out.")
            .pointAt(util.vector().blockSurface(cutter, Direction.SOUTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        Body resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 3), FACING_NORTH);
        scene.idle(10);

        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        resident.turnTo(scene, FACING_NORTH);
        scene.idle(10);
        resident.hold(scene, new ItemStack(Items.OAK_PLANKS, 2));
        scene.idle(10);
        resident.walkTo(scene, util.vector().topOf(3, 0, 2));
        resident.turnTo(scene, FACING_NORTH);
        scene.idle(20);
        resident.hold(scene, new ItemStack(Items.STICK, 4));
        scene.overlay().showOutline(PonderPalette.OUTPUT, table, util.select().position(table), 90);
        scene.overlay().showText(90)
            .text("Residents with the crafting trade work them. They make what the colony's orders call for, and fetch the ingredients from its stores first.")
            .pointAt(util.vector().topOf(table))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
        resident.hold(scene, ItemStack.EMPTY);

        resident.walkTo(scene, util.vector().topOf(5, 0, 2));
        resident.turnTo(scene, FACING_NORTH);
        resident.hold(scene, new ItemStack(Items.STONE, 1));
        scene.idle(20);
        resident.hold(scene, new ItemStack(Items.STONE_BRICKS, 1));
        scene.overlay().showText(80)
            .text("The stonecutter works its own recipes. The smithing table is used for upgrades only, never for trims.")
            .pointAt(util.vector().topOf(cutter))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);
        resident.hold(scene, ItemStack.EMPTY);

        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        resident.hold(scene, new ItemStack(Items.RAW_IRON, 16));
        scene.idle(10);
        resident.walkTo(scene, util.vector().topOf(2, 0, 4));
        resident.turnTo(scene, FACING_SOUTH);
        scene.idle(10);
        resident.hold(scene, new ItemStack(Items.RAW_IRON, 8));
        scene.world().modifyBlock(furnace, state -> state.setValue(BlockStateProperties.LIT, true), false);
        scene.idle(10);
        resident.walkTo(scene, util.vector().topOf(4, 0, 4));
        resident.turnTo(scene, FACING_SOUTH);
        scene.idle(10);
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showOutline(PonderPalette.FAST, "furnaces",
            util.select().position(furnace).add(util.select().position(second)), 90);
        scene.overlay().showText(90)
            .text("At a furnace they put in a load and come back for it once it is cooked. A large batch is shared out among the idle furnaces of the same kind.")
            .pointAt(util.vector().topOf(second))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        resident.hold(scene, new ItemStack(Items.COAL, 1));
        scene.idle(10);
        resident.walkTo(scene, util.vector().topOf(4, 0, 4));
        resident.turnTo(scene, FACING_SOUTH);
        scene.idle(10);
        resident.hold(scene, ItemStack.EMPTY);
        scene.world().modifyBlock(second, state -> state.setValue(BlockStateProperties.LIT, true), false);
        scene.overlay().showText(90)
            .text("A loaded furnace that has gone cold gets fuel one piece at a time, and only what is on the fuel list: coal and charcoal to begin with.")
            .pointAt(util.vector().topOf(second))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.world().modifyBlock(furnace, state -> state.setValue(BlockStateProperties.LIT, false), false);
        resident.walkTo(scene, util.vector().topOf(2, 0, 4));
        resident.turnTo(scene, FACING_SOUTH);
        scene.idle(15);
        resident.hold(scene, new ItemStack(Items.IRON_INGOT, 8));
        scene.overlay().showOutline(PonderPalette.OUTPUT, furnace, util.select().position(furnace), 80);
        scene.overlay().showText(80)
            .text("Anything left finished in a furnace that nobody is waiting on gets taken out too, so the next load has room.")
            .pointAt(util.vector().topOf(furnace))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);
        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        resident.hold(scene, ItemStack.EMPTY);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.GREEN, stations, stations, 90);
        scene.overlay().showText(90)
            .text("The Workshop page counts the stations and lists each one, a furnace with the fuel in it or No fuel. The fuel list is the first row there.")
            .pointAt(util.vector().topOf(furnace))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
    }

    private static BlockState facing(Block block, Direction direction) {
        return block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, direction);
    }
}
