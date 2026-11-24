package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.StairBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class DiningScene {
    private DiningScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        var table = DiningTables.sample().orElseThrow();
        scene.title("dining", "Dining: set a table for the colony");
        PonderScenery.openWorld(scene, util, 8);
        PonderScenery.grassGround(scene, util, 8);
        BlockPos store = util.grid().at(6, 1, 1);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        for (int x = 2; x <= 4; x++) {
            scene.world().setBlock(util.grid().at(x, 1, 3), table, false);
            if (x == 3) { continue; }
            for (int z : new int[] {2, 4}) {
                scene.world().setBlock(util.grid().at(x, 1, z), Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, z == 2 ? Direction.NORTH : Direction.SOUTH), false);
            }
        }
        scene.world().showSection(util.select().fromTo(0, 0, 0, 7, 2, 7), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.BLUE, "area", util.select().fromTo(1, 0, 1, 6, 2, 5), 120);
        scene.overlay().showText(120)
            .text("Mark out the tables and the chairs beside them, then right-click and choose Dining area. Use tables from a supported mod; bottom stairs work as chairs. Leave an aisle beside the chairs.")
            .pointAt(util.vector().topOf(3, 1, 4)).placeNearTarget().attachKeyFrame();
        scene.idle(130);
        scene.overlay().showOutline(PonderPalette.GREEN, "table", util.select().fromTo(2, 1, 3, 4, 1, 3), 100);
        scene.overlay().showText(110)
            .text("Table tops joined on one level form one table. Residents serve from any reachable edge and eat at any free chair.")
            .pointAt(util.vector().topOf(3, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        scene.overlay().showText(120)
            .text("On the card, choose Table food and Servings per tabletop block (1–4). Tables without storage hold one placed food, such as a cake.")
            .pointAt(util.vector().topOf(2, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(130);
        var waiter = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(6, 0, 2), 180);
        waiter.hold(scene, new ItemStack(Items.CAKE));
        waiter.walkTo(scene, util.vector().topOf(5, 0, 3));
        scene.world().setBlock(util.grid().at(2, 2, 3), Blocks.CAKE.defaultBlockState(), false);
        waiter.hold(scene, ItemStack.EMPTY);
        waiter.walkTo(scene, util.vector().topOf(6, 0, 4));
        var diner = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(2, 0, 6), 180);
        diner.walkTo(scene, util.vector().topOf(2, 0, 5));
        diner.lookAt(scene, util.vector().topOf(2, 1, 3));
        scene.world().modifyEntities(ResidentEntity.class, resident -> {
            if (resident.getX() < 3) { resident.leisureMotion("dining"); }
        });
        scene.overlay().showText(110)
            .text("Residents bring the food from storage, or have it made, and set the table. Hungry residents take a free seat and eat.")
            .pointAt(util.vector().topOf(2, 2, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        scene.world().modifyBlock(util.grid().at(2, 2, 3), state -> state.setValue(CakeBlock.BITES, 2), false);
        scene.overlay().showOutline(PonderPalette.INPUT, "store", util.select().position(store), 120);
        scene.overlay().showText(130)
            .text("Bar drinks is a separate list. Supported bar counters in the area restock themselves, and diners fetch a drink with their meal. Plain bar cabinets need delivery orders.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(140);
    }
}
