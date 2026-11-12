package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class LoadoutRegistryGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LoadoutRegistryGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void allocationDoesNotCountOneUnitTwice(GameTestHelper helper) {
        var first = LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 4, 1);
        var second = LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 4, 1);
        var profile = new LoadoutProfile(UUID.randomUUID(), "Archers", List.of(first, second));
        var carried = List.of(new ItemStack(Items.ARROW, 5));
        for (int pass = 0; pass < 3; pass++) {
            var missing = PatrolSupplies.allocate(profile, carried, slot -> ItemStack.EMPTY);
            helper.assertValueEqual(missing.get(first.id()), 0, "first entry receives its four arrows");
            helper.assertValueEqual(missing.get(second.id()), 3, "second entry receives only the remaining arrow");
            helper.assertValueEqual(carried.getFirst().getCount(), 5, "allocation does not mutate its source");
        }
        helper.succeed();
    }
}
