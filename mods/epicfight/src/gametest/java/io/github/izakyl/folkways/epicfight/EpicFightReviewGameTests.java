package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import io.github.izakyl.folkways.vanilla.patrol.PatrolContent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Regressions from the review of the upstream port: selection scope, mounted fallback and strike leases. */
@GameTestHolder("folkways_epicfight")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = FolkwaysEpicFightMod.ID, bus = EventBusSubscriber.Bus.MOD)
public final class EpicFightReviewGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(EpicFightReviewGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void diggingToolsAreNeverNativeWeapons(GameTestHelper helper) {
        try (EpicFightTestScene s = new EpicFightTestScene(helper)) {
            for (ItemStack tool : List.of(new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.IRON_SHOVEL),
                    new ItemStack(Items.IRON_HOE))) {
                helper.assertFalse(EpicFightWeaponAdapter.INSTANCE.accepts(tool), "EF capability does not turn " + tool + " into a weapon");
                helper.assertTrue(Loadouts.using(tool).isEmpty(), "profiles cannot list " + tool + " as a weapon");
            }
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.accepts(new ItemStack(Items.IRON_AXE)), "axes stay native weapons");
            // Without a profile, a pickaxe scanned first must not win over the carried sword.
            LoadoutProfiles.of(s.colony).assign(List.of(s.resident.id()), Optional.empty());
            ColonyFront.of(s.colony).setSetting(PatrolContent.ID, "no_profile",
                new ColonySettings.Value.Choice(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "engage")));
            ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
            s.resident.pack().setItem(0, pickaxe);
            s.resident.pack().setItem(3, s.sword);
            WeaponAttack attack = Weapons.open(s.worker, s.zombie).orElseThrow();
            helper.assertTrue(ItemStack.matches(s.resident.getMainHandItem(), s.sword), "the sword, not the earlier pickaxe, is borrowed");
            helper.assertTrue(s.resident.pack().getItem(0) == pickaxe, "the pickaxe stays in the pack");
            attack.stop();
            s.resident.holsterWeapon();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void mountedProfileFallsBackToUpstreamMelee(GameTestHelper helper) {
        Horse horse = EntityType.HORSE.create(helper.getLevel());
        try (EpicFightTestScene s = new EpicFightTestScene(helper)) {
            horse.setNoAi(true);
            horse.moveTo(s.resident.getX(), s.resident.getY(), s.resident.getZ());
            s.level.addFreshEntity(horse);
            helper.assertTrue(s.resident.startRiding(horse, true) && s.resident.isPassenger(), "resident rides the horse");
            WeaponAttack attack = Weapons.open(s.worker, s.zombie).orElse(null);
            helper.assertTrue(attack != null, "a profile sword still opens a attack while mounted");
            helper.assertFalse(attack instanceof EpicFightWeaponAttack, "EF declines the rider and upstream melee takes the profile sword");
            helper.assertTrue(ItemStack.matches(s.resident.getMainHandItem(), s.sword), "the exact profile sword is borrowed");
            attack.stop();
            s.resident.holsterWeapon();
            s.resident.stopRiding();
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "closing returns the one sword");
        } finally {
            horse.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void lapsedLeaseStopsStrikeBeforeCollision(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        WeaponAttack attack = s.open();
        AtomicBoolean lease = new AtomicBoolean(true);
        attack.leasedBy(lease::get);
        float health = s.zombie.getHealth();
        helper.assertTrue(attack.fire(s.zombie), "leased strike starts");
        lease.set(false);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            helper.assertFalse(attack.fire(s.zombie), "native preTick observes the lapsed lease");
            attack.stop();
            s.resident.holsterWeapon();
        }).thenIdle(30).thenExecute(() -> {
            helper.assertValueEqual(s.zombie.getHealth(), health, "a lapsed lease never lands a hit");
            helper.assertValueEqual(s.resident.pack().getItem(0).getDamageValue(), 9, "a lapsed lease spends no wear");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void stalledStrikeNodeCannotLandItsPendingStrike(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        Node node = EpicFightNodeFixture.strike(s.level, s.zombie);
        Workload.Continuous workload = (Workload.Continuous) node.spec().workload();
        float health = s.zombie.getHealth();
        helper.assertTrue(workload.holds(s.level, s.worker), "the upstream node opens a real EF strike");
        helper.assertTrue(s.resident.weaponDrawn(), "the strike holds the hand");
        // The node is then suspended (or paused) and never advances again: the strike must not land.
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            helper.assertValueEqual(s.zombie.getHealth(), health, "a node that stopped advancing grants no collision");
            helper.assertTrue(s.resident.getMainHandItem().isEmpty(), "the idle sweep holsters the revoked strike");
            helper.assertValueEqual(s.resident.pack().getItem(0).getDamageValue(), 9, "no wear is spent");
            node.released(s.level, s.worker, Ending.DROPPED);
            helper.assertFalse(s.resident.weaponDrawn(), "release still returns the loan");
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "one sword returns");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void sweepAdmitsOnlyTheSessionTarget(GameTestHelper helper) {
        Zombie bystander = EntityType.ZOMBIE.create(helper.getLevel());
        try (EpicFightTestScene s = new EpicFightTestScene(helper)) {
            bystander.setNoAi(true);
            bystander.moveTo(s.zombie.getX(), s.zombie.getY(), s.zombie.getZ() + 0.6);
            s.level.addFreshEntity(bystander);
            EpicFightWeaponAttack attack = (EpicFightWeaponAttack) s.open();
            helper.assertTrue(attack.fire(s.zombie), "strike starts on the admitted target");
            helper.assertTrue(attack.permits(s.zombie), "the admitted target may be struck");
            helper.assertFalse(attack.permits(bystander), "another hostile inside the sweep may not");
            attack.stop();
            s.resident.holsterWeapon();
        } finally {
            bystander.discard();
        }
        helper.succeed();
    }
}
