package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class EquipmentLedgerGameTests {
    private static final ResourceLocation MOUNT = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "mount");
    private static final ResourceLocation HORSE_ARMOR =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "horse_armor");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(EquipmentLedgerGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void danglingAssignmentsFailInsteadOfDisappearing(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            new ProfileLedger(colony).create("Current");
            CompoundTag saved = colony.kept(EquipmentProfiles.ID).copy();
            CompoundTag assignment = new CompoundTag();
            assignment.putUUID("resident", UUID.randomUUID());
            assignment.putUUID("profile", UUID.randomUUID());
            saved.getList("assignments", Tag.TAG_COMPOUND).add(assignment);
            colony.keep(EquipmentProfiles.ID, saved);
            boolean rejected = false;
            try {
                new ProfileLedger(colony);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "a dangling assignment must fail the profile load");
            helper.assertValueEqual(colony.kept(EquipmentProfiles.ID), saved, "failed loading preserves the source");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void absentComponentTypesRemainRawThroughRenameDuplicateAndSave(GameTestHelper helper) {
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
            saved.put("profiles", profiles);
            saved.put("assignments", new ListTag());
            colony.keep(EquipmentProfiles.ID, saved);
            ProfileLedger ledger = new ProfileLedger(colony);
            EquipmentProfile loaded = ledger.all().getFirst();
            helper.assertFalse(loaded.entries().getFirst().available(),
                "a missing component codec creates an unavailable entry");
            ledger.replace(loaded.renamed("Renamed"));
            ledger.duplicate(loaded.id());
            var rows = colony.kept(EquipmentProfiles.ID).getList("profiles", Tag.TAG_COMPOUND);
            helper.assertValueEqual(rows.size(), 2, "duplicate keeps an independent profile");
            for (int n = 0; n < rows.size(); n++) {
                helper.assertValueEqual(
                    rows.getCompound(n).getList("entries", Tag.TAG_COMPOUND).getCompound(0).getCompound("items"),
                    item, "unknown item and component data survive each save unchanged");
            }
            helper.assertValueEqual(new ProfileLedger(colony).all().size(), 2,
                "unavailable component entries load again safely");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void slotsHoldOneItemAndWeaponsBringTheirAmmunition(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            ProfileLedger ledger = new ProfileLedger(colony);
            var profile = ledger.create("Archer");
            ledger.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.BOW), 1, 1));
            ledger.put(profile.id(), GearSlots.HEAD, ItemSpec.of(Items.IRON_HELMET));
            var saved = ledger.get(profile.id()).orElseThrow();
            helper.assertValueEqual(saved.entries().size(), 3, "a bow brings one carried ammunition entry");
            helper.assertTrue(saved.carried().get(1).items().equals(ItemSpec.of(net.minecraft.tags.ItemTags.ARROWS))
                && saved.carried().get(1).target() > 1, "its ammunition is carried at the suggested amount");
            UUID head = saved.in(GearSlots.HEAD).orElseThrow().id();
            ledger.put(profile.id(), GearSlots.HEAD, ItemSpec.of(Items.LEATHER_HELMET));
            helper.assertValueEqual(ledger.get(profile.id()).orElseThrow().in(GearSlots.HEAD).orElseThrow().id(),
                head, "replacing a slot keeps its entry identity");
            boolean refused = false;
            try {
                ledger.add(profile.id(),
                    EquipmentProfile.Entry.slotted(GearSlots.HEAD, ItemSpec.of(Items.LEATHER_HELMET)));
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            helper.assertTrue(refused, "a slot holds one entry");
            helper.assertFalse(
                EquipmentProfile.Entry.slotted(GearSlots.HEAD, ItemSpec.of(Items.IRON_SWORD)).available(),
                "a sword does not fit the head slot");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    // Version 3 patrol loadouts become equipment profiles in the single upgrade to version 4.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void v3PatrolLoadoutsBecomeEquipmentProfiles(GameTestHelper helper) {
        UUID guards = UUID.randomUUID(), scouts = UUID.randomUUID();
        UUID firstGuard = UUID.randomUUID(), secondGuard = UUID.randomUUID(), unassigned = UUID.randomUUID();
        var registries = helper.getLevel().registryAccess();
        ListTag loadoutRows = new ListTag();
        loadoutRows.add(profile(guards, "Guards", List.of(
            loadout("WEAPON", ItemSpec.of(Items.IRON_SWORD).save(registries), null, 1, 1),
            loadout("WEAPON", ItemSpec.of(Items.BOW).save(registries), null, 1, 1),
            loadout("ARMOR", ItemSpec.of(Items.IRON_HELMET).save(registries), "head", 1, 1),
            loadout("ARMOR", ItemSpec.of(Items.IRON_CHESTPLATE).save(registries), "chest", 1, 1),
            loadout("ARMOR", ItemSpec.of(Items.IRON_LEGGINGS).save(registries), "legs", 1, 1),
            loadout("ARMOR", ItemSpec.of(Items.IRON_BOOTS).save(registries), "feet", 1, 1),
            loadout("MOUNT", ItemSpec.of(Items.SADDLE).save(registries), null, 1, 1),
            loadout("HORSE_ARMOR", ItemSpec.of(Items.IRON_HORSE_ARMOR).save(registries), "body", 1, 1),
            loadout("SUPPLY", ItemSpec.of(Items.ARROW).save(registries), null, 32, 8))));
        loadoutRows.add(profile(scouts, "Scouts", List.of(
            loadout("SUPPLY", ItemSpec.of(Items.SPYGLASS).save(registries), null, 1, 1))));
        CompoundTag loadouts = new CompoundTag();
        loadouts.put("profiles", loadoutRows);
        loadouts.put("assignments", assignments(Map.of(firstGuard, guards, secondGuard, guards)));
        CompoundTag allowed = new CompoundTag();
        allowed.putUUID("id", guards);
        ListTag allowedRows = new ListTag();
        allowedRows.add(allowed);
        CompoundTag target = new CompoundTag();
        target.putUUID("target", UUID.randomUUID());
        target.put("allowed", allowedRows);
        ListTag targets = new ListTag();
        targets.add(target);
        CompoundTag staffing = new CompoundTag();
        staffing.put("targets", targets);
        CompoundTag keeps = new CompoundTag();
        keeps.put("folkways_vanilla:patrol_loadouts", loadouts);
        keeps.put("folkways_vanilla:patrol_staffing", staffing.copy());
        CompoundTag colonyTag = new CompoundTag();
        colonyTag.put("keeps", keeps);
        ListTag colonies = new ListTag();
        colonies.add(colonyTag);
        CompoundTag raw = new CompoundTag();
        raw.put("colonies", colonies);

        Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, 3);

        CompoundTag migrated = raw.getList("colonies", Tag.TAG_COMPOUND).getCompound(0).getCompound("keeps");
        helper.assertFalse(migrated.contains("folkways_vanilla:patrol_loadouts"), "the old ledger is replaced");
        helper.assertValueEqual(migrated.getCompound("folkways_vanilla:patrol_staffing"), staffing,
            "staffing continues to allow the original profile ids");
        CompoundTag bag = migrated.getCompound("folkways_core:equipment");
        helper.assertValueEqual(bag.getList("assignments", Tag.TAG_COMPOUND),
            loadouts.getList("assignments", Tag.TAG_COMPOUND), "assignments survive unchanged");
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            colony.keep(EquipmentProfiles.ID, bag);
            ProfileLedger ledger = new ProfileLedger(colony);
            helper.assertValueEqual(ledger.all().size(), 2, "assigned and unassigned profiles survive");
            EquipmentProfile guard = ledger.get(guards).orElseThrow();
            helper.assertValueEqual(guard.name(), "Guards", "profile names survive");
            helper.assertValueEqual(guard.entries().stream().map(EquipmentProfile.Entry::id).toList(),
                loadoutRows.getCompound(0).getList("entries", Tag.TAG_COMPOUND).stream()
                    .filter(row -> !((CompoundTag) row).getString("kind").equals("MOUNT"))
                    .map(row -> ((CompoundTag) row).getUUID("id")).toList(), "entry ids and order survive");
            helper.assertTrue(guard.carried().getFirst().items().equals(ItemSpec.of(Items.IRON_SWORD))
                && guard.in(GearSlots.HEAD).isPresent() && guard.in(GearSlots.CHEST).isPresent()
                && guard.in(GearSlots.LEGS).isPresent() && guard.in(GearSlots.FEET).isPresent()
                && guard.riding() && guard.in(HORSE_ARMOR).isPresent(),
                "the first weapon, all armour slots and mount equipment fill their slots");
            helper.assertTrue(guard.carried().stream().anyMatch(entry -> entry.items().equals(ItemSpec.of(Items.BOW))
                && entry.target() == 1 && entry.threshold() == 1), "a second weapon is carried");
            helper.assertTrue(guard.carried().stream().anyMatch(entry -> entry.items().equals(ItemSpec.of(Items.ARROW))
                && entry.target() == 32 && entry.threshold() == 8), "supplies keep target and threshold");
            helper.assertTrue(ledger.assigned(firstGuard).map(EquipmentProfile::id).equals(Optional.of(guards))
                && ledger.assigned(secondGuard).map(EquipmentProfile::id).equals(Optional.of(guards))
                && ledger.assigned(unassigned).isEmpty(), "residents keep their assignments");
        } finally {
            colony.raze();
        }
        CompoundTag once = raw.copy();
        Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, 3);
        helper.assertValueEqual(raw, once, "the migration is idempotent after removing the source bag");
        Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, 4);
        helper.assertValueEqual(raw, once, "current saves need no migration");
        helper.succeed();
    }

    private static CompoundTag profile(UUID id, String name, List<CompoundTag> entries) {
        CompoundTag profile = new CompoundTag();
        profile.putUUID("id", id);
        profile.putString("name", name);
        ListTag rows = new ListTag();
        rows.addAll(entries);
        profile.put("entries", rows);
        return profile;
    }

    private static CompoundTag loadout(String kind, Tag items, String slot, int target, int threshold) {
        CompoundTag entry = new CompoundTag();
        entry.putUUID("id", UUID.randomUUID());
        entry.putString("kind", kind);
        entry.put("items", items);
        if (slot != null) {
            entry.putString("slot", slot);
        }
        entry.putInt("target", target);
        entry.putInt("threshold", threshold);
        return entry;
    }

    private static ListTag assignments(Map<UUID, UUID> pairs) {
        ListTag rows = new ListTag();
        pairs.forEach((resident, profile) -> {
            CompoundTag row = new CompoundTag();
            row.putUUID("resident", resident);
            row.putUUID("profile", profile);
            rows.add(row);
        });
        return rows;
    }
}
