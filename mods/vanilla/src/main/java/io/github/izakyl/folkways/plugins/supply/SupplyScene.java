package io.github.izakyl.folkways.plugins.supply;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
final class SupplyScene {
    private SupplyScene() {
    }

    static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(SupplyContent.ID, "folkways.page.supply", SupplyContent.SOURCE,
            Scenes.scene("creative_supplies", SupplyScene::program, SupplyContent.SOURCE)));
    }

    static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("creative_supplies", "Creative supplies: only what the task needs");
        PonderScenery.openWorld(scene, util, 7);
        PonderScenery.grassGround(scene, util, 7);
        BlockPos source = util.grid().at(1, 1, 2);
        BlockPos table = util.grid().at(3, 1, 2);
        BlockPos store = util.grid().at(5, 1, 2);
        scene.world().setBlock(source, SupplyContent.BLOCK.get().defaultBlockState(), false);
        scene.world().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 2, 6), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showControls(util.vector().topOf(source), Pointing.DOWN, 70)
            .rightClick().withItem(new ItemStack(SupplyContent.ITEM.get()));
        scene.overlay().showText(90)
            .text("Place and configure a creative supply box in Creative mode. Choose all items, or a whitelist of items, tags and searches.")
            .pointAt(util.vector().topOf(source)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.overlay().showOutline(PonderPalette.GREEN, source, util.select().position(source), 90);
        scene.overlay().showText(90)
            .text("Mark the box with the colony book. It supplies tasks; mark an ordinary chest separately to store their finished goods.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(1, 0, 4), 180);
        resident.walkTo(scene, util.vector().topOf(1, 0, 3));
        resident.hold(scene, new ItemStack(Items.WHEAT, 3));
        scene.overlay().showText(90)
            .text("Residents walk to the box and take only the finite amount their current job needs. A full pack or an unreachable box waits.")
            .pointAt(util.vector().topOf(source)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        resident.walkTo(scene, util.vector().topOf(3, 0, 3));
        resident.hold(scene, new ItemStack(Items.BREAD));
        resident.walkTo(scene, util.vector().topOf(5, 0, 3));
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(100)
            .text("For a full production-chain test, whitelist base ingredients only and order the final dish. Intermediate foods and finished dishes must be made by the residents.")
            .pointAt(util.vector().topOf(table)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
    }
}
