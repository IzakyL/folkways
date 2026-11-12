package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.person.living.LivingContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class PatrolSupplyGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        io.github.izakyl.folkways.core.testing.SelectedTests.register(event, PatrolSupplyGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 650)
    public static void aGuardWalksToRealStorageForAWeaponAndArrowsThenShoots(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ItemStack bow = new ItemStack(Items.BOW);
        bow.setDamageValue(9);
        bow.set(DataComponents.CUSTOM_NAME, Component.literal("warehouse bow"));
        ItemStack arrows = new ItemStack(Items.ARROW, 16);
        arrows.set(DataComponents.CUSTOM_NAME, Component.literal("warehouse ammunition"));
        scene.store.setItem(0, bow);
        scene.store.setItem(1, arrows);
        arm(scene.colony, scene.body, "Bow", ItemSpec.of(Items.BOW));
        Zombie enemy = scene.enemy(7);
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.supplyState() == PatrolSupplies.State.FETCHING,
                    "a guard with no equipment starts a finite stock trip" + scene.debug()))
            .thenExecute(
            () ->
                helper.assertTrue(
                    scene.store.getItem(0).is(Items.BOW) && scene.body.pack().count(Items.BOW) == 0,
                    "stock remains in storage while the resident is still far away"))
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    enemy.getHealth() < enemy.getMaxHealth(),
                    "the guard obtains the real loadout and uses the normal weapon runner to shoot"
                    + scene.debug()))
            .thenExecute(
            () -> {
                helper.assertTrue(
                    enemy.getLastDamageSource() != null
                    && enemy.getLastDamageSource().getDirectEntity() instanceof AbstractArrow
                    && enemy.getLastDamageSource().getEntity() == scene.body,
                    "the damage comes from the supplied resident's actual arrow");
                helper.assertTrue(
                    scene.body.getLastHurtByMob() == null,
                    "the NoAI enemy never provoked the resident");
                helper.assertValueEqual(
                    scene.countInStore(Items.BOW), 0, "exactly one warehouse bow is taken");
                helper.assertValueEqual(
                    scene.countInStore(Items.ARROW),
                    8,
                    "the finite request takes only eight actual arrows");
                ItemStack actual = scene.actualWeapon();
                helper.assertValueEqual(
                    actual.get(DataComponents.CUSTOM_NAME),
                    Component.literal("warehouse bow"),
                    "weapon components survive storage handoff");
                helper.assertTrue(
                    actual.getDamageValue() > 9, "native shooting wears the actual warehouse bow");
                helper.assertValueEqual(
                    scene.weaponCount(), 1, "the pack and real hand jointly own one weapon");
                helper.assertTrue(
                    scene.leftAnchor, "the same resident physically walks to collect the stock");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 550)
    public static void aStockoutReleasesTheRunnerAndRetriesAfterRealStockAppears(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.body.pack().insert(new ItemStack(Items.BOW));
        arm(scene.colony, scene.body, "Bow", ItemSpec.of(Items.BOW));
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.supplyState() == PatrolSupplies.State.FETCHING,
                    "the missing arrows are asked of the planner"
                    + scene.debug()))
            .thenIdle(25)
            .thenExecute(
            () -> {
                helper.assertTrue(
                    Reach.standingAt(scene.body.position(), scene.anchor.block(scene.level))
                    && !scene.supplies.underway(scene.body.id()),
                    "an unavailable supply does not take the guard away");
                helper.assertTrue(!scene.body.weaponDrawn(), "stockout creates no"
                    + " native weapon"
                    + " loan");
                scene.store.setItem(0, new ItemStack(Items.ARROW, 8));
            })
            .thenWaitUntil(
            () ->
                helper.assertValueEqual(
                    scene.body.pack().count(Items.ARROW),
                    8,
                    "the outstanding delivery is planned once real stock appears"
                    + scene.debug()))
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    Reach.standingAt(scene.body.position(), scene.anchor.block(scene.level)),
                    "after collection the same guard returns to its slot" + scene.debug()))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.countInStore(Items.ARROW),
                    0,
                    "all eight real supplied arrows leave this warehouse");
                helper.assertValueEqual(
                    scene.body.pack().count(Items.BOW),
                    1,
                    "profile replenishment acquires no spare bow");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 450)
    public static void aNewIndividualOrderCancelsTheOldSupplyGeneration(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.store.setItem(0, new ItemStack(Items.BOW));
        scene.store.setItem(1, new ItemStack(Items.ARROW, 16));
        arm(scene.colony, scene.body, "Bow", ItemSpec.of(Items.BOW));
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.supplyState() == PatrolSupplies.State.FETCHING
                    && scene.body.getX() > scene.at(3, 1, 3).cell().getX(),
                    "the runner starts actually travelling for its guard's supplies"
                    + scene.debug()))
            .thenExecute(() -> scene.commands.hold(scene.body, scene.at(2, 1, 3)))
            .thenIdle(70)
            .thenExecute(
            () -> {
                helper.assertTrue(
                    scene.commands.current(scene.body.id()).orElseThrow().mode()
                    == ResidentCommands.Mode.HOLD,
                    "the newer individual handle retains control");
                helper.assertValueEqual(
                    scene.body.pack().count(Items.BOW),
                    0,
                    "a cancelled trip cannot complete its old requisition");
                helper.assertValueEqual(
                    scene.countInStore(Items.BOW),
                    1,
                    "the cancelled order never removes its warehouse weapon");
                helper.assertValueEqual(
                    scene.countInStore(Items.ARROW),
                    16,
                    "the cancelled order never removes ammunition");
                helper.assertTrue(scene.supplies.profiles().assigned(scene.body.id()).isPresent(),
                    "the newer order preserves the personal profile for later collection");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 550)
    public static void aFullPackLeavesStorageUntouchedAndRecoversWhenRoomIsFreed(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        for (int slot = 0; slot < scene.body.pack().getContainerSize(); slot++) {
            scene.body.pack().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        scene.store.setItem(0, new ItemStack(Items.BOW));
        scene.store.setItem(1, new ItemStack(Items.ARROW, 8));
        arm(scene.colony, scene.body, "Bow", ItemSpec.of(Items.BOW));
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.supplyState() == PatrolSupplies.State.FETCHING,
                    "the loadout is asked even with no room yet" + scene.debug()))
            .thenIdle(30)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.countInStore(Items.BOW), 1, "no weapon is taken before capacity exists");
                helper.assertTrue(
                    scene.level
                    .getEntitiesOfClass(
                    ItemEntity.class, scene.body.getBoundingBox().inflate(3))
                    .isEmpty(),
                    "full-pack supply spills no actual equipment");
                scene.body.pack().setItem(0, ItemStack.EMPTY);
                scene.body.pack().setItem(1, ItemStack.EMPTY);
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.body.pack().count(Items.BOW) == 1
                    && scene.body.pack().count(Items.ARROW) == 8,
                    "the delivery is planned into newly available pack"
                    + " room" + scene.debug()))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.countInStore(Items.BOW),
                    0,
                    "the actual bow moves once after capacity recovers");
                helper.assertValueEqual(
                    scene.countInStore(Items.ARROW), 0, "the actual eight arrows move once");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 450)
    public static void deathBeforeCollectionDropsExistingEquipmentOnceAndLeavesStockAtSource(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.setDamageValue(13);
        scene.body.pack().insert(sword);
        scene.store.setItem(0, new ItemStack(Items.BOW));
        arm(scene.colony, scene.body, "Bow", ItemSpec.of(Items.BOW));
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.supplyState() == PatrolSupplies.State.FETCHING,
                    "the supply trip begins" + scene.debug()))
            .thenExecute(() -> scene.body.hurt(scene.level.damageSources().genericKill(), 1000))
            .thenIdle(30)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.countInStore(Items.BOW),
                    1,
                    "death before arrival never removes warehouse stock");
                var drops =
                    scene.level.getEntitiesOfClass(
                    ItemEntity.class,
                    scene.body.getBoundingBox().inflate(4),
                    item -> item.getItem().is(Items.IRON_SWORD));
                helper.assertValueEqual(
                    drops.stream().mapToInt(item -> item.getItem().getCount()).sum(),
                    1,
                    "already-owned equipment drops exactly once");
                helper.assertValueEqual(
                    drops.getFirst().getItem().getDamageValue(),
                    13,
                    "death preserves the pre-existing equipment's durability");
                scene.colony
                    .claimOf(scene.body.id())
                    .ifPresent(held -> scene.colony.release(held.id(), ClaimRelease.DIED));
                scene.supplies.tick(scene.level.getServer());
                helper.assertTrue(
                    scene.supplyState() == PatrolSupplies.State.INACTIVE,
                    "after actual colony release the final cancellation status is no longer retained");
                helper.assertTrue(
                    scene.supplies.profiles().assigned(scene.body.id()).isEmpty(),
                    "a departed resident's saved loadout is removed with its per-resident state");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 450)
    public static void losingTheLastArrowDuringTheWindupDoesNotDuplicateOrSpendCarriedAmmo(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.body.pack().insert(new ItemStack(Items.BOW));
        scene.body.pack().insert(new ItemStack(Items.ARROW, 7));
        scene.store.setItem(0, new ItemStack(Items.ARROW));
        arm(scene.colony, scene.body, "Bow", ItemSpec.of(Items.BOW));
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.supplyState() == PatrolSupplies.State.FETCHING
                    && scene.supplies.underway(scene.body.id()),
                    "the resident sets out for the real last-arrow source before"
                    + " committing its collection"
                    + scene.debug()))
            .thenExecute(() -> scene.store.removeItem(0, 1))
            .thenIdle(60)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.body.pack().count(Items.ARROW),
                    7,
                    "failed collection does not consume or refund copies of carried ammunition");
                helper.assertValueEqual(
                    scene.countInStore(Items.ARROW),
                    0,
                    "the competing withdrawal remains the only source mutation");
                helper.assertValueEqual(
                    scene.weaponCount(), 1, "failed collection leaves the actual weapon intact");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 450)
    public static void storageWithdrawalAtArrivalCannotReadAnAdjacentUnregisteredContainer(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.body.pack().insert(new ItemStack(Items.BOW));
        arm(scene.colony, scene.body, "Bow", ItemSpec.of(Items.BOW));
        scene.store.setItem(0, new ItemStack(Items.ARROW, 8));
        helper.setBlock(new BlockPos(19, 1, 3), Blocks.BARREL);
        Container unregistered = Containers.at(scene.level, scene.at(19, 1, 3).block(scene.level)).orElseThrow();
        unregistered.setItem(0, new ItemStack(Items.ARROW, 8));
        scene.guard();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.supplyState() == PatrolSupplies.State.FETCHING
                    && scene.supplies.underway(scene.body.id()),
                    "the resident sets out for its registered"
                    + " source" + scene.debug()))
            .thenExecute(() -> scene.colony.stores(Scene.STORE_OWNER, scene.level.dimension(), List.of()))
            .thenIdle(60)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.body.pack().count(Items.ARROW), 0, "deregistered source supplies nothing");
                helper.assertValueEqual(
                    scene.countInStore(Items.ARROW), 8, "the original source keeps all actual arrows");
                helper.assertValueEqual(
                    unregistered.getItem(0).getCount(),
                    8,
                    "nearby unregistered inventory is never a fallback source");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void editingOrDeletingAProfileRevokesItsTripAndWeaponButPreservesTheGuardOrder(
        GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.body.pack().insert(new ItemStack(Items.BOW));
        scene.body.pack().insert(new ItemStack(Items.ARROW));
        arm(scene.colony, scene.body, "Bow", ItemSpec.of(Items.BOW));
        scene.store.setItem(0, new ItemStack(Items.ARROW, 20));
        scene.guard();
        var order = scene.commands.current(scene.body.id()).orElseThrow();
        var attack = Weapons.open(worker(scene.body)).orElseThrow();
        InventoryObservations.publish(scene.colony, scene.level);
        var view = scene.colony.view(scene.level);
        scene.supplies.resupply(worker(scene.body), Optional.of(order), Long.MIN_VALUE, Long.MIN_VALUE);
        helper.assertTrue(scene.supplies.status(scene.body.id()).state() == PatrolSupplies.State.FETCHING,
            "the profile asks for the missing arrows"
            + scene.debug());
        var profile = scene.supplies.profiles().assigned(scene.body.id()).orElseThrow();
        scene.supplies
            .profiles()
            .replace(new LoadoutProfile(profile.id(), "Renamed", profile.entries()));
        helper.assertTrue(scene.supplies.goals(view).isEmpty()
                && scene.supplyState() == PatrolSupplies.State.CANCELLED,
            "a profile edit withdraws the delivery asked under the old revision");
        helper.assertTrue(scene.body.getMainHandItem().isEmpty() && scene.body.pack().count(Items.BOW) == 1,
            "profile changes return the actual hand immediately");
        helper.assertTrue(
            scene.commands.current(scene.body.id()).orElseThrow() == order,
            "equipment editing never replaces route intent");
        scene.supplies.profiles().delete(profile.id());
        helper.assertTrue(
            scene.supplies.profiles().assigned(scene.body.id()).isEmpty(),
            "deleting a profile unassigns its residents");
        helper.assertTrue(
            scene.commands.current(scene.body.id()).orElseThrow() == order,
            "deletion preserves the same guard order");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 1200)
    public static void profileSuppliesWorkersAcrossPatrolChangesAndReturnsOnUnassignment(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        // Isolate profile supplies from the household's independent pocket-ration allowance.
        ((ColonyFront) Fronts.of(scene.colony)).setSetting(LivingContent.ID, LivingContent.FOOD.key(),
            new ColonySettings.Value.Items(List.of()));
        var profiles = scene.supplies.profiles().ledger();
        var profile = profiles.create("Worker equipment");
        profiles.put(profile.id(), GearSlots.HEAD,
            ItemSpec.of(Items.IRON_HELMET));
        profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.IRON_SWORD), 1, 1));
        profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.SHIELD), 1, 1));
        profiles.add(profile.id(), EquipmentProfile.Entry
            .carried(ItemSpec.of(Items.BREAD), 4, 2));
        scene.store.setItem(0, new ItemStack(Items.IRON_HELMET));
        scene.store.setItem(4, new ItemStack(Items.IRON_HELMET));
        scene.store.setItem(1, new ItemStack(Items.IRON_SWORD));
        scene.store.setItem(5, new ItemStack(Items.IRON_SWORD));
        scene.store.setItem(2, new ItemStack(Items.SHIELD));
        scene.store.setItem(6, new ItemStack(Items.SHIELD));
        scene.store.setItem(3, new ItemStack(Items.BREAD, 12));
        profiles.assign(List.of(scene.body.id()), Optional.of(profile.id()));
        var finished = new AtomicBoolean();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            scene.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                && scene.body.pack().count(Items.SHIELD) == 1
                && scene.body.pack().count(Items.IRON_SWORD) == 1 && scene.body.pack().count(Items.BREAD) == 4,
            "a resident without patrol duty fetches and wears its profile: " + scene.debug()))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.IRON_SWORD), 1, "initial supply takes one weapon");
                scene.body.pack().insert(new ItemStack(Items.IRON_PICKAXE));
                var owner = Scene.STORE_OWNER;
                var spec = NodeSpec.of(UUID.randomUUID(), owner,
                    WorkSite.on(scene.body),
                    Stances.WHEREVER,
                    Workload.Once.of(10))
                    .tools(ToolUse.of(
                        new ToolNeed.SuitableFor(Blocks.STONE.defaultBlockState())))
                    .done();
                var work = new Node() {
                    public NodeSpec spec() {
                        return spec;
                    }

                    public Optional<UUID> worker() {
                        return Optional.of(scene.body.id());
                    }

                    public Outcome commit(ServerLevel level, Worker who) {
                        helper.assertTrue(scene.body.getMainHandItem().is(Items.IRON_PICKAXE),
                            "work borrows the main hand for its tool");
                        helper.assertTrue(scene.body.pack().count(Items.IRON_SWORD) == 1,
                            "the weapon remains physical stock in the pack during work");
                        finished.set(true);
                        return Outcome.done();
                    }
                };
                scene.colony.submit(owner, scene.level.dimension(),
                    Grown.of(work), (id, ending) -> { });
            }).thenWaitUntil(() -> helper.assertTrue(finished.get(), "tool work completes: " + scene.debug()))
            .thenExecute(() -> scene.body.pack().insert(
                Containers.extract(scene.store, stack -> stack.is(Items.IRON_HELMET), 1)))
            .thenWaitUntil(() -> helper.assertTrue(scene.countInStore(Items.IRON_HELMET) == 1
                && scene.body.pack().count(Items.IRON_HELMET) == 0,
                "the worn helmet satisfies its slot, so a spare returns through ordinary hauling"))
            .thenExecute(() -> scene.body.pack().removeItem(
                IntStream.range(0, scene.body.pack().getContainerSize())
                    .filter(slot -> scene.body.pack().getItem(slot).is(Items.BREAD)).findFirst().orElseThrow(), 2))
            .thenIdle(40)
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.BREAD), 8, "at the refill line no bread is taken");
                Containers.extract(scene.body.pack(), stack -> stack.is(Items.BREAD), 1);
            }).thenWaitUntil(() -> helper.assertTrue(scene.body.pack().count(Items.BREAD) == 4,
                "below the line the personal request refills to target"))
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.IRON_SWORD), 1, "work keeps one weapon");
                scene.guard();
            }).thenIdle(40)
            .thenExecute(() -> scene.commands.release(scene.body.id())).thenIdle(40)
            .thenExecute(() -> {
                helper.assertValueEqual(scene.countInStore(Items.IRON_SWORD), 1, "patrol changes take no spare weapon");
                helper.assertValueEqual(scene.countInStore(Items.IRON_HELMET), 1, "patrol changes take no spare armor");
                helper.assertValueEqual(scene.countInStore(Items.BREAD), 5, "patrol changes take no spare supplies");
                profiles.assign(List.of(scene.body.id()), Optional.empty());
            }).thenWaitUntil(() -> helper.assertTrue(
                scene.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && scene.body.getOffhandItem().isEmpty()
                    && scene.countInStore(Items.IRON_HELMET) == 2 && scene.countInStore(Items.SHIELD) == 2
                    && scene.countInStore(Items.IRON_SWORD) == 2 && scene.countInStore(Items.BREAD) == 9,
                "unassignment unwears armor and ordinary hauling returns every surplus: " + scene.debug()
                    + " helmet=" + scene.countInStore(Items.IRON_HELMET) + " shield=" + scene.countInStore(Items.SHIELD)
                    + " sword=" + scene.countInStore(Items.IRON_SWORD) + " bread=" + scene.countInStore(Items.BREAD)
                    + " worn=" + scene.body.getItemBySlot(EquipmentSlot.HEAD) + "/" + scene.body.getOffhandItem()
                    + " pack=" + scene.body.pack().contents() + " diagnosis=" + scene.simulation.diagnosis()
                    + " route=" + scene.simulation.route(scene.body)))
            .thenSucceed();
    }

    private static Worker worker(ResidentEntity body) {
        return TestWorker.of(body).spill(body::spawnAtLocation);
    }

    private static final class Scene {
        static final ResourceLocation STORE_OWNER =
            ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_patrol_supply_store");
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentEntity body;
        final ResidentCommands commands;
        final PatrolSupplies supplies;
        final WorldPos anchor;
        final WorldPos source;
        final Container store;
        final LaborFixture simulation;
        final List<Entity> others = new ArrayList<>();
        boolean leftAnchor;
        boolean closed;
        int previousStock;

        Scene(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            for (int x = 0; x < 23; x++) {
                for (int z = 0; z < 9; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            helper.setBlock(new BlockPos(18, 1, 3), Blocks.BARREL);
            colony = Colonies.mint(level.getServer());
            commands = ResidentCommands.of(colony);
            supplies = PatrolSupplies.of(colony);
            anchor = at(2, 1, 3);
            source = at(18, 1, 3);
            store = Containers.at(level, source.block(level)).orElseThrow();
            colony.stores(STORE_OWNER, level.dimension(), List.of(source));
            body = PersonBody.RESIDENT.get().create(level);
            BlockPos start = anchor.block(level);
            body.moveTo(start.getX() + .5, start.getY(), start.getZ() + .5);
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            simulation = new LaborFixture(colony, level);
            helper.onEachTick(
                () -> {
                    previousStock = countInStore(Items.BOW) + countInStore(Items.ARROW);
                    simulation.tick();
                    int after = countInStore(Items.BOW) + countInStore(Items.ARROW);
                    if (after < previousStock) {
                        helper.assertTrue(
                            nearStore(),
                            "stock only moves after this resident physically reaches the container");
                    }
                    leftAnchor |= !Reach.standingAt(body.position(), anchor.block(level));
                });
            Cleanup.on(helper, this::close);
        }

        WorldPos at(int x, int y, int z) {
            return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z)));
        }

        void guard() {
            commands.guard(body, anchor, new Vec3(1, 0, 0));
        }

        PatrolSupplies.State supplyState() {
            return supplies.status(body.id()).state();
        }

        String debug() {
            return " [status="
                + supplies.status(body.id())
                + ", profile="
                + supplies.profiles().assigned(body.id()).map(LoadoutProfile::name)
                + ", order="
                + commands.current(body.id()).map(order -> order.mode() + "/" + order.status())
                + ", body="
                + body.blockPosition()
                + ", source="
                + source
                + ", registered="
                + colony.storesIn(level.dimension()).contains(source)
                + "/"
                + colony.storesIn(level.dimension()).size()
                + ", rules="
                + colony.storeRules(source).size()
                + ", resolved="
                + colony.store(level, source)
                .map(
                container ->
                    container.getClass().getName()
                        + "/bow="
                        + Goods.countIn(
                        container,
                        ItemSpec.of(ResourceLocation.withDefaultNamespace("bow")))
                        + "/arrow="
                        + Goods.countIn(container, ItemSpec.of(ItemTags.ARROWS)))
                + ", fixtureStock="
                + countInStore(Items.BOW)
                + "/"
                + countInStore(Items.ARROW)
                + ", pack=bow:"
                + body.pack().count(Items.BOW)
                + "/arrows:"
                + body.pack().count(Items.ARROW)
                + "/used:"
                + body.pack().contents().size()
                + ", hand="
                + body.getMainHandItem()
                + ", footings="
                + Reach.workableCells(level, source.block(level)).size()
                + ", claims="
                + colony.reservedInputs(level).size()
                + "]";
        }

        boolean nearStore() {
            // Planned collection arrives at a workable stance with Core's normal arrival tolerance.
            return Reach.stanceHolding(body.position(), Reach.workableCells(level, source.block(level))).isPresent();
        }

        int countInStore(Item item) {
            int count = 0;
            for (int slot = 0; slot < store.getContainerSize(); slot++) {
                if (store.getItem(slot).is(item)) {
                    count += store.getItem(slot).getCount();
                }
            }
            return count;
        }

        ItemStack actualWeapon() {
            return body.weaponDrawn()
                ? body.getMainHandItem()
                : body.pack().contents().stream()
                .filter(stack -> stack.is(Items.BOW))
                .findFirst()
                .orElse(ItemStack.EMPTY);
        }

        int weaponCount() {
            return body.pack().count(Items.BOW)
                + (body.weaponDrawn() && body.getMainHandItem().is(Items.BOW)
                ? body.getMainHandItem().getCount()
                : 0);
        }

        Zombie enemy(int x) {
            Zombie enemy = EntityType.ZOMBIE.create(level);
            enemy.setNoAi(true);
            enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            BlockPos feet = at(x, 1, 3).block(level);
            enemy.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            level.addFreshEntity(enemy);
            others.add(enemy);
            return enemy;
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            simulation.close();
            body.discard();
            others.forEach(Entity::discard);
            colony.raze();
        }
    }

    private static void arm(Colony colony, Body body, String name, ItemSpec weapon) {
        LoadoutProfiles profiles = LoadoutProfiles.of(colony);
        LoadoutProfile profile =
            profiles.all().stream()
            .filter(one -> one.name().equals(name))
            .findFirst()
            .orElseGet(() -> profiles.add(profiles.create(name).id(), LoadoutProfile.Entry.weapon(weapon)));
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }

    private static void assignLoadout(
        PatrolSupplies supplies,
        Body body, String name) {
        var profiles = supplies.profiles();
        var profile = profiles.all().stream().filter(value -> value.name().equals(name)).findFirst()
            .orElseGet(() -> {
                var created = profiles.create(name);
                var weapon = switch (name) {
                    case "melee" -> ItemSpec.of(
                        Tags.Items.MELEE_WEAPON_TOOLS);
                    case "bow" -> ItemSpec.of(Items.BOW);
                    case "crossbow" -> ItemSpec.of(Items.CROSSBOW);
                    default -> throw new IllegalArgumentException(name);
                };
                var entries = new ArrayList<LoadoutProfile.Entry>();
                entries.add(LoadoutProfile.Entry.weapon(weapon));
                if (!name.equals("melee")) {
                    entries.add(LoadoutProfile.Entry.supply(
                        ItemSpec.of(ItemTags.ARROWS), 8, 8));
                }
                profiles.replace(new LoadoutProfile(
                    created.id(), created.name(), entries));
                return profiles.get(created.id()).orElseThrow();
            });
        profiles.assign(List.of(body.id()), Optional.of(profile.id()));
    }
}
