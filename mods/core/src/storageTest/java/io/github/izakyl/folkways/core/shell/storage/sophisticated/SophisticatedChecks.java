package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.filter.FilterUpgradeItem;
import net.p3pp3rf1y.sophisticatedcore.upgrades.filter.FilterUpgradeWrapper;

public final class SophisticatedChecks {

    private SophisticatedChecks() {
    }

    public static void upgrades(GameTestHelper helper, BlockPos pos, IStorageWrapper wrapper, String mod) {
        manualUpgrades(helper, pos, wrapper, mod);
        UpgradeHandler upgrades = wrapper.getUpgradeHandler();
        for (String unsafe : List.of("void_upgrade", "advanced_void_upgrade", "compacting_upgrade",
            "advanced_compacting_upgrade", "smelting_upgrade", "auto_smelting_upgrade", "smoking_upgrade",
            "auto_smoking_upgrade", "blasting_upgrade", "auto_blasting_upgrade",
            "feeding_upgrade", "advanced_feeding_upgrade", "infinity_upgrade",
            "survival_infinity_upgrade", "alchemy_upgrade", "advanced_alchemy_upgrade", "xp_pump_upgrade")) {
            upgrades.setStackInSlot(0, PortChecks.item(mod + ":" + unsafe));
            PortChecks.unusable(helper, pos);
            upgrades.setStackInSlot(0, ItemStack.EMPTY);
            helper.assertTrue(PortChecks.at(helper, pos).usability().usable(), "removing " + unsafe + " recovers");
        }
        for (String extra : mod.equals("sophisticatedstorage") ? List.of("compression_upgrade")
            : List.of("tank_upgrade", "battery_upgrade", "mob_catcher_upgrade",
                "advanced_mob_catcher_upgrade")) {
            upgrades.setStackInSlot(0, PortChecks.item(mod + ":" + extra));
            PortChecks.unusable(helper, pos);
            upgrades.setStackInSlot(0, ItemStack.EMPTY);
        }
        for (String safe : List.of("pickup_upgrade", "advanced_pickup_upgrade", "magnet_upgrade",
            "advanced_magnet_upgrade", "jukebox_upgrade", "advanced_jukebox_upgrade", "pump_upgrade",
            "advanced_pump_upgrade")) {
            upgrades.setStackInSlot(0, PortChecks.item(mod + ":" + safe));
            helper.assertTrue(PortChecks.at(helper, pos).usability().usable(), "safe IO or interface: " + safe);
            helper.assertTrue(PortChecks.read(helper, PortChecks.at(helper, pos)).entries().isEmpty(),
                "upgrade slots are excluded");
            if (safe.contains("jukebox")) {
                PortChecks.roundTrip(helper, pos, false);
            }
            upgrades.setStackInSlot(0, ItemStack.EMPTY);
        }
        upgrades.setStackInSlot(0, PortChecks.item(mod + ":stack_upgrade_tier_1"));
        StorePort stacked = PortChecks.at(helper, pos);
        Room.Slots room = (Room.Slots) PortChecks.read(helper, stacked).room();
        long stoneLimit = room.cap().limit(Items.STONE.getDefaultInstance());
        helper.assertTrue(stoneLimit == wrapper.getInventoryHandler().getStackLimit(0, new ItemStack(Items.STONE))
            && stoneLimit > 64, "stack upgrade is reflected in capacity");
        for (var item : List.of(Items.STONE, Items.SNOWBALL, Items.DIAMOND_PICKAXE)) {
            ItemStack stack = new ItemStack(item);
            helper.assertValueEqual(room.cap().limit(stack),
                (long) wrapper.getInventoryHandler().getStackLimit(0, stack),
                "upgraded empty slots retain each item's native limit");
        }
        wrapper.getInventoryHandler().setStackInSlot(0, new ItemStack(Items.STONE, 17));
        wrapper.getInventoryHandler().setStackInSlot(1, new ItemStack(Items.STONE, 19));
        Room.Slots fragmented = (Room.Slots) PortChecks.read(helper, stacked).room();
        helper.assertTrue(fragmented.open() == room.open() - 2 && fragmented.bound().size() == 2
            && fragmented.bound().stream().mapToLong(Room.Cell::room).sum()
                == 2L * room.cap().limit(new ItemStack(Items.STONE)) - 36,
            "fragmented upgraded stacks publish exact top-up room independently of shared empty slots");
        for (int slot = 0; slot < wrapper.getInventoryHandler().getSlots(); slot++) {
            wrapper.getInventoryHandler().setStackInSlot(slot, new ItemStack(Items.STONE,
                wrapper.getInventoryHandler().getStackLimit(slot, new ItemStack(Items.STONE))));
        }
        Room.Slots full = (Room.Slots) PortChecks.read(helper, stacked).room();
        helper.assertTrue(full.open() == 0 && full.bound().stream().allMatch(cell -> cell.room() == 0),
            "a full upgraded store has no empty or top-up room");
        for (var item : List.of(Items.STONE, Items.SNOWBALL, Items.DIAMOND_PICKAXE)) {
            ItemStack stack = new ItemStack(item);
            helper.assertValueEqual(full.cap().limit(stack),
                (long) wrapper.getInventoryHandler().getStackLimit(0, stack),
                "a full upgraded store still reports each item's native slot limit");
        }
        for (int slot = 0; slot < wrapper.getInventoryHandler().getSlots(); slot++) {
            wrapper.getInventoryHandler().setStackInSlot(slot, ItemStack.EMPTY);
        }
        wrapper.getInventoryHandler().setStackInSlot(0, new ItemStack(Items.STONE, (int) stoneLimit));
        helper.assertTrue(PortChecks.count(helper, stacked) == stoneLimit, "bulk slot count exceeds 99");
        helper.assertTrue(stacked.take(helper.getLevel(), List.of(new Pull.Exact(new ItemStack(Items.STONE,
            (int) stoneLimit))), List.of(), false).done(), "bulk slot extracts its whole stock");
        upgrades.setStackInSlot(0, ItemStack.EMPTY);
        upgrades.setStackInSlot(0, PortChecks.item(mod + ":filter_upgrade"));
        FilterUpgradeWrapper filter = upgrades.getTypeWrappers(FilterUpgradeItem.TYPE).getFirst();
        filter.getFilterLogic().setAllowList(true);
        filter.getFilterLogic().getFilterHandler().setStackInSlot(0, new ItemStack(Items.STONE));
        StorePort filtered = PortChecks.at(helper, pos);
        helper.assertTrue(PortChecks.read(helper, filtered).refusesBySampling(), "native filters declare sampling");
        helper.assertTrue(!filtered.put(helper.getLevel(), List.of(new ItemStack(Items.DIRT)), List.of(), true, false)
            .done(), "directed orders retain the native filter");
        helper.assertTrue(filtered.put(helper.getLevel(), List.of(new ItemStack(Items.STONE)), List.of(), false, false)
            .done(), "filter admits selected goods");
        upgrades.setStackInSlot(0, ItemStack.EMPTY);
    }

