package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class ElevatorScene {
    private ElevatorScene() { }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(ElevatorPlugin.ID, "folkways.lessons.elevator",
            ResourceLocation.fromNamespaceAndPath("create", "elevator_pulley"),
            Scenes.scene("elevator", ElevatorScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("elevator", "Elevators: walk in, ride and walk out");
        PonderScenery.openWorld(scene, util, 9);
        PonderScenery.grassGround(scene, util, 9);
        BlockPos pulley = util.grid().at(3, 7, 3);
        scene.world().setBlock(pulley, PonderScenery.foreign(ResourceLocation.fromNamespaceAndPath("create", "elevator_pulley"),
            "facing", "north"), false);
        scene.world().setBlock(pulley.west(), PonderScenery.foreign(ResourceLocation.fromNamespaceAndPath("create", "creative_motor"),
            "facing", "east"), false);
        for (int y : new int[] {2, 5}) {
            scene.world().setBlock(util.grid().at(3, y, 5), PonderScenery.foreign(
                ResourceLocation.fromNamespaceAndPath("create", "elevator_contact"), "facing", "north"), false);
        }
        scene.world().setBlocks(util.select().fromTo(5, 1, 2, 7, 1, 4), Blocks.STONE_BRICKS.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(5, 4, 2, 7, 4, 4), Blocks.STONE_BRICKS.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(2, 1, 2, 4, 1, 4), Blocks.OAK_PLANKS.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(2, 2, 4, 2, 3, 4), Blocks.OAK_LOG.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(4, 2, 4, 4, 3, 4), Blocks.OAK_LOG.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(2, 3, 4, 4, 3, 4), Blocks.OAK_PLANKS.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(3, 2, 4), PonderScenery.foreign(
            ResourceLocation.fromNamespaceAndPath("create", "redstone_contact"), "facing", "south"), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 0, 8), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(5, 1, 2, 7, 4, 4), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(3, 2, 5, 3, 5, 5), Direction.DOWN);
        scene.world().showSection(util.select().fromTo(pulley.west(), pulley), Direction.DOWN);
        var cabin = scene.world().showIndependentSection(util.select().fromTo(2, 1, 2, 4, 3, 4), Direction.DOWN);
        scene.idle(20);
        scene.overlay().showText(130)
            .text("Build and power a Create elevator: a glued cabin under an elevator pulley, a moving redstone contact and matching elevator contacts at the floors. Keep the shaft clear and provide walkable landings beside the cabin.")
            .pointAt(util.vector().topOf(4, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(140);
        scene.overlay().showControls(util.vector().centerOf(pulley), Pointing.DOWN, 60)
            .leftClick().withItem(PonderScenery.book(Shape.Gesture.POINT));
        scene.overlay().showText(110)
            .text("With the book pointing, left click the elevator pulley to take it into the colony. Residents can then plan routes between its floors, even while the cabin is elsewhere.")
            .pointAt(util.vector().topOf(5, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        var rider = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(7, 1, 3), 90);
        var waiting = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(7, 1, 4), 90);
        rider.walkTo(scene, util.vector().topOf(3, 1, 3));
        scene.overlay().showOutline(PonderPalette.GREEN, "standing", util.select().position(3, 1, 3), 130);
        scene.overlay().showText(135)
            .text("A resident walks to the landing and waits for the cabin. When it arrives, he walks into a free standing place. Capacity comes from the clear connected deck, with room left for passing; adding seats does not add capacity.")
            .pointAt(rider.at().add(0, 1.5, 0)).placeNearTarget().attachKeyFrame();
        scene.idle(145);
        scene.world().moveSection(cabin, util.vector().of(0, 3, 0), 60);
        for (int step = 1; step <= 60; step++) {
            rider.moveTo(scene, util.vector().topOf(3, 1, 3).add(0, step / 20.0, 0), 90);
            scene.idle(1);
        }
        rider.walkTo(scene, util.vector().topOf(6, 4, 3));
        scene.overlay().showText(110)
            .text("The elevator carries standing residents to their requested floors. At the stop they walk out onto the landing and continue to work or bed. Keep the entrances and exits clear.")
            .pointAt(rider.at().add(0, 1, 0)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        scene.world().moveSection(cabin, util.vector().of(0, -3, 0), 60);
        scene.idle(65);
        waiting.walkTo(scene, util.vector().topOf(5, 1, 4));
        waiting.walkTo(scene, util.vector().topOf(3, 1, 3));
        scene.overlay().showText(110)
            .text("A full cabin leaves the next resident waiting for a later trip. A stopped or overstressed drive cannot provide a ride; keep the pulley powered and its floor contacts reachable.")
            .pointAt(waiting.at().add(0, 1.5, 0)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
    }
}
