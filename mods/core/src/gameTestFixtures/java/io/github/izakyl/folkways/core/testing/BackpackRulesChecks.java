package io.github.izakyl.folkways.core.testing;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.KitCapabilities;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.p3pp3rf1y.sophisticatedbackpacks.Config;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import org.jetbrains.annotations.Nullable;

/** Run under each genuine native inventory provider, comparing its player traversal to the resident. */
public final class BackpackRulesChecks {

    private BackpackRulesChecks() {
    }

    public static void check(GameTestHelper helper, @Nullable ResourceLocation accessory) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        body.setNoAi(true);
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "backpack_rules"));
        boolean previous = Config.SERVER.nerfsConfig.onlyWornBackpackTriggersUpgrades.get();
        try {
            ItemStack first = new ItemStack(ModItems.DIAMOND_BACKPACK.get());
            ItemStack second = new ItemStack(ModItems.BACKPACK.get());
            ItemStack chest = new ItemStack(ModItems.BACKPACK.get());
            ItemStack offhand = new ItemStack(ModItems.BACKPACK.get());
            body.pack().setItem(0, first);
            body.pack().setItem(1, second);
            body.setItemSlot(EquipmentSlot.CHEST, chest);
            body.setItemSlot(EquipmentSlot.OFFHAND, offhand);
            player.getInventory().setItem(0, first);
            player.getInventory().setItem(1, second);
            player.setItemSlot(EquipmentSlot.CHEST, chest);
            player.setItemSlot(EquipmentSlot.OFFHAND, offhand);
            var wrapper = BackpackWrapper.fromStack(first);
            wrapper.onInit(level);
            var upgrades = wrapper.getUpgradeHandler();
            upgrades.setStackInSlot(0, new ItemStack(ModItems.MAGNET_UPGRADE.get()));
            upgrades.setStackInSlot(1, new ItemStack(ModItems.FEEDING_UPGRADE.get()));
            upgrades.setStackInSlot(2, new ItemStack(ModItems.CRAFTING_UPGRADE.get()));
            // Contents of an effective bag do not themselves become inventory roots.
            wrapper.getInventoryHandler().setStackInSlot(0, new ItemStack(ModItems.BACKPACK.get()));
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Several player backpacks");
            profiles.put(profile.id(), GearSlots.CHEST, ItemSpec.of(chest.getItem()));
            profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(first.getItem()), 2, 1));
            if (accessory != null) {
                ItemStack back = new ItemStack(ModItems.BACKPACK.get());
                profiles.put(profile.id(), accessory, ItemSpec.of(back.getItem()));
                GearSlots.slot(accessory).orElseThrow().equip(body, back);
                equipPlayer(player, accessory, back);
            }
            EquipmentKits.validate(profiles.get(profile.id()).orElseThrow());
            for (boolean onlyWorn : List.of(false, true)) {
                Config.SERVER.nerfsConfig.onlyWornBackpackTriggersUpgrades.set(onlyWorn);
                List<ItemStack> nativeRoots = new ArrayList<>();
                PlayerInventoryProvider.get().runOnBackpacks(player, (stack, handler, identifier, slot) -> {
                    nativeRoots.add(stack);
                    return false;
                }, onlyWorn);
                List<ItemStack> effective = KitProviders.activeRoots(body);
                helper.assertTrue(effective.equals(nativeRoots),
                    "resident selection and priority equal the native player provider, onlyWorn=" + onlyWorn);
                helper.assertValueEqual(effective.size(), (onlyWorn ? 1 : 4) + (accessory == null ? 0 : 1),
                    "every eligible bag is effective, including several ordinary inventory bags by default");
                int cells = effective.stream().map(BackpackWrapper::fromStack)
                    .mapToInt(bag -> bag.getInventoryHandler().getSlots()).sum();
                helper.assertValueEqual(body.pack().getContainerSize(), body.pack().ownCells() + cells,
                    "exactly the selected real inventories join resident cargo");
                var abilities = KitProviders.capabilities(body);
                helper.assertTrue((abilities.ability(KitCapabilities.PICKUP_RADIUS) > 0) == !onlyWorn
                    && (abilities.ability(KitCapabilities.AUTO_FEED) > 0) == !onlyWorn
                    && !abilities.workstations().isEmpty() == !onlyWorn,
                    "magnet, feeding and workstations follow ordinary-inventory eligibility");
            }
            Config.SERVER.nerfsConfig.onlyWornBackpackTriggersUpgrades.set(false);
            upgrades.getSlotWrappers().get(0).setEnabled(false);
            helper.assertValueEqual(KitProviders.capabilities(body).ability(KitCapabilities.PICKUP_RADIUS), 0.0,
                "disabled native upgrades stay inactive on eligible bags");
            upgrades.getSlotWrappers().get(0).setEnabled(true);
            body.pack().setItem(0, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.CHEST, first);
            Config.SERVER.nerfsConfig.onlyWornBackpackTriggersUpgrades.set(true);
            helper.assertTrue(KitProviders.capabilities(body).ability(KitCapabilities.PICKUP_RADIUS) == 1.5
                && KitProviders.capabilities(body).ability(KitCapabilities.AUTO_FEED) == 0,
                "positive armor indices keep magnet pickup events but do not tick remote collection or feeding");
            upgrades.setStackInSlot(3, new ItemStack(ModItems.PICKUP_UPGRADE.get()));
            helper.assertValueEqual(KitProviders.capabilities(body).ability(KitCapabilities.PICKUP_RADIUS), 1.5,
                "the same chest bag still handles native pickup events in only-worn mode");
            if (accessory != null) {
                body.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                GearSlots.slot(accessory).orElseThrow().equip(body, first);
                helper.assertTrue(KitProviders.capabilities(body).ability(KitCapabilities.AUTO_FEED) > 0,
                    "accessory inventoryTick uses -1 and keeps feeding active in only-worn mode");
            }
            Config.SERVER.nerfsConfig.onlyWornBackpackTriggersUpgrades.set(false);
            body.pack().setItem(0, first);
            body.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            if (accessory != null) {
                GearSlots.slot(accessory).orElseThrow().equip(body, ItemStack.EMPTY);
            }
            // A shrinking flattened view must not leave contents behind in a removed base-cell backpack.
            body.pack().clearContent();
            helper.assertTrue(java.util.stream.IntStream.range(0, wrapper.getInventoryHandler().getSlots())
                .allMatch(slot -> wrapper.getInventoryHandler().getStackInSlot(slot).isEmpty()),
                "clearing cargo clears bag cells first");
        } finally {
            Config.SERVER.nerfsConfig.onlyWornBackpackTriggersUpgrades.set(previous);
            player.getInventory().clearContent();
            if (accessory != null) {
                equipPlayer(player, accessory, ItemStack.EMPTY);
            }
            body.discard();
            colony.raze();
        }
    }

    private static void equipPlayer(net.minecraft.server.level.ServerPlayer player, ResourceLocation slot,
                                    ItemStack stack) {
        if (slot.getNamespace().equals("curios")) {
            top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).orElseThrow()
                .getStacksHandler("back").orElseThrow().getStacks().setStackInSlot(0, stack);
        } else {
            io.wispforest.accessories.api.AccessoriesCapability.get(player).getContainers().get("back")
                .getAccessories().setItem(0, stack);
        }
    }
}
