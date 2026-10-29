package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.plugins.combat.DefenseControl;
import io.github.izakyl.folkways.plugins.combat.weapon.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.*;

@EventBusSubscriber(modid = FolkwaysEpicFight.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EpicFightIntegrationGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(EpicFightIntegrationGameTests.class); }

    @SubscribeEvent
    public static void testControl(Declaring event) {
        // Tests exercise the optional Vanilla service without making RTS a runtime dependency.
        if (!ModList.get().isLoaded("folkways_rts"))
            event.colony(DefenseControl.ID, scope -> scope.share(new EpicFightTestScene.Control()));
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void nativeSwordCollisionHitsOnceAndCloseCancelsRemainingFrames(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        AtomicInteger hits = new AtomicInteger();
        s.damageListener(event -> { if (event.getEntity() == s.zombie && event.getSource().getEntity() == s.resident) hits.incrementAndGet(); });
        WeaponSession session = Weapons.open(s.worker).orElseThrow();
        helper.assertTrue(session instanceof EpicFightWeaponSession, "upstream dispatcher selects EF for the actual profile sword");
        float health = s.zombie.getHealth();
        helper.assertFalse(session.fire(s.cow), "ordinary melee cannot target a neutral cow");
        helper.assertTrue(session.fire(s.zombie), "native attack starts");
        helper.assertValueEqual(s.zombie.getHealth(), health, "windup does not invent an immediate hit");
        helper.onEachTick(session::tick);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(s.zombie.getHealth() < health, "real EF collision advances"))
            .thenExecute(() -> {
                helper.assertValueEqual(hits.get(), 1, "native hit has no duplicate vanilla attack");
                helper.assertTrue(s.sword.getDamageValue() > 9, "actual item pays durability");
                session.close(); session.close();
            }).thenIdle(30).thenExecute(() -> {
                helper.assertValueEqual(hits.get(), 1, "closed animation cannot hit again");
                helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "one physical sword returns");
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void stopBeforeAnimationTickRevokesHitsAndPreservesTheRealLoan(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        WeaponSession session = s.open();
        float health = s.zombie.getHealth();
        helper.assertTrue(session.fire(s.zombie), "strike is accepted before cancellation");
        session.stop();
        helper.assertTrue(ResidentEpicFightPatch.of(s.resident).isTargetInvulnerable(s.zombie), "permission is immediately revoked");
        helper.assertTrue(s.resident.getMainHandItem() == s.sword, "stop retains the same loan until close");
        session.close(); session.close();
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(s.zombie.getHealth(), health, "later native frames cannot damage");
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "close returns one item");
            helper.assertValueEqual(s.resident.pack().getItem(0).getDamageValue(), 9, "cancelled hit spends no durability");
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deadTargetRejectsEveryLaterCollisionAndFireIntent(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        AtomicInteger hits = new AtomicInteger();
        s.damageListener(event -> { if (event.getEntity() == s.zombie && event.getSource().getEntity() == s.resident) hits.incrementAndGet(); });
        WeaponSession session = s.open();
        helper.assertTrue(session.fire(s.zombie), "strike starts before external target death");
        s.zombie.setHealth(0);
        session.tick();
        helper.assertFalse(session.fire(s.zombie), "dead targets never restart an attack");
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(hits.get(), 0, "dead target cannot be hit by a later native phase");
            helper.assertValueEqual(s.sword.getDamageValue(), 9, "dead-target collision spends no durability");
        }).thenSucceed();
    }
}
