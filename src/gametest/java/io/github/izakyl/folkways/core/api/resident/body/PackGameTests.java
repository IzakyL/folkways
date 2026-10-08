package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackGameTests {

    private static final String TEMPLATE = "empty";

    private PackGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PackGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aBatchOfGoodsThatStackToOneSpreadsOverCells(GameTestHelper helper) {
        Pack pack = new Pack();
        int batch = 5;

        ItemStack over = pack.insert(new ItemStack(Items.SADDLE, batch));

        helper.assertTrue(over.isEmpty(), "a pack with room took none of it back, got " + over);
        helper.assertTrue(pack.count(Items.SADDLE) == batch,
            "the pack should hold all " + batch + " saddles, got " + pack.count(Items.SADDLE));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void whatWillNotFitIsHandedBack(GameTestHelper helper) {
        Pack pack = new Pack();
        int cells = pack.getContainerSize();
        int batch = cells + 3;

        ItemStack over = pack.insert(new ItemStack(Items.SADDLE, batch));

        helper.assertTrue(pack.count(Items.SADDLE) == cells,
            "every cell should hold one saddle, got " + pack.count(Items.SADDLE) + " of " + cells);
        helper.assertTrue(over.getCount() == batch - cells,
            "the " + (batch - cells) + " that did not fit should come back, got " + over);
        helper.succeed();
    }
}
