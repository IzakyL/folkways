package io.github.izakyl.folkways.plugins.cookery;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class CookeryScenes {
    private CookeryScenes() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(CookeryContent.ID, "folkways.page.cookery", item("stockpot"),
            Scenes.scene("cookery_orders", CookeryScenes::orders, item("chopping_board"), item("stockpot")),
            Scenes.scene("cookery_tools", io.github.izakyl.folkways.plugins.cookery.tools.SharedToolsScene::program,
                item("kitchenware_racks"), item("iron_kitchen_knife"), item("kitchen_shovel"), item("sickle")),
            Scenes.scene("cookery_heat", io.github.izakyl.folkways.plugins.cookery.stove.HeatScene::program, item("stove")),
            Scenes.scene("cookery_millstone", io.github.izakyl.folkways.plugins.cookery.millstone.MillstoneScene::program,
                item("millstone")),
            Scenes.scene("cookery_spit", io.github.izakyl.folkways.plugins.cookery.spit.SpitScene::program,
                item("shawarma_spit")),
            Scenes.scene("cookery_steamer", io.github.izakyl.folkways.plugins.cookery.steamer.SteamerScene::program,
                item("steamer")),
            Scenes.scene("cookery_pots", io.github.izakyl.folkways.plugins.cookery.pot.CookingPotsScene::program,
                item("pot"), item("stockpot"), item("stockpot_lid")),
            Scenes.scene("cookery_rice", io.github.izakyl.folkways.plugins.cookery.farming.RiceScene::program,
                item("rice"), item("wild_rice"), item("rice_panicle")),
            Scenes.scene("cookery_equipment", io.github.izakyl.folkways.plugins.cookery.gear.EquipmentScene::program,
                item("straw_hat"), item("sickle"), item("farmer_chest_plate")),
            Scenes.scene("cookery_seeds", io.github.izakyl.folkways.plugins.cookery.seeds.SeedScene::program,
                item("straw_hat"), ResourceLocation.withDefaultNamespace("composter")),
            Scenes.scene("cookery_tray", io.github.izakyl.folkways.plugins.cookery.tray.TrayScene::program, item("bamboo_tray")),
            Scenes.scene("cookery_teapot", io.github.izakyl.folkways.plugins.cookery.teapot.TeaScene::program, item("teapot"), item("empty_cup")),
            Scenes.scene("cookery_chain", io.github.izakyl.folkways.plugins.cookery.pot.CookingChainScene::program,
                item("flour"), item("raw_dough"), item("raw_noodles"))));
    }

    private static ResourceLocation item(String path) {
        return ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", path);
    }

    public static void orders(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_orders", "A colony kitchen: order the final dish");
        PonderScenery.openWorld(scene, util, 7);
        PonderScenery.grassGround(scene, util, 7);
        var store = util.grid().at(1, 1, 1);
        var board = util.grid().at(3, 1, 1);
        var stove = util.grid().at(5, 1, 1);
        var pot = stove.above();
        var table = util.grid().at(1, 1, 5);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        scene.world().setBlock(board, com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.CHOPPING_BOARD.get().defaultBlockState(), false);
        scene.world().setBlock(stove, com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.STOVE.get().defaultBlockState(), false);
        scene.world().setBlock(pot, com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.STOCKPOT.get().defaultBlockState(), false);
        scene.world().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 3, 6), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(95)
            .text("Mark the kitchen appliances, a crafting table and storage with the colony book. Keep a reachable place to stand beside each appliance.")
            .pointAt(util.vector().topOf(board)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.overlay().showControls(util.vector().topOf(store), Pointing.DOWN, 80).rightClick()
            .withItem(PonderScenery.book(io.github.izakyl.folkways.front.api.Shape.Gesture.POINT));
        scene.overlay().showText(95)
            .text("Give residents the crafting trade, then set a delivery or maintain-stock order for the finished dish. The colony arranges the ingredients and intermediate steps.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 3), 180);
        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        resident.hold(scene, new ItemStack(Items.WHEAT));
        resident.walkTo(scene, util.vector().topOf(3, 0, 2));
        scene.overlay().showOutline(PonderPalette.INPUT, board, util.select().position(board), 95);
        scene.overlay().showText(95)
            .text("Keep base ingredients, containers and reusable kitchen tools in accessible stores. A missing ingredient or blocked appliance leaves the task waiting without spending its inputs.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(95)
            .text("The Kitchen page shows each registered appliance and its current state. Use its lessons to learn heat, shared tools, milling, cooking and farming.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
    }
}
