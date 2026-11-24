package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.KitCapabilities;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import io.github.izakyl.folkways.core.testing.KitChecks;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.p3pp3rf1y.sophisticatedbackpacks.Config;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.JukeboxUpgradeItem;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class ResidentJukeboxGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        io.github.izakyl.folkways.core.testing.SelectedTests.register(event, ResidentJukeboxGameTests.class);
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath("sophisticatedbackpacks", name);
    }

    private static EquipmentProfile.Entry entry(String name) {
        return name.endsWith("backpack") ? EquipmentProfile.Entry.slotted(
            io.github.izakyl.folkways.core.equipment.api.GearSlots.CHEST, ItemSpec.of(id(name)))
            : EquipmentProfile.Entry.carried(ItemSpec.of(id(name)), 1, 1);
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

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void nativeJukeboxSlotsAndBackpackValidation(GameTestHelper helper) {
        var provider = new BackpackKits();
        var basic = id("jukebox_upgrade");
        var advanced = id("advanced_jukebox_upgrade");
        helper.assertValueEqual(provider.childSlots(basic),
            ((JukeboxUpgradeItem) BuiltInRegistries.ITEM.get(basic)).getNumberOfSlots(), "native basic slot count");
        helper.assertValueEqual(provider.childSlots(basic), 1, "ordinary jukebox holds one real disc");
        int previous = Config.SERVER.advancedJukeboxUpgrade.numberOfSlots.get();
        try {
            Config.SERVER.advancedJukeboxUpgrade.numberOfSlots.set(4);
            helper.assertValueEqual(provider.childSlots(advanced), 4, "advanced slots read native live config");
        } finally {
            Config.SERVER.advancedJukeboxUpgrade.numberOfSlots.set(previous);
        }
        helper.assertTrue(provider.acceptsChild(basic, ResourceLocation.withDefaultNamespace("music_disc_13"))
            && !provider.acceptsChild(basic, ResourceLocation.withDefaultNamespace("paper")),
            "disc acceptance follows jukebox_playable rather than an item list");
        var colony = Colonies.mint(helper.getLevel().getServer());
        try {
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Jukebox rules");
            var bag = entry("backpack");
            var jukebox = entry("jukebox_upgrade").under(bag.id());
            profiles.add(profile.id(), bag);
            rejected(helper, () -> profiles.add(profile.id(), entry("netherite_backpack")),
                "a named slot has exactly one entry");
            profiles.add(profile.id(), jukebox);
            var disc = EquipmentProfile.Entry.carried(ItemSpec.of(Items.MUSIC_DISC_13), 1, 1).under(jukebox.id());
            profiles.add(profile.id(), disc);
            rejected(helper, () -> profiles.add(profile.id(), entry("crafting_upgrade").under(bag.id())),
                "native backpack upgrade slots reject overflow");
            rejected(helper, () -> profiles.add(profile.id(), EquipmentProfile.Entry.carried(
                ItemSpec.of(Items.MUSIC_DISC_CAT), 1, 1).under(jukebox.id())), "basic disc slots reject overflow");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void jukeboxFetchesAndReturnsARealDisc(GameTestHelper helper) {
        KitChecks.residentFetchesAndReturns(helper, PortChecks.item("sophisticatedbackpacks:backpack"),
            PortChecks.item("sophisticatedbackpacks:jukebox_upgrade"), false, new ItemStack(Items.MUSIC_DISC_13),
            body -> {
                ResidentJukebox.update(body);
                helper.assertTrue(ResidentJukebox.playing(body.id()), "fetched bag, upgrade and disc start playing");
            });
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void playbackReadsNativeContentsAndStopsWhenEquipmentOrProfileChanges(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        body.setNoAi(true);
        level.addFreshEntity(body);
        colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        try {
            var profiles = EquipmentProfiles.of(colony);
            var profile = profiles.create("Resident music");
            var bag = entry("diamond_backpack");
            var jukebox = entry("advanced_jukebox_upgrade").under(bag.id());
            var crafting = entry("crafting_upgrade").under(bag.id());
            profiles.add(profile.id(), bag);
            profiles.add(profile.id(), jukebox);
            profiles.add(profile.id(), crafting);
            profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.MUSIC_DISC_13), 1, 1)
                .under(jukebox.id()));
            profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.MUSIC_DISC_CAT), 1, 1)
                .under(jukebox.id()));
            profiles.assign(List.of(body.id()), Optional.of(profile.id()));
            ItemStack loose = PortChecks.item("sophisticatedbackpacks:diamond_backpack");
            body.pack().insert(loose);
            ResidentJukebox.update(body);
            helper.assertTrue(!ResidentJukebox.playing(body.id())
                && KitProviders.capabilities(body).equals(KitCapabilities.NONE), "loose items grant nothing");
            body.pack().clearContent();
            body.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, loose);
            var wrapper = net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper.fromStack(loose);
            wrapper.onInit(level);
            wrapper.getUpgradeHandler().setStackInSlot(0,
                PortChecks.item("sophisticatedbackpacks:advanced_jukebox_upgrade"));
            wrapper.getUpgradeHandler().setStackInSlot(1, PortChecks.item("sophisticatedbackpacks:crafting_upgrade"));
            var discs = ((net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.JukeboxUpgradeWrapper)
                wrapper.getUpgradeHandler().getSlotWrappers().get(0)).getDiscInventory();
            discs.insertItem(0, new ItemStack(Items.MUSIC_DISC_13), false);
            ResidentJukebox.update(body);
            helper.assertTrue(ResidentJukebox.playing(body.id()) && ResidentJukebox.track(body.id()) == 0
                && !KitProviders.capabilities(body).workstations().isEmpty(),
                    "native installed items activate music and work");
            discs.extractItem(0, 1, false);
            ResidentJukebox.update(body);
            helper.assertTrue(!ResidentJukebox.playing(body.id()), "removing the native disc stops playback");
            discs.insertItem(0, new ItemStack(Items.MUSIC_DISC_CAT), false);
            ResidentJukebox.update(body);
            helper.assertTrue(ResidentJukebox.playing(body.id()), "another installed disc restarts playback");
            wrapper.getUpgradeHandler().getSlotWrappers().get(0).setEnabled(false);
            ResidentJukebox.update(body);
            helper.assertTrue(!ResidentJukebox.playing(body.id()), "disabled jukeboxes do not play");
            wrapper.getUpgradeHandler().getSlotWrappers().get(0).setEnabled(true);
            ResidentJukebox.update(body);
            profiles.assign(List.of(body.id()), Optional.of(profiles.create("Silent").id()));
            helper.assertTrue(!ResidentJukebox.playing(body.id()), "changing profiles stops immediately");
            profiles.assign(List.of(body.id()), Optional.of(profile.id()));
            ResidentJukebox.update(body);
            helper.assertTrue(ResidentJukebox.playing(body.id()), "reassignment recomputes runtime playback");
            body.discard();
            helper.assertTrue(!ResidentJukebox.playing(body.id()), "entity unload discards runtime sound state");
        } finally {
            body.discard();
            colony.raze();
        }
        helper.succeed();
    }
}
