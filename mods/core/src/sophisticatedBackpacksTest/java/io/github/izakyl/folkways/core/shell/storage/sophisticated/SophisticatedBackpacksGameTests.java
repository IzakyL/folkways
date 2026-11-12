package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.testing.KitChecks;
import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;
import io.github.izakyl.folkways.core.resident.api.KitProvider;
import io.github.izakyl.folkways.core.resident.api.KitCapabilities;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import net.neoforged.fml.ModList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.p3pp3rf1y.sophisticatedbackpacks.Config;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class SophisticatedBackpacksGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        io.github.izakyl.folkways.core.testing.SelectedTests.register(event, SophisticatedBackpacksGameTests.class);
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void carriedKitFetchesCraftsFromOwnPackAndReturnsUnchanged(GameTestHelper helper) {
        KitChecks.residentFetchesAndReturns(helper,
            PortChecks.item("sophisticatedbackpacks:backpack"),
            PortChecks.item("sophisticatedbackpacks:crafting_upgrade"), true);
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void carriedKitRefusesUnsafeItemsAndRespectsNativeTierSlots(GameTestHelper helper) {
        var provider = new BackpackKits();
        for (String name : java.util.List.of("void_upgrade", "compacting_upgrade", "smelting_upgrade",
            "auto_smelting_upgrade", "smoking_upgrade", "auto_smoking_upgrade", "blasting_upgrade",
            "auto_blasting_upgrade", "inception_upgrade", "tool_swapper_upgrade", "refill_upgrade",
            "restock_upgrade", "deposit_upgrade", "unknown_upgrade")) {
            var item = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("sophisticatedbackpacks", name);
            helper.assertTrue(!provider.accepts(item) && provider.refusal(item).isPresent(),
                "unsafe or unknown kit upgrade refuses with translated reason: " + name);
        }
        var crafting = new KitProvider.Held(
            net.minecraft.resources.ResourceLocation.parse("sophisticatedbackpacks:crafting_upgrade"), 1);
        var magnet = new KitProvider.Held(
            net.minecraft.resources.ResourceLocation.parse("sophisticatedbackpacks:magnet_upgrade"), 1);
        var bag = new KitProvider.Held(
            net.minecraft.resources.ResourceLocation.parse("sophisticatedbackpacks:backpack"), 1);
        helper.assertTrue(provider.capabilities(java.util.List.of(crafting, magnet))
            .equals(KitCapabilities.NONE), "upgrades require an actual bag");
        var nativeSlots = Config.SERVER.leatherBackpack.upgradeSlotCount.get();
        Config.SERVER.leatherBackpack.upgradeSlotCount.set(1);
        try {
            var found = provider.capabilities(java.util.List.of(new KitProvider.Held(bag.item(), 1,
                java.util.List.of(crafting))));
            helper.assertTrue(found.workstations().contains(StoreWorkstations.Capability.CRAFTING)
                && found.abilities().isEmpty(), "a nested upgrade uses its native slot");
            helper.assertTrue(provider.capabilities(java.util.List.of(bag, crafting)).workstations().isEmpty(),
                "a loose upgrade never grants capabilities");
        } finally {
            Config.SERVER.leatherBackpack.upgradeSlotCount.set(nativeSlots);
        }
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void eligibleBackpacksExposeRealInventoryAndNativeStackLimits(GameTestHelper helper) {
        var body = io.github.izakyl.folkways.vanilla.person.PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.getLevel().addFreshEntity(body);
        try {
            ItemStack bag = PortChecks.item("sophisticatedbackpacks:diamond_backpack");
            body.pack().insert(bag);
            helper.assertValueEqual(body.pack().getContainerSize(), 18 + BackpackWrapper.fromStack(
                body.pack().getItem(0)).getInventoryHandler().getSlots(),
                "an ordinary inventory backpack exposes its real cells under the native default rule");
            body.pack().clearContent();
            body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, bag);
            var wrapper = BackpackWrapper.fromStack(bag);
            wrapper.onInit(helper.getLevel());
            int slots = wrapper.getInventoryHandler().getSlots();
            helper.assertValueEqual(body.pack().getContainerSize(), 18 + slots,
                "native inventory is added to base cells");
            var provider = new BackpackKits();
            var children = provider.children(bag, java.util.List.of()).orElseThrow();
            helper.assertTrue(children.insertItem(0, PortChecks.item("sophisticatedbackpacks:stack_upgrade_tier_1"),
                false)
                .isEmpty(), "native checked upgrade insertion succeeds");
            int limit = wrapper.getInventoryHandler().getStackLimit(0,
                new ItemStack(net.minecraft.world.item.Items.STONE));
            helper.assertTrue(limit > 64, "the stack upgrade changes a cell's actual limit");
            for (int slot = 0; slot < 18; slot++) {
                body.pack().setItem(slot, new ItemStack(net.minecraft.world.item.Items.DIRT, 64));
            }
            helper.assertTrue(body.pack().insert(new ItemStack(net.minecraft.world.item.Items.STONE, limit)).isEmpty(),
                "cargo insertion uses the backpack cell's real upgraded limit");
            helper.assertValueEqual(wrapper.getInventoryHandler().getStackInSlot(0).getCount(), limit,
                "all units are in one real native cell");
            helper.assertTrue(children.extractItem(0, 1, false).isEmpty(),
                "native removal refuses to shrink slots below their actual contents");
            var space = io.github.izakyl.folkways.core.plan.api.PackSpace.of(body.pack(), body.pack().contents());
            var stone = io.github.izakyl.folkways.core.item.api.ItemSpec.of(net.minecraft.world.item.Items.STONE);
            helper.assertValueEqual(space.capacity(stone), (long) (18 * 64 + slots * limit),
                "planning sums the actual per-cell limits");
            helper.assertTrue(space.load(java.util.Map.of(stone, (long) (18 * 64 + slots * limit)))
                != Integer.MAX_VALUE, "the native total fits the planning view");
            wrapper.getInventoryHandler().extractItem(0, limit, false);
            helper.assertTrue(!children.extractItem(0, 1, false).isEmpty(), "unloading makes native removal legal");
        } finally {
            body.discard();
        }
        helper.succeed();
    }

    private static BackpackBlockEntity backpack(GameTestHelper helper, BlockPos pos) {
        BackpackBlockEntity backpack = (BackpackBlockEntity) PortChecks.place(helper, pos,
            "sophisticatedbackpacks:backpack");
        backpack.setBackpack(PortChecks.item("sophisticatedbackpacks:backpack"));
        return backpack;
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void placedBackpackAtomicBatches(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        backpack(helper, pos);
        PortChecks.roundTrip(helper, pos, false);
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void upgradesAndFilters(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        var backpack = backpack(helper, pos);
        backpack.setBackpack(PortChecks.item("sophisticatedbackpacks:diamond_backpack"));
        SophisticatedChecks.upgrades(helper, pos, backpack.getBackpackWrapper(),
            "sophisticatedbackpacks");
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void inceptionChecksEffectiveContainedUpgrades(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        var outer = backpack(helper, pos).getBackpackWrapper();
        outer.getUpgradeHandler().setStackInSlot(0, PortChecks.item("sophisticatedbackpacks:inception_upgrade"));
        ItemStack innerStack = PortChecks.item("sophisticatedbackpacks:diamond_backpack");
        var inner = BackpackWrapper.fromStack(innerStack);
        inner.onInit(helper.getLevel());
        inner.getUpgradeHandler().setStackInSlot(0, PortChecks.item("sophisticatedbackpacks:void_upgrade"));
        boolean previous = Config.SERVER.inceptionUpgrade.upgradesInContainedBackpacksAreFunctional.get();
        Config.SERVER.inceptionUpgrade.upgradesInContainedBackpacksAreFunctional.set(true);
        try {
            outer.getInventoryHandler().setStackInSlot(0, innerStack);
            PortChecks.unusable(helper, pos);
            SophisticatedChecks.manualUpgrades(helper, pos, inner, "sophisticatedbackpacks");
            inner.getUpgradeHandler().setStackInSlot(0, PortChecks.item("sophisticatedbackpacks:crafting_upgrade"));
            Config.SERVER.inceptionUpgrade.upgradesInContainedBackpacksAreFunctional.set(false);
            outer.getUpgradeHandler().refreshWrappersThatImplementAndTypeWrappers();
            if (ModList.get().isLoaded("folkways_vanilla")) {
                SophisticatedChecks.capabilities(helper, pos, "inactive");
            }
            inner.getUpgradeHandler().setStackInSlot(0, ItemStack.EMPTY);
            helper.assertTrue(PortChecks.at(helper, pos).usability().usable(), "nested upgrade removal recovers");
        } finally {
            Config.SERVER.inceptionUpgrade.upgradesInContainedBackpacksAreFunctional.set(previous);
        }
        helper.succeed();
    }
}
