package io.github.izakyl.folkways.warfare;

import com.atsuishio.superbwarfare.api.event.ShootEvent;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity;
import com.atsuishio.superbwarfare.entity.vehicle.Mk42Entity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModEntities;
import com.atsuishio.superbwarfare.init.ModItems;
import com.atsuishio.superbwarfare.perk.ammo.HEBullet;
import com.atsuishio.superbwarfare.tools.CustomExplosion;
import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.colony.MemberToggle;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.plugins.combat.SpottedTargets;
import io.github.izakyl.folkways.plugins.combat.WeaponStations;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.patrol.PatrolStaffing;
import io.github.izakyl.folkways.plugins.patrol.SentryPosts;
import io.github.izakyl.folkways.plugins.patrol.WarfarePatrolFixture;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;

@EventBusSubscriber(modid = FolkwaysWarfareMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WarfareGameTests {

    private static boolean previousNoSave;
    private static final TicketType<UUID> TICKING =
        TicketType.create("folkways_warfare_test", Comparator.<UUID>naturalOrder());
    private static int nextScene;

    @BeforeBatch(batch = "warfare")
    public static void before(ServerLevel level) {
        previousNoSave = level.noSave;
        level.noSave = true;
        nextScene = 0;
    }

    @AfterBatch(batch = "warfare")
    public static void after(ServerLevel level) {
        level.noSave = previousNoSave;
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WarfareGameTests.class);
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 500)
    public static void firearmLoadoutCollectsStockThenFiresExactlyTheSuppliedRounds(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        f.store.setItem(0, new ItemStack(ModItems.GLOCK_17.get()));
        f.store.setItem(1, new ItemStack(ModItems.HANDGUN_AMMO.get(), 5));
        f.profile(ItemSpec.of(ModItems.GLOCK_17.get()), ItemSpec.of(ModItems.HANDGUN_AMMO.get()), 5, 5);
        f.guardArea();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.body.pack().count(ModItems.HANDGUN_AMMO.get()) == 5,
                    "normal supply trips collect all five actual rounds " + f.debug()))
            .thenExecute(() -> f.enemy(10, 10))
            .thenWaitUntil(
            () ->
                helper.assertValueEqual(
                    f.shots.get(),
                    5,
                    "the guard discharges exactly five native shots " + f.debug()))
            .thenIdle(40)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    f.shots.get(), 5, "an empty source and pack produce no extra shots");
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.HANDGUN_AMMO.get()),
                    0,
                    "each native shot consumes one supplied round");
                helper.assertTrue(f.store.isEmpty(), "the real source was withdrawn once");
            })
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 100)
    public static void gunsPreservePlayerLoadedRoundsAndActualItemComponents(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        ItemStack gun = new ItemStack(ModItems.GLOCK_17.get());
        gun.set(DataComponents.CUSTOM_NAME, Component.literal("player's gun"));
        GunData data = GunData.from(gun);
        data.initialize();
        data.ammo.set(2);
        data.save();
        f.body.pack().insert(gun);
        Zombie target = f.enemy(10, 10);
        WeaponSession session = Weapons.open(worker(f.body)).orElseThrow();
        f.sessions.add(session);
        helper.assertTrue(session.fire(target), "actual loose ammunition allows firing");
        helper.onEachTick(
            () -> {
                session.tick();
                if (session.state() == WeaponSession.State.READY) {
                    session.fire(target);
                }
            });
        helper.runAfterDelay(
            45,
            () -> {
                session.close();
                session.close();
                helper.assertValueEqual(f.shots.get(), 2, "two loaded rounds permit two shots");
                ItemStack returned =
                    f.body.pack().contents().stream()
                    .filter(stack -> stack.is(ModItems.GLOCK_17.get()))
                    .findFirst()
                    .orElseThrow();
                helper.assertValueEqual(
                    GunData.from(returned).ammo.get(),
                    0,
                    "the native magazine consumes finite loaded rounds");
                helper.assertValueEqual(
                    returned.get(DataComponents.CUSTOM_NAME),
                    Component.literal("player's gun"),
                    "the actual item returns with its components");
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.GLOCK_17.get()),
                    1,
                    "idempotent closure returns one gun");
                helper.assertTrue(
                    Weapons.open(worker(f.body)).isEmpty(), "an empty gun and empty pack cannot open a session");
                helper.succeed();
            });
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 120)
    public static void reopeningOrRestockingAGunCannotResetItsNativeCadence(GameTestHelper helper) {
        // The pump keeps its session until each shot, then reopens; the pistol goes back to the pack every tick.
        List<Cadence> guns = List.of(
            new Cadence(helper, ModItems.M_870.get(), ModItems.SHOTGUN_AMMO.get(), true),
            new Cadence(helper, ModItems.GLOCK_17.get(), ModItems.HANDGUN_AMMO.get(), false));
        helper.startSequence()
            .thenWaitUntil(() -> guns.forEach(gun -> helper.assertValueEqual(
                gun.scene.shots.get(), 3, "three supplied rounds eventually discharge from " + gun.item)))
            .thenExecute(() -> guns.forEach(gun -> helper.assertTrue(gun.spaced(), gun.held
                ? "reopening sessions cannot bypass the native pump"
                : "copying a stack back into the pack never bypasses RPM")))
            .thenSucceed();
    }

    // One gun firing three rounds at a still enemy, a fresh session at a time, noting when each shot left.
    private static final class Cadence {
        final Scene scene;
        final Item item;
        final boolean held;
        final List<Long> times = new ArrayList<>();
        WeaponSession current;

        Cadence(GameTestHelper helper, Item item, Item ammo, boolean held) {
            scene = new Scene(helper, false);
            this.item = item;
            this.held = held;
            scene.body.pack().insert(new ItemStack(item));
            scene.body.pack().insert(new ItemStack(ammo, 3));
            scene.profile(ItemSpec.of(item), ItemSpec.of(ammo), 3, 3);
            Zombie target = scene.enemy(10, 10);
            helper.onEachTick(
                () -> {
                    if (scene.shots.get() >= 3) {
                        return;
                    }
                    if (current == null) {
                        current = Weapons.open(worker(scene.body)).orElseThrow();
                        scene.sessions.add(current);
                        current.fire(target);
                    }
                    int before = scene.shots.get();
                    current.tick();
                    boolean shot = scene.shots.get() > before;
                    if (shot) {
                        times.add(scene.level.getGameTime());
                    }
                    if (shot || !held) {
                        current.close();
                        current = null;
                    }
                });
        }

        boolean spaced() {
            GunData data = GunData.from(scene.body.pack().contents().stream()
                .filter(stack -> stack.is(item)).findFirst().orElseThrow());
            int interval = held ? data.get(GunProp.BOLT_ACTION_TIME) : (int) Math.ceil(1200.0 / data.get(GunProp.RPM));
            return times.get(1) - times.get(0) >= interval && times.get(2) - times.get(1) >= interval;
        }
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 300)
    public static void friendlyResidentsAndPetsBlockGunfire(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        f.body.pack().insert(new ItemStack(ModItems.GLOCK_17.get()));
        f.body.pack().insert(new ItemStack(ModItems.HANDGUN_AMMO.get(), 2));
        Zombie enemy = f.enemy(12, 10);
        var wolf = EntityType.WOLF.create(f.level);
        f.place(wolf, 7, 10);
        wolf.setNoAi(true);
        f.level.addFreshEntity(wolf);
        f.entities.add(wolf);
        WeaponSession session = Weapons.open(worker(f.body)).orElseThrow();
        f.sessions.add(session);
        helper.assertFalse(session.fire(enemy), "a pet in the line of fire blocks the attack");
        wolf.discard();
        ResidentEntity friend = PersonBody.RESIDENT.get().create(f.level);
        friend.setNoAi(true);
        f.place(friend, 7, 10);
        f.level.addFreshEntity(friend);
        f.entities.add(friend);
        helper.assertFalse(session.fire(enemy), "another resident blocks the attack too");
        helper.assertValueEqual(
            f.body.pack().count(ModItems.HANDGUN_AMMO.get()), 2, "blocked shots spend no ammunition");
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void firearmChoicesUseTheSharedRegistryAndLoanTheSelectedActualGun(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        var provider = LoadoutRegistry.find(WarfareWeapons.LOADOUT).orElseThrow();
        for (var weapon : WarfareWeapons.ALL) {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(weapon.nativeId()));
            helper.assertTrue(provider.accepts(stack), "each supported physical gun has a provider");
            helper.assertValueEqual(
                provider.ammunition(stack).orElseThrow(),
                weapon.ammunition(),
                "each gun describes compatible ammunition");
            helper.assertTrue(
                provider.ranged(stack)
                && !provider.indirect(stack)
                && provider.magazineSize(stack) > 0
                && provider.cycleTicks(stack) > 0
                && provider.suggestedAmount(stack) > 0,
                "item usage metadata can prefill an editable profile supply");
        }
        ItemStack first = new ItemStack(ModItems.GLOCK_17.get());
        ItemStack selected = new ItemStack(ModItems.GLOCK_17.get());
        selected.set(DataComponents.CUSTOM_NAME, Component.literal("selected"));
        f.body.pack().insert(first);
        f.body.pack().insert(selected);
        f.body.pack().insert(new ItemStack(ModItems.HANDGUN_AMMO.get()));
        ItemStack actual = f.body.pack().getItem(1);
        WeaponSession session = WarfareWeapons.ALL.getFirst().open(worker(f.body), actual).orElseThrow();
        f.sessions.add(session);
        helper.assertTrue(
            f.body.getMainHandItem() == actual,
            "the shared adapter receives and loans the selected real stack");
        helper.assertTrue(session.continuousEngagement(), "native gun sessions retain their hand between shots");
        session.close();
        session.close();
        helper.assertValueEqual(
            f.body.pack().count(ModItems.GLOCK_17.get()), 2, "closing returns the selected gun exactly once");
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 550)
    public static void unsafePhysicalGunVariantsCannotReplaceOrReserveTheUsableLoadout(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        ItemStack unsafe = new ItemStack(ModItems.GLOCK_17.get());
        GunData he = GunData.from(unsafe);
        he.perk.set(HEBullet.INSTANCE, (short) 1);
        he.save();
        f.body.pack().insert(unsafe.copy());
        f.store.setItem(0, unsafe.copy());
        ItemStack unusable = new ItemStack(ModItems.GLOCK_17.get());
        GunData empty = GunData.from(unusable);
        empty.propertyOverrideString.set("{\"Magazine\":0}");
        empty.save();
        f.store.setItem(1, unusable);
        ItemStack usable = new ItemStack(ModItems.GLOCK_17.get());
        usable.set(DataComponents.CUSTOM_NAME, Component.literal("safe gun"));
        f.store.setItem(2, usable);
        f.store.setItem(3, new ItemStack(ModItems.HANDGUN_AMMO.get(), 2));
        f.profile(ItemSpec.of(ModItems.GLOCK_17.get()), ItemSpec.of(ModItems.HANDGUN_AMMO.get()), 2, 2);
        f.guardArea();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.body.pack().contents().stream()
                    .anyMatch(
                    stack ->
                        Component.literal("safe gun")
                            .equals(stack.get(DataComponents.CUSTOM_NAME))),
                    "normal logistics ignore unusable carried and stored guns " + f.debug()))
            .thenExecute(
            () -> {
                helper.assertTrue(
                    !f.store.getItem(0).isEmpty() && !f.store.getItem(1).isEmpty(),
                    "unusable stock is never withdrawn");
                helper.assertTrue(
                    f.supplies.keeps(f.body).stream()
                    .filter(amount -> Goods.matches(usable, amount.spec()))
                    .allMatch(amount -> !Goods.matches(unsafe, amount.spec())),
                    "the usable component identity owns the retention slot");
                f.enemy(10, 10);
            })
            .thenWaitUntil(
            () ->
                helper.assertValueEqual(
                    f.shots.get(), 2, "the accepted loadout fires only the two physical rounds " + f.debug()))
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void explosiveOrAlteredGunComponentsRevokeEvenAnOpenHandLoan(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        f.body.pack().insert(new ItemStack(ModItems.GLOCK_17.get()));
        f.body.pack().insert(new ItemStack(ModItems.HANDGUN_AMMO.get(), 2));
        Zombie target = f.enemy(10, 10);
        WeaponSession session = Weapons.open(worker(f.body)).orElseThrow();
        f.sessions.add(session);
        helper.assertTrue(session.fire(target), "a plain gun accepts the target");
        GunData data = GunData.from(f.body.getMainHandItem());
        data.perk.set(HEBullet.INSTANCE, (short) 1);
        data.save();
        helper.assertTrue(data.get(GunProp.EXPLOSION_DAMAGE) > 0, "the test uses the native HE trait");
        session.tick();
        helper.assertTrue(
            session.state() == WeaponSession.State.CLOSED,
            "an unsafe component change closes the actual loan before discharge");
        helper.assertValueEqual(f.shots.get(), 0, "no explosive bullet is fired");
        helper.assertValueEqual(
            f.body.pack().count(ModItems.HANDGUN_AMMO.get()), 2, "rejection preserves physical ammo");
        var provider = LoadoutRegistry.find(WarfareWeapons.LOADOUT).orElseThrow();
        for (String override :
            List.of(
            "invalid JSON",
            "{\"AmmoCostPerShoot\":0}",
            "{\"Magazine\":0}",
            "{\"AutoReload\":true}",
            "{\"BypassesArmor\":1}",
            "{\"Projectile\":\"superbwarfare:wire_guide_missile\"}",
            "{\"ExplosionRadius\":5}")) {
            ItemStack gun = new ItemStack(ModItems.GLOCK_17.get());
            GunData variant = GunData.from(gun);
            variant.propertyOverrideString.set(override);
            variant.save();
            helper.assertFalse(
                provider.accepts(gun),
                "unusable or unpredictable component override is rejected: " + override);
        }
        helper.succeed();
    }

    @BeforeBatch(batch = "warfare_rules")
    public static void beforeRules(ServerLevel level) {
        before(level);
    }

    @AfterBatch(batch = "warfare_rules")
    public static void afterRules(ServerLevel level) {
        after(level);
    }

    @GameTest(batch = "warfare_rules", template = "warfare_range")
    public static void nativeResidentExplosionsHonorRuleChangesCancellationAndProtectedBlockLists(
        GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        var shell = new MortarShellEntity(ModEntities.MORTAR_SHELL.get(), f.level);
        shell.setOwner(f.body);
        f.place(shell, 44, 10);
        f.level.addFreshEntity(shell);
        f.entities.add(shell);
        BlockPos at = f.at(44, 1, 10).block(f.level);
        f.level.setBlockAndUpdate(at, Blocks.DIRT.defaultBlockState());
        Zombie target = f.enemy(45, 10);
        Runnable explode =
            () ->
                new CustomExplosion.Builder(shell)
                    .attacker(f.body)
                    .position(Vec3.atCenterOf(at))
                    .damage(120)
                    .radius(3)
                    .explode();
        var rule = f.level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean previous = rule.get();
        AtomicInteger starts = new AtomicInteger();
        Consumer<ExplosionEvent.Start> cancel =
            event -> {
                if (event.getExplosion().getDirectSourceEntity() == shell) {
                    starts.incrementAndGet();
                    event.setCanceled(true);
                }
            };
        Consumer<ExplosionEvent.Detonate> protect =
            event -> {
                if (event.getExplosion().getDirectSourceEntity() == shell) {
                    event.getAffectedBlocks().clear();
                }
            };
        try {
            rule.set(false, f.level.getServer());
            explode.run();
            helper.assertTrue(
                f.level.getBlockState(at).is(Blocks.DIRT),
                "a rule change before impact prevents native block damage");
            helper.assertValueEqual(
                target.getHealth(), target.getMaxHealth(), "a refused explosion deals no entity damage");
            rule.set(true, f.level.getServer());
            NeoForge.EVENT_BUS.addListener(cancel);
            explode.run();
            helper.assertValueEqual(starts.get(), 1, "Start is cancellable before damage and is posted only once");
            helper.assertTrue(f.level.getBlockState(at).is(Blocks.DIRT), "cancellation preserves the block");
            helper.assertValueEqual(
                target.getHealth(), target.getMaxHealth(), "cancellation preserves nearby entities");
            NeoForge.EVENT_BUS.unregister(cancel);
            NeoForge.EVENT_BUS.addListener(protect);
            explode.run();
            helper.assertTrue(
                f.level.getBlockState(at).is(Blocks.DIRT),
                "Detonate block protection runs before native tier destruction");
            NeoForge.EVENT_BUS.unregister(protect);
            explode.run();
            helper.assertTrue(
                f.level.getBlockState(at).isAir(),
                "without a protection veto the same native explosion still destroys ordinary blocks");
        } finally {
            NeoForge.EVENT_BUS.unregister(cancel);
            NeoForge.EVENT_BUS.unregister(protect);
            rule.set(previous, f.level.getServer());
        }
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 220)
    public static void mortarInSentryZoneUsesOnlyADistantPatrolObservation(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.MORTAR_SHELL.get(), 3));
        f.postStation();
        Zombie target = f.enemy(80, 10);
        SpottedTargets.Spot spot = f.spot(target);
        helper.assertFalse(SpottedTargets.sees(f.body, target), "the sentry cannot observe this target itself");
        Node node = f.node();
        helper.onEachTick(() -> f.tick(node));
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    !f.level
                    .getEntitiesOfClass(
                    MortarShellEntity.class, mortar.getBoundingBox().inflate(160))
                    .isEmpty(),
                    "SBW emits the physical indirect shell " + f.debug()))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.MORTAR_SHELL.get()),
                    2,
                    "exactly one pack shell settles at native emission");
                helper.assertTrue(
                    mortar.getItem(0).isEmpty(), "preparation never leaves duplicated physical shells");
                helper.assertFalse(f.body.isPassenger(), "mortar operation stands beside the station");
                helper.assertTrue(
                    spot.position().equals(target.position()),
                    "the report supplies immutable aim coordinates");
            })
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 220)
    public static void predictedMortarImpactHoldsForAlliesAndOwnColonyBlocks(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.MORTAR_SHELL.get(), 3));
        f.postStation();
        Zombie target = f.enemy(80, 10);
        var ally = EntityType.WOLF.create(f.level);
        ally.setNoAi(true);
        f.place(ally, 80, 23);
        f.level.addFreshEntity(ally);
        f.entities.add(ally);
        Node node = f.node();
        helper.startSequence()
            // Block chunks can be present before their entities become visible to safety queries.
            .thenWaitUntil(() -> helper.assertTrue(f.level.getEntity(ally.getUUID()) == ally
                && f.level.getEntity(target.getUUID()) == target
                && f.level.getEntity(mortar.getUUID()) == mortar
                && f.level.getEntity(f.body.id()) == f.body,
                "the ticketed arena exposes the ally, target, mortar and operator before engagement"))
            .thenExecute(() -> {
                f.spot(target);
                helper.onEachTick(() -> {
                    f.tick(node);
                    helper.assertTrue(SpottedTargets.of(f.colony).get(f.body, target.getUUID()).isPresent(),
                        "the report remains fresh throughout both unsafe impact holds");
                    helper.assertValueEqual(mortar.getEntityData().get(MortarEntity.FIRE_TIME), 0,
                        "an unsafe predicted impact never starts preparation");
                    helper.assertValueEqual(f.body.pack().count(ModItems.MORTAR_SHELL.get()), 3,
                        "an unsafe predicted impact never consumes ammunition");
                });
            })
            .thenIdle(45)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    mortar.getEntityData().get(MortarEntity.FIRE_TIME),
                    0,
                    "an ally inside twice the configured radius blocks preparation");
                double radius = mortar.getGunData("Main").get(GunProp.EXPLOSION_RADIUS);
                helper.assertTrue(f.level.getEntitiesOfClass(Wolf.class,
                    target.getBoundingBox().inflate(radius * 2)).contains(ally),
                    "the living ally remains visible inside twice the configured radius");
                ally.discard();
                f.block(new BlockPos(80, 1, 13), Blocks.BARREL);
                f.colony.stores(WarfareStations.ID, f.level.dimension(), List.of(f.at(80, 1, 13)));
            })
            .thenIdle(45)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.MORTAR_SHELL.get()),
                    3,
                    "ally and owned-block holds never consume ammunition");
                helper.assertTrue(
                    f.level
                    .getEntitiesOfClass(
                    MortarShellEntity.class, mortar.getBoundingBox().inflate(160))
                    .isEmpty(),
                    "neither unsafe prediction launches a shell");
            })
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 600)
    public static void mk42OperatorWalksToTheExternalHullAndFires(GameTestHelper helper) {
        walkingStation(helper, ModEntities.MK_42.get(), false, false);
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 600)
    public static void arrivingOperatorBoardsWithoutAnEnemyAndWithdrawsCleanly(GameTestHelper helper) {
        Scene f = new Scene(helper, true, 20, 18, true);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(f.level.getEntity(f.body.id()) == f.body
            && f.level.getEntity(station.getUUID()) == station, "the approach arena exposes both entities"))
            .thenExecute(() -> {
                f.joinBody();
                f.profile(ItemSpec.of(Items.BOW), ItemSpec.of(ModItems.LARGE_SHELL_HE.get()), 3, 1);
                f.body.pack().insert(new ItemStack(Items.BOW));
                f.body.pack().insert(new ItemStack(ModItems.LARGE_SHELL_HE.get(), 3));
                f.postStation();
            })
            .thenWaitUntil(() -> helper.assertTrue(f.body.getVehicle() == station && WeaponStations.ownsSeat(f.body),
                "normal arrival boards the exact station without waiting for an enemy " + f.debug()))
            .thenExecute(() -> {
                helper.assertTrue(f.body.walkDist / .6 >= 4, "the unopposed operator really walks to the hull");
                helper.assertValueEqual(f.shots.get(), 0, "an unopposed operator does not fire");
                helper.assertValueEqual(f.body.pack().count(ModItems.LARGE_SHELL_HE.get()), 3,
                    "boarding alone consumes no ammunition");
                f.unpost();
                helper.assertFalse(f.body.isPassenger() || WeaponStations.ownsSeat(f.body),
                    "withdrawal releases the idle native seat immediately");
            }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 600)
    public static void mortarOperatorWalksToAnExternalFootingAndFires(GameTestHelper helper) {
        walkingStation(helper, ModEntities.MORTAR.get(), false, false);
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 600)
    public static void stationApproachFallsBackFromAnEnclosedFootingAndBlockingEntity(GameTestHelper helper) {
        walkingStation(helper, ModEntities.MK_42.get(), true, false);
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 800)
    public static void sealedStationApproachWaitsWithoutMountingThenRetriesWhenOpened(GameTestHelper helper) {
        walkingStation(helper, ModEntities.MK_42.get(), false, true);
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 180)
    public static void containedUnenrolledStationLeavesSentryFiringOnlyHandheldWeapon(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.GLOCK_17.get()));
        f.body.pack().insert(new ItemStack(ModItems.HANDGUN_AMMO.get(), 5));
        f.body.pack().insert(new ItemStack(ModItems.LARGE_SHELL_HE.get(), 3));
        f.guardArea();
        Zombie target = f.enemy(3, 20);
        Node node = f.node();
        helper.onEachTick(() -> f.tick(node));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(target.getHealth() < target.getMaxHealth(),
            "the area sentry physically fires its profile handgun " + f.debug()))
            .thenExecute(() -> {
                helper.assertTrue(station.getPassengers().isEmpty() && !WeaponStations.ownsSeat(f.body),
                    "the contained unenrolled emplacement never gains the area sentry");
                helper.assertValueEqual(f.body.pack().count(ModItems.LARGE_SHELL_HE.get()), 3,
                    "handheld defence consumes no emplacement ammunition");
                helper.assertTrue(f.body.pack().count(ModItems.HANDGUN_AMMO.get()) < 5,
                    "the profile handgun consumes physical ammunition");
            }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 220)
    public static void spyglassForwardObserverReportsBeyondDefaultSightForNativeMortar(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.MORTAR_SHELL.get(), 3));
        f.postStation();
        Zombie target = f.enemy(80, 10);
        ResidentEntity scout = PersonBody.RESIDENT.get().create(f.level);
        f.place(scout, 40, 10);
        scout.setNoAi(true);
        f.level.addFreshEntity(scout);
        f.colony.hold(PersonContent.ID, Claimed.Entity.of(scout)).orElseThrow();
        scout.joinColony(f.colony.id());
        f.entities.add(scout);
        var profiles = LoadoutProfiles.of(f.colony);
        var profile = profiles.create("Forward observer");
        profiles.add(profile.id(), LoadoutProfile.Entry.supply(ItemSpec.of(Items.SPYGLASS), 1, 1));
        profiles.assign(List.of(scout.id()), Optional.of(profile.id()));
        helper.assertTrue(SpottedTargets.of(f.colony).report(scout, target),
            "assigned optics allow the distant report before physical delivery");
        scout.pack().insert(new ItemStack(Items.SPYGLASS));
        helper.assertTrue(SpottedTargets.of(f.colony).observe(scout).contains(target),
            "the physical spyglass lets the forward observer discover the target forty blocks away");
        f.place(scout, 3, 20);
        Node node = f.node();
        helper.onEachTick(() -> f.tick(node));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!f.level.getEntitiesOfClass(
            MortarShellEntity.class, mortar.getBoundingBox().inflate(160)).isEmpty(),
            "the independently staffed mortar emits a native shell using the spyglass report " + f.debug()))
            .thenExecute(() -> helper.assertValueEqual(f.body.pack().count(ModItems.MORTAR_SHELL.get()), 2,
                "the real observer report spends exactly one carried mortar shell"))
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void stationIncumbentsSurviveUnloadWithoutBorrowingColocatedWeapons(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity original = f.station(ModEntities.MK_42.get(), 6, 10);
        VehicleEntity replacement = f.station(ModEntities.MK_42.get(), 6, 10);
        f.postStation();
        helper.assertTrue(f.found().entity().filter(original.getUUID()::equals).isPresent(),
            "the held original UUID wins over a co-located candidate");
        var saved = new PatrolStaffing(f.colony);
        helper.assertTrue(saved.positions(f.zone).getFirst().member().filter(f.body.id()::equals).isPresent(),
            "the existing staffing ledger persists the desired operator");
        Node node = f.node();
        f.tick(node);
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        SentryPosts.of(f.colony).tick(f.level.getServer());
        helper.assertFalse(node.ready(f.level), "unloading the exact station revokes the old duty");
        helper.assertTrue(replacement.getPassengers().isEmpty(), "the replacement cannot inherit the old lease");
        helper.assertTrue(f.colony.claim(f.zone).isPresent()
            && new PatrolStaffing(f.colony).positions(f.zone).getFirst().member()
                .filter(f.body.id()::equals).isPresent(),
            "unloading preserves the explicit holding and incumbent");
        helper.assertFalse(SentryPosts.of(f.colony).act(SentryPosts.ID, "withdraw/" + f.zone,
            Optional.empty(), f.colony.view(f.level)).refused(), "withdraw an absent enrolled station");
        helper.assertTrue(f.colony.claim(f.zone).isEmpty() && PatrolStaffing.of(f.colony).positions(f.zone).isEmpty(),
            "withdrawal removes the holding and its composition");
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void entityStationEnrollmentRetainsKeeperAuthorityRangeAndColonyExclusion(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10);
        var player = FakePlayerFactory.get(f.level, new GameProfile(UUID.randomUUID(), "station_keeper"));
        Charters.keep(f.colony, Charter.founded("Station test", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(f.colony.id()));
        helper.assertTrue(ColonyAuthority.forColony(player, f.colony.id()).isPresent(),
            "the keeper acting for the colony is authorized for entity inclusion");
        f.place(player, 15, 10);
        helper.assertTrue(MemberToggle.toggleEntity(f.colony, station, player).refused(),
            "a nine-block request exceeds the existing eight-block entity range");
        f.place(player, 7, 10);
        helper.assertFalse(MemberToggle.toggleEntity(f.colony, station, player).refused(),
            "the ordinary member toggle includes the nearby supported entity");
        var foreign = Colonies.mint(f.level.getServer());
        try {
            helper.assertTrue(MemberToggle.toggleEntity(foreign, station, player).refused(),
                "a station already held by another colony cannot be included again");
        } finally {
            foreign.raze();
        }
        helper.assertTrue(f.colony.claimOf(station.getUUID())
            .filter(holding -> holding.owner().equals(SentryPosts.STATION)).isPresent(),
            "inclusion creates an independent entity holding");
        UUID holding = f.colony.claimOf(station.getUUID()).orElseThrow().id();
        PatrolStaffing.of(f.colony).tick(f.level.getServer());
        helper.assertTrue(PatrolStaffing.of(f.colony).configured(holding), "enrollment derives automatic staffing");
        helper.assertValueEqual(PatrolStaffing.of(f.colony).members(holding), List.of(f.body.id()),
            "the idle combat resident fills without post configuration");
        helper.assertFalse(MemberToggle.toggleEntity(f.colony, station, player).refused(),
            "the same point action withdraws the independent entity");
        Charters.keep(f.colony, Charter.founded("Revoked", UUID.randomUUID()));
        helper.assertTrue(ColonyAuthority.forColony(player, f.colony.id()).isEmpty(),
            "revoked keeper authority cannot mutate station membership");
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void stationsNeedIndependentEnrollmentAndUnsafeProjectileTypesAreExcluded(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity outside = f.station(ModEntities.MK_42.get(), 20, 10);
        f.guardArea();
        helper.assertTrue(
            SentryPosts.of(f.colony).stations(f.level,
                SentryPosts.of(f.colony).zone(f.level, f.zone).orElseThrow()).isEmpty(),
            "an area sentry never receives a station");
        VehicleEntity inside = f.station(ModEntities.MK_42.get(), 6, 10);
        helper.assertTrue(SentryPosts.of(f.colony).stations(f.level,
            SentryPosts.of(f.colony).zone(f.level, f.zone).orElseThrow()).isEmpty(),
            "even a contained station requires its own enrolled post");
        f.unpost();
        f.postStation();
        helper.assertTrue(f.found().entity().filter(inside.getUUID()::equals).isPresent(),
            "the independent post offers only its exact entity");
        helper.assertFalse(f.found().indirect(), "Mk42 retains its own direct line of fire");
        inside.modifyGunData("Main", data -> data.changeAmmoConsumer(0, null));
        helper.assertFalse(WarfareStations.automatic(inside), "AP is excluded");
        helper.assertFalse(WarfareStations.automatic(f.station(ModEntities.TOW.get(), 7, 10)), "TOW is excluded");
        helper.assertFalse(
            WarfareStations.emplacement(f.station(ModEntities.LAV_150.get(), 7, 14)), "vehicles are excluded");
        helper.assertTrue(
            WarfareStations.emplacement(f.station(ModEntities.FH_77BW.get(), 7, 17)),
            "a stopped howitzer retains station support");
        helper.assertTrue(outside.getPassengers().isEmpty(), "outside stations have no operator");
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 120)
    public static void nativeStationSeatIsExclusiveAndClosesWhenTradeIsRevoked(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.LARGE_SHELL_HE.get(), 3));
        f.postStation();
        Zombie target = f.enemy(44, 10);
        f.spot(target);
        Node node = f.node();
        f.tick(node);
        helper.assertTrue(WeaponStations.ownsSeat(f.body), "Vanilla owns the temporary native seat");
        helper.assertTrue(WeaponStations.acquire(f.found(), worker(f.body)).isEmpty(), "leases are exclusive");
        var trade = f.body.licences().trade(Vocations.required(PatrolContent.VOCATION)).orElseThrow();
        f.body.licences().allow(trade, false);
        SentryPosts.of(f.colony).tick(f.level.getServer());
        helper.assertFalse(f.body.isPassenger(), "trade revocation releases the owned native seat");
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void ownedHowitzerRetainsAimInsteadOfResidentHeadPitch(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        ArtilleryEntity station = (ArtilleryEntity) f.station(ModEntities.FH_77BW.get(), 6, 10);
        station.modifyGunData("Main", data -> data.changeAmmoConsumer(1, null));
        f.postStation();
        WeaponStations.Lease lease = f.lease();
        helper.assertTrue(f.body.getVehicle() == station && WeaponStations.ownsSeat(f.body),
            "entry owns the resident seat");
        helper.assertValueEqual(station.getSeatIndex(f.body), station.getTurretControllerIndex(),
            "entry selects the gunner seat");
        Vector3f aim = new Vector3f(.2F, .3F, .9F).normalize();
        station.setShootVec(aim);
        f.body.setXRot(0);
        f.body.setYRot(0);
        station.baseTick();
        helper.assertTrue(station.getShootVec().equals(aim), "native passenger tick retains the leased station aim");
        lease.close();
        helper.assertFalse(WeaponStations.ownsSeat(f.body), "closing the lease removes the exception");
        helper.runAfterDelay(61, () -> {
            helper.assertTrue(f.body.startRiding(station), "a resident can occupy an unleased native seat");
            helper.assertTrue(station.changeSeat(f.body, station.getTurretControllerIndex()),
                "use the native gunner seat");
            station.setShootVec(aim);
            f.body.setXRot(0);
            f.body.setYRot(0);
            station.baseTick();
            helper.assertFalse(station.getShootVec().equals(aim),
                "an unleased controller keeps native passenger input");
            helper.succeed();
        });
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 180)
    public static void removingCombatAssignmentRevokesPendingMortarBeforeReconciliation(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.MORTAR_SHELL.get(), 3));
        f.postStation();
        PatrolStaffing staffing = PatrolStaffing.of(f.colony);
        f.spot(f.enemy(80, 10));
        Node node = f.node();
        helper.onEachTick(() -> f.tick(node));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 6,
                "the composed operator starts native preparation");
        }).thenExecute(() -> {
            LoadoutProfiles.of(f.colony).assign(List.of(f.body.id()), Optional.empty());
            helper.assertValueEqual(staffing.positions(f.zone).getFirst().status(), "filled",
                "the cached staffing result has not reconciled yet");
            helper.assertFalse(node.ready(f.level), "a different ranged profile immediately revokes duty");
            helper.assertValueEqual(SentryPosts.of(f.colony).status(f.level, f.body.id(),
                SentryPosts.of(f.colony).zone(f.level, f.zone).orElseThrow()), "profile_mismatch",
                "the live refusal explains the staffing mismatch");
        }).thenIdle(30).thenExecute(() -> {
            helper.assertValueEqual(f.shots.get(), 0, "the queued native shot never emits");
            helper.assertValueEqual(f.body.pack().count(ModItems.MORTAR_SHELL.get()), 3,
                "revoked duty spends no physical shells");
            helper.assertFalse(f.body.isPassenger(), "revoked staffing releases the owned seat");
            staffing.tick(f.level.getServer());
            helper.assertValueEqual(staffing.positions(f.zone).getFirst().status(), "unfilled",
                "removing the assignment releases the incumbent");
            helper.assertValueEqual(staffing.shortage(f.zone), 1,
                "the mismatched slot reports the required profile shortage");
        }).thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 100)
    public static void restoredStationSnapshotReleasesOnlyItsOrphanedSeat(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.LARGE_SHELL_HE.get(), 3));
        f.postStation();
        f.spot(f.enemy(44, 10));
        f.tick(f.node());
        helper.assertTrue(f.body.getVehicle() == station, "the temporary seat is owned before saving");
        CompoundTag snapshot = new CompoundTag();
        helper.assertTrue(station.save(snapshot), "save native riding data with persisted loan identity");
        f.body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        station.discard();
        VehicleEntity restored = (VehicleEntity) EntityType.loadEntityRecursive(snapshot, f.level, entity -> entity);
        ResidentEntity operator = (ResidentEntity) restored.getFirstPassenger();
        f.entities.add(restored);
        f.entities.add(operator);
        f.level.addFreshEntityWithPassengers(restored);
        helper.runAfterDelay(
            5,
            () -> {
                helper.assertTrue(
                    restored.getPassengers().isEmpty(), "a restored transient loan never resumes");
                helper.succeed();
            });
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 180)
    public static void aDirectStationEmitsThroughItsNativeQueueAndConsumesOneShell(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.LARGE_SHELL_HE.get(), 1));
        f.postStation();
        Zombie target = f.enemy(44, 10);
        f.spot(target);
        Node node = f.node();
        helper.onEachTick(() -> f.tick(node));
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    f.shots.get() > 0,
                    "the native queued vehicle discharge executes " + f.debug()))
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.LARGE_SHELL_HE.get()),
                    0,
                    "one native emission consumes one profile shell");
                helper.assertValueEqual(
                    station.getAmmoCount("Main"), 0, "borrowed magazine ammo is restored");
                f.unpost();
                helper.assertFalse(
                    f.body.isPassenger(), "unposting releases the owned seat immediately");
            })
            .thenExecute(
            () -> {
                AtomicInteger nativeCalls = new AtomicInteger();
                WarfareStations.queued(station, nativeCalls::incrementAndGet).run();
                helper.assertValueEqual(
                    nativeCalls.get(),
                    1,
                    "a discovered, unposted station preserves unrelated player-native queues");
            })
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 180)
    public static void anAllyEnteringDuringMortarPreparationCancelsWithoutSpendingTheShell(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.MORTAR_SHELL.get(), 3));
        f.postStation();
        f.spot(f.enemy(80, 10));
        Node node = f.node();
        helper.onEachTick(() -> f.tick(node));
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 6,
                    "the native delayed cycle begins"))
            .thenExecute(
            () -> {
                var ally = EntityType.WOLF.create(f.level);
                ally.setNoAi(true);
                f.place(ally, 80, 23);
                f.level.addFreshEntity(ally);
                f.entities.add(ally);
            })
            .thenIdle(30)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.MORTAR_SHELL.get()),
                    3,
                    "the failed discharge preserves all real shells");
                helper.assertValueEqual(
                    mortar.getEntityData().get(MortarEntity.FIRE_TIME),
                    0,
                    "the newly unsafe prediction cancels native preparation");
            })
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 180)
    public static void staleHiddenTargetAndRevokedDutyCancelPendingMortarFire(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.MORTAR_SHELL.get(), 3));
        f.postStation();
        Zombie target = f.enemy(80, 10);
        SpottedTargets.Spot spot = f.spot(target);
        Node node = f.node();
        helper.onEachTick(() -> f.tick(node));
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 6,
                    "prepare from the observed position"))
            .thenExecute(() -> f.place(target, 84, 10))
            .thenIdle(25)
            .thenExecute(
            () -> {
                helper.assertTrue(
                    SpottedTargets.of(f.colony).resolve(spot, f.body).isEmpty(),
                    "hidden movement invalidates the immutable report");
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.MORTAR_SHELL.get()),
                    3,
                    "a displaced hidden target cannot spend a shell");
                f.unpost();
                helper.assertValueEqual(
                    mortar.getEntityData().get(MortarEntity.FIRE_TIME),
                    0,
                    "revoking the post leaves no pending discharge");
            })
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void deathUnloadAndStationRemovalReleaseNativeSeatsImmediately(GameTestHelper helper) {
        for (String how : List.of("death", "unload", "removal")) {
            Scene f = new Scene(helper, false);
            VehicleEntity station = f.station(ModEntities.MK_42.get(), 6, 10);
            f.body.pack().insert(new ItemStack(ModItems.LARGE_SHELL_HE.get(), 3));
            f.postStation();
            f.spot(f.enemy(44, 10));
            f.tick(f.node());
            helper.assertTrue(WeaponStations.ownsSeat(f.body), "the operator owns the seat before " + how);
            switch (how) {
                case "death" -> {
                    f.body.setHealth(0);
                    f.body.die(f.level.damageSources().genericKill());
                }
                case "unload" -> f.body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                default -> station.discard();
            }
            helper.assertTrue(how.equals("removal") ? !f.body.isPassenger() : station.getPassengers().isEmpty(),
                how + " releases the native seat in the same event");
        }
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 450)
    public static void profileShellTargetIsCollectedFromRealStoresWithoutAHiddenDutyAmount(GameTestHelper helper) {
        Scene f = new Scene(helper, true);
        f.station(ModEntities.MORTAR.get(), 6, 10);
        f.profile(ItemSpec.of(Items.BOW), ItemSpec.of(ModItems.MORTAR_SHELL.get()), 7, 4);
        f.body.pack().insert(new ItemStack(Items.BOW));
        f.store.setItem(0, new ItemStack(ModItems.MORTAR_SHELL.get(), 8));
        f.postStation();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.MORTAR_SHELL.get()),
                    7,
                    "normal profile logistics collect the player-set target of seven "
                    + f.debug()))
            .thenExecute(
            () ->
                helper.assertValueEqual(
                    IntStream.range(0, f.store.getContainerSize())
                    .map(slot -> f.store.getItem(slot).getCount())
                    .sum(),
                    1,
                    "remaining registered stock stays physical " + f.debug()))
            .thenSucceed();
    }

    @GameTest(batch = "warfare", template = "warfare_range")
    public static void rejectedProjectileInsertionRefundsTheExactPhysicalGunRound(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        f.body.pack().insert(new ItemStack(ModItems.GLOCK_17.get()));
        ItemStack round = new ItemStack(ModItems.HANDGUN_AMMO.get());
        round.set(DataComponents.CUSTOM_NAME, Component.literal("refunded round"));
        f.body.pack().insert(round);
        Zombie target = f.enemy(10, 10);
        Consumer<EntityJoinLevelEvent> reject =
            event -> {
                if (event.getEntity() instanceof Projectile projectile && projectile.getOwner() == f.body) {
                    event.setCanceled(true);
                }
            };
        WeaponSession session = Weapons.open(worker(f.body)).orElseThrow();
        f.sessions.add(session);
        NeoForge.EVENT_BUS.addListener(reject);
        try {
            helper.assertTrue(session.fire(target), "the clear shot begins");
            session.tick();
            helper.assertValueEqual(
                Goods.countIn(f.body.pack(), ItemSpec.exact(round)),
                1L,
                "rejected insertion returns the same component-bearing round");
        } finally {
            NeoForge.EVENT_BUS.unregister(reject);
        }
        helper.succeed();
    }

    @GameTest(batch = "warfare", template = "warfare_range", timeoutTicks = 80)
    public static void aStationOutsideTheAssignedZoneNeverAcquiresAnOperatorOrFires(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        VehicleEntity station = f.station(ModEntities.MK_42.get(), 20, 10);
        f.body.pack().insert(new ItemStack(ModItems.LARGE_SHELL_HE.get(), 3));
        f.guardArea();
        f.spot(f.enemy(44, 10));
        Node node = f.node();
        helper.onEachTick(() -> f.tick(node));
        helper.runAfterDelay(
            40,
            () -> {
                helper.assertTrue(
                    station.getPassengers().isEmpty(), "a nearby outside station never gets a sentry");
                helper.assertValueEqual(f.shots.get(), 0, "outside stations never fire through the duty");
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.LARGE_SHELL_HE.get()), 3, "outside discovery spends no shell");
                helper.succeed();
            });
    }

    @GameTest(batch = "warfare", template = "warfare_mortar", timeoutTicks = 100)
    public static void unpostingPreservesUnrelatedNativeMortarPreparation(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 6, 10);
        f.postStation();
        f.tick(f.node());
        mortar.getItems().set(0, new ItemStack(ModItems.MORTAR_SHELL.get()));
        mortar.vehicleShoot(null, "Main", f.at(80, 1, 10).block(f.level).getCenter());
        helper.assertTrue(
            mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 0,
            "an unrelated native player shot begins preparation");
        f.unpost();
        helper.assertTrue(
            mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 0,
            "closing an idle sentry lease preserves the unrelated native shot");
        helper.assertValueEqual(mortar.getItem(0).getCount(), 1, "the player's physical shell is preserved");
        helper.succeed();
    }

    @GameTest(batch = "warfare_rules", template = "warfare_mortar", timeoutTicks = 200)
    public static void mobGriefingPermissionIsRecheckedBeforeNativeStationLaunch(GameTestHelper helper) {
        Scene f = new Scene(helper, false);
        MortarEntity mortar = (MortarEntity) f.station(ModEntities.MORTAR.get(), 6, 10);
        f.body.pack().insert(new ItemStack(ModItems.MORTAR_SHELL.get(), 3));
        f.postStation();
        f.spot(f.enemy(80, 10));
        Node node = f.node();
        var rule = f.level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING);
        boolean previous = rule.get();
        Cleanup.on(helper, () -> rule.set(previous, f.level.getServer()));
        rule.set(false, f.level.getServer());
        helper.onEachTick(() -> f.tick(node));
        helper.startSequence()
            .thenIdle(40)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    mortar.getEntityData().get(MortarEntity.FIRE_TIME),
                    0,
                    "mob-griefing refusal prevents preparation and launch");
                helper.assertValueEqual(
                    f.body.pack().count(ModItems.MORTAR_SHELL.get()),
                    3,
                    "permission refusal leaves real shells untouched");
                rule.set(true, f.level.getServer());
            })
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    !f.level
                    .getEntitiesOfClass(
                    MortarShellEntity.class, mortar.getBoundingBox().inflate(160))
                    .isEmpty(),
                    "the permitted native shot emits a real shell"))
            .thenSucceed();
    }

    private static void walkingStation(GameTestHelper helper, EntityType<? extends VehicleEntity> type,
        boolean obstructed, boolean sealed) {
        Scene f = new Scene(helper, true, 20, 18, true);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(f.level.getEntity(f.body.id()) == f.body,
            "the freshly ticketed approach arena exposes its resident"))
            .thenExecute(() -> {
                f.joinBody();
                walkingStationScene(f, type, obstructed, sealed);
            });
    }

    private static void walkingStationScene(Scene f, EntityType<? extends VehicleEntity> type,
        boolean obstructed, boolean sealed) {
        GameTestHelper helper = f.helper;
        VehicleEntity station = f.station(type, 6, 10);
        if (station instanceof ArtilleryEntity) {
            station.modifyGunData("Main", data -> data.changeAmmoConsumer(1, null));
        }
        var ammo = station instanceof MortarEntity ? ModItems.MORTAR_SHELL.get() : ModItems.LARGE_SHELL_HE.get();
        f.profile(ItemSpec.of(Items.BOW), ItemSpec.of(ammo), 3, 1);
        f.body.pack().insert(new ItemStack(Items.BOW));
        f.body.pack().insert(new ItemStack(ammo, 3));
        f.postStation();
        var posts = SentryPosts.of(f.colony);
        var post = posts.zone(f.level, f.zone).orElseThrow();
        var found = posts.stations(f.level, post);
        helper.assertFalse(found.isEmpty(), "the initial station is usable: automatic="
            + WarfareStations.automatic(station) + ", ammo=" + WarfareStations.ammunition(station)
            + ", speed=" + station.getDeltaMovement() + ", weapon=" + WarfareStations.weaponName(station));
        WeaponStations.Station weapon = found.getFirst();
        List<BlockPos> candidates = posts.stances(f.level, post);
        helper.assertTrue(candidates.size() > 1, "the hull offers alternative external approach cells");
        helper.assertFalse(candidates.stream().anyMatch(cell -> Reach.standingAt(f.body.position(), cell)),
            "the resident starts outside every external approach cell");
        for (BlockPos candidate : candidates) {
            AABB standing = f.body.getBoundingBox().move(Vec3.atBottomCenterOf(candidate)
                .subtract(f.body.position()));
            helper.assertFalse(standing.intersects(station.getBoundingBox()),
                "every approach cell clears the real hull with the resident's entire body");
            helper.assertTrue(standing.inflate(1, 0, 1).intersects(station.getBoundingBox()),
                "every approach cell belongs to the first full-body ring beside the hull");
        }
        if (obstructed) {
            VehicleEntity blocker = f.station(ModEntities.MK_42.get(), 10, 13);
            helper.assertTrue(candidates.stream().anyMatch(cell -> blocker.getBoundingBox().intersects(
                f.body.getBoundingBox().move(Vec3.atBottomCenterOf(cell).subtract(f.body.position())))),
                "the extra entity really occupies an originally valid approach cell");
            BlockPos enclosed = posts.stances(f.level, post).stream().min(Comparator.comparingDouble(
                cell -> f.body.position().distanceToSqr(Vec3.atBottomCenterOf(cell)))).orElseThrow();
            for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
                for (int y = 0; y < 3; y++) {
                    f.level.setBlockAndUpdate(enclosed.relative(direction).above(y), Blocks.STONE.defaultBlockState());
                }
            }
            helper.assertTrue(posts.stances(f.level, post).contains(enclosed),
                "the enclosed nearest cell still has block footing and headroom");
            var path = f.body.getNavigation().createPath(enclosed, 0);
            helper.assertTrue(path == null || !path.canReach(), "the nearest footing is unreachable");
            helper.assertTrue(posts.stances(f.level, post).stream().noneMatch(cell -> blocker.getBoundingBox()
                .intersects(f.body.getBoundingBox().move(Vec3.atBottomCenterOf(cell).subtract(f.body.position())))),
                "another native blocking entity also excludes occupied approach cells");
        }
        if (sealed) {
            approachWall(f, Blocks.STONE);
        }
        if (weapon.indirect()) {
            for (int x = 64; x < 96; x++) {
                for (int z = 0; z < 24; z++) {
                    for (int y = 1; y < 20; y++) {
                        f.block(new BlockPos(x, y, z), Blocks.STONE);
                    }
                }
            }
        }
        int targetX = weapon.indirect() ? 80 : 44;
        int targetY = weapon.indirect() ? 20 : 1;
        Zombie target = f.enemy(targetX, targetY, 10);
        target.setNoGravity(true);
        ResidentEntity scout = PersonBody.RESIDENT.get().create(f.level);
        scout.setNoAi(true);
        scout.setNoGravity(true);
        BlockPos observation = f.at(targetX - 50, targetY + 16, 23).block(f.level);
        scout.moveTo(observation.getX() + .5, observation.getY(), observation.getZ() + .5, -90, 0);
        f.level.addFreshEntity(scout);
        f.entities.add(scout);
        AtomicBoolean noRoute = new AtomicBoolean();
        helper.onEachTick(() -> {
            SpottedTargets.of(f.colony).report(scout, target);
            Bodies.of(f.body).flatMap(body -> body.balk()).filter(balk -> balk.why() == LaborRefusal.NO_WAY_THERE)
                .ifPresent(balk -> noRoute.set(true));
            if (WeaponStations.ownsSeat(f.body)) {
                helper.assertTrue(f.body.walkDist / .6 >= 4,
                    "the operator must really walk before acquiring a native seat");
            } else if (posts.stances(f.level, post).stream()
                .noneMatch(cell -> Reach.standingAt(f.body.position(), cell))) {
                helper.assertValueEqual(f.shots.get(), 0, "no shot before reaching the hull");
                helper.assertValueEqual(f.body.pack().count(ammo), 3, "approaching consumes no ammunition");
            }
        });
        var sequence = helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(f.level.getEntity(scout.id()) == scout,
                "the observer's freshly ticketed chunk exposes its resident"))
            .thenExecute(() -> {
                helper.assertTrue(f.colony.hold(PersonContent.ID, Claimed.Entity.of(scout)).isPresent(),
                    "the loaded forward observer joins the colony");
                scout.joinColony(f.colony.id());
            });
        if (sealed) {
            sequence.thenWaitUntil(() -> helper.assertTrue(noRoute.get(), "wait for real no-route feedback"))
                .thenExecute(() -> {
                    helper.assertFalse(f.body.isPassenger(), "an unreachable station never mounts its operator");
                    helper.assertValueEqual(f.shots.get(), 0, "a sealed approach cannot fire");
                    helper.assertValueEqual(f.body.pack().count(ammo), 3, "failed travel consumes no ammunition");
                    helper.assertTrue(PatrolStaffing.of(f.colony).members(f.zone).contains(f.body.id()),
                        "failed travel keeps the desired filled slot");
                    helper.assertTrue(noRoute.get(), "failed travel reports the real NO_WAY_THERE refusal");
                    approachWall(f, Blocks.AIR);
                });
        }
        sequence.thenWaitUntil(() -> helper.assertTrue(station instanceof MortarEntity
            ? !f.level.getEntitiesOfClass(MortarShellEntity.class, station.getBoundingBox().inflate(160)).isEmpty()
            : f.shots.get() > 0,
            "normal labour walks to the station and emits a native shot " + f.debug()))
            .thenExecute(() -> {
                helper.assertTrue(f.body.walkDist / .6 >= 4, "normal travel records at least four walked blocks");
                helper.assertTrue(f.body.pack().count(ammo) < 3, "native emission spends carried ammunition");
                if (station instanceof MortarEntity) {
                    helper.assertFalse(f.body.isPassenger(), "the native mortar operates from beside its hull");
                    helper.assertFalse(f.level.getEntitiesOfClass(MortarShellEntity.class,
                        station.getBoundingBox().inflate(160)).isEmpty(), "the mortar emits a physical shell");
                } else {
                    helper.assertTrue(f.body.getVehicle() == station && WeaponStations.ownsSeat(f.body),
                        "normal non-forced mounting owns the exact enrolled weapon's seat");
                }
            }).thenSucceed();
    }

    private static void approachWall(Scene f, Block block) {
        for (int x = 0; x <= 14; x++) {
            for (int z = 0; z < 24; z++) {
                if (x != 0 && x != 14 && z != 0 && z != 23) {
                    continue;
                }
                for (int y = 1; y <= 4; y++) {
                    f.block(new BlockPos(x, y, z), block);
                }
            }
        }
    }

    private static Worker worker(ResidentEntity body) {
        return worker(body, List.of());
    }

    private static Worker worker(ResidentEntity body, List<Node> route) {
        return TestWorker.of(body).spill(body::spawnAtLocation).route(route);
    }

    private static final class Scene {

        final GameTestHelper helper;
        final BlockPos origin;
        final UUID ticket = UUID.randomUUID();
        final List<ChunkPos> chunks = new ArrayList<>();
        final ServerLevel level;
        final Colony colony;
        final ResidentEntity body;
        final PatrolSupplies supplies;
        final Container store;
        final List<Entity> entities = new ArrayList<>();
        final List<WeaponSession> sessions = new ArrayList<>();
        final List<WeaponStations.Lease> leases = new ArrayList<>();
        final List<Node> nodes = new ArrayList<>();
        UUID zone;
        final AtomicInteger shots = new AtomicInteger();
        final Consumer<ShootEvent.Post> listener;
        final LaborFixture labor;
        boolean closed;

        Scene(GameTestHelper helper, boolean simulate) {
            this(helper, simulate, 3, 10, false);
        }

        Scene(GameTestHelper helper, boolean simulate, int startX, int startZ, boolean deferMembership) {
            this.helper = helper;
            level = helper.getLevel();
            origin = helper.absolutePos(BlockPos.ZERO).offset(0, 0, 512 + nextScene++ * 512);
            for (int x = deferMembership ? -4 : -1; x <= (deferMembership ? 12 : 8); x++) {
                for (int z = deferMembership ? -4 : -1; z <= (deferMembership ? 4 : 2); z++) {
                    var chunk = new ChunkPos(origin.offset(x * 16, 0, z * 16));
                    level.getChunk(chunk.x, chunk.z);
                    level.getChunkSource().addRegionTicket(TICKING, chunk, 2, ticket, true);
                    chunks.add(chunk);
                }
            }
            for (int x = 0; x < 96; x++) {
                for (int z = 0; z < 24; z++) {
                    block(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            colony = Colonies.mint(level.getServer());
            supplies = PatrolSupplies.of(colony);
            body = PersonBody.RESIDENT.get().create(level);
            body.setNoAi(!simulate);
            place(body, startX, startZ);
            level.addFreshEntity(body);
            if (!deferMembership) {
                joinBody();
            }
            block(new BlockPos(3, 1, 4), Blocks.BARREL);
            WorldPos source = at(3, 1, 4);
            store = Containers.at(level, source.block(level)).orElseThrow();
            colony.stores(WarfareStations.ID, level.dimension(), List.of(source));
            listener =
                event -> {
                    if (event.getShooter() == body) {
                        shots.incrementAndGet();
                    }
                };
            NeoForge.EVENT_BUS.addListener(listener);
            labor = simulate ? new LaborFixture(colony, level) : null;
            if (labor != null) {
                helper.onEachTick(labor::tick);
            }
            if (!deferMembership) {
                profile(ItemSpec.of(ModItems.GLOCK_17.get()), ItemSpec.of(ModItems.HANDGUN_AMMO.get()), 1, 1);
            }
            Cleanup.on(helper, this::close);
        }

        void joinBody() {
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
        }

        WorldPos at(int x, int y, int z) {
            return WorldPos.of(level, origin.offset(x, y, z));
        }

        void block(BlockPos at, Block block) {
            level.setBlockAndUpdate(origin.offset(at), block.defaultBlockState());
        }

        void place(Entity entity, int x, int z) {
            BlockPos pos = at(x, 1, z).block(level);
            entity.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, -90, 0);
        }

        Zombie enemy(int x, int z) {
            return enemy(x, 1, z);
        }

        Zombie enemy(int x, int y, int z) {
            Zombie enemy = EntityType.ZOMBIE.create(level);
            enemy.setNoAi(true);
            enemy.setGlowingTag(true);
            BlockPos spawn = at(x, y, z).block(level);
            enemy.moveTo(spawn.getX() + .5, spawn.getY(), spawn.getZ() + .5, -90, 0);
            enemy.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10000);
            enemy.setHealth(10000);
            level.addFreshEntity(enemy);
            entities.add(enemy);
            return enemy;
        }

        VehicleEntity station(EntityType<? extends VehicleEntity> type, int x, int z) {
            VehicleEntity vehicle = type.create(level);
            place(vehicle, x, z);
            if (vehicle instanceof Mk42Entity) {
                vehicle.modifyGunData("Main", data -> data.changeAmmoConsumer(1, null));
            }
            level.addFreshEntity(vehicle);
            entities.add(vehicle);
            return vehicle;
        }

        void profile(ItemSpec gun, ItemSpec ammo, int target, int threshold) {
            LoadoutProfiles profiles = LoadoutProfiles.of(colony);
            LoadoutProfile created = profiles.create("Warfare test");
            profiles.replace(
                new LoadoutProfile(
                created.id(),
                created.name(),
                List.of(
                LoadoutProfile.Entry.weapon(gun),
                LoadoutProfile.Entry.supply(ammo, target, threshold))));
            profiles.assign(List.of(body.id()), Optional.of(created.id()));
        }

        void postStation() {
            VehicleEntity station = entities.stream().filter(VehicleEntity.class::isInstance)
                .map(VehicleEntity.class::cast)
                .filter(vehicle -> vehicle.blockPosition().equals(at(6, 1, 10).block(level)))
                .findFirst().orElseThrow();
            zone = colony.hold(SentryPosts.STATION, Claimed.Entity.of(station)).orElseThrow().id();
            PatrolStaffing.of(colony).tick(level.getServer());
        }

        void unpost() {
            if (colony.claim(zone).filter(held -> held.owner().equals(SentryPosts.STATION)).isPresent()) {
                colony.release(zone, io.github.izakyl.folkways.core.colony.api.ClaimRelease.LET_GO);
                PatrolStaffing.of(colony).tick(level.getServer());
            } else {
                PatrolStaffing.of(colony).configure(level, zone, Set.of(), 0);
            }
            SentryPosts.of(colony).tick(level.getServer());
        }

        void guardArea() {
            if (LoadoutProfiles.of(colony).assigned(body.id()).isEmpty()) {
                profile(ItemSpec.of(Items.BOW), ItemSpec.of(ModItems.MORTAR_SHELL.get()), 3, 1);
                body.pack().insert(new ItemStack(Items.BOW));
            }
            Set<WorldPos> cells = new LinkedHashSet<>();
            for (int x = 3; x <= 7; x++) {
                for (int z = 8; z <= 12; z++) {
                    cells.add(at(x, 1, z));
                }
            }
            zone = colony.hold(SentryPosts.ZONE, new Claimed.Area(cells)).orElseThrow().id();
            PatrolStaffing.of(colony).configure(level, zone,
                Set.of(LoadoutProfiles.of(colony).assigned(body.id()).orElseThrow().id()), 1);
        }

        WeaponStations.Station found() {
            return SentryPosts.of(colony).stations(level, SentryPosts.of(colony).zone(level, zone).orElseThrow())
                .stream()
                .findFirst()
                .orElseThrow();
        }

        WeaponStations.Lease lease() {
            WeaponStations.Lease lease = WeaponStations.acquire(found(), worker(body)).orElseThrow();
            leases.add(lease);
            return lease;
        }

        SpottedTargets.Spot spot(Zombie target) {
            ResidentEntity scout = PersonBody.RESIDENT.get().create(level);
            place(scout, (int) (target.getX() - origin.getX()) - 8, 10);
            scout.setNoAi(true);
            level.addFreshEntity(scout);
            colony.hold(PersonContent.ID, Claimed.Entity.of(scout)).orElseThrow();
            scout.joinColony(colony.id());
            entities.add(scout);
            int x = (int) (scout.getX() - origin.getX());
            colony.hold(PatrolContent.BEAT, new Claimed.Line(List.of(at(3, 0, 20), at(5, 0, 20)))).orElseThrow();
            WarfarePatrolFixture.observe(colony, level, route -> worker(scout, route));
            helper.assertTrue(
                SpottedTargets.of(colony).get(body, target.getUUID()).isPresent(),
                "the distant route worker supplies the only genuine sight report");
            place(scout, 3, 20);
            return SpottedTargets.of(colony).get(body, target.getUUID()).orElseThrow();
        }

        Node node() {
            Node node = SentryPosts.of(colony).urges(worker(body), colony.view(level)).getFirst().take().get();
            nodes.add(node);
            if (labor == null && colony.claim(zone).orElseThrow().owner().equals(SentryPosts.STATION)) {
                BlockPos stance = node.spec().stances().cells().iterator().next().block(level);
                body.moveTo(stance.getX() + .5, stance.getY(), stance.getZ() + .5, -90, 0);
            }
            return node;
        }

        void tick(Node node) {
            ((Workload.Continuous) node.spec().workload()).holds(level, worker(body));
        }

        String debug() {
            return "pos="
                + body.position()
                + ", supply="
                + supplies.status(body.id())
                + ", pack="
                + body.pack().contents()
                + ", riding="
                + body.getVehicle()
                + ", store="
                + IntStream.range(0, store.getContainerSize())
                .mapToObj(slot -> store.getItem(slot).toString())
                .toList()
                + ", post="
                + (zone == null
                ? "none"
                : SentryPosts.of(colony)
                .status(level, body.id(), SentryPosts.of(colony).zone(level, zone).orElseThrow()));
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            NeoForge.EVENT_BUS.unregister(listener);
            leases.forEach(WeaponStations.Lease::close);
            SentryPosts.of(colony).close();
            sessions.forEach(WeaponSession::close);
            if (labor != null) {
                labor.close();
            }
            body.discard();
            entities.forEach(Entity::discard);
            colony.raze();
            level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(200)).forEach(Entity::discard);
            for (var chunk : chunks) {
                level.getChunkSource().removeRegionTicket(TICKING, chunk, 2, ticket, true);
            }
        }
    }
}
