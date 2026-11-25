package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.equipment.api.EquipmentKits;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.KitCapabilities;
import io.github.izakyl.folkways.core.resident.api.KitProvider;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
public final class KitTreeGameTests {
    private static final ResourceLocation ROOT = ResourceLocation.withDefaultNamespace("leather_chestplate");
    private static final ResourceLocation CHILD = ResourceLocation.withDefaultNamespace("echo_shard");
    private static final ResourceLocation LEAF = ResourceLocation.withDefaultNamespace("amethyst_shard");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        KitProviders.register(ResourceLocation.parse("folkways_core:test_tree"), new KitProvider() {
            public boolean accepts(ResourceLocation item) {
                return item.equals(ROOT) || item.equals(CHILD) || item.equals(LEAF);
            }

            public boolean parent(ResourceLocation item) {
                return item.equals(ROOT) || item.equals(CHILD);
            }

            public boolean acceptsChild(ResourceLocation parent, ResourceLocation child) {
                return parent.equals(ROOT) && child.equals(CHILD) || parent.equals(CHILD) && child.equals(LEAF);
            }

            public int childSlots(ResourceLocation parent) {
                return parent.equals(ROOT) ? 2 : parent.equals(CHILD) ? 1 : 0;
            }

            public boolean requiresParent(ResourceLocation item) {
                return item.equals(CHILD);
            }

            public KitCapabilities capabilities(List<Held> held) {
                long leaves = held.stream().flatMap(root -> root.children().stream())
                    .flatMap(child -> child.children().stream()).mapToLong(Held::count).sum();
                return leaves == 0 ? KitCapabilities.NONE
                    : new KitCapabilities(Map.of(KitCapabilities.AUTO_FEED, (double) leaves), Set.of());
            }
        });
        event.register(KitTreeGameTests.class);
    }

    private static EquipmentProfile.Entry root() {
        return EquipmentProfile.Entry.slotted(io.github.izakyl.folkways.core.equipment.api.GearSlots.CHEST,
            ItemSpec.of(Items.LEATHER_CHESTPLATE));
    }

    private static EquipmentProfile.Entry child(UUID parent) {
        return EquipmentProfile.Entry.carried(ItemSpec.of(Items.ECHO_SHARD), 1, 1).under(parent);
    }

    private static EquipmentProfile.Entry leaf(UUID parent) {
        return EquipmentProfile.Entry.carried(ItemSpec.of(Items.AMETHYST_SHARD), 1, 1).under(parent);
    }

    private static void rejected(GameTestHelper helper, Runnable action, String reason) {
        boolean rejected = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, reason);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void validatesPersistsAndCascadesKitTrees(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Tree");
            var root = root();
            var child = child(root.id());
            var leaf = leaf(child.id());
            rejected(helper, () -> profiles.add(profile.id(), child), "missing parent rejects");
            rejected(helper, () -> profiles.add(profile.id(), child(UUID.randomUUID())), "dangling parent rejects");
            profiles.add(profile.id(), root);
            rejected(helper, () -> profiles.add(profile.id(), leaf(root.id())), "incompatible parent rejects");
            rejected(helper, () -> profiles.add(profile.id(), child.withAmounts(3, 1)), "excess slots reject");
            profiles.add(profile.id(), child);
            profiles.add(profile.id(), leaf);
            var saved = colony.kept(EquipmentProfiles.ID).getList("profiles", Tag.TAG_COMPOUND).getCompound(0)
                .getList("entries", Tag.TAG_COMPOUND);
            helper.assertTrue(saved.getCompound(1).getUUID("parent").equals(root.id())
                && saved.getCompound(2).getUUID("parent").equals(child.id()), "parent UUIDs are saved in v4");
            var restored = new ProfileLedger(colony).get(profile.id()).orElseThrow();
            helper.assertValueEqual(restored.entries(), List.of(root, child, leaf), "parent UUIDs round trip");
            var slots = new PersonalEquipmentPreview().preview(colony, restored, helper.getLevel().getServer()).slots();
            helper.assertValueEqual(slots.get(root.id()), new EquipmentKits.Slots(1, 2), "root usage");
            helper.assertValueEqual(slots.get(child.id()), new EquipmentKits.Slots(1, 1), "nested parent usage");
            var copy = profiles.duplicate(profile.id());
            profiles.replace(new EquipmentProfile(profile.id(), profile.name(), List.of(child, leaf)));
            helper.assertTrue(profiles.get(profile.id()).orElseThrow().entries().isEmpty(),
                "removing a parent cascades through every depth even for direct replacement");
            helper.assertValueEqual(profiles.get(copy.id()).orElseThrow().entries(), restored.entries(),
                "duplicate retains its own complete tree");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void inventoryRootsCannotOwnChildren(GameTestHelper helper) {
        var loose = EquipmentProfile.Entry.carried(ItemSpec.of(Items.LEATHER_CHESTPLATE), 1, 1);
        var profile = new EquipmentProfile(UUID.randomUUID(), "Loose", List.of(loose, child(loose.id())));
        rejected(helper, () -> EquipmentKits.validate(profile), "inventory entries have no child tree");
        EquipmentKits.validate(profile.with(List.of(loose)));
        helper.assertTrue(EquipmentKits.slots(profile.with(List.of(loose))).isEmpty(),
            "a loose storage item has no active upgrade slots");
        helper.succeed();
    }
}
