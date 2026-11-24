package io.github.izakyl.folkways.vanilla.cookery.gear;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class EquipmentScene {
    private EquipmentScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_equipment", "Farm clothing and shared sickles");
        PonderScenery.openWorld(scene, util, 9);
        PonderScenery.grassGround(scene, util, 9);
        scene.world().setBlocks(util.select().fromTo(2, 0, 3, 5, 0, 3), Blocks.FARMLAND.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(2, 1, 3, 5, 1, 3), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), false);
        scene.world().setBlock(util.grid().at(1, 2, 1), ModBlocks.KITCHENWARE_RACKS.get().defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(1, 1, 1), Blocks.CHEST.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 3, 8), Direction.DOWN);
        var farmer = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(3, 0, 5), 180);
        farmer.hold(scene, new ItemStack(ModItems.STRAW_HAT.get()));
        scene.overlay().showText(90).text("Gathering grass seeds borrows a straw hat, wears it and returns it after. A hat in a pack does nothing, and other work never asks for one.")
            .pointAt(util.vector().topOf(3, 2, 5)).placeNearTarget().attachKeyFrame();
        scene.idle(40);
        farmer.hold(scene, ItemStack.EMPTY);
        scene.world().modifyEntities(ResidentEntity.class, resident -> resident.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.STRAW_HAT.get())));
        scene.idle(60);

        scene.overlay().showText(90).text("Farming clothes have their own allowed list. Water-farming tasks may borrow a full set and return it after. Clothes a resident already wears are never swapped.")
            .pointAt(util.vector().topOf(3, 2, 5)).placeNearTarget().attachKeyFrame();
        scene.idle(100);

        var plot = util.select().fromTo(2, 0, 3, 5, 1, 3);
        scene.overlay().showOutline(PonderPalette.GREEN, plot, plot, 100);
        scene.overlay().showText(100).text("Farms pick by hand by default: tea regrows from age 3, tomato and chili from age 5. Set the farm's Harvest method to Sickle to cut whole plants.")
            .pointAt(util.vector().topOf(3, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        farmer.walkTo(scene, util.vector().topOf(3, 0, 4));
        farmer.hold(scene, new ItemStack(ModItems.SICKLE.get()));
        scene.world().setBlock(util.grid().at(3, 1, 3), Blocks.WHEAT.defaultBlockState(), false);
        scene.overlay().showText(100).text("The sickle is borrowed for the task and cuts only the claimed crop, never a neighbor's field.")
            .pointAt(util.vector().topOf(3, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(110);

        farmer.walkTo(scene, util.vector().topOf(2, 0, 1));
        farmer.lookAt(scene, util.vector().centerOf(1, 2, 1));
        scene.overlay().showText(100).text("Knives, kitchen shovels and sickles are shared colony tools. If there is none, one is made; if it is in use, other work waits. Afterwards it goes back to a rack or storage.")
            .pointAt(util.vector().topOf(1, 2, 1)).placeNearTarget().attachKeyFrame();
        scene.idle(50);
        farmer.hold(scene, ItemStack.EMPTY);
        scene.idle(60);

        scene.world().setBlocks(util.select().fromTo(5, 1, 5, 7, 2, 7), Blocks.WATER.defaultBlockState(), false);
        farmer.walkTo(scene, util.vector().topOf(6, 0, 6));
        scene.world().modifyEntities(ResidentEntity.class, resident -> {
            resident.setItemSlot(EquipmentSlot.CHEST, new ItemStack(ModItems.FARMER_CHEST_PLATE.get()));
            resident.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModItems.FARMER_LEGGINGS.get()));
            resident.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.FARMER_BOOTS.get()));
        });
        scene.overlay().showText(100).text("Cookery's water movement bonus needs all four farmer pieces worn. Water tasks borrow the whole set; a hat alone only adds seed drops.")
            .pointAt(util.vector().topOf(6, 1, 6)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
    }
}