    public static void manualUpgrades(GameTestHelper helper, BlockPos pos, IStorageWrapper wrapper, String mod) {
        var upgrades = wrapper.getUpgradeHandler();
        var heldPort = PortChecks.at(helper, pos);
        List<String> installed = new ArrayList<>();
        for (String name : List.of("crafting_upgrade", "stonecutter_upgrade", "anvil_upgrade", "smithing_upgrade")) {
            if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath(mod, name))) {
                continue;
            }
            installed.add(name);
            upgrades.setStackInSlot(0, PortChecks.item(mod + ":" + name));
            helper.assertTrue(heldPort.usability().usable(), "manual upgrade is usable: " + name);
            capabilities(helper, pos, name);
            upgrades.setStackInSlot(1, PortChecks.item(mod + ":void_upgrade"));
            helper.assertTrue(StoreWorkstations.at(helper.getLevel(), helper.absolutePos(pos)).isEmpty(),
                "an unusable store cannot supply capabilities");
            upgrades.setStackInSlot(1, ItemStack.EMPTY);
            capabilities(helper, pos, name);
            upgrades.setStackInSlot(0, ItemStack.EMPTY);
            capabilities(helper, pos, "removed");
        }
        for (int slot = 0; slot < installed.size(); slot++) {
            upgrades.setStackInSlot(slot, PortChecks.item(mod + ":" + installed.get(slot)));
        }
        helper.assertTrue(heldPort.usability().usable(), "all registered manual upgrades coexist safely");
        Set<StoreWorkstations.Capability> expected = mod.equals("sophisticatedstorage")
            ? Set.of(StoreWorkstations.Capability.CRAFTING, StoreWorkstations.Capability.STONECUTTING)
            : Set.of(StoreWorkstations.Capability.values());
        helper.assertValueEqual(StoreWorkstations.at(helper.getLevel(), helper.absolutePos(pos)), expected,
            "coexisting manual upgrades provide the union of capabilities, with none added by the anvil");
        for (int slot = 0; slot < installed.size(); slot++) {
            upgrades.setStackInSlot(slot, ItemStack.EMPTY);
        }
        capabilities(helper, pos, "removed");
        helper.assertValueEqual(installed.size(), mod.equals("sophisticatedstorage") ? 2 : 4,
            "all manual upgrade registrations in the pinned native jar were checked");
    }

    public static void capabilities(GameTestHelper helper, BlockPos pos, String upgrade) {
        Set<StoreWorkstations.Capability> expected = Map.of(
            "crafting_upgrade", Set.of(StoreWorkstations.Capability.CRAFTING),
            "stonecutter_upgrade", Set.of(StoreWorkstations.Capability.STONECUTTING),
            "smithing_upgrade", Set.of(StoreWorkstations.Capability.SMITHING))
            .getOrDefault(upgrade, Set.of());
        helper.assertValueEqual(StoreWorkstations.at(helper.getLevel(), helper.absolutePos(pos)), expected,
            "current effective manual upgrade capabilities: " + upgrade);
    }
}
