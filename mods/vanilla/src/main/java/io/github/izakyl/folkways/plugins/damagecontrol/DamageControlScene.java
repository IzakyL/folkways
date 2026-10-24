package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class DamageControlScene {
    private DamageControlScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(DamageControlContent.ID, "folkways.page.damage_control",
            ResourceLocation.withDefaultNamespace("water_bucket"),
            Scenes.scene("damage_control", DamageControlScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("damage_control", "Damage control: put out fires and restore missing blocks");
        PonderScenery.openWorld(scene, util, 7);
        PonderScenery.grassGround(scene, util, 7);
        scene.world().setBlocks(util.select().fromTo(2, 1, 3, 4, 2, 3), Blocks.OAK_PLANKS.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(2, 1, 3, 4, 2, 3), Direction.DOWN);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "zone", util.select().fromTo(1, 1, 1, 5, 3, 5), 100);
        scene.overlay().showText(90)
            .text("Frame a damage control zone and assign residents to damage control. On-duty workers leave ordinary work to fight fires; fleeing danger still comes first.")
            .pointAt(util.vector().centerOf(3, 1, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        var worker = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(1, 0, 2), 90);
        scene.world().setBlock(util.grid().at(2, 1, 2), Blocks.FIRE.defaultBlockState(), false);
        worker.walkTo(scene, util.vector().topOf(1, 0, 2));
        scene.idle(20);
        scene.world().setBlock(util.grid().at(2, 1, 2), Blocks.AIR.defaultBlockState(), false);
        scene.overlay().showText(90)
            .text("Workers smother nearby fire without placing water. With the firefighting extension, they borrow a water-filled extinguisher and refill it from colony supplies.")
            .pointAt(util.vector().centerOf(2, 1, 2)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.overlay().showText(90)
            .text("Record a baseline on the Damage Control page, then enable repairs for the zone. Only missing building blocks are restored, using materials from colony stores.")
            .pointAt(util.vector().centerOf(3, 2, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.world().setBlock(util.grid().at(3, 2, 3), Blocks.AIR.defaultBlockState(), false);
        scene.idle(30);
        scene.world().setBlock(util.grid().at(3, 2, 3), Blocks.OAK_PLANKS.defaultBlockState(), false);
        scene.overlay().showText(90)
            .text("Containers, crops, fluids and machines stay out of the baseline. Different blocks are reported as conflicts. After remodelling, record the baseline again.")
            .pointAt(util.vector().centerOf(3, 2, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
    }
}
