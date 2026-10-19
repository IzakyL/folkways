package io.github.izakyl.folkways.cookery.gear;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
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
        scene.overlay().showText(90).text("A hat in the pack has no seed effect. Enable hat wearing for a farmer and allow the clothing item; the one real hat moves into the head slot and is visible on the resident.")
            .pointAt(util.vector().topOf(3, 2, 5)).placeNearTarget().attachKeyFrame();
        scene.idle(40);
        farmer.hold(scene, ItemStack.EMPTY);
        scene.world().modifyEntities(ResidentEntity.class, resident -> resident.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.STRAW_HAT.get())));
        scene.idle(60);

        scene.overlay().showText(90).text("Clothing has its own allowlist. Farmer armor is optional, and an unrelated helmet is never overwritten. Disabling farming or wearing returns managed clothes to storage, without a second pack copy.")
            .pointAt(util.vector().topOf(3, 2, 5)).placeNearTarget().attachKeyFrame();
        scene.idle(100);

        var plot = util.select().fromTo(2, 0, 3, 5, 1, 3);
        scene.overlay().showOutline(PonderPalette.GREEN, plot, plot, 100);
        scene.overlay().showText(100).text("A farm normally uses Pick: tea regrows from age three, tomato and chili from age five. Open that plot's Harvest mode setting and choose Sickle for native whole-harvest loot and young regrowth.")
            .pointAt(util.vector().topOf(3, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        farmer.walkTo(scene, util.vector().topOf(3, 0, 4));
        farmer.hold(scene, new ItemStack(ModItems.SICKLE.get()));
        scene.world().setBlock(util.grid().at(3, 1, 3), Blocks.WHEAT.defaultBlockState(), false);
        scene.overlay().showText(100).text("The sickle is borrowed for this task. Residents cut the claimed root without sweeping a neighbor's land. Mature crops, the blacklist, sweep-enchantment wear and the ten-tick cooldown still apply; rice is counted once.")
            .pointAt(util.vector().topOf(3, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(110);

        farmer.walkTo(scene, util.vector().topOf(2, 0, 1));
        scene.overlay().showText(100).text("Knives, spatulas and sickles are shared colony tools. One missing tool may be made from raw materials; a tool already borrowed makes other work wait. Return it to an available rack or warehouse after the task. Maintain orders can provide more.")
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
        scene.overlay().showText(100).text("Only the complete four-slot farmer set activates Cookery's water movement effect. The hat alone enables its seed loot; the chestplate, leggings and boots must also be genuinely equipped for the full-set effect.")
            .pointAt(util.vector().topOf(6, 1, 6)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
    }
}
