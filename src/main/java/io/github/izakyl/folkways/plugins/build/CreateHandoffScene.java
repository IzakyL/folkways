package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

// Only declared when Create is loaded; Create's items are looked up by id so this class never loads Create's.
@OnlyIn(Dist.CLIENT)
public final class CreateHandoffScene {

    private static final int PLATE = 7;
    private static final float FACING_NORTH = 180.0F;
    private static final float FACING_SOUTH = 0.0F;

    private static final int LOW = 2;
    private static final int HIGH = 4;
    private static final int MIDDLE = 3;

    private CreateHandoffScene() {
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("create_handoff", "Handing a Create schematic to the colony");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos boulder = util.grid().at(MIDDLE, 1, MIDDLE);
        scene.world().setBlock(boulder, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 3, PLATE - 1), Direction.DOWN);
        scene.idle(15);

        ItemStack schematic = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("create", "schematic")));
        ItemStack book = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        Selection site = util.select().fromTo(LOW, 1, LOW, HIGH, 2, HIGH);
        Selection empty = util.select().position(boulder);

        scene.overlay().showOutline(PonderPalette.BLUE, site, site, 90);
        scene.overlay().showControls(util.vector().topOf(MIDDLE, 2, MIDDLE), Pointing.DOWN, 80)
            .withItem(schematic);
        scene.overlay().showText(90)
            .text("With Create installed, a schematic you have put in place with Create's own tools can go to the colony instead of to a schematicannon.")
            .pointAt(util.vector().topOf(MIDDLE, 2, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showControls(util.vector().topOf(HIGH, 2, LOW), Pointing.DOWN, 80)
            .withItem(book);
        scene.overlay().showText(80)
            .text("Hold the schematic, and the colony's book in your other hand.")
            .pointAt(util.vector().topOf(HIGH, 2, LOW))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showControls(util.vector().topOf(MIDDLE, 2, MIDDLE), Pointing.DOWN, 90)
            .rightClick()
            .withItem(schematic);
        scene.overlay().showText(90)
            .text("The schematic's tools have one more among them: Hand Off, marked with the colony book. Choose it and right click.")
            .pointAt(util.vector().topOf(MIDDLE, 2, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showOutline(PonderPalette.RED, empty, empty, 100);
        scene.overlay().showText(100)
            .text("A small window asks one thing: what to do with cells the blueprint leaves empty. Leave what stands there, which it starts on, or dig it out.")
            .pointAt(util.vector().topOf(boulder))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        scene.overlay().showText(90)
            .text("Place the build order. It is filed where the schematic stands, turned and mirrored the way you set it.")
            .pointAt(util.vector().topOf(MIDDLE, 2, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(60);

        Body resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(MIDDLE, 0, 0),
            FACING_SOUTH);
        scene.idle(10);
        resident.hold(scene, new ItemStack(Items.IRON_PICKAXE));
        resident.walkTo(scene, util.vector().topOf(MIDDLE, 0, MIDDLE - 1));
        scene.idle(10);
        scene.world().destroyBlock(boulder);
        scene.idle(15);
        resident.walkTo(scene, util.vector().topOf(MIDDLE, 0, LOW - 1));
        resident.hold(scene, new ItemStack(Items.STONE_BRICKS, 16));
        BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
        for (int y = 1; y <= 2; y++) {
            scene.world().setBlocks(util.select().fromTo(LOW, y, LOW, LOW, y, HIGH), bricks, false);
            scene.idle(5);
            scene.world().setBlocks(util.select().fromTo(LOW, y, HIGH, HIGH, y, HIGH), bricks, false);
            scene.idle(5);
            scene.world().setBlocks(util.select().fromTo(HIGH, y, LOW, HIGH, y, HIGH), bricks, false);
            scene.idle(5);
            scene.world().setBlocks(util.select().fromTo(LOW, y, LOW, HIGH, y, LOW), bricks, false);
            scene.idle(10);
        }
        resident.turnTo(scene, FACING_NORTH);
        resident.hold(scene, ItemStack.EMPTY);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.GREEN, site, site, 90);
        scene.overlay().showText(90)
            .text("From there it is a build order like any other: residents raise it, and it shows on the Building page. It is never raised at once, not even in creative.")
            .pointAt(util.vector().topOf(MIDDLE, 2, MIDDLE))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
    }
}
