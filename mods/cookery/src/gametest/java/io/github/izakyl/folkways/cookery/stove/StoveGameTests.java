package io.github.izakyl.folkways.cookery.stove;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoveGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(StoveGameTests.class); }

    @GameTest(template = "empty")
    public static void ignitionWearsTheActualFlintOnceAndNeverTakesCoal(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, ModBlocks.STOVE.get().defaultBlockState());
        var person = PersonBody.RESIDENT.get().create(level);
        ItemStack flint = new ItemStack(Items.FLINT_AND_STEEL);
        WorldPos at = WorldPos.of(level, cell);
        try {
            helper.assertTrue(!StoveIgnition.light(level, at, person, new ItemStack(Items.COAL)),
                "coal is not a stove ignition material");
            helper.assertTrue(StoveIgnition.light(level, at, person, flint), "the native stove can be lit by a resident");
            helper.assertValueEqual(flint.getDamageValue(), 1, "the actual flint loses one durability");
            helper.assertTrue(!StoveIgnition.light(level, at, person, flint), "an already lit stove is not charged again");
            helper.assertValueEqual(flint.getDamageValue(), 1, "a repeated interaction does not double the wear");
            helper.assertTrue(level.getBlockState(cell).getValue(StoveBlock.LIT), "the real LIT state supplies heat");
            helper.succeed();
        } finally {
            person.discard();
        }
    }

    @GameTest(template = "empty")
    public static void waterRejectsIgnitionBeforeAnyChargeIsSpent(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, ModBlocks.STOVE.get().defaultBlockState());
        level.setBlockAndUpdate(cell.above(), Blocks.WATER.defaultBlockState());
        var person = PersonBody.RESIDENT.get().create(level);
        ItemStack charges = new ItemStack(Items.FIRE_CHARGE, 2);
        try {
            helper.assertTrue(!StoveIgnition.light(level, WorldPos.of(level, cell), person, charges),
                "water makes the real heat source unusable");
            helper.assertValueEqual(charges.getCount(), 2, "waiting for dry conditions cannot waste charges");
            helper.assertTrue(!level.getBlockState(cell).getValue(StoveBlock.LIT), "a wet stove stays cold");
            helper.succeed();
        } finally {
            person.discard();
        }
    }

    @GameTest(template = "empty")
    public static void aFireChargeIsConsumedOnceAndTheStoveNeedsNoFuelSlot(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, ModBlocks.STOVE.get().defaultBlockState());
        var person = PersonBody.RESIDENT.get().create(level);
        ItemStack charges = new ItemStack(Items.FIRE_CHARGE, 2);
        try {
            helper.assertTrue(StoveIgnition.light(level, WorldPos.of(level, cell), person, charges), "a charge lights the stove");
            helper.assertValueEqual(charges.getCount(), 1, "exactly one real charge is consumed");
            helper.assertTrue(level.getBlockEntity(cell) == null, "the native stove has no invented fuel inventory");
            helper.succeed();
        } finally {
            person.discard();
        }
    }
}
