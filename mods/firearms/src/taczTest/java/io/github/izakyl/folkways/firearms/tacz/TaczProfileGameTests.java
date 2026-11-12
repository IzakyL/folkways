package io.github.izakyl.folkways.firearms.tacz;

import io.github.izakyl.folkways.firearms.FolkwaysFirearmsMod;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.init.ModItems;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysFirearmsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_firearms")
@PrefixGameTestTemplate(false)
public final class TaczProfileGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TaczProfileGameTests.class);
    }

    @GameTest(batch = "tacz_native", template = "empty_large", templateNamespace = "folkways_core")
    public static void providerDescribesRealGunDataWithoutOwningSupplyAmounts(GameTestHelper helper) {
        var scene = new TaczWeaponGameTests.Fixture(helper, "m1911", FireMode.SEMI, 5);
        var provider = new TaczLoadouts();
        ItemStack gun = scene.actual();
        var data = TaczGuns.index(TaczWeaponGameTests.id("m1911")).orElseThrow().getGunData();
        helper.assertTrue(provider.ranged(gun) && !provider.indirect(gun), "TACZ is direct ranged fire");
        helper.assertValueEqual(
            provider.magazineSize(gun), data.getAmmoAmount(), "magazine metadata uses gun data");
        helper.assertValueEqual(
            provider.cycleTicks(gun),
            (int) Math.ceil(1200D / data.getRoundsPerMinute(FireMode.SEMI)),
            "semi cycle uses native RPM");
        helper.assertTrue(
            provider.range(gun) <= TaczConfig.range(TaczWeaponGameTests.id("m1911")),
            "effective range respects per-gun and server caps");
        helper.assertValueEqual(
            provider.roundsLoaded(gun, TaczGuns.ammunition(data.getAmmoId())),
            5,
            "loaded count includes the chamber");
        helper.assertValueEqual(
            provider.roundsLoaded(gun, TaczGuns.ammunition(TaczWeaponGameTests.id("9mm"))),
            0,
            "wrong calibre cannot count loaded rounds");
        var profiles = LoadoutProfiles.of(scene.colony);
        var profile = profiles.assigned(scene.body.id()).orElseThrow();
        helper.assertValueEqual(
            profile.entries().get(1).target(),
            data.getAmmoAmount() * 3,
            "adding the gun prefills three magazines once");
        var entries = new ArrayList<>(profile.entries());
        var supply = entries.get(1);
        entries.set(1, new LoadoutProfile.Entry(supply.id(), supply.kind(), supply.items(), supply.slot(), 13, 2));
        profiles.replace(new LoadoutProfile(profile.id(), profile.name(), entries));
        profiles.add(profile.id(), LoadoutProfile.Entry.weapon(TaczGuns.weapon(TaczWeaponGameTests.id("m1911"))));
        helper.assertValueEqual(
            profiles.get(profile.id()).orElseThrow().entries().get(1).target(),
            13,
            "adding another gun preserves the player's supply target");
        helper.assertValueEqual(
            profiles.get(profile.id()).orElseThrow().entries().get(1).threshold(),
            2,
            "the player owns the resupply threshold");
        ItemStack bolt = TaczWeaponGameTests.gun("kar98", FireMode.SEMI, 0);
        var boltData = TaczGuns.index(TaczWeaponGameTests.id("kar98")).orElseThrow().getGunData();
        helper.assertTrue(
            provider.cycleTicks(bolt) >= Math.ceil(boltData.getBoltActionTime() * 20),
            "manual bolt action bounds the cycle");
        ItemStack burst = TaczWeaponGameTests.gun("m16a4", FireMode.BURST, 0);
        var burstData = TaczGuns.index(TaczWeaponGameTests.id("m16a4")).orElseThrow().getGunData().getBurstData();
        helper.assertTrue(
            provider.cycleTicks(burst) >= Math.ceil(burstData.getMinInterval() * 20),
            "burst delay bounds the cycle");
        helper.succeed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", templateNamespace = "folkways_core")
    public static void profileVariantsPinGunModelAndAllowAttachmentsAndReloadedState(GameTestHelper helper) {
        var scene = new TaczWeaponGameTests.Fixture(helper, "m16a4", FireMode.BURST, 6);
        var provider = new TaczLoadouts();
        helper.assertTrue(
            provider.weaponChoices().stream()
            .anyMatch(stack -> Goods.matches(stack, TaczGuns.weapon(TaczWeaponGameTests.id("m1911")))),
            "picker exposes concrete supported GunId variants");
        helper.assertTrue(
            provider.weaponChoices().stream()
            .noneMatch(stack -> Goods.matches(stack, TaczGuns.weapon(TaczWeaponGameTests.id("rpg7")))),
            "picker excludes explosive guns");
        ItemStack attachment = new ItemStack(ModItems.ATTACHMENT.get());
        ((IAttachment) attachment.getItem()).setAttachmentId(attachment, TaczWeaponGameTests.id("scope_acog_ta31"));
        ItemStack actual = scene.body.pack().getItem(0);
        IGun operator = (IGun) actual.getItem();
        helper.assertTrue(operator.allowAttachment(actual, attachment), "the scope is compatible with this gun");
        operator.installAttachment(helper.getLevel().registryAccess(), actual, attachment);
        var identity = provider.profileWeapon(actual);
        ItemStack reloaded = actual.copy();
        ((IGun) reloaded.getItem()).setCurrentAmmoCount(reloaded, 20);
        helper.assertTrue(Goods.matches(reloaded, identity), "normal identity survives magazine changes");
        helper.assertTrue(
            Goods.matches(TaczWeaponGameTests.gun("m16a4", FireMode.BURST, 6), identity),
            "model choices also collect conventional unattached guns");
        helper.assertFalse(
            Goods.matches(TaczWeaponGameTests.gun("ak47", FireMode.AUTO, 6), identity),
            "another GunId remains distinct");
        helper.succeed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", templateNamespace = "folkways_core")
    public static void directObservationCannotBypassOwnSightOrGunRange(GameTestHelper helper) {
        var scene = new TaczWeaponGameTests.Fixture(helper, "m1911", FireMode.SEMI, 3);
        scene.open();
        scene.target.setPos(scene.body.getX() + 17, scene.body.getY(), scene.body.getZ());
        helper.assertTrue(
            scene.attack.spottedTarget(scene.target, scene.target.position(), () -> true),
            "a live observation can be bound before range validation");
        helper.assertFalse(scene.attack.fire(scene.target), "shared spotting does not extend the per-gun cap");
        scene.target.setPos(scene.body.getX() + 6, scene.body.getY(), scene.body.getZ());
        var wall = scene.body.blockPosition().offset(3, 1, 0);
        helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        helper.assertFalse(scene.attack.fire(scene.target), "direct guns retain their own line-of-sight check");
        helper.getLevel().setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        helper.assertTrue(scene.attack.fire(scene.target), "clear sight within range permits a real fire intent");
        helper.assertValueEqual(scene.total("45acp"), 3, "refusals and fire intents do not invent or spend rounds");
        helper.succeed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100_000)
    public static void revokedObservationStopsDelayedBurstWithoutAnotherSessionTick(GameTestHelper helper) {
        var scene = new TaczWeaponGameTests.Fixture(helper, "m16a4", FireMode.BURST, 6);
        AtomicBoolean authorized = new AtomicBoolean(true);
        scene.open();
        helper.assertTrue(
            scene.attack.spottedTarget(scene.target, scene.target.position(), authorized::get),
            "sentry observation installs a live direct-fire permission");
        scene.firstShot =
            () -> {
                authorized.set(false);
                scene.advance = false;
            };
        scene.attack.fire(scene.target);
        helper.startSequence()
            .thenWaitUntil(
            () -> helper.assertTrue(scene.shots.get() == 1, "first authorized round fires"))
            .thenExecute(() -> scene.waitMillis(1250))
            .thenWaitUntil(scene::waited)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.shots.get(), 1, "revoked observation blocks every delayed round");
                helper.assertValueEqual(scene.total("556x45"), 5, "blocked rounds stay physical");
            })
            .thenSucceed();
    }
}
