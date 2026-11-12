package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysEpicFightMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_epicfight")
@PrefixGameTestTemplate(false)
public final class EpicFightIntegrationGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(EpicFightIntegrationGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 240)
    public static void nativeSwordCollisionHitsOnceAndCloseCancelsRemainingFrames(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        AtomicInteger hits = new AtomicInteger();
        s.damageListener(event -> { if (event.getEntity() == s.zombie && event.getSource().getEntity() == s.resident) hits.incrementAndGet(); });
        WeaponAttack attack = Weapons.open(s.worker).orElseThrow();
        helper.assertTrue(attack instanceof EpicFightWeaponAttack, "upstream dispatcher selects EF for the actual profile sword");
        float health = s.zombie.getHealth();
        helper.assertFalse(attack.fire(s.cow), "ordinary melee cannot target a neutral cow");
        helper.assertTrue(attack.fire(s.zombie), "native attack starts");
        helper.assertValueEqual(s.zombie.getHealth(), health, "windup does not invent an immediate hit");
        helper.onEachTick(attack::tick);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(s.zombie.getHealth() < health, "real EF collision advances"))
            .thenExecute(() -> {
                helper.assertValueEqual(hits.get(), 1, "native hit has no duplicate vanilla attack");
                helper.assertTrue(s.sword.getDamageValue() > 9, "actual item pays durability");
                attack.stop();
                s.resident.holsterWeapon(); attack.stop(); s.resident.holsterWeapon();
            }).thenIdle(30).thenExecute(() -> {
                helper.assertValueEqual(hits.get(), 1, "closed animation cannot hit again");
                helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "one physical sword returns");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void stopBeforeAnimationTickRevokesHitsAndPreservesTheRealLoan(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        WeaponAttack attack = s.open();
        float health = s.zombie.getHealth();
        helper.assertTrue(attack.fire(s.zombie), "strike is accepted before cancellation");
        attack.stop();
        helper.assertTrue(ResidentEpicFightPatch.of(s.resident).isTargetInvulnerable(s.zombie), "permission is immediately revoked");
        helper.assertTrue(ItemStack.matches(s.resident.getMainHandItem(), s.sword), "stop keeps the physical weapon drawn until holstering");
        attack.stop();
        s.resident.holsterWeapon(); attack.stop(); s.resident.holsterWeapon();
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(s.zombie.getHealth(), health, "later native frames cannot damage");
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "close returns one item");
            helper.assertValueEqual(s.resident.pack().getItem(0).getDamageValue(), 9, "cancelled hit spends no durability");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void deadTargetRejectsEveryLaterCollisionAndFireIntent(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        AtomicInteger hits = new AtomicInteger();
        s.damageListener(event -> { if (event.getEntity() == s.zombie && event.getSource().getEntity() == s.resident) hits.incrementAndGet(); });
        WeaponAttack attack = s.open();
        helper.assertTrue(attack.fire(s.zombie), "strike starts before external target death");
        s.zombie.setHealth(0);
        attack.tick();
        helper.assertFalse(attack.fire(s.zombie), "dead targets never restart an attack");
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(hits.get(), 0, "dead target cannot be hit by a later native phase");
            helper.assertValueEqual(s.sword.getDamageValue(), 9, "dead-target collision spends no durability");
        }).thenSucceed();
    }
}
