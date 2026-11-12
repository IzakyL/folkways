package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.api.CoreSettings;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.combat.SpottedTargets;
import io.github.izakyl.folkways.vanilla.patrol.PatrolContent;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class LoadoutProfileGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        io.github.izakyl.folkways.core.testing.SelectedTests.register(event, LoadoutProfileGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void equippingArmorPublishesTheWornItemBeforePlanningAnotherTrip(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        ResidentEntity body = scene.body();
        var helmet = LoadoutProfile.Entry.armor(EquipmentSlot.HEAD, ItemSpec.of(Items.IRON_HELMET));
        LoadoutProfile profile = scene.profile(List.of(helmet));
        scene.store(12).setItem(0, new ItemStack(Items.IRON_HELMET));
        body.pack().insert(new ItemStack(Items.IRON_HELMET));
        InventoryObservations.publish(scene.colony, helper.getLevel());
        helper.assertTrue(Equipment.reconcile(scene.colony, body), "the helmet moves into its armor slot");
        InventorySnapshot.Resident observed = scene.colony.inventory().snapshot().residents().get(body.id());
        helper.assertTrue(observed.state() != InventorySnapshot.State.OBSERVED
            || scene.supplies.observedDeficits(body, profile, observed).get(helmet.id()) == 0,
            "the inventory index must not publish an observed missing helmet after equipping it");
        InventoryObservations.publish(scene.colony, helper.getLevel());
        scene.supplies.resupply(TestWorker.of(body), Optional.empty(), Long.MIN_VALUE, Long.MIN_VALUE);
        helper.assertTrue(scene.supplies.goals(scene.colony.view(helper.getLevel())).isEmpty()
            && scene.supplies.status(body.id()).state() == PatrolSupplies.State.READY,
            "worn armor must not ask for another delivery");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 250)
    public static void damagedArmorStaysEquippedWithAFullPackAndDrawnWeapons(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        ResidentEntity body = scene.body();
        scene.profile(List.of(
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD)),
            LoadoutProfile.Entry.armor(EquipmentSlot.HEAD, ItemSpec.of(Items.IRON_HELMET)),
            LoadoutProfile.Entry.armor(EquipmentSlot.CHEST, ItemSpec.of(Items.IRON_CHESTPLATE)),
            LoadoutProfile.Entry.armor(EquipmentSlot.LEGS, ItemSpec.of(Items.IRON_LEGGINGS)),
            LoadoutProfile.Entry.armor(EquipmentSlot.FEET, ItemSpec.of(Items.IRON_BOOTS))));
        body.pack().insert(new ItemStack(Items.IRON_SWORD));
        for (var item : List.of(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS)) {
            ItemStack armor = new ItemStack(item);
            armor.setDamageValue(7);
            armor.set(DataComponents.CUSTOM_NAME, Component.literal("worn armor"));
            body.pack().insert(armor);
        }
        helper.assertTrue(Equipment.reconcile(scene.colony, body), "the whole suit is equipped");
        for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
            if (body.pack().getItem(slot).isEmpty()) {
                body.pack().setItem(slot, new ItemStack(Items.WHEAT, 64));
            }
        }
        helper.assertFalse(body.canPickUpLoot(), "residents do not use native mob loot pickup");
        scene.simulate();
        int[] damage = {7};
        helper.onEachTick(() -> {
            scene.supplies.tick(helper.getLevel().getServer());
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                ItemStack worn = body.getItemBySlot(slot);
                helper.assertTrue(!worn.isEmpty() && worn.getDamageValue() == damage[0]
                    && Component.literal("worn armor").equals(worn.get(DataComponents.CUSTOM_NAME)),
                    "damaged named armor remains in its slot on every tick");
            }
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                body.getBoundingBox().inflate(8)).isEmpty(), "no equipment falls out");
            helper.assertTrue(scene.supplies.profiles().assigned(body.id()).isPresent(),
                "the loadout remains assigned while labor runs");
        });
        WeaponAttack[] attack = new WeaponAttack[1];
        helper.startSequence()
            .thenIdle(10)
            .thenExecute(() -> {
                for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                    body.getItemBySlot(slot).setDamageValue(8);
                }
                damage[0] = 8;
            })
            .thenIdle(10)
            .thenExecute(() -> attack[0] = Weapons.open(TestWorker.of(body)).orElseThrow())
            .thenIdle(40)
            .thenExecute(() -> { attack[0].stop(); body.holsterWeapon(); })
            .thenIdle(60)
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 180)
    public static void mismatchedDamagedArmorCannotBeReequippedByTheSameDemand(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        ResidentEntity body = scene.body();
        ItemSpec pinned = ItemSpec.of(Items.IRON_HELMET)
            .withComponents(DataComponentMap.builder().set(DataComponents.DAMAGE, 0).build());
        scene.profile(List.of(LoadoutProfile.Entry.armor(EquipmentSlot.HEAD, pinned)));
        body.pack().insert(new ItemStack(Items.IRON_HELMET));
        helper.assertTrue(Equipment.reconcile(scene.colony, body), "the matching helmet is equipped");
        for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
            body.pack().setItem(slot, new ItemStack(Items.WHEAT, 64));
        }
        body.getItemBySlot(EquipmentSlot.HEAD).setDamageValue(7);
        helper.assertTrue(Equipment.reconcile(scene.colony, body), "a component mismatch removes the helmet");
        List<ItemEntity> dropped = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
            body.getBoundingBox().inflate(4), item -> item.getItem().is(Items.IRON_HELMET));
        helper.assertValueEqual(dropped.size(), 1, "a full pack causes one real helmet drop");
        scene.others.addAll(dropped);
        helper.assertFalse(Equipment.reconcile(scene.colony, body), "the same mismatched helmet cannot be worn again");
        helper.assertTrue(body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the armor slot stays empty");
        helper.assertFalse(body.canPickUpLoot(), "native mob loot pickup is disabled");
        scene.simulate();
        helper.onEachTick(() -> helper.assertTrue(body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
            "labor and nearby dropped armor do not re-equip the mismatched helmet"));
        helper.startSequence().thenIdle(100).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 450)
    public static void spyglassSupplyCollectsPhysicalStockAndProfileRemovalRevokesSight(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        Container stock = scene.store(12);
        stock.setItem(0, new ItemStack(Items.SPYGLASS));
        Zombie target = EntityType.ZOMBIE.create(helper.getLevel());
        target.moveTo(scene.body().position().add(8, 0, 0));
        target.setNoAi(true);
        target.setGlowingTag(true);
        helper.getLevel().addFreshEntity(target);
        scene.others.add(target);
        double base = SpottedTargets.sight(scene.body(), target);
        LoadoutProfile profile = scene.profile(List.of(LoadoutProfile.Entry.supply(ItemSpec.of(Items.SPYGLASS), 1, 1)));
        helper.assertTrue(SpottedTargets.sight(scene.body(), target)
            == base * FolkwaysVanillaConfig.observationSightMultiplier(),
            "assigned optics determine sight before physical collection");
        scene.simulate();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.body().pack().count(Items.SPYGLASS) == 1,
            "normal profile logistics collect one real spyglass " + scene.supplies.status(scene.body().id())))
            .thenExecute(() -> {
                helper.assertTrue(stock.isEmpty(), "the observation item leaves real storage exactly once");
                helper.assertTrue(SpottedTargets.sight(scene.body(), target)
                    == base * FolkwaysVanillaConfig.observationSightMultiplier(),
                    "physical collection activates observation sight");
                scene.supplies.profiles().replace(new LoadoutProfile(profile.id(), profile.name(), List.of()));
                helper.assertTrue(scene.body().pack().count(Items.SPYGLASS) == 1
                    && SpottedTargets.sight(scene.body(), target) == base,
                    "removing the profile requirement revokes the bonus while the item is still carried");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void twoResidentsOnOneProfileEachCollectTheirOwnFullPhysicalSet(GameTestHelper helper) {
        Scene scene = new Scene(helper, 2);
        Container stock = scene.store(12);
        stock.setItem(0, new ItemStack(Items.IRON_SWORD));
        stock.setItem(1, new ItemStack(Items.IRON_SWORD));
        stock.setItem(2, new ItemStack(Items.IRON_HELMET));
        stock.setItem(3, new ItemStack(Items.IRON_HELMET));
        stock.setItem(4, new ItemStack(Items.ARROW, 20));
        scene.profile(
            List.of(
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD)),
            LoadoutProfile.Entry.armor(EquipmentSlot.HEAD, ItemSpec.of(Items.IRON_HELMET)),
            LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 6, 3)));
        scene.simulate();
        helper.startSequence()
            .thenWaitUntil(
            () -> {
                for (ResidentEntity body : scene.people) {
                    helper.assertTrue(
                        body.pack().count(Items.IRON_SWORD) == 1
                        && body.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                        && body.pack().count(Items.ARROW) == 6,
                        "each resident physically collects and wears their own profile set: "
                        + scene.supplies.status(body.id()));
                }
            })
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    stock.getItem(4).getCount(),
                    8,
                    "twelve physical arrows were collected, without shared ownership");
                helper.assertTrue(
                    stock.getItem(0).isEmpty() && stock.getItem(1).isEmpty(),
                    "two real weapons leave storage");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 650)
    public static void componentPinnedEntryRefusesAnOrdinaryInstanceAndKeepsTheMatchingOne(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        Container stock = scene.store(12);
        ItemStack named = new ItemStack(Items.IRON_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("family sword"));
        var spec =
            ItemSpec.of(Items.IRON_SWORD)
            .withComponents(
            DataComponentMap.builder()
            .set(DataComponents.CUSTOM_NAME, Component.literal("family sword"))
            .build());
        scene.profile(List.of(LoadoutProfile.Entry.weapon(spec)));
        stock.setItem(0, new ItemStack(Items.IRON_SWORD));
        scene.simulate();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.supplies.status(scene.body().id()).state()
                    == PatrolSupplies.State.FETCHING,
                    "the pinned sword is asked of the planner"))
            .thenIdle(100)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.body().pack().count(Items.IRON_SWORD),
                    0,
                    "plain sword cannot satisfy pinned components");
                helper.assertTrue(
                    scene.supplies.status(scene.body().id()).state() == PatrolSupplies.State.FETCHING,
                    "the delivery stays asked while no matching stock exists");
                stock.setItem(1, named.copy());
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.body().pack().count(Items.IRON_SWORD) == 1,
                    "matching physical sword is collected"))
            .thenExecute(
            () -> {
                helper.assertTrue(
                    !stock.getItem(0).isEmpty() && stock.getItem(1).isEmpty(),
                    "only matching stock leaves");
                helper.assertTrue(
                    scene.supplies.keeps(scene.body()).stream()
                    .anyMatch(amount -> Goods.matches(named, amount.spec())),
                    "component identity survives pack retention");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void patrolNeverDuplicatesPersonalRequestsOrWithdrawsThemWhenDutyEnds(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        ResidentEntity body = scene.body();
        scene.profile(List.of(LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)),
            LoadoutProfile.Entry.armor(EquipmentSlot.HEAD, ItemSpec.of(Items.IRON_HELMET))));
        InventoryObservations.publish(scene.colony, helper.getLevel());
        Worker worker = TestWorker.of(body);
        long now = helper.getLevel().getGameTime();
        var view = scene.colony.view(helper.getLevel());
        scene.supplies.resupply(worker, Optional.empty(), now, now + 200);
        helper.assertTrue(scene.supplies.goals(view).isEmpty(),
            "Core owns the personal demands; patrol publishes no second copy");
        scene.supplies.resupply(worker, Optional.empty(), now, now - 1);
        helper.assertTrue(scene.supplies.goals(view).isEmpty(), "ending duty adds no patrol delivery");
        helper.assertTrue(scene.supplies.profiles().assigned(body.id()).isPresent(),
            "ending duty keeps the profile and its personal supply requirements");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void failedWeaponOpenMarksStockChangedAndProfileLogisticsRefillAmmo(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        ResidentEntity body = scene.body();
        body.pack().insert(new ItemStack(Items.BOW));
        Container stock = scene.store(9);
        stock.setItem(0, new ItemStack(Items.ARROW, 4));
        scene.profile(List.of(LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)),
            LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 4, 1)));
        InventoryObservations.publish(scene.colony, helper.getLevel());
        Worker worker = TestWorker.of(body);
        helper.assertTrue(Weapons.ranged(worker), "assigned bow determines ranged planning before refill");
        helper.assertTrue(Weapons.open(worker).isEmpty(), "opening fails with no real ammunition");
        helper.assertFalse(Weapons.armed(worker), "combat waits for the failed loadout to be refilled");
        helper.assertTrue(scene.colony.inventory().snapshot().residents().get(body.id()).state()
            != InventorySnapshot.State.OBSERVED,
            "failed opening marks the resident observation changed");
        scene.simulate();
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(body.pack().count(Items.ARROW), 4,
            "profile logistics deliver the missing physical ammunition"))
            .thenExecute(() -> {
                scene.supplies.resupply(worker, Optional.empty(), Long.MIN_VALUE, Long.MIN_VALUE);
                helper.assertTrue(Weapons.armed(worker), "combat resumes once the assigned loadout is ready");
                WeaponAttack attack = Weapons.open(worker).orElseThrow();
                helper.assertTrue(body.getMainHandItem().is(Items.BOW), "the refilled resident opens its real bow");
                attack.stop();
                body.holsterWeapon();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void restockingStartsBelowThresholdAndFillsToTargetFromEveryStore(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        Container first = scene.store(9), second = scene.store(16);
        first.setItem(0, new ItemStack(Items.ARROW, 2));
        second.setItem(0, new ItemStack(Items.ARROW, 20));
        scene.body().pack().insert(new ItemStack(Items.ARROW, 4));
        scene.profile(List.of(LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 12, 4)));
        scene.simulate();
        helper.startSequence()
            .thenIdle(25)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.body().pack().count(Items.ARROW),
                    4,
                    "equal to threshold does not trigger restock");
                helper.assertValueEqual(
                    first.getItem(0).getCount(), 2, "no source mutation before threshold crossing");
                Containers.extract(scene.body().pack(), stack -> stack.is(Items.ARROW), 1);
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.body().pack().count(Items.ARROW) == 12,
                    "refill below the threshold fills to the target, wherever the arrows are"))
            .thenIdle(30)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.body().pack().count(Items.ARROW), 12, "restock stops at the player target");
                helper.assertValueEqual(
                    first.getItem(0).getCount() + second.getItem(0).getCount(),
                    13,
                    "exactly nine arrows leave storage");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void profileWeaponSelectionChangesWithTargetAndValidatesAmmoAtOpen(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        ResidentEntity body = scene.body();
        body.setNoAi(true);
        body.pack().insert(new ItemStack(Items.BOW));
        body.pack().insert(new ItemStack(Items.IRON_SWORD));
        body.pack().insert(new ItemStack(Items.ARROW, 2));
        LoadoutProfile profile =
            scene.profile(
            List.of(
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)),
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD))));
        helper.assertTrue(profile.hasRanged(), "ranged profile query works without equipping it");
        Zombie target = EntityType.ZOMBIE.create(helper.getLevel());
        target.setNoAi(true);
        target.moveTo(body.getX() + 7, body.getY(), body.getZ());
        helper.getLevel().addFreshEntity(target);
        scene.others.add(target);
        Worker worker = TestWorker.of(body);
        WeaponAttack bow = Weapons.select(worker, target, null);
        helper.assertTrue(
            body.getMainHandItem().is(Items.BOW) && bow.fire(target), "ranged target begins an actual bow draw");
        target.moveTo(body.getX() + 2, body.getY(), body.getZ());
        WeaponAttack sword = Weapons.select(worker, target, bow);
        helper.assertTrue(
            !bow.fire(target) && body.getMainHandItem().is(Items.IRON_SWORD),
            "closing enemy cancels the bow before opening melee");
        target.moveTo(body.getX() + 7, body.getY(), body.getZ());
        WeaponAttack ranged = Weapons.select(worker, target, sword);
        helper.assertTrue(
            !sword.fire(target) && body.getMainHandItem().is(Items.BOW),
            "range selects bow again");
        helper.assertTrue(ranged.fire(target), "second bow draw begins");
        Containers.extract(body.pack(), stack -> stack.is(Items.ARROW), 2);
        WeaponAttack stillDrawing = Weapons.select(worker, target, ranged);
        helper.assertTrue(stillDrawing == ranged, "planning keeps the assigned bow despite ammunition removal");
        stillDrawing.stop();
        body.holsterWeapon();
        helper.assertTrue(Weapons.select(worker, target, null) == null,
            "opening the assigned bow fails after ammunition is removed");
        helper.assertValueEqual(body.pack().count(Items.BOW), 1, "one actual bow returns");
        helper.assertValueEqual(body.pack().count(Items.IRON_SWORD), 1, "one actual sword returns");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void noProfileDefaultsToFleeAndAtHandEngagementNeverFetches(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        ResidentEntity body = scene.body();
        body.pack().insert(new ItemStack(Items.IRON_SWORD));
        Worker worker = TestWorker.of(body);
        helper.assertTrue(
            !Weapons.armed(worker) && Weapons.open(worker).isEmpty(),
            "default no-profile policy leaves combat to fear despite carried weapon");
        helper.assertTrue(
            ColonyFront.of(scene.colony)
            .setSetting(
            PatrolContent.ID,
            "no_profile",
            new ColonySettings.Value.Choice(
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "engage"))),
            "colony can choose at-hand engagement");
        helper.assertTrue(Weapons.armed(worker), "at-hand policy recognizes existing equipment");
        WeaponAttack attack = Weapons.open(worker).orElseThrow();
        attack.stop();
        body.holsterWeapon();
        scene.store(12).setItem(0, new ItemStack(Items.BOW));
        scene.supplies.resupply(worker, Optional.empty(), Long.MIN_VALUE, Long.MIN_VALUE);
        helper.assertTrue(
            scene.supplies.goals(scene.colony.view(helper.getLevel())).isEmpty(),
            "at-hand engagement never requests gear");
        helper.assertTrue(
            scene.supplies.keeps(body).isEmpty(), "no profile creates no stock target or retention allowance");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void overlappingEntriesUseDistinctUnitsAndArmorNeverOverwritesPlayerEquipment(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        ResidentEntity body = scene.body();
        body.pack().insert(new ItemStack(Items.IRON_SWORD));
        var first = LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD));
        var second = LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD));
        LoadoutProfile profile =
            scene.profile(
            List.of(
            first,
            second,
            LoadoutProfile.Entry.armor(EquipmentSlot.HEAD, ItemSpec.of(Items.IRON_HELMET))));
        helper.assertValueEqual(
            scene.supplies.deficits(body, profile).get(first.id()), 0, "first entry owns the physical sword");
        helper.assertValueEqual(
            scene.supplies.deficits(body, profile).get(second.id()),
            1,
            "second entry still needs another physical sword");
        body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        scene.colony.inventoryFeedback().changedResident(body.id());
        InventoryObservations.publish(scene.colony, helper.getLevel());
        scene.supplies.resupply(TestWorker.of(body), Optional.empty(), Long.MIN_VALUE, Long.MIN_VALUE);
        helper.assertValueEqual(
            scene.supplies.status(body.id()).state(),
            PatrolSupplies.State.BLOCKED,
            "unrelated worn gear blocks collection visibly");
        helper.assertTrue(
            body.getItemBySlot(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET), "player armor remains physically worn");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void combatProfilesSuggestAmmoWithoutAddingTradeToolDefaults(GameTestHelper helper) {
        Scene scene = new Scene(helper, 0);
        LoadoutProfiles profiles = scene.supplies.profiles();
        LoadoutProfile profile = profiles.create("Archers");
        profile = profiles.add(profile.id(), LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)));
        helper.assertTrue(
            profile.entries().stream()
            .anyMatch(
            entry ->
                entry.kind() == LoadoutProfile.Kind.SUPPLY
                    && entry.target() == 8
                    && entry.threshold() == 8),
            "bow parameters suggest editable ammo quantities");
        helper.assertTrue(
            CoreSettings.tool(Vocations.required(PatrolContent.VOCATION)).byDefault().isEmpty(),
            "patrolling has no weapon tool defaults");
        helper.assertTrue(
            Vocations.required(PatrolContent.VOCATION).kit().isEmpty(), "patrolling has no weapon kit deliveries");
        helper.assertTrue(
            !KitRules.allows(scene.colony, Vocations.required(PatrolContent.VOCATION), Items.BOW)
            && !KitRules.allows(scene.colony, Vocations.required(PatrolContent.VOCATION), Items.IRON_SWORD),
            "combat weapons are absent from default tool lists");
        LoadoutProfile copy = profiles.duplicate(profile.id());
        profiles.delete(profile.id());
        helper.assertTrue(
            profiles.get(copy.id()).isPresent() && !copy.id().equals(profile.id()),
            "duplicate has independent colony identity");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void invalidArmorAndUnrecognizedWeaponsStayUnavailableInsteadOfBeingEquipped(GameTestHelper helper) {
        Scene scene = new Scene(helper, 1);
        ResidentEntity body = scene.body();
        body.pack().insert(new ItemStack(Items.BOW));
        boolean rejected = false;
        try {
            scene.profile(List.of(LoadoutProfile.Entry.armor(EquipmentSlot.HEAD, ItemSpec.of(Items.BOW))));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected && body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(),
            "an illegal named-slot item is refused when saving");
        var profile = scene.profile(List.of(LoadoutProfile.Entry.supply(ItemSpec.of(Items.STONE), 1, 1)));
        helper.assertTrue(profile.entries().getFirst().kind() == LoadoutProfile.Kind.SUPPLY,
            "an ordinary inventory item needs no weapon or equipment interpretation");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void profileShortagesCountSetsAndAllocateStockOnlyOnce(GameTestHelper helper) {
        Scene scene = new Scene(helper, 2);
        Container stock = scene.store(12);
        stock.setItem(0, new ItemStack(Items.IRON_SWORD));
        stock.setItem(1, new ItemStack(Items.ARROW, 6));
        var weapon = LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD));
        var arrows = LoadoutProfile.Entry.supply(ItemSpec.of(Items.ARROW), 4, 2);
        var profile = scene.profile(List.of(weapon, arrows));
        var shortage = ProfileShortages.read(scene.colony, profile, helper.getLevel().getServer());
        helper.assertTrue(
            shortage.assigned() == 2 && shortage.incomplete() == 2 && shortage.unloaded() == 0,
            "two residents each need an entire set");
        helper.assertValueEqual(shortage.missing().get(weapon.id()), 2, "two distinct weapons are missing");
        helper.assertValueEqual(
            shortage.unavailableStock().get(weapon.id()), 1, "one stored sword cannot cover two sets");
        helper.assertValueEqual(
            shortage.unavailableStock().get(arrows.id()),
            2,
            "six stored arrows cover only six of eight needed units");
        helper.assertValueEqual(stock.getItem(1).getCount(), 6, "shortage preview never mutates real storage");
        helper.succeed();
    }

    private static final class Scene {
        final GameTestHelper helper;
        final Colony colony;
        final PatrolSupplies supplies;
        final List<ResidentEntity> people = new ArrayList<>();
        final List<Entity> others = new ArrayList<>();
        final List<WorldPos> stores = new ArrayList<>();
        LaborFixture simulation;

        Scene(GameTestHelper helper, int count) {
            this.helper = helper;
            colony = Colonies.mint(helper.getLevel().getServer());
            supplies = PatrolSupplies.of(colony);
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 7; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            for (int n = 0; n < count; n++) {
                ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel());
                BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2 + n * 2));
                body.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
                helper.getLevel().addFreshEntity(body);
                colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
                body.joinColony(colony.id());
                people.add(body);
            }
            Cleanup.on(helper, this::close);
        }

        ResidentEntity body() {
            return people.getFirst();
        }

        Container store(int x) {
            helper.setBlock(new BlockPos(x, 1, 3), Blocks.BARREL);
            WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(x, 1, 3)));
            stores.add(at);
            colony.stores(
                ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "profile_test"),
                helper.getLevel().dimension(),
                stores);
            return Containers.at(helper.getLevel(), at.block(helper.getLevel())).orElseThrow();
        }

        LoadoutProfile profile(List<LoadoutProfile.Entry> entries) {
            var profile = supplies.profiles().create("Test profile");
            profile = new LoadoutProfile(profile.id(), profile.name(), entries);
            supplies.profiles().replace(profile);
            supplies.profiles().assign(people.stream().map(ResidentEntity::id).toList(), Optional.of(profile.id()));
            return profile;
        }

        void simulate() {
            simulation = new LaborFixture(colony, helper.getLevel());
            helper.onEachTick(simulation::tick);
        }

        private void close() {
            if (simulation != null) {
                simulation.close();
            }
            people.forEach(Entity::discard);
            others.forEach(Entity::discard);
            colony.raze();
        }
    }
}
