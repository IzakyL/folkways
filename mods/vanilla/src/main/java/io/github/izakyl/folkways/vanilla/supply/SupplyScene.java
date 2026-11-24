package io.github.izakyl.folkways.vanilla.supply;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
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
        event.enqueueWork(() -> Scenes.folder(Scenes.Category.PRODUCTION, SupplyContent.ID, "folkways.page.supply", SupplyContent.SOURCE,
            Scenes.scene("folkways_vanilla", "creative_supplies", SupplyScene::program, SupplyContent.SOURCE)));
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
            .text("In creative mode, place a creative supply box and set it to offer all items, or a whitelist of items, tags and searches.")
            .pointAt(util.vector().topOf(source)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        scene.overlay().showOutline(PonderPalette.GREEN, source, util.select().position(source), 90);
        scene.overlay().showText(90)
            .text("Take in the box with the book to supply tasks. Take in a plain chest too, for finished goods.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(1, 0, 4), 180);
        resident.walkTo(scene, util.vector().topOf(1, 0, 3));
        resident.hold(scene, new ItemStack(Items.WHEAT, 3));
        scene.overlay().showText(90)
            .text("Residents take only what their current job needs. If their pack is full or the box is out of reach, they wait.")
            .pointAt(util.vector().topOf(source)).placeNearTarget().attachKeyFrame();
        scene.idle(100);
        resident.walkTo(scene, util.vector().topOf(3, 0, 3));
        resident.hold(scene, new ItemStack(Items.BREAD));
        resident.walkTo(scene, util.vector().topOf(5, 0, 3));
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(100)
            .text("To test a full production chain, whitelist only raw ingredients and order the final dish. Residents must make everything in between.")
            .pointAt(util.vector().topOf(table)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
    }
}
