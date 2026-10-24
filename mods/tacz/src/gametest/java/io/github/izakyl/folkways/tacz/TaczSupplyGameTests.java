package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.init.ModItems;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysTaczMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class TaczSupplyGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(TaczSupplyGameTests.class); }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 700)
    public static void guardWalksForExactGunAndCaliberAndCountsLoadedRounds(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity body = scene.person(2);
        scene.store.setItem(0, TaczWeaponGameTests.gun("ak47", FireMode.AUTO, 6));
        scene.store.setItem(1, TaczWeaponGameTests.ammo("9mm", 40));
        scene.store.setItem(2, TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 2));
        scene.store.setItem(3, TaczWeaponGameTests.ammo("45acp", 40));
        scene.supplies.loadout(body, TaczLoadouts.selection(TaczWeaponGameTests.id("m1911")));
        scene.guard(body); scene.run();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.rounds(body) == 32,
                "guard collects bounded finite stock including loaded rounds: " + scene.supplies.status(body.id())))
            .thenExecute(() -> {
                helper.assertTrue(scene.reached, "stock only changes after actual arrival");
                helper.assertValueEqual(scene.store.getItem(0).getCount(), 1, "same item with other GunId remains");
                helper.assertValueEqual(scene.store.getItem(1).getCount(), 40, "same item with other AmmoId remains");
                helper.assertValueEqual(scene.stockRounds(), 10, "only thirty loose rounds withdrawn for the two loaded rounds");
                helper.assertTrue(scene.supplies.keeps(body).stream().anyMatch(amount -> Goods.matches(TaczWeaponGameTests.ammo("45acp", 1), amount.spec())),
                    "future hauling retains the correct caliber");
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 700)
    public static void twoGuardsCompeteForOneActualGunWithoutDuplicatingStock(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity first = scene.person(2), second = scene.person(4);
        scene.store.setItem(0, TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 0));
        scene.store.setItem(1, TaczWeaponGameTests.ammo("45acp", 6));
        for (ResidentEntity body : scene.people) {
            scene.supplies.loadout(body, TaczLoadouts.selection(TaczWeaponGameTests.id("m1911"))); scene.guard(body);
        }
        scene.run();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.people.stream().mapToInt(body -> body.pack().count(ModItems.MODERN_KINETIC_GUN.get())).sum() == 1,
                "one resident reaches the finite gun"))
            .thenIdle(80).thenExecute(() -> {
                helper.assertValueEqual(scene.people.stream().mapToInt(body -> body.pack().count(ModItems.MODERN_KINETIC_GUN.get())).sum(), 1,
                    "competing collection retains one gun");
                helper.assertValueEqual(scene.rounds(first) + scene.rounds(second) + scene.stockRounds(), 6, "all rounds remain finite under contention");
                helper.assertValueEqual(scene.store.getItem(0).getCount(), 0, "one actual withdrawal");
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large")
    public static void manualGunInfersAmmoAndLowWaterIncludesTheChamber(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity body = scene.person(2); body.setNoAi(true);
        ItemStack actual = TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 9); body.pack().insert(actual);
        scene.store.setItem(0, TaczWeaponGameTests.ammo("45acp", 40)); scene.guard(body);
        var order = scene.commands.current(body.id());
        helper.assertTrue(scene.supplies.urges(TaczWeaponGameTests.worker(body), order, 0, 0).isEmpty(), "nine total rounds stay above low water");
        ItemStack carried = body.pack().getItem(0);
        IGun gun = (IGun) carried.getItem(); gun.setCurrentAmmoCount(carried, 7);
        var urges = scene.supplies.urges(TaczWeaponGameTests.worker(body), order, 0, 0);
        helper.assertValueEqual(urges.size(), 1, "eight total rounds trigger ammunition-only inference");
        helper.assertValueEqual(scene.stockRounds(), 40, "an urge does not teleport stock");
        helper.assertTrue(scene.supplies.preference(body.id()).isEmpty(), "manual equipment does not persist an explicit selection");
        helper.succeed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 300)
    public static void retreatRevokesAmmunitionCollectionBeforeArrival(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity body = scene.person(2);
        body.pack().insert(TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 0));
        scene.store.setItem(0, TaczWeaponGameTests.ammo("45acp", 40)); scene.guard(body); scene.run();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.supplies.status(body.id()).state() == PatrolSupplies.State.FETCHING,
                "guard begins physical collection"))
            .thenExecute(() -> scene.commands.retreat(body, scene.at(2, 1, 3)))
            .thenIdle(50).thenExecute(() -> {
                helper.assertValueEqual(scene.stockRounds(), 40, "cancelled collection spends no stock");
                helper.assertValueEqual(scene.rounds(body), 0, "retreat grants no ammunition");
            }).thenSucceed();
    }

    @GameTest(batch = "tacz_native", template = "empty_large", timeoutTicks = 100_000)
    public static void workRunnerKeepsTheGunSessionThroughAnEntireEngagement(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        ResidentEntity body = scene.person(2); body.pack().insert(TaczWeaponGameTests.gun("m1911", FireMode.SEMI, 4));
        scene.guard(body);
        var target = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());
        target.setNoAi(true); target.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.CARVED_PUMPKIN));
        target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000); target.setHealth(1000);
        target.moveTo(body.getX() + 6, body.getY(), body.getZ()); helper.getLevel().addFreshEntity(target);
        scene.target = target; scene.run();
        long started = System.currentTimeMillis();
        helper.onEachTick(() -> {
            if (System.currentTimeMillis() - started > 20_000) {
                helper.fail("engagement deadline: rounds=" + scene.rounds(body) + ", health=" + body.getHealth()
                    + ", target=" + target.getHealth() + ", hand=" + body.getMainHandItem()
                    + ", order=" + scene.commands.current(body.id()).map(order -> order.mode() + "/" + order.status()));
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(scene.rounds(body) <= 2 && target.getHealth() < 1000,
                "BodyRunner advances more than one native shot without closing after the first"))
            .thenExecute(() -> {
                scene.commands.retreat(body, WorldSpaces.at(body));
                helper.assertFalse(body.hasWeaponSession(), "retreat closes the continuing engagement immediately");
                helper.assertValueEqual(body.pack().count(ModItems.MODERN_KINETIC_GUN.get()), 1, "actual gun returns once");
            }).thenSucceed();
    }

    static final class Scene implements GameTestListener {
        final GameTestHelper helper;
        final Colony colony;
        final PatrolSupplies supplies;
        final ResidentCommands commands;
        final Container store;
        final WorldPos source;
        final List<ResidentEntity> people = new ArrayList<>();
        final java.util.Map<java.util.UUID, net.minecraft.world.level.ChunkPos> tickets = new java.util.HashMap<>();
        final LaborFixture labor;
        net.minecraft.world.entity.Entity target;
        boolean running;
        boolean reached;
        boolean closed;
        Scene(GameTestHelper helper) {
            this.helper = helper;
            for (int x = 0; x < 23; x++) { for (int z = 0; z < 9; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); } }
            helper.setBlock(new BlockPos(18, 1, 3), Blocks.BARREL);
            source = at(18, 1, 3); store = Containers.at(helper.getLevel(), source.block(helper.getLevel())).orElseThrow();
            colony = Colonies.mint(helper.getLevel().getServer()); supplies = PatrolSupplies.of(colony); commands = ResidentCommands.of(colony);
            colony.stores(ResourceLocation.fromNamespaceAndPath("folkways", "tacz_test"), helper.getLevel().dimension(), List.of(source));
            labor = new LaborFixture(colony, helper.getLevel());
            helper.onEachTick(() -> {
                if (!running) { return; }
                int before = store.countItem(ModItems.AMMO.get()) + store.countItem(ModItems.MODERN_KINETIC_GUN.get());
                labor.tick();
                if (store.countItem(ModItems.AMMO.get()) + store.countItem(ModItems.MODERN_KINETIC_GUN.get()) < before) {
                    helper.assertTrue(people.stream().anyMatch(body -> Reach.withinReach(body.blockPosition(), source.block(helper.getLevel()))), "only on-site collection moves real stock");
                    reached = true;
                }
            });
            helper.testInfo.addListener(this);
        }
        WorldPos at(int x, int y, int z) { return WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(x, y, z))); }
        ResidentEntity person(int x) {
            ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel()); BlockPos feet = at(x, 1, 3).block(helper.getLevel());
            body.moveTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
            tickets.put(body.getUUID(), body.chunkPosition());
            helper.getLevel().getChunkSource().addRegionTicket(TaczWeaponGameTests.TICKING, body.chunkPosition(), 2, body.getUUID());
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow(); body.joinColony(colony.id()); helper.getLevel().addFreshEntity(body); people.add(body);
            return body;
        }
        void guard(ResidentEntity body) { commands.guard(body, WorldSpaces.at(body), new Vec3(1, 0, 0)); }
        void run() { running = true; }
        int stockRounds() { return (int) Goods.countIn(store, TaczGuns.ammunition(TaczWeaponGameTests.id("45acp"))); }
        int rounds(ResidentEntity body) {
            return (int) Goods.countIn(body.pack(), TaczGuns.ammunition(TaczWeaponGameTests.id("45acp")))
                + LoadoutRegistry.carried(body).stream().filter(TaczGuns::supported).mapToInt(TaczGuns::loaded).sum();
        }
        void close() {
            if (closed) { return; } closed = true; running = false;
            labor.close(); people.forEach(body -> {
                helper.getLevel().getChunkSource().removeRegionTicket(TaczWeaponGameTests.TICKING, tickets.get(body.getUUID()), 2, body.getUUID());
                body.discard();
            }); if (target != null) { target.discard(); } colony.raze();
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
