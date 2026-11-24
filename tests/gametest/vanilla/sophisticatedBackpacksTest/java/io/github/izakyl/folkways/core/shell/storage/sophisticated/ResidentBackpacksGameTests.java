package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.testing.BackpackRulesChecks;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.HashMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.p3pp3rf1y.sophisticatedbackpacks.Config;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class ResidentBackpacksGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        io.github.izakyl.folkways.core.testing.SelectedTests.register(event, ResidentBackpacksGameTests.class);
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void inventoryArmorAndOffhandFollowPlayerRules(GameTestHelper helper) {
        BackpackRulesChecks.check(helper, null);
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void duplicateUuidsAndOptionalSlownessUseNativeRules(GameTestHelper helper) {
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.getLevel().addFreshEntity(body);
        var nerfs = Config.SERVER.nerfsConfig;
        boolean previousDedupe = Config.SERVER.tickDedupeLogicDisabled.get();
        boolean previousSlow = nerfs.tooManyBackpacksSlowness.get();
        int previousMax = nerfs.maxNumberOfBackpacks.get();
        try {
            ItemStack chest = new ItemStack(ModItems.BACKPACK.get());
            var first = BackpackWrapper.fromStack(chest);
            first.onInit(helper.getLevel());
            first.getInventoryHandler();
            ItemStack copy = chest.copy();
            var second = BackpackWrapper.fromStack(copy);
            body.setItemSlot(EquipmentSlot.CHEST, chest);
            body.pack().setItem(0, copy);
            Config.SERVER.tickDedupeLogicDisabled.set(true);
            ResidentBackpacks.check(body, new HashMap<>());
            helper.assertTrue(first.getContentsUuid().equals(second.getContentsUuid()),
                "disabled periodic dedupe leaves copied native UUIDs alone");
            Config.SERVER.tickDedupeLogicDisabled.set(false);
            ResidentBackpacks.check(body, new HashMap<>());
            helper.assertTrue(first.getContentsUuid().isPresent() && second.getContentsUuid().isEmpty(),
                "native deduplicator retains the first equal-size bag and removes the duplicate UUID");
            nerfs.tooManyBackpacksSlowness.set(true);
            nerfs.maxNumberOfBackpacks.set(1);
            ResidentBackpacks.check(body, new HashMap<>());
            helper.assertTrue(body.hasEffect(nerfs.getEffect()) && ResidentBackpacks.roots(body, false).size() == 2,
                "the configurable maximum applies an effect without refusing or deactivating a second bag");
        } finally {
            Config.SERVER.tickDedupeLogicDisabled.set(previousDedupe);
            nerfs.tooManyBackpacksSlowness.set(previousSlow);
            nerfs.maxNumberOfBackpacks.set(previousMax);
            body.discard();
        }
        helper.succeed();
    }
}
