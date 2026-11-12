package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.BodyActions;
import io.github.izakyl.folkways.core.combat.api.DefenseControl;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import yesman.epicfight.registry.entries.EpicFightItems;
import yesman.epicfight.world.damagesource.StunType;

@EventBusSubscriber(modid = FolkwaysEpicFightMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_epicfight")
@PrefixGameTestTemplate(false)
public final class EpicFightEquipmentGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(EpicFightEquipmentGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void drawnStackPreservesComponentsAndRepeatedHolsterReturnsOne(GameTestHelper helper) {
        try (EpicFightTestScene s = new EpicFightTestScene(helper)) {
            s.sword.set(DataComponents.CUSTOM_NAME, Component.literal("actual named sword"));
            s.sword.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            ItemStack expected = s.sword.copy();
            WeaponAttack attack = s.open();
            helper.assertTrue(ItemStack.matches(s.resident.getMainHandItem(), expected), "drawn weapon retains its components and count");
            helper.assertTrue(s.resident.pack().getItem(0).isEmpty(), "borrow removes the real stack");
            attack.stop();
            s.resident.holsterWeapon(); attack.stop(); s.resident.holsterWeapon();
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "one physical stack returns exactly once");
            helper.assertTrue(ItemStack.matches(expected, s.resident.pack().getItem(0)), "all components and durability survive");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void copiedStackForeignPackAndDifferentOwnerCannotBorrow(GameTestHelper helper) {
        try (EpicFightTestScene s = new EpicFightTestScene(helper)) {
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.open(s.worker, s.sword.copy()).isEmpty(), "a presentation copy is not an actual pack reference");
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.open(s.worker(new SimpleContainer(4), s.resident.resident()), s.sword).isEmpty(), "foreign container is rejected");
            Resident foreign = new Resident(UUID.randomUUID(), s.resident.resident().kind());
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.open(s.worker(s.resident.pack(), foreign), s.sword).isEmpty(), "foreign resident identity is rejected");
            helper.assertTrue(ItemStack.matches(s.resident.pack().getItem(0), s.sword), "rejection preserves the physical pack stack");
            helper.assertFalse(s.resident.weaponDrawn(), "rejection never claims the hand");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void offThreadOpenAndSecondHandOwnerAreRejected(GameTestHelper helper) {
        try (EpicFightTestScene s = new EpicFightTestScene(helper)) {
            boolean rejected = CompletableFuture.supplyAsync(() -> EpicFightWeaponAdapter.INSTANCE.open(s.worker, s.sword).isEmpty()).join();
            helper.assertTrue(rejected, "opening requires the authoritative server thread");
            WeaponAttack attack = s.open();
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.open(s.worker, s.sword).isEmpty(), "one hand cannot have two owners");
            helper.assertTrue(s.resident.isActiveAttack(attack), "failed second open leaves first owner intact");
            attack.stop();
            s.resident.holsterWeapon();
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "rejections do not duplicate or lose the item");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void profileSelectsNativeSpearInsteadOfEarlierInventorySword(GameTestHelper helper) {
        try (EpicFightTestScene s = new EpicFightTestScene(helper)) {
            ItemStack spear = new ItemStack(EpicFightItems.IRON_SPEAR.get());
            s.resident.pack().setItem(1, spear);
            helper.assertTrue(Loadouts.using(spear).orElseThrow() == EpicFightLoadoutProvider.INSTANCE, "EF weapons have an upstream loadout provider");
            s.profile(spear.getItem());
            helper.assertValueEqual(Weapons.range(s.worker, s.zombie), EpicFightWeaponAdapter.INSTANCE.range(), "provider reports conservative native melee approach spacing");
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.range() < Weapons.MELEE.range(), "native EF approach spacing is more conservative than the ordinary 4.5-block melee contract");
            WeaponAttack attack = Weapons.open(s.worker, s.zombie).orElseThrow();
            helper.assertTrue(attack instanceof EpicFightWeaponAttack && ItemStack.matches(s.resident.getMainHandItem(), spear), "profile chooses exact carried spear through public dispatcher");
            helper.assertTrue(ItemStack.matches(s.resident.pack().getItem(0), s.sword), "selection leaves earlier sword untouched");
            attack.stop();
            s.resident.holsterWeapon();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 240)
    public static void nativeIronSpearCollisionSpendsOnlyActualLoanDurability(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        ItemStack earlier = new ItemStack(EpicFightItems.IRON_SPEAR.get());
        earlier.setDamageValue(3);
        earlier.set(DataComponents.CUSTOM_NAME, Component.literal("earlier spear"));
        ItemStack selected = new ItemStack(EpicFightItems.IRON_SPEAR.get());
        selected.setDamageValue(9);
        selected.set(DataComponents.CUSTOM_NAME, Component.literal("actual collision spear"));
        selected.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.resident.pack().setItem(0, earlier); s.resident.pack().setItem(1, selected);
        s.profile(selected.getItem());
        WeaponAttack attack = EpicFightWeaponAdapter.INSTANCE.open(s.worker, selected).orElseThrow();
        float health = s.zombie.getHealth();
        helper.assertTrue(ItemStack.matches(s.resident.getMainHandItem(), selected), "exact overload borrows selected actual spear");
        helper.assertTrue(attack.fire(s.zombie), "native spear strike begins");
        helper.assertValueEqual(s.zombie.getHealth(), health, "native windup does no immediate damage");
        helper.onEachTick(attack::tick);
        ItemStack[] returned = new ItemStack[1];
        float[] stoppedHealth = new float[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(s.zombie.getHealth() < health, "native EF spear collision hits"))
            .thenExecute(() -> {
                helper.assertTrue(s.resident.getMainHandItem().getDamageValue() > 9, "actual spear pays hit durability");
                helper.assertValueEqual(earlier.getDamageValue(), 3, "earlier spear pays no wear");
                returned[0] = s.resident.getMainHandItem().copy(); stoppedHealth[0] = s.zombie.getHealth();
                attack.stop();
                s.resident.holsterWeapon(); attack.stop(); s.resident.holsterWeapon();
                helper.assertValueEqual(s.resident.pack().countMatching(stack -> ItemStack.matches(stack, returned[0])), 1, "unique worn component state returns once");
            }).thenIdle(30).thenExecute(() -> {
                helper.assertValueEqual(s.zombie.getHealth(), stoppedHealth[0], "closed native spear has no delayed damage");
                helper.assertValueEqual(s.resident.pack().count(selected.getItem()), 2, "both original physical spears remain");
                helper.assertValueEqual(s.resident.pack().countMatching(stack -> ItemStack.matches(stack, returned[0])), 1, "delayed frames cannot change or duplicate returned spear");
                helper.assertTrue(ItemStack.matches(s.resident.pack().getItem(0), earlier), "unselected weapon keeps its components");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void newOrderRevokesAnUnorderedStrikeBeforeNativeFrame(GameTestHelper helper) {
        orderRevocation(helper, false);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void replacingLiveOrderIdentityRevokesNativeStrike(GameTestHelper helper) {
        orderRevocation(helper, true);
    }

    private static void orderRevocation(GameTestHelper helper, boolean initialOrder) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        ResidentCommands commands = ResidentCommands.of(s.colony);
        var body = Bodies.of(s.resident).orElseThrow();
        if (initialOrder) {
            commands.hold(body, WorldSpaces.at(s.resident));
        }
        WeaponAttack attack = s.open();
        float health = s.zombie.getHealth();
        helper.assertTrue(attack.fire(s.zombie), "strike begins under captured live order identity");
        var replacement = commands.hold(body, WorldSpaces.at(s.resident));
        helper.assertTrue(DefenseControl.of(s.colony).current(s.resident.id()).orElseThrow() == replacement,
            "Vanilla exposes the replacement RTS order to the native adapter");
        // Production command replacement revokes the loan before the next native animation frame.
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            helper.assertFalse(attack.fire(s.zombie), "live identity replacement returns old loan before native hit");
            helper.assertFalse(s.resident.weaponDrawn(), "old work cannot retain hand authority");
        }).thenIdle(30).thenExecute(() -> {
            helper.assertValueEqual(s.zombie.getHealth(), health, "replacement grants no residual collision");
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "replacement returns one actual item");
            helper.assertValueEqual(s.resident.pack().getItem(0).getDamageValue(), 9, "revocation spends no wear");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void explicitPermissionRevocationStopsBeforeCollision(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        WeaponAttack attack = s.open();
        AtomicBoolean authorized = new AtomicBoolean(true);
        float health = s.cow.getHealth();
        helper.assertTrue(attack.explicitTarget(s.cow, authorized::get), "exact neutral target accepts a live permission callback");
        helper.assertTrue(attack.fire(s.cow), "explicit authorized strike starts");
        authorized.set(false);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            helper.assertFalse(attack.fire(s.zombie), "native preTick observes revoked callback");
            attack.stop();
            s.resident.holsterWeapon();
        }).thenIdle(30).thenExecute(() -> {
            helper.assertValueEqual(s.cow.getHealth(), health, "revoked explicit target is never hit");
            helper.assertValueEqual(s.resident.pack().getItem(0).getDamageValue(), 9, "revoked permission spends no wear");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void nativeStunPausesLaborButCannotPreventLoanCleanup(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        WeaponAttack attack = s.open();
        ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(s.resident);
        helper.assertTrue(attack.fire(s.zombie), "strike begins");
        helper.assertTrue(BodyActions.allowed(Bodies.of(s.resident).orElseThrow()), "ordinary strike does not block its owning runner");
        helper.assertTrue(patch.applyStun(StunType.LONG, 1), "native EF stun is applied");
        helper.startSequence().thenWaitUntil(() -> helper.assertFalse(BodyActions.allowed(Bodies.of(s.resident).orElseThrow()), "real external stun closes the labor gate"))
            .thenExecute(() -> {
                attack.stop();
                s.resident.holsterWeapon(); attack.stop(); s.resident.holsterWeapon();
                helper.assertFalse(s.resident.weaponDrawn(), "cleanup proceeds while labor is paused");
                helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "stunned cleanup returns one item");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void emptyHandIsNotAnEpicFightWeaponLoan(GameTestHelper helper) {
        try (EpicFightTestScene s = new EpicFightTestScene(helper)) {
            helper.assertFalse(EpicFightWeaponAdapter.INSTANCE.accepts(ItemStack.EMPTY), "EF extension only implements actual native melee items");
            helper.assertTrue(EpicFightWeaponAdapter.INSTANCE.open(s.worker, ItemStack.EMPTY).isEmpty(), "empty hand cannot create a physical EF loan");
            helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "empty hand request preserves pack");
        }
        helper.succeed();
    }
}
