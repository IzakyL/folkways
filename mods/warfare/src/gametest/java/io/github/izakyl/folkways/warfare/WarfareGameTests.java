package io.github.izakyl.folkways.warfare;

import com.atsuishio.superbwarfare.api.event.ShootEvent;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import com.atsuishio.superbwarfare.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.group.Deployment;
import io.github.izakyl.folkways.plugins.combat.group.UnitGroups;
import io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysWarfareMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WarfareGameTests {
    private static boolean previousNoSave;
    private static final net.minecraft.server.level.TicketType<java.util.UUID> TICKING =
        net.minecraft.server.level.TicketType.create("folkways_warfare_test", java.util.Comparator.<java.util.UUID>naturalOrder());
    private static int nextScene;

    @net.minecraft.gametest.framework.BeforeBatch(batch = "warfare")
    public static void before(net.minecraft.server.level.ServerLevel level) { previousNoSave = level.noSave; level.noSave = true; nextScene = 0; }
    @net.minecraft.gametest.framework.AfterBatch(batch = "warfare")
    public static void after(net.minecraft.server.level.ServerLevel level) { level.noSave = previousNoSave; }

    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(WarfareGameTests.class); }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 500)
    public static void firearmLoadoutCollectsStockThenFiresExactlyTheSuppliedRounds(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        f.store.setItem(0, new ItemStack(ModItems.GLOCK_17.get()));
        f.store.setItem(1, new ItemStack(ModItems.HANDGUN_AMMO.get(), 5));
        f.supplies.loadout(f.body, WarfareWeapons.FIREARM);
        var group = UnitGroups.of(f.colony).create(List.of(f.body));
        group.guard(new Deployment(f.at(3, 1, 10), f.at(3, 1, 10), new Vec3(1, 0, 0), new Deployment.Formation(2)));
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(f.body.pack().count(ModItems.HANDGUN_AMMO.get()) == 5, "normal supply trips collect all five actual rounds " + f.debug()))
            .thenExecute(() -> f.enemy(10, 10))
            .thenWaitUntil(() -> helper.assertValueEqual(f.shots.get(), 5, "the guard discharges exactly five native shots " + f.debug()))
            .thenIdle(40)
            .thenExecute(() -> {
                helper.assertValueEqual(f.shots.get(), 5, "an empty source and pack produce no extra shots");
                helper.assertValueEqual(f.body.pack().count(ModItems.HANDGUN_AMMO.get()), 0, "each native shot consumes one supplied round");
                helper.assertTrue(f.store.isEmpty(), "the real source was withdrawn once");
            }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 100)
    public static void gunsPreservePlayerLoadedRoundsAndActualItemComponents(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        ItemStack gun = new ItemStack(ModItems.GLOCK_17.get());
        gun.set(DataComponents.CUSTOM_NAME, Component.literal("player's gun"));
        GunData data = GunData.from(gun); data.initialize(); data.ammo.set(7); data.save();
        f.body.pack().insert(gun); f.body.pack().insert(new ItemStack(ModItems.HANDGUN_AMMO.get(), 2));
        Zombie target = f.enemy(10, 10);
        WeaponSession session = Weapons.open(worker(f.body)).orElseThrow();
        f.sessions.add(session);
        helper.assertTrue(session.fire(target), "actual loose ammunition allows firing");
        helper.onEachTick(() -> { session.tick(); if (session.state() == WeaponSession.State.READY) { session.fire(target); } });
        helper.runAfterDelay(45, () -> {
            session.close(); session.close();
            helper.assertValueEqual(f.shots.get(), 2, "two loose rounds permit two shots");
            ItemStack returned = f.body.pack().contents().stream().filter(stack -> stack.is(ModItems.GLOCK_17.get())).findFirst().orElseThrow();
            helper.assertValueEqual(GunData.from(returned).ammo.get(), 7, "player-loaded rounds remain untouched");
            helper.assertValueEqual(returned.get(DataComponents.CUSTOM_NAME), Component.literal("player's gun"), "the actual item returns with its components");
            helper.assertValueEqual(f.body.pack().count(ModItems.GLOCK_17.get()), 1, "idempotent closure returns one gun");
            helper.succeed();
        });
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 80)
    public static void gunsNeverFireWithoutSuppliedAmmo(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        ItemStack gun = new ItemStack(ModItems.AK_47.get());
        GunData data = GunData.from(gun); data.initialize(); data.ammo.set(30); data.save();
        f.body.pack().insert(gun); f.enemy(10, 10);
        helper.assertTrue(Weapons.open(worker(f.body)).isEmpty(), "player-loaded rounds are preserved until loose ammo is supplied");
        helper.runAfterDelay(35, () -> {
            helper.assertValueEqual(f.shots.get(), 0, "no ammunition means no native discharge");
            helper.assertValueEqual(GunData.from(gun).ammo.get(), 30, "loaded ammunition is retained"); helper.succeed();
        });
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 120)
    public static void boltActionsKeepNativePumpTimeAcrossSessionReopening(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        f.body.pack().insert(new ItemStack(ModItems.M_870.get()));
        f.body.pack().insert(new ItemStack(ModItems.SHOTGUN_AMMO.get(), 3));
        Zombie target = f.enemy(10, 10);
        List<Long> times = new ArrayList<>();
        final WeaponSession[] current = {null};
        helper.onEachTick(() -> {
            if (f.shots.get() >= 3) { return; }
            if (current[0] == null) { current[0] = Weapons.open(worker(f.body)).orElseThrow(); f.sessions.add(current[0]); current[0].fire(target); }
            int before = f.shots.get(); current[0].tick();
            if (f.shots.get() > before) { times.add(f.level.getGameTime()); current[0].close(); current[0] = null; }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(f.shots.get(), 3, "the pump eventually fires three finite rounds"))
            .thenExecute(() -> {
                int bolt = GunData.from(f.body.getMainHandItem().isEmpty() ? f.body.pack().contents().getFirst() : f.body.getMainHandItem())
                    .get(com.atsuishio.superbwarfare.data.gun.GunProp.BOLT_ACTION_TIME);
                helper.assertTrue(times.get(1) - times.get(0) >= bolt && times.get(2) - times.get(1) >= bolt,
                    "reopening sessions cannot bypass the native pump");
            }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 120)
    public static void returningAGunToItsPackCannotResetItsFiringCadence(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        f.body.pack().insert(new ItemStack(ModItems.GLOCK_17.get()));
        f.body.pack().insert(new ItemStack(ModItems.HANDGUN_AMMO.get(), 3));
        Zombie target = f.enemy(10, 10);
        List<Long> times = new ArrayList<>();
        helper.onEachTick(() -> {
            if (f.shots.get() >= 3) { return; }
            WeaponSession session = Weapons.open(worker(f.body)).orElseThrow();
            try {
                session.fire(target);
                int before = f.shots.get(); session.tick();
                if (f.shots.get() > before) { times.add(f.level.getGameTime()); }
            } finally { session.close(); }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(f.shots.get(), 3, "three supplied rounds eventually discharge"))
            .thenExecute(() -> {
                int rpm = GunData.from(f.body.pack().getItem(0)).get(com.atsuishio.superbwarfare.data.gun.GunProp.RPM);
                int interval = (int) Math.ceil(1200.0 / rpm);
                helper.assertTrue(times.get(1) - times.get(0) >= interval && times.get(2) - times.get(1) >= interval,
                    "copying a stack back into the pack never bypasses RPM");
            }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void onlyEmplacementsInsideTheGuardAreaAreDetected(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity outside = f.station(ModEntities.TOW.get(), 20, 10);
        f.guard();
        helper.assertTrue(WarfareStations.find(f.colony, f.body).isEmpty(), "a nearby station beyond the guard area is ignored");
        VehicleEntity inside = f.station(ModEntities.TOW.get(), 6, 10);
        helper.assertTrue(WarfareStations.find(f.colony, f.body).orElse(null) == inside, "the post selects the contained station");
        helper.assertFalse(WarfareStations.emplacement(f.station(ModEntities.LAV_150.get(), 7, 14)), "ordinary combat vehicles are excluded");
        helper.assertTrue(WarfareStations.emplacement(f.station(ModEntities.FH_77BW.get(), 7, 17)), "the requested howitzer is supported as a stopped weapon station");
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 350)
    public static void guardWalksMountsAimsFiresThenDismountsOnRetreat(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        VehicleEntity station = f.station(ModEntities.TOW.get(), 8, 10);
        f.body.pack().insert(new ItemStack(ModItems.MEDIUM_ANTI_GROUND_MISSILE.get(), 2));
        f.enemy(23, 10); f.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(f.body.getVehicle() == station, "the labour runner walks to the actual weapon seat " + f.debug()))
            .thenWaitUntil(() -> helper.assertTrue(f.shots.get() > 0, "the mounted operator aims and uses SBW's native vehicle fire " + f.debug()))
            .thenExecute(() -> {
                helper.assertTrue(station.getShootVec(f.body, 1).x > .9, "the native barrel is aimed towards the enemy");
                f.commands.retreat(f.body, f.at(3, 1, 5));
                helper.assertFalse(f.body.isPassenger(), "retreat releases the same handle's seat immediately");
            }).thenIdle(20).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 450)
    public static void stationShellsAreCollectedByNormalLogistics(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 8, 10);
        f.store.setItem(0, new ItemStack(ModItems.LARGE_SHELL_AP.get(), 2)); f.guard();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(f.store.isEmpty() && f.body.getVehicle() == station,
                "real shells travel from the registered colony store to the operator " + f.debug()))
            .thenExecute(() -> {
                int ammo = station.getAmmoCount("Main") + station.getItems().stream().filter(stack -> stack.is(ModItems.LARGE_SHELL_AP.get())).mapToInt(ItemStack::getCount).sum()
                    + f.body.pack().count(ModItems.LARGE_SHELL_AP.get());
                helper.assertValueEqual(ammo, 2, "warehouse, pack and native magazine conserve the two shells");
            }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 400)
    public static void componentExactStationDemandHaulsOnlyMatchingPhysicalShells(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 8, 10);
        ItemStack shell = new ItemStack(ModItems.MORTAR_SHELL.get());
        shell.set(DataComponents.CUSTOM_NAME, Component.literal("marked shell"));
        mortar.getItems().set(0, shell.copy());
        f.store.setItem(0, new ItemStack(ModItems.MORTAR_SHELL.get(), 2));
        f.store.setItem(1, shell.copyWithCount(2));
        // A wounded guard collects its duty stock while yielding operation to recovery.
        f.body.setHealth(5); f.guard();
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(
                Goods.countIn(f.body.pack(), ItemSpec.exact(shell)), 2L, "the actual runner hauls the matching component variant " + f.debug()))
            .thenExecute(() -> {
                helper.assertValueEqual(f.store.getItem(0).getCount(), 2, "plain shells never substitute for marked shells");
                helper.assertTrue(f.store.getItem(1).isEmpty(), "matching physical stock was collected exactly once");
                helper.assertTrue(f.supplies.keeps(f.body).stream().anyMatch(amount -> amount.spec().equals(ItemSpec.exact(shell))),
                    "duty stock is retained even without a personal loadout");
            }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 300)
    public static void friendlyResidentsAndPetsBlockGunfire(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        f.body.pack().insert(new ItemStack(ModItems.GLOCK_17.get())); f.body.pack().insert(new ItemStack(ModItems.HANDGUN_AMMO.get(), 2));
        Zombie enemy = f.enemy(12, 10);
        var wolf = EntityType.WOLF.create(f.level); f.place(wolf, 7, 10); wolf.setNoAi(true);
        f.level.addFreshEntity(wolf); f.entities.add(wolf);
        WeaponSession session = Weapons.open(worker(f.body)).orElseThrow(); f.sessions.add(session);
        helper.assertFalse(session.fire(enemy), "a pet in the line of fire blocks the attack");
        wolf.discard();
        ResidentEntity friend = PersonBody.RESIDENT.get().create(f.level); friend.setNoAi(true); f.place(friend, 7, 10);
        f.level.addFreshEntity(friend); f.entities.add(friend);
        helper.assertFalse(session.fire(enemy), "another resident blocks the attack too");
        helper.assertValueEqual(f.body.pack().count(ModItems.HANDGUN_AMMO.get()), 2, "blocked shots spend no ammunition"); helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 240)
    public static void friendlyImpactAreaAndOwnedBlocksPreventStationFire(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        f.station(ModEntities.TOW.get(), 8, 10); f.body.pack().insert(new ItemStack(ModItems.MEDIUM_ANTI_GROUND_MISSILE.get(), 2));
        Zombie enemy = f.enemy(23, 10);
        var wolf = EntityType.WOLF.create(f.level); wolf.setNoAi(true); f.place(wolf, 23, 13); f.level.addFreshEntity(wolf); f.entities.add(wolf);
        f.guard();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(f.body.isPassenger(), "the guard reaches its seat"))
            .thenIdle(60).thenExecute(() -> {
                helper.assertValueEqual(f.shots.get(), 0, "a pet in the predicted blast area prevents firing"); wolf.discard();
                f.block(new BlockPos(23, 1, 13), Blocks.BARREL);
                f.colony.stores(WarfareStations.ID, f.level.dimension(), List.of(f.at(3, 1, 4), f.at(23, 1, 13)));
            }).thenIdle(60).thenExecute(() -> helper.assertValueEqual(f.shots.get(), 0, "colony-owned blocks in the impact area prevent firing"))
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 200)
    public static void mortarCancellationPreservesAComponentExactShell(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 6, 10);
        ItemStack shell = new ItemStack(ModItems.MORTAR_SHELL.get()); shell.set(DataComponents.CUSTOM_NAME, Component.literal("kept shell"));
        mortar.getItems().set(0, shell); f.guard();
        helper.assertTrue(Goods.matches(shell, WarfareStations.supplies(f.colony, f.body).getFirst().spec()), "the shell demand preserves components");
        helper.assertFalse(Goods.matches(new ItemStack(ModItems.MORTAR_SHELL.get()), WarfareStations.supplies(f.colony, f.body).getFirst().spec()),
            "a different shell component cannot substitute in logistics");
        Zombie foe = f.enemy(23, 10);
        StationNode node = f.node(mortar); f.body.moveTo(mortar.position().add(-2, 0, 0));
        node.commit(f.level, worker(f.body));
        mortar.getEntityData().set(MortarEntity.FIRE_TIME, 25);
        f.commands.release(f.body.id()); node.close(); node.close();
        helper.runAfterDelay(35, () -> {
            helper.assertValueEqual(mortar.getEntityData().get(MortarEntity.FIRE_TIME), 0, "interruption cancels the delayed native discharge");
            helper.assertValueEqual(mortar.getItem(0).get(DataComponents.CUSTOM_NAME), Component.literal("kept shell"), "the original shell stays physical and component-identical");
            helper.assertValueEqual(foe.getHealth(), foe.getMaxHealth(), "a cancelled pending shell never hits its target"); helper.succeed();
        });
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 100)
    public static void revokingTradeOrRemovingTheStationCleansUpItsSeat(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10); f.guard();
        f.body.moveTo(station.position().add(-2, 0, 0)); StationNode node = f.node(station); node.commit(f.level, worker(f.body));
        helper.assertTrue(f.body.getVehicle() == station, "the real operator mounts");
        var trade = f.body.licences().trade(Vocations.required(PatrolContent.VOCATION)).orElseThrow();
        f.body.licences().allow(trade, false);
        node.commit(f.level, worker(f.body));
        helper.assertFalse(f.body.isPassenger(), "revoking the trade dismounts the operator");
        f.body.licences().allow(trade, true); StationNode second = f.node(station); second.commit(f.level, worker(f.body));
        station.discard();
        helper.assertFalse(f.body.isPassenger(), "station removal cleans up the operator"); helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 100)
    public static void unloadReleasesOnlyTheOwnedSeat(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10); f.guard();
        f.body.moveTo(station.position().add(-2, 0, 0)); StationNode node = f.node(station); node.commit(f.level, worker(f.body));
        helper.assertTrue(f.body.isPassenger(), "the original duty owns the seat");
        f.body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.assertTrue(station.getPassengers().isEmpty(), "unload immediately clears the native seat");
        node.close(); helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 350)
    public static void mortarAimsAndDischargesItsRealShellAfterTheNativeDelay(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        for (int x = 32; x < 112; x++) { for (int z = 0; z < 24; z++) { f.block(new BlockPos(x, 0, z), Blocks.STONE); } }
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 8, 10);
        f.body.pack().insert(new ItemStack(ModItems.MORTAR_SHELL.get(), 2));
        f.enemy(80, 10); f.guard();
        AtomicInteger seen = new AtomicInteger();
        helper.onEachTick(() -> seen.accumulateAndGet(f.level.getEntitiesOfClass(
            com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity.class, mortar.getBoundingBox().inflate(120)).size(), Math::max));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 0,
                "the guard starts the native delayed mortar cycle " + f.debug()))
            .thenWaitUntil(() -> helper.assertTrue(seen.get() > 0, "the native mortar creates a physical shell"))
            .thenExecute(() -> {
                f.commands.release(f.body.id());
                helper.assertTrue(!f.body.isPassenger(), "a mortar operator works beside the station without inventing a seat");
                helper.assertValueEqual(f.body.pack().count(ModItems.MORTAR_SHELL.get()) + mortar.getItems().stream().mapToInt(ItemStack::getCount).sum(),
                    1, "one native mortar discharge consumes one real shell");
            }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void deathEndsAnActiveStationDuty(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10); f.guard();
        f.body.moveTo(station.position().add(-2, 0, 0)); StationNode node = f.node(station); node.commit(f.level, worker(f.body));
        helper.assertTrue(f.body.isPassenger(), "a living operator owns the actual station seat");
        // SBW's enclosed-seat damage hook cancels genericKill; establish an actual death instead.
        f.body.setHealth(0);
        f.body.die(f.level.damageSources().genericKill());
        node.commit(f.level, worker(f.body));
        helper.assertTrue(station.getPassengers().isEmpty(), "death releases the native station seat health=" + f.body.getHealth());
        helper.assertFalse(node.valid(), "the dead operator cannot retain a firing duty"); helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 120)
    public static void anEmptyStationNeverMountsOrFiresAndOccupiedSeatsStayOwned(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        VehicleEntity station = f.station(ModEntities.TOW.get(), 8, 10);
        f.enemy(23, 10); f.guard();
        helper.runAfterDelay(60, () -> {
            helper.assertFalse(f.body.isPassenger(), "an empty station gives no operating duty");
            helper.assertValueEqual(f.shots.get(), 0, "a station without physical rounds never fires");
            var occupant = EntityType.ZOMBIE.create(f.level); occupant.setNoAi(true); f.place(occupant, 8, 10);
            f.level.addFreshEntity(occupant); f.entities.add(occupant); occupant.startRiding(station);
            f.body.pack().insert(new ItemStack(ModItems.MEDIUM_ANTI_GROUND_MISSILE.get(), 2));
            helper.assertTrue(WarfareStations.find(f.colony, f.body).isEmpty(), "an occupied station cannot be taken over");
            helper.assertTrue(occupant.getVehicle() == station, "the original occupant retains its seat");
            helper.succeed();
        });
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 350)
    public static void aFriendEnteringDuringTheMortarDelayCancelsThePhysicalDischarge(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        for (int x = 32; x < 112; x++) { for (int z = 0; z < 24; z++) { f.block(new BlockPos(x, 0, z), Blocks.STONE); } }
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 8, 10);
        f.body.pack().insert(new ItemStack(ModItems.MORTAR_SHELL.get(), 2));
        f.enemy(80, 10); f.guard();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 6, "the native delayed cycle starts"))
            .thenExecute(() -> {
                var wolf = EntityType.WOLF.create(f.level); wolf.setNoAi(true); f.place(wolf, 80, 12);
                f.level.addFreshEntity(wolf); f.entities.add(wolf);
            }).thenIdle(30).thenExecute(() -> {
                helper.assertValueEqual(mortar.getEntityData().get(MortarEntity.FIRE_TIME), 0, "the changed impact area cancels the pending shot");
                helper.assertTrue(f.level.getEntitiesOfClass(com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity.class,
                    mortar.getBoundingBox().inflate(150)).isEmpty(), "no shell is spawned towards a friendly impact area");
                helper.assertValueEqual(f.body.pack().count(ModItems.MORTAR_SHELL.get()) + mortar.getItem(0).getCount(), 2,
                    "the cancelled discharge preserves both physical rounds");
            }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void firearmChoicesUseTheSharedRegistryAndLoanTheSelectedActualGun(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        var provider = io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry.find(WarfareWeapons.LOADOUT).orElseThrow();
        helper.assertValueEqual(provider.choices().size(), WarfareWeapons.ALL.size(), "each supported gun has a registry choice");
        for (var weapon : WarfareWeapons.ALL) {
            ItemStack stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(weapon.nativeId()));
            var options = provider.infer(stack).orElseThrow();
            helper.assertTrue(provider.valid(options) && Goods.matches(stack, provider.weapon(options)), "inference retains the correct weapon");
            helper.assertValueEqual(provider.ammunition(options).getFirst().spec(), weapon.ammunition(), "each choice requests compatible ammunition");
        }
        ItemStack first = new ItemStack(ModItems.GLOCK_17.get());
        ItemStack selected = new ItemStack(ModItems.GLOCK_17.get()); selected.set(DataComponents.CUSTOM_NAME, Component.literal("selected"));
        f.body.pack().insert(first); f.body.pack().insert(selected); f.body.pack().insert(new ItemStack(ModItems.HANDGUN_AMMO.get()));
        ItemStack actual = f.body.pack().getItem(1);
        WeaponSession session = WarfareWeapons.ALL.getFirst().open(worker(f.body), actual).orElseThrow(); f.sessions.add(session);
        helper.assertTrue(f.body.getMainHandItem() == actual, "the shared adapter receives and loans the selected real stack");
        helper.assertTrue(session.continuousEngagement(), "native gun sessions retain their hand between shots");
        session.close(); session.close();
        helper.assertValueEqual(f.body.pack().count(ModItems.GLOCK_17.get()), 2, "closing returns the selected gun exactly once");
        helper.succeed();
    }

    private static Worker worker(ResidentEntity body) {
        return new Worker() {
            public Resident resident() { return body.resident(); }
            public Mob body() { return body; }
            public Container pack() { return body.pack(); }
            public List<Store> within() { return List.of(); }
            public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { body.spawnAtLocation(stack); }
            public Effects effects() { return body.effects(); }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return Placement.NOWHERE; }
        };
    }

    private static final class Scene implements GameTestListener {
        final GameTestHelper helper;
        final BlockPos origin;
        final java.util.UUID ticket = java.util.UUID.randomUUID();
        final List<net.minecraft.world.level.ChunkPos> chunks = new ArrayList<>();
        final ServerLevel level;
        final Colony colony;
        final ResidentEntity body;
        final ResidentCommands commands;
        final PatrolSupplies supplies;
        final Container store;
        final List<Entity> entities = new ArrayList<>();
        final List<WeaponSession> sessions = new ArrayList<>();
        final List<StationNode> nodes = new ArrayList<>();
        final AtomicInteger shots = new AtomicInteger();
        final Consumer<ShootEvent.Post> listener;
        final LaborFixture labor;
        boolean closed;

        Scene(GameTestHelper helper, boolean simulate) {
            this.helper = helper; level = helper.getLevel();
            origin = helper.absolutePos(BlockPos.ZERO).offset(0, 0, 512 + nextScene++ * 512);
            for (int x = -1; x <= 8; x++) { for (int z = -1; z <= 2; z++) {
                var chunk = new net.minecraft.world.level.ChunkPos(origin.offset(x * 16, 0, z * 16));
                level.getChunk(chunk.x, chunk.z);
                level.getChunkSource().addRegionTicket(TICKING, chunk, 2, ticket, true);
                chunks.add(chunk);
            } }
            for (int x = 0; x < 32; x++) { for (int z = 0; z < 24; z++) { block(new BlockPos(x, 0, z), Blocks.STONE); } }
            colony = Colonies.mint(level.getServer()); commands = ResidentCommands.of(colony); supplies = PatrolSupplies.of(colony);
            body = PersonBody.RESIDENT.get().create(level); body.setNoAi(!simulate); place(body, 3, 10);
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow(); body.joinColony(colony.id()); level.addFreshEntity(body);
            block(new BlockPos(3, 1, 4), Blocks.BARREL); WorldPos source = at(3, 1, 4);
            store = Containers.at(level, source.block(level)).orElseThrow(); colony.stores(WarfareStations.ID, level.dimension(), List.of(source));
            listener = event -> { if (event.getShooter() == body) { shots.incrementAndGet(); } }; NeoForge.EVENT_BUS.addListener(listener);
            labor = simulate ? new LaborFixture(colony, level) : null;
            if (labor != null) { helper.onEachTick(labor::tick); }
            helper.testInfo.addListener(this);
        }
        WorldPos at(int x, int y, int z) { return WorldPos.of(level, origin.offset(x, y, z)); }
        void block(BlockPos at, net.minecraft.world.level.block.Block block) { level.setBlockAndUpdate(origin.offset(at), block.defaultBlockState()); }
        void place(Entity entity, int x, int z) { BlockPos pos = at(x, 1, z).block(level); entity.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, -90, 0); }
        Zombie enemy(int x, int z) {
            Zombie enemy = EntityType.ZOMBIE.create(level); enemy.setNoAi(true); place(enemy, x, z);
            enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10000); enemy.setHealth(10000);
            level.addFreshEntity(enemy); entities.add(enemy); return enemy;
        }
        VehicleEntity station(EntityType<? extends VehicleEntity> type, int x, int z) {
            VehicleEntity vehicle = type.create(level); place(vehicle, x, z); level.addFreshEntity(vehicle); entities.add(vehicle); return vehicle;
        }
        void guard() { commands.guard(body, at(3, 1, 10), new Vec3(1, 0, 0)); }
        StationNode node(VehicleEntity station) {
            StationNode node = new StationNode(colony, body, station, commands.current(body.id()).orElseThrow(), Stances.at(at(4, 1, 10)));
            nodes.add(node); return node;
        }
        String debug() { return "pos=" + body.position() + ", supply=" + supplies.status(body.id()) + ", pack=" + body.pack().contents() + ", riding=" + body.getVehicle(); }
        void close() {
            if (closed) { return; } closed = true;
            NeoForge.EVENT_BUS.unregister(listener); nodes.forEach(StationNode::close); sessions.forEach(WeaponSession::close);
            if (labor != null) { labor.close(); } body.discard(); entities.forEach(Entity::discard); colony.raze();
            level.getEntitiesOfClass(Entity.class, new net.minecraft.world.phys.AABB(origin).inflate(200)).forEach(Entity::discard);
            for (var chunk : chunks) { level.getChunkSource().removeRegionTicket(TICKING, chunk, 2, ticket, true); }
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
