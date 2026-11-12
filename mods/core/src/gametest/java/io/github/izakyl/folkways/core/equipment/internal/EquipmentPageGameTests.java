package io.github.izakyl.folkways.core.equipment.internal;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.front.engine.charter.Charter;
import io.github.izakyl.folkways.core.front.engine.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.KitCapabilities;
import io.github.izakyl.folkways.core.resident.api.KitProvider;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class EquipmentPageGameTests {
    private static final ResourceLocation BARREL = ResourceLocation.withDefaultNamespace("iron_chestplate");
    private static final ResourceLocation JUKEBOX = ResourceLocation.withDefaultNamespace("jukebox");
    private static volatile int barrelSlots = 2;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        // A barrel holds jukeboxes, which only go inside it; a jukebox holds two discs.
        KitProviders.register(ResourceLocation.parse("folkways_core:test_page_tree"), new KitProvider() {
            public boolean accepts(ResourceLocation item) {
                return item.equals(BARREL) || item.equals(JUKEBOX) || disc(item);
            }

            private static boolean disc(ResourceLocation item) {
                return item.getPath().startsWith("music_disc_") && item.getNamespace().equals("minecraft");
            }

            public boolean parent(ResourceLocation item) {
                return item.equals(BARREL) || item.equals(JUKEBOX);
            }

            public boolean acceptsChild(ResourceLocation parent, ResourceLocation child) {
                return parent.equals(BARREL) && child.equals(JUKEBOX) || parent.equals(JUKEBOX) && disc(child);
            }

            public int childSlots(ResourceLocation parent) {
                return parent.equals(BARREL) ? barrelSlots : parent.equals(JUKEBOX) ? 2 : 0;
            }

            public Optional<Component> childSlotsName(ResourceLocation parent) {
                return parent.equals(JUKEBOX) ? Optional.of(Component.literal("Discs")) : Optional.empty();
            }

            public boolean requiresParent(ResourceLocation item) {
                return item.equals(JUKEBOX);
            }

            public KitCapabilities capabilities(List<Held> held) {
                return KitCapabilities.NONE;
            }
        });
        event.register(EquipmentPageGameTests.class);
    }

    private static CompoundTag request(String action, EquipmentProfiles profiles, EquipmentProfile profile) {
        CompoundTag request = new CompoundTag();
        request.putString("action", action);
        request.putUUID("profile", profile.id());
        request.putLong("revision", profiles.revision(profile.id()));
        return request;
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void profilePageRechecksKeeperAuthorityAndRejectsStaleEdits(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "equipment_keeper"));
        Charters.keep(colony, Charter.founded("profile test", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var page = new EquipmentPage(colony.id());
        try {
            CompoundTag create = new CompoundTag();
            create.putString("action", "create");
            create.putString("name", "Guard equipment");
            helper.assertTrue(page.apply(player, create), "keeper creates a colony profile through the page action");
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.all().getFirst();
            CompoundTag add = request("add", profiles, profile);
            add.put("items", ItemSpec.of(Items.SPYGLASS).save(helper.getLevel().registryAccess()));
            helper.assertTrue(page.apply(player, add), "the carried picker adds a one-item entry");
            var entry = profiles.get(profile.id()).orElseThrow().entries().getFirst();
            helper.assertTrue(entry.slot().isEmpty() && entry.target() == 1 && entry.threshold() == 1,
                "a new carried entry keeps one and restocks when none is left");
            CompoundTag rename = request("rename", profiles, profile);
            rename.putString("name", "Archers");
            helper.assertTrue(page.apply(player, rename), "the current revision can be renamed");
            rename.putString("name", "Stale edit");
            helper.assertFalse(page.apply(player, rename),
                "an older editor cannot overwrite another keeper's revision");
            helper.assertValueEqual(profiles.get(profile.id()).orElseThrow().name(), "Archers",
                "stale request leaves the accepted name intact");
            var intruder = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "equipment_intruder"));
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
    public static void slotPicksMustFitTheirSlot(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "equipment_slots"));
        Charters.keep(colony, Charter.founded("Slots", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var page = new EquipmentPage(colony.id());
        var registries = helper.getLevel().registryAccess();
        try {
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Patrol");
            CompoundTag head = request("slot", profiles, profile);
            head.putString("slot", GearSlots.HEAD.toString());
            head.put("items", ItemSpec.of(Items.IRON_SWORD).save(registries));
            helper.assertFalse(page.apply(player, head), "a sword does not go on the head");
            head.put("items", ItemSpec.of(Items.IRON_HELMET).save(registries));
            helper.assertTrue(page.apply(player, head), "a helmet does");
            CompoundTag add = request("add", profiles, profile);
            add.put("items", ItemSpec.of(Items.BOW).save(registries));
            helper.assertTrue(page.apply(player, add), "weapons are ordinary inventory entries");
            var saved = profiles.get(profile.id()).orElseThrow();
            helper.assertTrue(saved.in(GearSlots.HEAD).isPresent() && saved.carried().size() == 2,
                "one armour slot plus the bow and ammunition");
            helper.assertTrue(GearSlots.slot(ResourceLocation.parse("folkways_core:mainhand")).isEmpty()
                && GearSlots.slot(ResourceLocation.parse("folkways_core:offhand")).isEmpty(),
                    "hands are presentation only");
            var tag = (CompoundTag) page.reading(player);
            helper.assertValueEqual(tag.getList("slots", Tag.TAG_COMPOUND).size(), 1, "the page shows filled armour");
            helper.assertValueEqual(tag.getList("carried", Tag.TAG_COMPOUND).size(), 2, "and two inventory entries");
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
        var page = new EquipmentPage(colony.id());
        try {
            var profiles = EquipmentProfiles.of(colony);
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
            profiles.add(second.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.ARROW), 8, 2));
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
            helper.assertFalse(page.apply(player, assign), "no-profile also checks the old assignment");
            assign.putUUID("expected", second.id());
            helper.assertTrue(page.apply(player, assign), "no-profile immediately clears the assignment");
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
    public static void entryActionsValidateAmountsAndKeepEntryIdentity(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "profile_amounts"));
        Charters.keep(colony, Charter.founded("Amounts", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var page = new EquipmentPage(colony.id());
        try {
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Supplies");
            var arrows = EquipmentProfile.Entry.carried(ItemSpec.of(Items.ARROW), 8, 2);
            var bread = EquipmentProfile.Entry.carried(ItemSpec.of(Items.BREAD), 4, 1);
            profiles.add(profile.id(), arrows);
            profiles.add(profile.id(), bread);
            CompoundTag request = request("amount", profiles, profile);
            request.putUUID("entry", arrows.id());
            request.putInt("target", 16);
            request.putInt("threshold", 4);
            helper.assertTrue(page.apply(player, request), "carried amounts save together");
            request.putLong("revision", profiles.revision(profile.id()));
            request.putInt("threshold", 17);
            helper.assertFalse(page.apply(player, request), "threshold cannot exceed target");
            request.putInt("target", 4097);
            request.putInt("threshold", 0);
            helper.assertFalse(page.apply(player, request), "target cannot exceed 4096");
            request.putInt("target", 0);
            helper.assertFalse(page.apply(player, request), "target must be positive");
            profiles.put(profile.id(), GearSlots.CHEST, ItemSpec.of(Items.IRON_CHESTPLATE));
            var sword = profiles.get(profile.id()).orElseThrow().in(GearSlots.CHEST).orElseThrow();
            request.putUUID("entry", sword.id());
            request.putLong("revision", profiles.revision(profile.id()));
            request.putInt("target", 2);
            helper.assertFalse(page.apply(player, request), "a slot holds exactly one item");
            request.putUUID("entry", bread.id());
            request.putString("action", "left");
            helper.assertTrue(page.apply(player, request), "carried entries reorder by stable entry ID");
            helper.assertValueEqual(profiles.get(profile.id()).orElseThrow().carried().getFirst().id(), bread.id(),
                "reordering keeps the chosen item");
            request.putString("action", "edit");
            request.putUUID("entry", sword.id());
            request.putLong("revision", profiles.revision(profile.id()));
            request.put("items", ItemSpec.of(Items.LEATHER_CHESTPLATE).save(helper.getLevel().registryAccess()));
            helper.assertTrue(page.apply(player, request), "replace keeps the entry identity");
            helper.assertValueEqual(profiles.get(profile.id()).orElseThrow().in(GearSlots.CHEST).orElseThrow()
                .items(), ItemSpec.of(Items.LEATHER_CHESTPLATE), "replacement uses the picked item");
            request.putString("action", "remove");
            request.putLong("revision", profiles.revision(profile.id()));
            helper.assertTrue(page.apply(player, request), "a slot can be emptied");
            helper.assertTrue(profiles.get(profile.id()).orElseThrow().in(GearSlots.CHEST).isEmpty(),
                "the weapon hand is empty again");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    private static CompoundTag each(GameTestHelper helper, CompoundTag request, ItemSpec... picked) {
        ListTag each = new ListTag();
        for (ItemSpec items : picked) {
            each.add(items.save(helper.getLevel().registryAccess()));
        }
        request.put("each", each);
        return request;
    }

    private static CompoundTag row(CompoundTag reading, UUID entry) {
        return java.util.stream.Stream.concat(reading.getList("slots", Tag.TAG_COMPOUND).stream(),
            reading.getList("carried", Tag.TAG_COMPOUND).stream()).map(CompoundTag.class::cast)
            .filter(row -> row.getUUID("id").equals(entry)).findFirst().orElseThrow();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void nestedEntriesAreAddedUnderTheirParentAndRemovedWithIt(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "profile_nested"));
        Charters.keep(colony, Charter.founded("Nested", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var page = new EquipmentPage(colony.id());
        var registries = helper.getLevel().registryAccess();
        try {
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Band");
            CompoundTag add = request("slot", profiles, profile);
            add.putString("slot", GearSlots.CHEST.toString());
            add.put("items", ItemSpec.of(Items.IRON_CHESTPLATE).save(registries));
            helper.assertTrue(page.apply(player, add), "a parent is added at the top level");
            var barrel = profiles.get(profile.id()).orElseThrow().in(GearSlots.CHEST).orElseThrow();
            CompoundTag under = each(helper, request("add", profiles, profile), ItemSpec.of(Items.JUKEBOX));
            under.putUUID("parent", barrel.id());
            helper.assertTrue(page.apply(player, under), "the barrel's own add places a jukebox inside it");
            var jukebox = profiles.get(profile.id()).orElseThrow().entries().get(1);
            helper.assertValueEqual(jukebox.parent(), Optional.of(barrel.id()), "the jukebox names its parent");
            CompoundTag discs = each(helper, request("add", profiles, profile), ItemSpec.of(Items.MUSIC_DISC_CAT),
                ItemSpec.of(Items.MUSIC_DISC_13));
            discs.putUUID("parent", jukebox.id());
            helper.assertTrue(page.apply(player, discs), "each picked disc becomes its own child entry");
            var saved = profiles.get(profile.id()).orElseThrow();
            helper.assertTrue(saved.entries().size() == 4 && saved.entries().subList(2, 4).stream()
                .allMatch(disc -> disc.parent().equals(Optional.of(jukebox.id())) && disc.target() == 1),
                "both discs sit in the jukebox, one each");
            CompoundTag third = each(helper, request("add", profiles, profile), ItemSpec.of(Items.MUSIC_DISC_STAL));
            third.putUUID("parent", jukebox.id());
            helper.assertFalse(page.apply(player, third), "a full jukebox takes no third disc");
            var reading = (CompoundTag) page.reading(player);
            helper.assertTrue(reading.toString().contains("folkways.kits.problem.over_slots"),
                "the refusal names the full slots rather than a generic rejection");
            CompoundTag shown = row(reading, jukebox.id());
            helper.assertTrue(shown.getInt("slots_used") == 2 && shown.getInt("slots_total") == 2
                && shown.toString().contains("Discs") && shown.getUUID("parent").equals(barrel.id()),
                "the jukebox row counts its discs under its provider's name and names its parent");
            helper.assertTrue(row(reading, barrel.id()).toString().contains("folkways.kits.child_slots"),
                "a provider without a name of its own gets Core's word for slots");
            var lastDisc = saved.entries().get(3);
            CompoundTag left = request("left", profiles, profile);
            left.putUUID("entry", lastDisc.id());
            helper.assertTrue(page.apply(player, left), "a disc moves among its siblings");
            helper.assertValueEqual(profiles.get(profile.id()).orElseThrow().entries().get(2).id(), lastDisc.id(),
                "the discs swapped places");
            left = request("left", profiles, profile);
            left.putUUID("entry", lastDisc.id());
            helper.assertFalse(page.apply(player, left), "the first child cannot move out past its parent");
            barrelSlots = 0;
            reading = (CompoundTag) page.reading(player);
            helper.assertTrue(row(reading, barrel.id()).getString("state").equals("INVALID")
                && row(reading, jukebox.id()).getBoolean("beyond")
                && reading.get("footer").toString().contains("folkways.equipment.kit_inert"),
                "a parent over its slots and the child past them are marked, and the kit is said not to work");
            CompoundTag rename = request("rename", profiles, profile);
            rename.putString("name", "Quiet band");
            helper.assertTrue(page.apply(player, rename), "edits that leave the overflow no worse still save");
            CompoundTag more = each(helper, request("add", profiles, profile), ItemSpec.of(Items.JUKEBOX));
            more.putUUID("parent", barrel.id());
            helper.assertFalse(page.apply(player, more), "but nothing more goes into an overfull parent");
            CompoundTag remove = request("remove", profiles, profile);
            remove.putUUID("entry", jukebox.id());
            helper.assertTrue(page.apply(player, remove), "removing the jukebox fixes the overflow");
            helper.assertValueEqual(profiles.get(profile.id()).orElseThrow().entries().stream()
                .map(EquipmentProfile.Entry::id).toList(), List.of(barrel.id()),
                "and takes both discs with it");
        } finally {
            barrelSlots = 2;
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
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Guard");
            boolean refused = false;
            try {
                profiles.assign(List.of(resident.id(), outsider.id()), Optional.of(profile.id()));
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            helper.assertTrue(refused && profiles.assigned(resident.id()).isEmpty(),
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
