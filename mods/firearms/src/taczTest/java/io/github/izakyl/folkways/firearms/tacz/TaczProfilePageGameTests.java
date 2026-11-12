package io.github.izakyl.folkways.firearms.tacz;

import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.firearms.FolkwaysFirearmsMod;
import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.init.ModItems;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.equipment.internal.EquipmentPage;
import io.github.izakyl.folkways.core.front.engine.charter.Charter;
import io.github.izakyl.folkways.core.front.engine.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysFirearmsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_firearms")
@PrefixGameTestTemplate(false)
public final class TaczProfilePageGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TaczProfilePageGameTests.class);
    }

    @GameTest(batch = "tacz_native", template = "empty", templateNamespace = "folkways_core")
    public static void gunVariantPagePrefillsCalibreAndKeepsProfileIdentityAcrossReloads(GameTestHelper helper) {
        var colony = Colonies.mint(helper.getLevel().getServer());
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "tacz_keeper"));
        Charters.keep(colony, Charter.founded("Gun profiles", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var page = new EquipmentPage();
        page.build(player);
        try {
            var profiles = LoadoutProfiles.of(colony);
            var profile = profiles.create("Pistols");
            ItemStack gun = new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
            ((IGun) gun.getItem()).setGunId(gun, ResourceLocation.parse("tacz:m1911"));
            ((IGun) gun.getItem()).setCurrentAmmoCount(gun, 3);
            CompoundTag add = new CompoundTag();
            add.putString("action", "add");
            add.put("items", TaczGuns.weapon(ResourceLocation.parse("tacz:m1911"))
                .save(helper.getLevel().registryAccess()));
            add.putUUID("profile", profile.id());
            add.putLong("revision", profiles.revision(profile.id()));
            helper.assertTrue(page.apply(player, add), "keeper adds a gun model to inventory through the profile page");
            var saved = profiles.get(profile.id()).orElseThrow();
            helper.assertTrue(saved.hasRanged(), "component-pinned firearm is recognized as a ranged profile");
            var provider = Loadouts.using(gun).orElseThrow();
            var ammo = provider.ammunition(gun).orElseThrow();
            helper.assertValueEqual(saved.entries().size(), 2, "one weapon adds one matching supply row");
            helper.assertTrue(
                Goods.matches(Goods.display(ammo), saved.entries().get(1).items()),
                "the page prefills the gun's exact calibre");
            helper.assertValueEqual(
                saved.entries().get(1).target(),
                provider.suggestedAmount(gun),
                "the provider suggests the initial amount");
            ((IGun) gun.getItem()).setCurrentAmmoCount(gun, 7);
            helper.assertTrue(saved.entries().getFirst().matches(gun), "profile identity survives reloads");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}
