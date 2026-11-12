package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.PackSpace;
import io.github.izakyl.folkways.core.resident.internal.ResidentPack;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.support.Leaves;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackGameTests {

    private static final String TEMPLATE = "empty";

    private PackGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PackGameTests.class);
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aBatchOfGoodsThatStackToOneSpreadsOverCells(GameTestHelper helper) {
        Pack pack = new ResidentPack();
        int batch = 5;

        ItemStack over = pack.insert(new ItemStack(Items.SADDLE, batch));

        helper.assertTrue(over.isEmpty(), "a pack with room took none of it back, got " + over);
        helper.assertTrue(pack.count(Items.SADDLE) == batch,
            "the pack should hold all " + batch + " saddles, got " + pack.count(Items.SADDLE));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void whatWillNotFitIsHandedBack(GameTestHelper helper) {
        Pack pack = new ResidentPack();
        int cells = pack.getContainerSize();
        int batch = cells + 3;

        ItemStack over = pack.insert(new ItemStack(Items.SADDLE, batch));

        helper.assertTrue(pack.count(Items.SADDLE) == cells,
            "every cell should hold one saddle, got " + pack.count(Items.SADDLE) + " of " + cells);
        helper.assertTrue(over.getCount() == batch - cells,
            "the " + (batch - cells) + " that did not fit should come back, got " + over);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void unknownDropsReserveEmptyCellsAlongsideConcreteCargo(GameTestHelper helper) {
        Pack pack = new ResidentPack(4);
        pack.setItem(0, new ItemStack(Items.WHEAT, 63));
        var wheat = Goods.specOf(pack.getItem(0));
        PackSpace space = PackSpace.of(pack, pack.contents());

        helper.assertTrue(space.fits(List.of(Leaves.room(3), List.of(new Amount(wheat, 1)))),
            "unknown drops use empty cells while known cargo can top up an existing stack");
        helper.assertFalse(space.fits(List.of(Leaves.room(3), List.of(new Amount(wheat, 2)))),
            "concrete overflow cannot share a cell reserved for unknown drops");
        helper.assertTrue(space.fits(List.of(List.of(new Amount(wheat, -63)), Leaves.room(4))),
            "unloading cargo frees its cell for unknown drops later on the route");
        helper.assertFalse(space.fits(List.of(Leaves.room(4))),
            "unknown drops cannot occupy a cell that still holds cargo");
        var other = ItemSpec.of(ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "unknown_drops"));
        helper.assertTrue(space.fits(List.of(Leaves.room(2), List.of(new Amount(other, 1))))
                && !space.fits(List.of(Leaves.room(2), List.of(new Amount(other, 2)))),
            "specs with no real item each reserve whole cells alongside other unknown drops");
        helper.assertTrue(pack.getItem(0).getCount() == 63 && space.free() == 3,
            "planning leaves both the live pack and its snapshot unchanged");

        pack.setItem(1, new ItemStack(Items.BREAD));
        PackSpace personal = PackSpace.of(pack, List.of(pack.getItem(0)));
        helper.assertTrue(personal.fits(List.of(Leaves.room(2)))
                && !personal.fits(List.of(Leaves.room(3))),
            "personal items keep their cells when reserving room for unknown drops");
        helper.succeed();
    }
}
