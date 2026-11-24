package io.github.izakyl.folkways.vanilla.cookery;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import io.github.izakyl.folkways.vanilla.cookery.stove.HeatScene;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
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
        event.enqueueWork(() -> {
            Scenes.folder(Scenes.Category.PRODUCTION, CookeryContent.ID, "folkways.page.cookery", item("stockpot"),
                Scenes.scene("folkways_vanilla", "cookery_orders", CookeryScenes::orders, item("chopping_board"), item("stockpot")),
                Scenes.scene("folkways_vanilla", "cookery_tools", io.github.izakyl.folkways.vanilla.cookery.tools.SharedToolsScene::program,
                    item("kitchenware_racks"), item("iron_kitchen_knife"), item("kitchen_shovel"), item("sickle")),
                Scenes.scene("folkways_vanilla", "cookery_heat", io.github.izakyl.folkways.vanilla.cookery.stove.HeatScene::program, item("stove")),
                Scenes.scene("folkways_vanilla", "cookery_millstone", io.github.izakyl.folkways.vanilla.cookery.millstone.MillstoneScene::program,
                    item("millstone")),
                Scenes.scene("folkways_vanilla", "cookery_spit", io.github.izakyl.folkways.vanilla.cookery.spit.SpitScene::program,
                    item("shawarma_spit")),
                Scenes.scene("folkways_vanilla", "cookery_steamer", io.github.izakyl.folkways.vanilla.cookery.steamer.SteamerScene::program,
                    item("steamer")),
                Scenes.scene("folkways_vanilla", "cookery_pots", io.github.izakyl.folkways.vanilla.cookery.pot.CookingPotsScene::program,
                    item("pot"), item("stockpot"), item("stockpot_lid")),
                Scenes.scene("folkways_vanilla", "cookery_rice", io.github.izakyl.folkways.vanilla.cookery.farming.RiceScene::program,
                    item("rice"), item("wild_rice"), item("rice_panicle")),
                Scenes.scene("folkways_vanilla", "cookery_equipment", io.github.izakyl.folkways.vanilla.cookery.gear.EquipmentScene::program,
                    item("straw_hat"), item("sickle"), item("farmer_chest_plate")),
                Scenes.scene("folkways_vanilla", "cookery_seeds", io.github.izakyl.folkways.vanilla.cookery.seeds.SeedScene::program,
                    item("straw_hat"), ResourceLocation.withDefaultNamespace("composter")),
                Scenes.scene("folkways_vanilla", "cookery_tray", io.github.izakyl.folkways.vanilla.cookery.tray.TrayScene::program, item("bamboo_tray")),
                Scenes.scene("folkways_vanilla", "cookery_teapot", io.github.izakyl.folkways.vanilla.cookery.teapot.TeaScene::program, item("teapot"), item("empty_cup")),
                Scenes.scene("folkways_vanilla", "cookery_chain", io.github.izakyl.folkways.vanilla.cookery.pot.CookingChainScene::program,
                    item("flour"), item("raw_dough"), item("raw_noodles")));
            if (OptionalIntegrations.TAVERN.enabled()) {
                Scenes.add(CookeryContent.ID, Scenes.scene("folkways_vanilla", "tavern",
                    io.github.izakyl.folkways.vanilla.cookery.tavern.TavernScene::program,
                    ResourceLocation.fromNamespaceAndPath("kaleidoscope_tavern", "barrel")));
            }
        });
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
        scene.world().setBlock(stove, HeatScene.litStove(), false);
        scene.world().setBlock(pot, com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.STOCKPOT.get().defaultBlockState(), false);
        scene.world().setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 6, 3, 6), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(95)
            .text("Take in the kitchen stations, a crafting table and storage. Leave a reachable standing spot beside each station.")
            .pointAt(util.vector().topOf(board)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        scene.overlay().showControls(util.vector().topOf(store), Pointing.DOWN, 80).rightClick()
            .withItem(PonderScenery.book(io.github.izakyl.folkways.core.front.api.Shape.Gesture.POINT));
        scene.overlay().showText(95)
            .text("Give residents the Craft trade, then order the finished dish. The colony works out the ingredients and steps.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 3), 180);
        resident.walkTo(scene, util.vector().topOf(1, 0, 2));
        resident.hold(scene, new ItemStack(Items.WHEAT));
        resident.walkTo(scene, util.vector().topOf(3, 0, 2));
        resident.lookAt(scene, util.vector().centerOf(board));
        scene.overlay().showOutline(PonderPalette.INPUT, board, util.select().position(board), 95);
        scene.overlay().showText(95)
            .text("Keep raw ingredients, containers and kitchen tools in reachable storage. If something is missing, the task waits without using anything up.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
        resident.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(95)
            .text("Each kitchen station's card shows what it is doing. The other lessons here cover heat, tools, milling, cooking and farming.")
            .pointAt(util.vector().topOf(pot)).placeNearTarget().attachKeyFrame();
        scene.idle(105);
    }
}
