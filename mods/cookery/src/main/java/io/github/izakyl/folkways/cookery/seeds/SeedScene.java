package io.github.izakyl.folkways.cookery.seeds;

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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class SeedScene {
    private SeedScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("cookery_seeds", "Keep seeds, compost surplus, restore grass");
        PonderScenery.openWorld(scene, util, 15);
        PonderScenery.grassGround(scene, util, 15);
        var chest = util.grid().at(2, 1, 2);
        var compost = util.grid().at(12, 1, 12);
        scene.world().setBlock(chest, Blocks.CHEST.defaultBlockState(), false);
        scene.world().setBlock(compost, Blocks.COMPOSTER.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(5, 1, 5, 9, 1, 9), Blocks.SHORT_GRASS.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 14, 3, 14), Direction.DOWN);
        var patch = util.select().fromTo(0, 0, 0, 14, 1, 14);
        scene.overlay().showOutline(PonderPalette.GREEN, patch, patch, 100);
        scene.overlay().showText(100).text("Register a Seed grass area with the colony book. A farmer wearing a real straw hat gathers its short grass only while obtainable grass seeds are missing. A hat does not create seeds without a real source.")
            .pointAt(util.vector().topOf(7, 0, 7)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        var farmer = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(7, 0, 10), 180);
        scene.world().modifyEntities(ResidentEntity.class, resident -> resident.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.STRAW_HAT.get())));
        scene.world().destroyBlock(util.grid().at(7, 1, 9));
        farmer.hold(scene, new ItemStack(ModItems.TOMATO_SEED.get()));
        scene.overlay().showText(100).text("Drops use the original loot chances. Keep a rare first seed for expansion: sow it, grow it and collect its real offspring. Other seeds need their own farms or valid conversion recipes; short grass cannot supply every crop.")
            .pointAt(util.vector().topOf(7, 1, 9)).placeNearTarget().attachKeyFrame();
        scene.idle(50);
        scene.world().setBlock(util.grid().at(7, 0, 11), Blocks.FARMLAND.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(7, 1, 11), ModBlocks.TOMATO_CROP.get().defaultBlockState(), false);
        farmer.lookAt(scene, util.vector().centerOf(7, 1, 11));
        farmer.hold(scene, ItemStack.EMPTY);
        scene.idle(60);

        scene.world().modifyBlockEntity(chest, ChestBlockEntity.class, store -> store.setItem(0, new ItemStack(ModItems.TOMATO_SEED.get(), 64)));
        farmer.hold(scene, new ItemStack(ModItems.TOMATO_SEED.get(), 21));
        scene.overlay().showText(110).text("Configure automatic composting, the default seed reserve and individual seed reserves on the Composting page. Sowing, transit, task claims and explicit orders are protected first. Wild rice and edible rice share a reserve, with wild seeds preferred.")
            .pointAt(util.vector().topOf(chest)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        farmer.walkTo(scene, util.vector().topOf(11, 0, 12));
        farmer.lookAt(scene, util.vector().centerOf(compost));
        scene.world().setBlock(compost, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 7), false);
        farmer.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(100).text("Mark a composter with the Colony Book. No straw hat is needed. A resident spends one batch at the native average cost and fills it directly to level seven. The reserve is checked again before feeding; an incomplete batch waits.")
            .pointAt(util.vector().topOf(compost)).placeNearTarget().attachKeyFrame();
        scene.idle(50);
        scene.idle(60);

        scene.world().setBlock(compost, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 7), false);
        farmer.hold(scene, ItemStack.EMPTY);
        scene.overlay().showText(100).text("Level seven must mature for the original twenty ticks. At level eight a resident collects the one real bone meal, and the composter empties. New sowing claims protect the next batch before it is fed.")
            .pointAt(util.vector().topOf(compost)).placeNearTarget().attachKeyFrame();
        scene.idle(20);
        scene.world().setBlock(compost, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 8), false);
        scene.idle(25);
        scene.world().setBlock(compost, Blocks.COMPOSTER.defaultBlockState(), false);
        farmer.hold(scene, new ItemStack(Items.BONE_MEAL));
        scene.idle(65);

        scene.world().setBlock(util.grid().at(7, 1, 11), Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(7, 0, 11), Blocks.GRASS_BLOCK.defaultBlockState(), false);
        farmer.walkTo(scene, util.vector().topOf(7, 0, 7));
        scene.world().setBlocks(util.select().fromTo(5, 1, 5, 9, 1, 9), Blocks.AIR.defaultBlockState(), false);
        scene.overlay().showOutline(PonderPalette.GREEN, patch, patch, 120);
        scene.overlay().showText(110).text("With Restore grass enabled, real bone meal can replenish a safe, flat 15 by 15 grass patch over non-grass soil. The full native growth footprint must belong to this area. Missing bone meal, a small patch or unsafe bounds makes restoration wait.")
            .pointAt(util.vector().topOf(7, 0, 7)).placeNearTarget().attachKeyFrame();
        scene.idle(55);
        farmer.hold(scene, ItemStack.EMPTY);
        scene.world().setBlocks(util.select().fromTo(6, 1, 6, 8, 1, 8), Blocks.SHORT_GRASS.defaultBlockState(), false);
        scene.idle(65);
    }
}
