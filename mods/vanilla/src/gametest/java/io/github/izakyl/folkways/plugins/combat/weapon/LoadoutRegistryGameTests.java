package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class LoadoutRegistryGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LoadoutRegistryGameTests.class);
    }

    @GameTest(template = "empty")
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

    @GameTest(template = "empty")
    public static void absentComponentTypesRemainRawThroughProfileRenameDuplicateAndSave(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            CompoundTag item = new CompoundTag();
            item.putString("item", "absent:rifle");
            CompoundTag values = new CompoundTag();
            values.putString("absent:gun_state", "keep this exact identity");
            CompoundTag components = new CompoundTag();
            components.put("values", values);
            item.put("components", components);
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", UUID.randomUUID());
            entry.putString("kind", "WEAPON");
            entry.put("items", item);
            entry.putInt("target", 1);
            entry.putInt("threshold", 1);
            ListTag entries = new ListTag();
            entries.add(entry);
            CompoundTag profile = new CompoundTag();
            profile.putUUID("id", UUID.randomUUID());
            profile.putString("name", "Unavailable rifle");
            profile.put("entries", entries);
            ListTag profiles = new ListTag();
            profiles.add(profile);
            CompoundTag saved = new CompoundTag();
            saved.putInt("version", 4);
            saved.put("profiles", profiles);
            colony.keep(LoadoutProfiles.SAVED, saved);
            var loadouts = new PatrolSupplies(colony).profiles();
            var loaded = loadouts.all().getFirst();
            helper.assertFalse(
                loaded.entries().getFirst().available(), "missing component codec creates an"
                + " unavailable entry");
            loadouts.replace(new LoadoutProfile(loaded.id(), "Renamed", loaded.entries()));
            loadouts.duplicate(loaded.id());
            var rows = colony.kept(LoadoutProfiles.SAVED).getList("profiles", Tag.TAG_COMPOUND);
            helper.assertValueEqual(rows.size(), 2, "duplicate keeps an independent profile");
            for (int n = 0; n < rows.size(); n++) {
                helper.assertValueEqual(
                    rows.getCompound(n).getList("entries", Tag.TAG_COMPOUND).getCompound(0).getCompound("items"),
                    item,
                    "unknown item/component data survives each save unchanged");
            }
            helper.assertValueEqual(
                new PatrolSupplies(colony).profiles().all().size(),
                2,
                "unavailable component entries load again safely");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void requestedGunKeepsItsModelButNotHeldAttachments(GameTestHelper helper) {
        CompoundTag typed = ItemSpec.of(Items.STICK).save(helper.getLevel().registryAccess());
        CompoundTag identity = new CompoundTag();
        identity.putString("GunId", "tacz:m1911");
        identity.putString("AttachmentSCOPE", "old held attachment");
        CompoundTag typedValues = new CompoundTag(), typedComponents = new CompoundTag();
        typedValues.put("minecraft:custom_data", identity);
        typedComponents.put("values", typedValues);
        typed.put("components", typedComponents);
        CompoundTag converted = LoadoutProfiles.withoutPins(typed);
        CompoundTag actual = converted.getCompound("components").getCompound("values")
            .getCompound("minecraft:custom_data");
        helper.assertValueEqual(actual.getString("GunId"), "tacz:m1911", "typed gun identity survives");
        helper.assertFalse(actual.contains("AttachmentSCOPE"), "held attachments do not constrain");
        identity.remove("GunId");
        identity.remove("AttachmentSCOPE");
        identity.putString("AmmoId", "tacz:45acp");
        helper.assertValueEqual(LoadoutProfiles.withoutPins(typed), typed, "typed ammunition is unchanged");
        helper.succeed();
    }
}
