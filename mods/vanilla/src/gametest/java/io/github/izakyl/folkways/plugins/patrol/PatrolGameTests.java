package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.inventory.InventoryObservations;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.DefenseControl;
import io.github.izakyl.folkways.plugins.combat.SpottedTargets;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PatrolGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PatrolGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 550)
    public static void ordinaryPatrolDutyCollectsFiniteSuppliesWithoutAManualOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 23, 9);
        helper.setBlock(new BlockPos(18, 1, 3), Blocks.BARREL);
        Colony colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        place(helper, body, new BlockPos(2, 1, 3));
        colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        WorldPos source = WorldPos.of(level, helper.absolutePos(new BlockPos(18, 1, 3)));
        Container store = Containers.at(level, source.block(level)).orElseThrow();
        colony.stores(
            ResourceLocation.fromNamespaceAndPath("folkways", "test_duty_supply"),
            level.dimension(),
            List.of(source));
        store.setItem(0, new ItemStack(Items.BOW));
        store.setItem(1, new ItemStack(Items.ARROW, 16));
        arm(colony, body, "Bow", ItemSpec.of(Items.BOW));
        colony.service(PatrolContent.ID, PatrolPresence.class)
            .orElseThrow()
            .watched(body.id(), level.getGameTime(), 1000);
        var simulation = new LaborFixture(colony, level);
        helper.onEachTick(simulation::tick);
        Cleanup.on(helper, () -> {
            simulation.close();
            body.discard();
            colony.raze();
        });
        helper.startSequence()
            .thenWaitUntil(
            () ->
                helper.assertTrue(
                    body.pack().count(Items.BOW) == 1 && body.pack().count(Items.ARROW) == 8,
                    "ordinary patrol duty obtains one real bow and eight arrows through the existing runner"))
            .thenExecute(
            () -> {
                helper.assertTrue(store.getItem(0).isEmpty(), "the real bow leaves storage once");
                helper.assertValueEqual(
                    store.getItem(1).getCount(), 8, "half the finite ammunition remains in storage");
                helper.assertTrue(
                    DefenseControl.of(colony).current(body.id()).isEmpty(),
                    "autonomous duty never creates a manual order");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty")
    public static void martialAndStalwartChangeActualHostileDamage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Body guard = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        place(helper, guard.mob(), new BlockPos(1, 1, 1));
        place(helper, zombie, new BlockPos(2, 1, 1));
        guard.mob().setNoAi(true);
        zombie.setNoAi(true);
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(0);
        try {
            grant(guard, "patrolling_martial", "patrolling_stalwart");
            float before = zombie.getHealth();
            zombie.hurt(level.damageSources().mobAttack(guard.mob()), 4);
            helper.assertTrue(Math.abs(before - zombie.getHealth() - 5.4) < .01,
                "martial rank three increases a real hostile hit by thirty-five percent");
            before = guard.mob().getHealth();
            guard.mob().hurt(level.damageSources().mobAttack(zombie), 4);
            helper.assertTrue(Math.abs(before - guard.mob().getHealth() - 3.2) < .01,
                "stalwart rank three reduces a real hostile hit by twenty percent");
        } finally { guard.mob().discard(); zombie.discard(); }
        helper.succeed();
    }

    @GameTest(template = "sight_range")
    public static void vigilanceFindsDistantThreatsAndReportsWithoutCreatingMeleeHunters(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 40, 4);
        Colony colony = Colonies.mint(level.getServer());
        Body scout = PersonBody.RESIDENT.get().create(level);
        Body peer = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        place(helper, scout.mob(), new BlockPos(1, 1, 1));
        place(helper, peer.mob(), new BlockPos(1, 1, 2));
        place(helper, zombie, new BlockPos(35, 1, 1));
        zombie.setGlowingTag(true);
        colony.hold(PersonContent.ID, Held.Entity.of(scout.mob())).orElseThrow();
        colony.hold(PersonContent.ID, Held.Entity.of(peer.mob())).orElseThrow();
        scout.joinColony(colony.id());
        peer.joinColony(colony.id());
        arm(colony, scout, "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
        arm(colony, peer, "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
        scout.mob().setNoAi(true); peer.mob().setNoAi(true); zombie.setNoAi(true);
        try {
            grant(scout, "patrolling_alert");
            helper.assertValueEqual((int) scout.effects().value(PatrolContent.ALERT_RANGE), 6,
                "the scout has all three vigilance ranks");
            helper.assertTrue(scout.mob().hasLineOfSight(zombie),
                "the thirty-four-block sight line lies entirely inside the reserved test arena");
            PatrolPresence patrol = new PatrolPresence(colony);
            patrol.watched(scout.id(), level.getGameTime());
            patrol.watched(peer.id(), level.getGameTime());
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            scout.pack().insert(sword.copy());
            peer.pack().insert(sword.copy());
            InventoryObservations.publish(colony, level);
            helper.assertTrue(patrol.urges(worker(peer, sword), colony.view(level)).isEmpty(),
                "ordinary patroller cannot independently discover a threat thirty-four blocks away");
            helper.assertFalse(patrol.urges(worker(scout, sword), colony.view(level)).isEmpty(),
                "vigilance expands actual discovery range");
            helper.assertTrue(patrol.urges(worker(peer, sword), colony.view(level)).isEmpty(),
                "shared spotting does not turn a melee patroller into a hunter beyond its sight");
            helper.assertFalse(SpottedTargets.of(colony).targets(peer.mob()).isEmpty(),
                "the colony retains the scout's genuine observation");
        } finally {
            scout.mob().discard(); peer.mob().discard(); zombie.discard(); colony.raze();
        }
        helper.succeed();
    }

    private static void grant(Body body, String... ids) {
        net.minecraft.nbt.CompoundTag saved = body.mob().saveWithoutId(new net.minecraft.nbt.CompoundTag());
        var attachments = saved.getCompound("neoforge:attachments");
        var state = attachments.getCompound("folkways:body_state");
        var perks = state.getCompound("Perks");
        var vocations = perks.getCompound("vocations");
        var trade = new net.minecraft.nbt.CompoundTag();
        var owned = new net.minecraft.nbt.ListTag();
        for (String id : ids) {
            var entry = new net.minecraft.nbt.CompoundTag();
            entry.putString("id", id); entry.putInt("rank", 3); owned.add(entry);
        }
        trade.put("owned", owned); trade.putInt("level", 6); trade.putInt("xp", 2400);
        vocations.put(PatrolContent.VOCATION.toString(), trade);
        perks.put("vocations", vocations); state.put("Perks", perks);
        attachments.put("folkways:body_state", state); saved.put("neoforge:attachments", attachments);
        body.mob().load(saved);
    }

    @GameTest(template = "empty")
    public static void reassuringWardsUseTheStrongestLivingCoverage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stop = helper.absolutePos(new BlockPos(1, 1, 1));
        long now = level.getGameTime();
        try {
            Wards.ward(level, stop, now + 100, 6, 3);
            Wards.ward(level, stop, now + 50, 3, 10);
            helper.assertValueEqual(Wards.comfort(level, stop), 10,
                "overlapping reassurance uses strongest bonus, not sum");
            helper.assertValueEqual(Wards.comfort(level, stop.offset(5, 0, 0)), 3,
                "the stronger but smaller ward cannot reassure outside its own range");
            helper.assertValueEqual(Wards.comfort(level, stop.offset(7, 0, 0)), 0,
                "reassurance cannot reach beyond protection");
        } finally { Wards.stopped(null); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWardedStopKeepsNaturalMonstersFromSpawningRoundIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stop = helper.absolutePos(new BlockPos(1, 1, 1));
        int radius = FolkwaysVanillaConfig.patrolWardRadius();
        long now = level.getGameTime();
        Wards.ward(level, stop, now + 100);
        try {
            helper.assertTrue(Wards.warded(level, stop.offset(radius - 1, 0, 0)), "a stop wards what is in its reach");
            helper.assertFalse(Wards.warded(level, stop.offset(radius + 1, 0, 0)), "a stop wards nothing past its reach");
            helper.assertFalse(Wards.warded(level, stop.above(radius + 1)), "a stop wards nothing far above it");

            Zombie zombie = EntityType.ZOMBIE.create(level);
            zombie.moveTo(stop.getX() + 2.5, stop.getY(), stop.getZ() + 0.5);
            MobSpawnEvent.PositionCheck natural = new MobSpawnEvent.PositionCheck(zombie, level, MobSpawnType.NATURAL, null);
            Wards.positionCheck(natural);
            helper.assertValueEqual(natural.getResult(), MobSpawnEvent.PositionCheck.Result.FAIL,
                "a monster spawning of its own accord in a ward");
            MobSpawnEvent.PositionCheck spawner = new MobSpawnEvent.PositionCheck(zombie, level, MobSpawnType.SPAWNER, null);
            Wards.positionCheck(spawner);
            helper.assertValueEqual(spawner.getResult(), MobSpawnEvent.PositionCheck.Result.DEFAULT,
                "a monster from a spawner in a ward");

            Wards.ward(level, stop, now - 1);
            helper.assertTrue(Wards.until(level, stop) > now, "a later ward is never cut short by an earlier one");
        } finally {
            Wards.stopped(null);
        }
        helper.assertFalse(Wards.warded(level, stop), "wards are forgotten when the server stops");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stopsAreLaidAlongADrawnRouteNoFurtherApartThanAWardReaches(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 0; x < 8; x++) {
            helper.setBlock(new BlockPos(x, 0, 0), Blocks.STONE);
        }
        for (int z = 0; z < 8; z++) {
            helper.setBlock(new BlockPos(7, 0, z), Blocks.STONE);
        }
        BlockPos start = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos corner = helper.absolutePos(new BlockPos(7, 0, 0));
        BlockPos end = helper.absolutePos(new BlockPos(7, 0, 7));
        List<Stop> stops = Beats.along(level, List.of(start, corner, end));
        helper.assertTrue(stops.size() >= 3, "a route with a corner stops at its start, corner and end; got " + stops);
        helper.assertValueEqual(stops.getFirst().at().cell(), start.above(), "the first stop, on the start");
        helper.assertValueEqual(stops.getLast().at().cell(), end.above(), "the last stop, on the end");
        int reach = Math.max(4, FolkwaysVanillaConfig.patrolWardRadius());
        for (int at = 1; at < stops.size(); at++) {
            double apart = Math.sqrt(stops.get(at - 1).at().cell().distSqr(stops.get(at).at().cell()));
            helper.assertTrue(apart <= reach + 1, "neighbouring stops " + apart + " apart, past a ward's reach");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLapWalksTheRouteInOrderByOnePatrollerAndTheNextComesBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Stop> stops = List.of(
            new Stop(WorldPos.of(level, helper.absolutePos(new BlockPos(0, 1, 0))), Stances.WHEREVER),
            new Stop(WorldPos.of(level, helper.absolutePos(new BlockPos(3, 1, 0))), Stances.WHEREVER),
            new Stop(WorldPos.of(level, helper.absolutePos(new BlockPos(6, 1, 0))), Stances.WHEREVER));
        Colony colony = Colonies.mint(level.getServer());
        try {
            PatrolPresence patrol = new PatrolPresence(colony);
            UUID route = UUID.randomUUID();
            Grown out = patrol.round(route, 0, stops);
            Grown back = patrol.round(route, 1, stops);
            helper.assertTrue(out.workerGroups().size() == 1 && out.workerGroups().getFirst().size() == 3,
                "one patroller walks the whole lap");
            helper.assertValueEqual(focus(out, 0), stops.getFirst().at(), "the first lap setting out from the start");
            helper.assertValueEqual(focus(back, 0), stops.getLast().at(), "the next lap setting out from the end");
            helper.assertFalse(out.nodes().getFirst().id().equals(back.nodes().getFirst().id()),
                "each lap is new work, not the last one offered again");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    private static WorldPos focus(Grown lap, int at) {
        return lap.nodes().get(at).spec().site().where();
    }

    @GameTest(template = "empty")
    public static void onlyAnArmedPatrollerOnDutyGoesForAMonster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 6, 3);
        Colony colony = Colonies.mint(level.getServer());
        Body guard = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        place(helper, guard.mob(), new BlockPos(1, 1, 1));
        place(helper, zombie, new BlockPos(4, 1, 1));
        zombie.setNoAi(true);
        try {
            PatrolPresence patrol = new PatrolPresence(colony);
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            helper.assertTrue(patrol.urges(worker(guard, sword), colony.view(level)).isEmpty(),
                "a resident who has not stood watch is not on patrol");

            patrol.watched(guard.id(), level.getGameTime());
            helper.assertFalse(patrol.urges(worker(guard, ItemStack.EMPTY), colony.view(level)).isEmpty(),
                "an unprofiled patroller attempts combat without probing inventory");
            List<Urge> urges = patrol.urges(worker(guard, sword), colony.view(level));
            helper.assertTrue(
                urges.size() == 1
                && urges.get(0)
                .id()
                .equals(
                ResourceLocation.fromNamespaceAndPath(
                PatrolPresence.ENGAGE.getNamespace(),
                PatrolPresence.ENGAGE.getPath() + "/" + zombie.getUUID())),
                "an armed resident on patrol goes for the zombie beside it");
            helper.assertTrue(urges.get(0).weight() > Urge.DIRE && urges.get(0).preempts(),
                "a patroller at full health stands its ground over fleeing");
        } finally {
            guard.mob().discard();
            zombie.discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStrikeHitsWithTheWeaponInHand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        Body guard = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        // Prevent daylight fire from starting a damage cooldown during the equipment ticks.
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        place(helper, guard.mob(), new BlockPos(1, 1, 1));
        place(helper, zombie, new BlockPos(2, 1, 1));
        zombie.setNoAi(true);
        guard.pack().insert(new ItemStack(Items.IRON_SWORD));
        Worker actual = worker(guard, guard.pack());
        StrikeNode strike = new StrikeNode(level, zombie, Stances.WHEREVER);
        var attack = (Workload.Continuous) strike.spec().workload();
        helper.onEachTick(() -> attack.holds(level, actual));
        helper.runAfterDelay(15, () -> {
            try {
                helper.assertTrue(zombie.getMaxHealth() - zombie.getHealth() >= 5.0F,
                    "an actual iron sword strikes for its own damage, not a bare fist's");
                strike.released(level, actual, Ending.DONE);
                helper.assertValueEqual(guard.pack().count(Items.IRON_SWORD), 1, "the actual sword returns once");
            } finally {
                guard.mob().discard();
                zombie.discard();
                colony.raze();
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void anArmedResidentDefendsItselfOffDutyAndYieldsWhenWounded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 6, 3);
        Colony colony = Colonies.mint(level.getServer());
        Body guard = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        place(helper, guard.mob(), new BlockPos(1, 1, 1));
        place(helper, zombie, new BlockPos(3, 1, 1));
        guard.mob().setNoAi(true);
        zombie.setNoAi(true);
        PatrolPresence patrol = new PatrolPresence(colony);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        helper.assertTrue(
            patrol.urges(worker(guard, sword), colony.view(level)).isEmpty(),
            "an off-duty resident does not seek out an unengaged monster");
        guard.mob().hurt(level.damageSources().mobAttack(zombie), 1);
        helper.assertTrue(
            !patrol.urges(worker(guard, ItemStack.EMPTY), colony.view(level)).isEmpty(),
            "an unprofiled resident attempts defence without probing inventory");
        guard.pack().insert(sword);
        Worker actual = worker(guard, guard.pack());
        List<Urge> defence = patrol.urges(actual, colony.view(level));
        helper.assertTrue(
            defence.size() == 1 && defence.getFirst().weight() > Urge.DIRE,
            "an armed healthy resident may stand its ground against its attacker off duty");
        Node strike = defence.getFirst().take().get();
        var attack = (Workload.Continuous) strike.spec().workload();
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        helper.onEachTick(() -> attack.holds(level, actual));
        helper.runAfterDelay(
            15,
            () -> {
                try {
                    helper.assertTrue(
                        zombie.getHealth() < zombie.getMaxHealth(), "self-defence reuses a real patrol strike");
                    strike.released(level, actual, Ending.DONE);
                    guard.mob().setHealth(guard.mob().getMaxHealth() * .25F);
                    helper.assertTrue(
                        patrol.urges(actual, colony.view(level)).getFirst().weight() < Urge.DIRE,
                        "a wounded defender yields to the existing fear urge");
                } finally {
                    guard.mob().discard();
                    zombie.discard();
                    colony.raze();
                }
                helper.succeed();
            });
    }

    @GameTest(template = "empty")
    public static void patrolDoesNotProvokeANeutralEnderman(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floor(helper, 6, 3);
        Colony colony = Colonies.mint(level.getServer());
        Body guard = PersonBody.RESIDENT.get().create(level);
        EnderMan enderman = EntityType.ENDERMAN.create(level);
        place(helper, guard.mob(), new BlockPos(1, 1, 1));
        place(helper, enderman, new BlockPos(4, 1, 1));
        enderman.setNoAi(true);
        try {
            PatrolPresence patrol = new PatrolPresence(colony);
            patrol.watched(guard.id(), level.getGameTime());
            Worker armed = worker(guard, new ItemStack(Items.IRON_SWORD));
            helper.assertTrue(patrol.urges(armed, colony.view(level)).isEmpty(), "neutral enderman is left alone");
            enderman.setTarget(guard.mob());
            helper.assertFalse(
                patrol.urges(armed, colony.view(level)).isEmpty(),
                "an enderman that is already attacking may be opposed");
            enderman.setTarget(null);
            enderman.stopBeingAngry();
            float before = enderman.getHealth();
            new StrikeNode(level, enderman, Stances.WHEREVER).commit(level, armed);
            helper.assertValueEqual(enderman.getHealth(), before, "a queued strike rechecks restored neutrality");
        } finally {
            guard.mob().discard();
            enderman.discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStrikeCannotHitThroughASolidWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        Body guard = PersonBody.RESIDENT.get().create(level);
        Zombie zombie = EntityType.ZOMBIE.create(level);
        place(helper, guard.mob(), new BlockPos(1, 1, 1));
        place(helper, zombie, new BlockPos(3, 1, 1));
        guard.mob().setNoAi(true);
        zombie.setNoAi(true);
        try {
            helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
            guard.presentHeldItem(new ItemStack(Items.IRON_SWORD));
            float before = zombie.getHealth();
            new StrikeNode(level, zombie, Stances.WHEREVER).commit(level, worker(guard, guard.mob().getMainHandItem()));
            helper.assertValueEqual(zombie.getHealth(), before, "a solid wall stops the strike at commit time");
        } finally {
            guard.mob().discard();
            zombie.discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLowerRankCannotRenewAHigherRanksRadius(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stop = helper.absolutePos(new BlockPos(1, 100, 1));
        int radius = FolkwaysVanillaConfig.patrolWardRadius();
        long now = level.getGameTime();
        Wards.ward(level, stop, now + 5, radius + 3);
        Wards.ward(level, stop, now + 100, radius);
        helper.assertTrue(Wards.warded(level, stop.east(radius + 1)), "higher rank's original ward still applies");
        helper.runAfterDelay(6, () -> {
            Wards.ward(level, stop, level.getGameTime() + 100, radius);
            helper.assertFalse(Wards.warded(level, stop.east(radius + 1)), "low-rank renewal cannot inherit the expired larger radius");
            helper.assertTrue(Wards.warded(level, stop.east(radius - 1)), "ordinary coverage remains");
            helper.succeed();
        });
    }

    private static void floor(GameTestHelper helper, int width, int depth) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    private static void place(GameTestHelper helper, Mob mob, BlockPos relative) {
        BlockPos at = helper.absolutePos(relative);
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        helper.getLevel().addFreshEntity(mob);
    }

    private static Worker worker(Body body, ItemStack weapon) {
        Container pack = new SimpleContainer(1);
        pack.setItem(0, weapon);
        return worker(body, pack);
    }

    private static Worker worker(Body body, Container pack) {
        return TestWorker.of(body).pack(pack).heldFromPack();
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
}
