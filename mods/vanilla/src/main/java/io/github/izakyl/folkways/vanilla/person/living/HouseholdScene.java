package io.github.izakyl.folkways.vanilla.person.living;

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
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class HouseholdScene {

    private static final int PLATE = 8;
    private static final float FACING_NORTH = 180.0F;

    private HouseholdScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(Scenes.Category.PEOPLE, LivingContent.ID, "folkways.page.living",
            ResourceLocation.withDefaultNamespace("red_bed"),
            Scenes.scene("folkways_vanilla", "household", HouseholdScene::program),
            Scenes.scene("folkways_vanilla", "housing", HousingScene::program),
            Scenes.scene("folkways_vanilla", "dining", DiningScene::program).when(() -> DiningTables.sample().isPresent()),
            Scenes.scene("folkways_vanilla", "recreation", RecreationScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("household", "Household: beds, sleep and meals");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos redHead = util.grid().at(1, 1, 1);
        BlockPos redFoot = util.grid().at(1, 1, 2);
        BlockPos blueHead = util.grid().at(3, 1, 1);
        BlockPos blueFoot = util.grid().at(3, 1, 2);
        BlockPos chest = util.grid().at(6, 1, 1);

        scene.world().setBlock(redHead, bed(Blocks.RED_BED, BedPart.HEAD), false);
        scene.world().setBlock(redFoot, bed(Blocks.RED_BED, BedPart.FOOT), false);
        scene.world().setBlock(blueHead, bed(Blocks.BLUE_BED, BedPart.HEAD), false);
        scene.world().setBlock(blueFoot, bed(Blocks.BLUE_BED, BedPart.FOOT), false);
        scene.world().setBlock(chest,
            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), false);

        Selection redBed = util.select().fromTo(redHead, redFoot);
        Selection blueBed = util.select().fromTo(blueHead, blueFoot);

        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 1, PLATE - 1), Direction.DOWN);
        scene.idle(15);

        Vec3 startA = util.vector().topOf(2, 0, 4);
        Body residentA = PonderScenery.body(scene, PersonBody.RESIDENT.get(), startA, FACING_NORTH);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.BLUE, "red", redBed, 90);
        scene.overlay().showOutline(PonderPalette.GREEN, "blue", blueBed, 90);
        scene.overlay()
            .showText(90)
            .text("Take in beds with the book. Residents claim free beds on their own.")
            .pointAt(util.vector().topOf(redFoot))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        residentA.walkTo(scene, util.vector().topOf(5, 0, 4));
        residentA.walkTo(scene, util.vector().topOf(5, 0, 6));
        residentA.hold(scene, new ItemStack(Items.IRON_PICKAXE));
        scene.idle(10);
        scene.overlay()
            .showText(90)
            .text("Walking and work tire residents out. When tired, they go to their own bed to sleep. Without a bed they cannot sleep, and their mood drops.")
            .pointAt(residentA.at().add(0.0, 1.5, 0.0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(60);
        residentA.hold(scene, ItemStack.EMPTY);
        residentA.walkTo(scene, util.vector().topOf(2, 0, 3));
        residentA.walkTo(scene, util.vector().topOf(1, 0, 3));
        residentA.turnTo(scene, FACING_NORTH);
        scene.overlay().showOutline(PonderPalette.BLUE, "red", redBed, 40);
        scene.idle(40);

        residentA.walkTo(scene, util.vector().topOf(4, 0, 4));
        scene.overlay()
            .showText(80)
            .text("Work and walking use up food. Time spent working and distance walked add up to fatigue.")
            .pointAt(residentA.at().add(0.0, 1.5, 0.0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        residentA.walkTo(scene, util.vector().topOf(6, 0, 2));
        residentA.turnTo(scene, FACING_NORTH);
        scene.idle(10);
        residentA.hold(scene, new ItemStack(Items.BREAD, 2));
        scene.overlay().showOutline(PonderPalette.OUTPUT, "chest", util.select().position(chest), 90);
        scene.overlay()
            .showText(100)
            .text("Each resident carries three days of food and eats when hungry. Choose allowed food under Settings → Food and daily needs.")
            .pointAt(util.vector().topOf(chest))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        residentA.hold(scene, ItemStack.EMPTY);
        scene.overlay().showOutline(PonderPalette.RED, "chest", util.select().position(chest), 80);
        scene.overlay()
            .showText(80)
            .text("With no allowed food in storage, packs stay empty, and a starving resident loses health.")
            .pointAt(util.vector().topOf(chest))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay()
            .showText(90)
            .text("Hold the book to see a card over each resident: a drumstick for fullness and a moon for rest.")
            .pointAt(residentA.at().add(0.0, 2.2, 0.0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showOutline(PonderPalette.BLUE, "red", redBed, 100);
        scene.overlay().showText(100)
            .text("Hold the book to see a card over each bed: who sleeps there, the room grade and how to improve it.")
            .pointAt(util.vector().topOf(redFoot)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
    }

    private static BlockState bed(Block block, BedPart part) {
        return block.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, part);
    }
}
