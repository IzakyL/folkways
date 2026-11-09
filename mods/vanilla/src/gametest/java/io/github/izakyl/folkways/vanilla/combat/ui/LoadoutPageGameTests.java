package io.github.izakyl.folkways.vanilla.combat.ui;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.front.engine.charter.Charter;
import io.github.izakyl.folkways.core.front.engine.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class LoadoutPageGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LoadoutPageGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void profilePageRechecksKeeperAuthorityAndRejectsStaleEdits(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "loadout_keeper"));
        Charters.keep(colony, Charter.founded("profile test", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var page = new LoadoutPage(colony.id());
        try {
            CompoundTag create = new CompoundTag();
            create.putString("action", "create");
            create.putString("name", "Guard equipment");
            helper.assertTrue(page.apply(player, create), "keeper creates a colony profile through the page action");
            var profiles = LoadoutProfiles.of(colony);
            var profile = profiles.all().getFirst();
            CompoundTag gear = new CompoundTag();
            gear.putString("action", "observation_gear");
            gear.putUUID("profile", profile.id());
            gear.putLong("revision", profiles.revision(profile.id()));
            ListTag choices = new ListTag();
            choices.add(StringTag.valueOf("minecraft:spyglass"));
            gear.put("choices", choices);
            helper.assertTrue(page.apply(player, gear),
                "the observation picker creates a physical supply requirement");
            var entry = profiles.get(profile.id()).orElseThrow().entries().getFirst();
            helper.assertTrue(entry.kind() == LoadoutProfile.Kind.SUPPLY
                && entry.target() == 1 && entry.threshold() == 1,
                "observation gear uses the ordinary one-item supply contract");
            gear.putLong("revision", profiles.revision(profile.id()));
            choices.set(0, StringTag.valueOf("minecraft:diamond"));
            helper.assertFalse(page.apply(player, gear), "forged non-observation choices are refused by the server");
            CompoundTag rename = new CompoundTag();
            rename.putString("action", "rename");
            rename.putString("name", "Archers");
            rename.putUUID("profile", profile.id());
            rename.putLong("revision", profiles.revision(profile.id()));
            helper.assertTrue(page.apply(player, rename), "the current revision can be renamed");
            rename.putString("name", "Stale edit");
            helper.assertFalse(
                page.apply(player, rename), "an older editor cannot overwrite another keeper's revision");
            helper.assertValueEqual(
                profiles.get(profile.id()).orElseThrow().name(),
                "Archers",
                "stale request leaves the accepted name intact");
            var intruder =
                FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "loadout_intruder"));
            intruder.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
            helper.assertFalse(page.apply(intruder, create), "forged current-colony state confers no keeper authority");
            Charters.keep(colony, Charter.founded("revoked", UUID.randomUUID()));
            helper.assertFalse(page.apply(player, create), "page authority is rechecked after revocation");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void residentSelectorChecksAssignmentProfileRevisionAndAuthority(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var foreign = Colonies.mint(helper.getLevel().getServer());
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "profile_selector"));
        Charters.keep(colony, Charter.founded("Selector", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        var outsider = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.getLevel().addFreshEntity(resident);
        helper.getLevel().addFreshEntity(outsider);
        colony.hold(PersonContent.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(colony.id());
        foreign.hold(PersonContent.ID, Claimed.Entity.of(outsider)).orElseThrow();
        outsider.joinColony(foreign.id());
        var page = new LoadoutPage(colony.id());
        try {
            var profiles = LoadoutProfiles.of(colony);
            var first = profiles.create("First");
            var second = profiles.create("Second");
            CompoundTag assign = new CompoundTag();
            assign.putString("action", "assign_resident");
            assign.putUUID("resident", resident.id());
            assign.putUUID("profile", first.id());
            assign.putLong("revision", profiles.revision(first.id()));
            helper.assertTrue(page.apply(player, assign), "selector immediately assigns the resident");
            assign.putUUID("profile", second.id());
            helper.assertFalse(page.apply(player, assign), "stale empty assignment cannot overwrite another keeper");
            assign.putUUID("expected", first.id());
            profiles.add(second.id(), LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 8, 2));
            helper.assertFalse(page.apply(player, assign), "stale target profile is refused");
            assign.putLong("revision", profiles.revision(second.id()));
            helper.assertTrue(page.apply(player, assign), "current assignment and profile can be changed");
            assign.putUUID("resident", outsider.id());
            helper.assertFalse(page.apply(player, assign), "foreign residents are refused");
            assign.putUUID("resident", resident.id());
            assign.putUUID("expected", second.id());
            assign.putUUID("profile", foreign.id());
            helper.assertFalse(page.apply(player, assign), "foreign profile IDs are refused");
            assign.remove("profile");
            assign.putUUID("expected", first.id());
            helper.assertFalse(page.apply(player, assign), "no-loadout also checks the old assignment");
            assign.putUUID("expected", second.id());
            helper.assertTrue(page.apply(player, assign), "no-loadout immediately clears the assignment");
            assign.remove("expected");
            assign.putUUID("profile", second.id());
            profiles.delete(second.id());
            helper.assertFalse(page.apply(player, assign), "deleted picker choices cannot be assigned");
            assign.putUUID("profile", first.id());
            assign.putLong("revision", profiles.revision(first.id()));
            Charters.keep(colony, Charter.founded("Revoked", UUID.randomUUID()));
            helper.assertFalse(page.apply(player, assign), "open selector rechecks keeper authority");
            helper.assertTrue(profiles.assigned(resident.id()).isEmpty(), "refusals leave assignment unchanged");
        } finally {
            resident.discard();
            outsider.discard();
            colony.raze();
            foreign.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void compactEntryActionsValidateAmountsAndKeepEntryIdentity(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "profile_amounts"));
        Charters.keep(colony, Charter.founded("Amounts", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var page = new LoadoutPage(colony.id());
        try {
            var profiles = LoadoutProfiles.of(colony);
            var profile = profiles.create("Supplies");
            var supply = LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 8, 2);
            profiles.add(profile.id(), supply);
            CompoundTag request = new CompoundTag();
            request.putUUID("profile", profile.id());
            request.putUUID("entry", supply.id());
            request.putLong("revision", profiles.revision(profile.id()));
            request.putString("action", "amount");
            request.putInt("target", 16);
            request.putInt("threshold", 4);
            helper.assertTrue(page.apply(player, request), "supply fields save together");
            request.putLong("revision", profiles.revision(profile.id()));
            request.putInt("threshold", 17);
            helper.assertFalse(page.apply(player, request), "threshold cannot exceed target");
            request.putInt("target", 4097);
            request.putInt("threshold", 0);
            helper.assertFalse(page.apply(player, request), "target cannot exceed 4096");
            request.putInt("target", 0);
            helper.assertFalse(page.apply(player, request), "target must be positive");
            var weapon = LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD));
            profiles.add(profile.id(), weapon);
            request.putUUID("entry", weapon.id());
            request.putLong("revision", profiles.revision(profile.id()));
            request.putInt("target", 2);
            helper.assertFalse(page.apply(player, request), "weapon quantities remain fixed");
            request.putString("action", "up");
            helper.assertTrue(page.apply(player, request), "arrows reorder by stable entry ID");
            helper.assertValueEqual(profiles.get(profile.id()).orElseThrow().entries().getFirst().id(),
                weapon.id(), "reordering keeps the chosen item");
            request.putString("action", "edit");
            request.putLong("revision", profiles.revision(profile.id()));
            request.put("items", ItemSpec.of(Items.STONE_SWORD).save(helper.getLevel().registryAccess()));
            helper.assertTrue(page.apply(player, request), "replace keeps the entry identity");
            helper.assertValueEqual(profiles.get(profile.id()).orElseThrow().entries().getFirst().items(),
                ItemSpec.of(Items.STONE_SWORD), "replacement uses the picked item");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void bulkProfileAssignmentValidatesEveryoneBeforeChangingAnyone(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var foreign = Colonies.mint(helper.getLevel().getServer());
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        var outsider = PersonBody.RESIDENT.get().create(helper.getLevel());
        helper.getLevel().addFreshEntity(resident);
        helper.getLevel().addFreshEntity(outsider);
        colony.hold(PersonContent.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(colony.id());
        foreign.hold(PersonContent.ID, Claimed.Entity.of(outsider)).orElseThrow();
        outsider.joinColony(foreign.id());
        try {
            var profiles = LoadoutProfiles.of(colony);
            var profile = profiles.create("Guard");
            boolean refused = false;
            try {
                profiles.assign(List.of(resident.id(), outsider.id()), Optional.of(profile.id()));
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            helper.assertTrue(
                refused && profiles.assigned(resident.id()).isEmpty(),
                "a foreign second member rejects the entire bulk operation");
            profiles.assign(List.of(resident.id()), Optional.of(profile.id()));
            profiles.delete(profile.id());
            helper.assertTrue(profiles.assigned(resident.id()).isEmpty(), "deletion clears assignments atomically");
        } finally {
            resident.discard();
            outsider.discard();
            colony.raze();
            foreign.raze();
        }
        helper.succeed();
    }
}
