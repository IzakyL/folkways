package io.github.izakyl.folkways.firearms.tacz;

import io.github.izakyl.folkways.firearms.FolkwaysFirearmsMod;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.init.ModItems;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.vanilla.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.vanilla.patrol.PatrolStaffing;
import io.github.izakyl.folkways.vanilla.patrol.SentryPosts;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysFirearmsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_firearms")
@PrefixGameTestTemplate(false)
public final class TaczSupplyGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TaczSupplyGameTests.class);
    }

    @GameTest(batch = "tacz_native", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void profileCollectsExactGunAndCaliberAndCountsLoadedRounds(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity body = scene.person(2);
        scene.store.setItem(0, TaczWeaponGameTests.gun("ak47", FireMode.AUTO, 6));
        scene.store.setItem(1, TaczWeaponGameTests.ammo("9mm", 40));
        scene.store.setItem(2, TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 2));
        scene.store.setItem(3, TaczWeaponGameTests.ammo("45acp", 40));
        scene.profile(body, 27, 5);
        scene.guard(body);
        scene.run();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.rounds(body) == 27,
                    "guard collects bounded finite stock including loaded rounds: "
                    + scene.supplies.status(body.id())))
            .thenExecute(
            () -> {
                helper.assertTrue(scene.reached, "stock only changes after actual arrival");
                helper.assertValueEqual(
                    scene.store.getItem(0).getCount(), 1, "same item with other GunId remains");
                helper.assertValueEqual(
                    scene.store.getItem(1).getCount(), 40, "same item with other AmmoId remains");
                helper.assertValueEqual(
                    scene.stockRounds(),
                    15,
                    "only twenty-five loose rounds withdrawn for the two loaded rounds");
                helper.assertTrue(
                    scene.supplies.keeps(body).stream()
                    .anyMatch(
                    amount ->
                        Goods.matches(
                            TaczWeaponGameTests.ammo("45acp", 1),
                            amount.spec())),
                    "future hauling retains the correct caliber");
            })
            .thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void twoGuardsCompeteForOneActualGunWithoutDuplicatingStock(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity first = scene.person(2), second = scene.person(4);
        scene.store.setItem(0, TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 0));
        scene.store.setItem(1, TaczWeaponGameTests.ammo("45acp", 6));
        for (ResidentEntity body : scene.people) {
            scene.profile(body, 27, 5);
            scene.guard(body);
        }
        scene.run();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.people.stream()
                    .mapToInt(
                    body ->
                        body.pack()
                            .count(
                            ModItems.MODERN_KINETIC_GUN
                            .get()))
                    .sum()
                    == 1,
                    "one resident reaches the finite gun"))
            .thenIdle(80)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.people.stream()
                    .mapToInt(body -> body.pack().count(ModItems.MODERN_KINETIC_GUN.get()))
                    .sum(),
                    1,
                    "competing collection retains one gun");
                helper.assertValueEqual(
                    scene.rounds(first) + scene.rounds(second) + scene.stockRounds(),
                    6,
                    "all rounds remain finite under contention");
                helper.assertValueEqual(scene.store.getItem(0).getCount(), 0, "one actual withdrawal");
            })
            .thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void profileThresholdIsStrictAndIncludesTheChamber(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity body = scene.person(2);
        body.pack().insert(TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 9));
        scene.profile(body, 27, 9);
        scene.store.setItem(0, TaczWeaponGameTests.ammo("45acp", 40));
        scene.run();
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            helper.assertValueEqual(scene.stockRounds(), 40, "threshold equality does not collect");
            helper.assertValueEqual(scene.rounds(body), 9, "the chamber counts toward the refill line");
            ItemStack carried = body.pack().getItem(0);
            ((IGun) carried.getItem()).setCurrentAmmoCount(carried, 7);
            body.pack().setChanged();
        }).thenWaitUntil(() -> helper.assertValueEqual(scene.rounds(body), 27,
            "below the line, ordinary personal supply refills a non-patrolling resident"))
            .thenExecute(() -> helper.assertValueEqual(scene.stockRounds(), 21,
                "only nineteen loose rounds are fetched for eight rounds already in the gun"))
            .thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void profileReplacementRevokesAmmunitionCollectionBeforeArrival(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity body = scene.person(2);
        body.pack().insert(TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 0));
        scene.profile(body, 27, 5);
        scene.store.setItem(0, TaczWeaponGameTests.ammo("45acp", 40));
        scene.guard(body);
        scene.run();
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    scene.supplies.status(body.id()).state() == PatrolSupplies.State.FETCHING,
                    "guard begins physical collection"))
            .thenExecute(() -> LoadoutProfiles.of(scene.colony).assign(List.of(body.id()), Optional.empty()))
            .thenIdle(50)
            .thenExecute(
            () -> {
                helper.assertValueEqual(
                    scene.stockRounds(), 40, "cancelled collection spends no stock");
                helper.assertValueEqual(scene.rounds(body), 0, "revocation grants no ammunition");
            })
            .thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100_000)
    public static void composedSentryRevokesQueuedFireWhenAllowedProfilesChange(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity body = scene.person(2);
        ResidentEntity replacement = scene.person(4);
        replacement.moveTo(replacement.getX(), replacement.getY(), replacement.getZ() + 4);
        body.pack().insert(TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 4));
        scene.profile(body, 4, 0);
        UUID profile = LoadoutProfiles.of(scene.colony).assigned(body.id()).orElseThrow().id();
        UUID replacementProfile = LoadoutProfiles.of(scene.colony).duplicate(profile).id();
        LoadoutProfiles.of(scene.colony).assign(List.of(replacement.id()), Optional.of(replacementProfile));
        UUID zone = scene.zone(body);
        PatrolStaffing staffing = PatrolStaffing.of(scene.colony);
        staffing.configure(helper.getLevel(), zone, Set.of(profile), 1);
        helper.assertValueEqual(scene.posts.residents(zone), List.of(body.id()), "the matching firearm resident fills");
        var target = EntityType.ZOMBIE.create(helper.getLevel());
        target.setNoAi(true);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        target.setHealth(1000);
        target.moveTo(body.getX() + 6, body.getY(), body.getZ());
        helper.getLevel().addFreshEntity(target);
        scene.target = target;
        scene.run();
        long started = System.currentTimeMillis();
        helper.onEachTick(() -> {
            if (System.currentTimeMillis() - started > 20_000) {
                helper.fail("composed sentry engagement deadline: rounds=" + scene.rounds(body));
            }
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(scene.rounds(body) <= 2 && target.getHealth() < 1000,
                "the composed operator physically fires multiple native shots");
        }).thenExecute(() -> {
            var queued = TaczDischargeGuard.capture(body, body.getMainHandItem(), () -> true);
            helper.assertTrue(queued.getAsBoolean(), "the current operator has a live discharge permission");
            int rounds = scene.rounds(body);
            staffing.configure(helper.getLevel(), zone, Set.of(replacementProfile), 1);
            helper.assertFalse(queued.getAsBoolean(), "changing allowed profiles revokes queued discharge");
            scene.posts.tick(helper.getLevel().getServer());
            helper.assertFalse(body.weaponDrawn(), "the old operator releases its native gun session");
            helper.assertValueEqual(
                body.pack().count(ModItems.MODERN_KINETIC_GUN.get()), 1, "the actual gun returns once");
            helper.assertValueEqual(scene.rounds(body), rounds, "revoked callbacks consume no ammunition");
            helper.assertValueEqual(scene.posts.residents(zone), List.of(replacement.id()),
                "only the replacement occupies the slot");
        }).thenSucceed();
    }

    static final class Scene {

        final GameTestHelper helper;
        final Colony colony;
        final PatrolSupplies supplies;
        final SentryPosts posts;
        final Container store;
        final WorldPos source;
        final List<ResidentEntity> people = new ArrayList<>();
        final Map<UUID, ChunkPos> tickets = new HashMap<>();
        final LaborFixture labor;
        Entity target;
        boolean running;
        boolean reached;
        boolean closed;

        Scene(GameTestHelper helper) {
            this.helper = helper;
            for (int x = 0; x < 23; x++) {
                for (int z = 0; z < 9; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            helper.setBlock(new BlockPos(18, 1, 3), Blocks.BARREL);
            source = at(18, 1, 3);
            store = Containers.at(helper.getLevel(), source.block(helper.getLevel())).orElseThrow();
            colony = Colonies.mint(helper.getLevel().getServer());
            supplies = PatrolSupplies.of(colony);
            posts = SentryPosts.of(colony);
            colony.stores(
                ResourceLocation.fromNamespaceAndPath("folkways_firearms", "tacz_test"),
                helper.getLevel().dimension(),
                List.of(source));
            labor = new LaborFixture(colony, helper.getLevel());
            helper.onEachTick(
                () -> {
                    if (!running) {
                        return;
                    }
                    int before =
                        store.countItem(ModItems.AMMO.get())
                        + store.countItem(ModItems.MODERN_KINETIC_GUN.get());
                    labor.tick();
                    if (store.countItem(ModItems.AMMO.get()) + store.countItem(ModItems.MODERN_KINETIC_GUN.get())
                        < before) {
                        helper.assertTrue(
                            people.stream()
                            .anyMatch(
                            body ->
                                // A planned draw is made from a stance the body stands within IN_THE_CELL of.
                                body.position().distanceTo(Vec3.atCenterOf(source.block(helper.getLevel())))
                                    <= Reach.PLAYER_BLOCK_REACH + Reach.IN_THE_CELL),
                            "only on-site collection moves real stock");
                        reached = true;
                    }
                });
            Cleanup.on(helper, this::close);
        }

        WorldPos at(int x, int y, int z) {
            return WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z)));
        }

        ResidentEntity person(int x) {
            ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel());
            BlockPos feet = at(x, 1, 3).block(helper.getLevel());
            body.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            tickets.put(body.getUUID(), body.chunkPosition());
            helper.getLevel()
                .getChunkSource()
                .addRegionTicket(TaczWeaponGameTests.TICKING, body.chunkPosition(), 2, body.getUUID());
            helper.getLevel().addFreshEntity(body);
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            people.add(body);
            return body;
        }

        void profile(ResidentEntity body, int target, int threshold) {
            var profiles = LoadoutProfiles.of(colony);
            var profile = profiles.create("Pistols");
            profile =
                profiles.add(
                profile.id(),
                LoadoutProfile.Entry.weapon(TaczGuns.weapon(TaczWeaponGameTests.id("m1911"))));
            var entries = new ArrayList<>(profile.entries());
            var ammo = entries.get(1);
            entries.set(
                1, new LoadoutProfile.Entry(ammo.id(), ammo.kind(), ammo.items(), ammo.slot(), target, threshold));
            profiles.replace(new LoadoutProfile(profile.id(), profile.name(), entries));
            profiles.assign(List.of(body.id()), Optional.of(profile.id()));
        }

        void guard(ResidentEntity body) {
            PatrolStaffing.of(colony).configure(helper.getLevel(), zone(body),
                Set.of(LoadoutProfiles.of(colony).assigned(body.id()).orElseThrow().id()), 1);
        }

        UUID zone(ResidentEntity body) {
            Set<WorldPos> cells = new LinkedHashSet<>();
            for (int x = 0; x < 2; x++) {
                for (int z = 0; z < 2; z++) {
                    cells.add(WorldPos.of(helper.getLevel(), body.blockPosition().below().offset(x, 0, z)));
                }
            }
            return colony.hold(SentryPosts.ZONE, new Claimed.Area(cells)).orElseThrow().id();
        }

        void run() {
            running = true;
        }

        int stockRounds() {
            return (int) Goods.countIn(store, TaczGuns.ammunition(TaczWeaponGameTests.id("45acp")));
        }

        int rounds(ResidentEntity body) {
            return (int) Goods.countIn(body.pack(), TaczGuns.ammunition(TaczWeaponGameTests.id("45acp")))
                + LoadoutRegistry.carried(body).stream()
                .filter(TaczGuns::supported)
                .mapToInt(TaczGuns::loaded)
                .sum();
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            running = false;
            labor.close();
            people.forEach(
                body -> {
                    helper.getLevel()
                        .getChunkSource()
                        .removeRegionTicket(
                        TaczWeaponGameTests.TICKING, tickets.get(body.getUUID()), 2, body.getUUID());
                    body.discard();
                });
            if (target != null) {
                target.discard();
            }
            colony.raze();
        }
    }
}
