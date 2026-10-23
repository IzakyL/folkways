package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class DiningScene {
    private DiningScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("dining", "Dining: set a table for the colony");
        PonderScenery.openWorld(scene, util, 8);
        PonderScenery.grassGround(scene, util, 8);
        BlockPos store = util.grid().at(6, 1, 1);
        scene.world().setBlock(store, Blocks.CHEST.defaultBlockState(), false);
        for (int x = 2; x <= 4; x++) {
            scene.world().setBlock(util.grid().at(x, 1, 3), Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP), false);
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
            .text("With the book marking out, frame a dining area around tables and adjacent chairs. Top slabs make tables and bottom stairs make chairs. Include the chairs in the area and leave a clear aisle beside them.")
            .pointAt(util.vector().topOf(3, 1, 4)).placeNearTarget().attachKeyFrame();
        scene.idle(130);
        scene.overlay().showOutline(PonderPalette.GREEN, "table", util.select().fromTo(2, 1, 3, 4, 1, 3), 100);
        scene.overlay().showText(110)
            .text("Connected table tops on the same level form one table. Residents can serve it from any reachable edge and eat from any free chair beside it; they do not need to reach each dish separately.")
            .pointAt(util.vector().topOf(3, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        scene.overlay().showText(120)
            .text("Choose food and 1 to 4 servings per table cell in the area settings. Furniture with an inventory uses that many slots, up to its capacity. A plain top holds one placed dish, such as cake.")
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
            .text("Residents bring the chosen food from colony supplies or arrange its production, then set the table. Hungry neighbours take a free place and eat. Keep ingredients and a route to the table available.")
            .pointAt(util.vector().topOf(2, 2, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        scene.world().modifyBlock(util.grid().at(2, 2, 3), state -> state.setValue(CakeBlock.BITES, 2), false);
        scene.overlay().showOutline(PonderPalette.INPUT, "store", util.select().position(store), 120);
        scene.overlay().showText(130)
            .text("The drink list is separate from food. With compatible bar furniture installed, dining areas stock supported drink counters automatically. Diners fetch a drink with their meal; ordinary storage cabinets can instead be stocked by delivery orders.")
            .pointAt(util.vector().topOf(store)).placeNearTarget().attachKeyFrame();
        scene.idle(140);
    }
}
