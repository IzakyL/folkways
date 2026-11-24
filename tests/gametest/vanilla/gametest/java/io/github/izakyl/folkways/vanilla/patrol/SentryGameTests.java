package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.combat.api.LoadoutProvider;
import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.combat.api.SpottedTargets.Spot;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.Lease;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.combat.SpottedTargets;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.vanilla.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.vanilla.combat.weapon.ProfileShortages;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class SentryGameTests {
    private static final Map<WorldPos, TestStation> STATIONS = new LinkedHashMap<>();
    private static boolean registered;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SentryGameTests.class);
        if (!registered) {
            registered = true;
            Loadouts.register(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_long_range"),
                new LoadoutProvider() {
                    public boolean accepts(ItemStack weapon) {
                        return weapon.is(Items.BOW) && weapon.has(DataComponents.CUSTOM_NAME)
                            && weapon.getHoverName().getString().equals("test long-range bow");
                    }

                    public boolean ranged(ItemStack weapon) {
                        return true;
                    }

                    public double range(ItemStack weapon) {
                        return 48;
                    }

                    public boolean longRange(ItemStack weapon) {
                        return true;
                    }

                    public Optional<ItemSpec> ammunition(ItemStack weapon) {
                        return Optional.of(ItemSpec.of(Items.ARROW));
                    }
                });
            Controls.register(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_sentry_station"),
                Controls.SPECIFIC, (level, pos) -> Optional.ofNullable(STATIONS.get(pos))
                    .map(Signal.class::cast).stream().toList());
        }
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void residentArrowsRespectColonyMembership(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ServerLevel level = fixture.level;
        ResidentEntity ally = fixture.resident(new BlockPos(6, 1, 2));
        ResidentEntity outsider = fixture.resident(new BlockPos(9, 1, 2));
        var colony = Colonies.mint(level.getServer());
        outsider.joinColony(colony.id());
        var member = helper.makeMockPlayer(GameType.SURVIVAL);
        var charterPlayer = new ServerPlayer(level.getServer(), level, member.getGameProfile(),
            ClientInformation.createDefault());
        var memberColony = Charters.found(charterPlayer, "Archer's colony");
        fixture.sentry.joinColony(memberColony.id());
        ally.joinColony(memberColony.id());
        Cleanup.on(helper, () -> {
            memberColony.raze();
            colony.raze();
        });
        Zombie hostile = fixture.zombie(new BlockPos(12, 1, 2));
        for (LivingEntity victim : List.of(ally, member, hostile, outsider)) {
            float before = victim.getHealth();
            new BowArrow(level, fixture.sentry).hit(victim);
            if (victim == ally || victim == member) {
                helper.assertValueEqual(victim.getHealth(), before, "the resident's bow can never damage its colony");
            } else {
                helper.assertTrue(victim.getHealth() < before, "the resident's arrow damages other parties");
            }
        }
        float health = ally.getHealth();
        ally.hurt(level.damageSources().playerAttack(member), 4);
        helper.assertValueEqual(ally.getHealth(), health, "a colony player also cannot damage a resident");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void residentExplosionSparesColonyAndDestroysBlocks(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity ally = fixture.resident(new BlockPos(6, 1, 2));
        Zombie hostile = fixture.zombie(new BlockPos(6, 1, 4));
        var block = new BlockPos(6, 1, 3);
        helper.setBlock(block, Blocks.WHITE_WOOL);
        float allyHealth = ally.getHealth();
        float hostileHealth = hostile.getHealth();
        var center = net.minecraft.world.phys.Vec3.atCenterOf(helper.absolutePos(block));
        fixture.level.explode(fixture.sentry, center.x, center.y, center.z, 4, Level.ExplosionInteraction.TNT);
        helper.assertValueEqual(ally.getHealth(), allyHealth, "the resident's explosion spares its colony");
        helper.assertTrue(hostile.getHealth() < hostileHealth, "the native explosion damages a hostile");
        helper.assertBlockPresent(Blocks.AIR, block);
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void patrolChoosesTargetAwayFromColonyMember(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        var ally = fixture.resident(new BlockPos(5, 1, 2));
        ally.setNoAi(true);
        Zombie blocked = fixture.zombie(new BlockPos(8, 1, 2));
        Zombie clear = fixture.zombie(new BlockPos(9, 1, 6));
        var patrol = fixture.colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow();
        patrol.watched(fixture.sentry.id(), fixture.level.getGameTime(), 100);
        var urges = patrol.urges(worker(fixture.sentry, List.of()), fixture.colony.view(fixture.level));
        helper.assertFalse(urges.stream().anyMatch(urge -> urge.id().getPath().endsWith(blocked.getStringUUID())),
            "the nearer target lies behind another resident");
        helper.assertTrue(urges.stream().anyMatch(urge -> urge.id().getPath().endsWith(clear.getStringUUID())),
            "patrol engages the clear alternative");
        helper.succeed();
    }

    @GameTest(template = "sight_range")
    public static void assignedSpyglassGrantsLongerSightAndSupplyDeficit(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.sentry.setNoAi(true);
        Zombie target = f.zombie(new BlockPos(43, 1, 2));
        double base = SpottedTargets.sight(f.sentry, target);
        helper.assertFalse(SpottedTargets.sees(f.sentry, target), "the target is beyond default sight");
        f.sentry.pack().setItem(2, new ItemStack(Items.SPYGLASS));
        helper.assertTrue(SpottedTargets.sight(f.sentry, target) == base, "unrequested gear grants no bonus");
        var profiles = LoadoutProfiles.of(f.colony);
        UUID profile = profiles.assigned(f.sentry.id()).orElseThrow().id();
        var gear = LoadoutProfile.Entry.supply(ItemSpec.of(Items.SPYGLASS), 1, 1);
        profiles.add(profile, gear);
        helper.assertTrue(SpottedTargets.sight(f.sentry, target)
            == base * FolkwaysVanillaConfig.observationSightMultiplier(),
            "one physical profile spyglass adds the configured sight bonus");
        helper.assertTrue(SpottedTargets.of(f.colony).observe(f.sentry).contains(target),
            "the longer actual scan discovers and reports the distant hostile");
        helper.assertTrue(SpottedTargets.of(f.colony).get(f.sentry, target.getUUID()).isPresent(),
            "forward observers publish the same real observations used by indirect stations");
        f.sentry.pack().setItem(3, new ItemStack(Items.SPYGLASS));
        helper.assertTrue(SpottedTargets.sight(f.sentry, target)
            == base * FolkwaysVanillaConfig.observationSightMultiplier(),
            "multiple physical spyglasses do not stack");
        f.sentry.pack().setItem(2, ItemStack.EMPTY);
        f.sentry.pack().setItem(3, ItemStack.EMPTY);
        helper.assertTrue(SpottedTargets.sight(f.sentry, target)
            == base * FolkwaysVanillaConfig.observationSightMultiplier(),
            "assigned gear continues to determine sight after removal");
        var shortage = ProfileShortages.read(f.colony, profiles.get(profile).orElseThrow(), f.level.getServer());
        helper.assertValueEqual(shortage.missing().get(gear.id()), 1, "the missing physical gear is a profile deficit");
        helper.assertValueEqual(shortage.unavailableStock().get(gear.id()), 1,
            "absent stores expose a real stock shortage");
        helper.assertTrue(SpottedTargets.of(f.colony).observe(f.sentry).contains(target),
            "assigned optics continue to discover the distant target");
        f.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void nonRangedProfileIsRefusedAndAssignmentSurvivesReload(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        arm(fixture.colony, fixture.sentry, "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
        PatrolStaffing.of(fixture.colony).configure(fixture.level, fixture.zone,
            Set.of(LoadoutProfiles.of(fixture.colony).assigned(fixture.sentry.id()).orElseThrow().id()), 1);
        helper.assertFalse(fixture.posts.assigned(fixture.sentry.id()), "melee residents cannot operate sentries");
        helper.assertTrue(fixture.posts.lines(HeldFacilities.find(fixture.colony, fixture.zone).orElseThrow().view(),
            fixture.colony.view(fixture.level)).toString().contains("ranged_required"),
            "the zone card exposes why no resident is eligible");
        fixture.assign();
        helper.assertTrue(new SentryPosts(fixture.colony).assigned(fixture.sentry.id()),
            "a fresh service reads the saved desired post assignment");
        helper.assertValueEqual(fixture.posts.capacity(fixture.level,
            fixture.posts.zone(fixture.level, fixture.zone).orElseThrow()), 1, "four horizontal cells hold one sentry");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "sight_range", timeoutTicks = 300)
    public static void sentryShootsHostileEnteringSightWithinWeaponRange(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.assign();
        Zombie target = fixture.zombie(new BlockPos(47, 1, 2));
        target.setNoGravity(true);
        LaborFixture simulation = fixture.simulate();
        helper.onEachTick(simulation::tick);
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            helper.assertTrue(SpottedTargets.of(fixture.colony).targets(fixture.sentry).isEmpty(),
                "outside sight creates no observation or shot");
            fixture.move(target, new BlockPos(11, 1, 2));
        }).thenWaitUntil(() -> helper.assertTrue(target.getHealth() < target.getMaxHealth(),
            "the real runner fires a carried bow at the hostile entering circular sight"))
            .thenExecute(() -> helper.assertTrue(
                fixture.sentry.getX() < helper.absolutePos(new BlockPos(4, 1, 2)).getX(),
                "the sentry fires from its post rather than pursuing"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 240)
    public static void indirectStationUsesOnlyAnotherResidentsFreshObservation(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.assign();
        TestStation station = fixture.station();
        ResidentEntity scout = fixture.resident(new BlockPos(15, 1, 4));
        Zombie target = fixture.zombie(new BlockPos(17, 1, 2));
        fixture.wall();
        helper.assertFalse(fixture.sentry.hasLineOfSight(target), "the post cannot see through the wall");
        helper.assertTrue(SpottedTargets.of(fixture.colony).report(scout, target), "only the scout sees and reports");
        fixture.sentry.setNoAi(true);
        Node node = fixture.node();
        fixture.tick(node);
        helper.assertValueEqual(station.shots, 1, "the indirect station executes using the shared observed position");
        helper.assertValueEqual(fixture.sentry.pack().count(Items.ARROW), 31, "one real station round is loaded");
        node.released(fixture.level, worker(fixture.sentry, List.of()), Ending.DONE);
        helper.assertValueEqual(station.releases, 1, "releasing the node closes its station lease");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 240)
    public static void expiredSpotCannotFireAndHiddenMovementDoesNotRefreshIt(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.assign();
        TestStation station = fixture.station();
        ResidentEntity scout = fixture.resident(new BlockPos(15, 1, 4));
        Zombie target = fixture.zombie(new BlockPos(17, 1, 2));
        fixture.wall();
        SpottedTargets spots = SpottedTargets.of(fixture.colony);
        helper.assertTrue(spots.report(scout, target), "a live scout reports the hostile");
        Spot observation = spots.get(fixture.sentry, target.getUUID()).orElseThrow();
        fixture.sentry.setNoAi(true);
        Node node = fixture.node();
        helper.startSequence().thenIdle(FolkwaysVanillaConfig.spottedTargetTtl()).thenExecute(() -> {
            fixture.tick(node);
            helper.assertValueEqual(station.shots, 0, "an expired observation never reaches operate");
            helper.assertTrue(spots.resolve(observation, fixture.sentry).isEmpty(), "expiry is checked at execution");
            helper.assertValueEqual(fixture.sentry.pack().count(Items.ARROW), 32, "expired fire spends no ammo");
            helper.assertTrue(spots.report(scout, target), "the scout can make a new genuine observation");
            Spot fresh = spots.get(fixture.sentry, target.getUUID()).orElseThrow();
            fixture.move(target, new BlockPos(21, 1, 2));
            helper.assertTrue(spots.resolve(fresh, fixture.sentry).isEmpty(),
                "a hidden target moving away from the observed position invalidates the shot");
            node.ended(fixture.level, Ending.DONE);
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void stationIsExclusiveAndReleasedOnDepartureDeathAndUnload(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.assign();
        TestStation station = fixture.station();
        Node node = fixture.node();
        fixture.tick(node);
        Worker operator = worker(fixture.sentry, List.of());
        helper.assertTrue(WeaponStations.take(station, operator).isEmpty(), "the station has one operator");
        fixture.unassign();
        helper.assertValueEqual(station.releases, 1, "leaving the assigned post releases the station");
        fixture.assign();
        node = fixture.node();
        fixture.tick(node);
        fixture.sentry.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.assertValueEqual(station.releases, 2, "unloading closes the lease immediately");
        ResidentEntity replacement = fixture.resident(new BlockPos(2, 1, 2));
        WeaponStations.Manning lease = WeaponStations.take(station, worker(replacement, List.of())).orElseThrow();
        replacement.hurt(fixture.level.damageSources().genericKill(), 1000);
        helper.assertValueEqual(station.releases, 3, "death releases a station immediately");
        lease.close();
        helper.assertValueEqual(station.releases, 3, "repeated release is idempotent");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void outOfRangeSentryCallsPatrolWhichResumesItsUnfinishedRoute(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        arm(fixture.colony, fixture.sentry, "Crossbow", ItemSpec.of(Items.CROSSBOW));
        fixture.sentry.pack().setItem(0, new ItemStack(Items.CROSSBOW));
        fixture.assign(false);
        for (int x = 6; x <= 11; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 8), Blocks.STONE);
            }
        }
        ResidentEntity patroller = fixture.resident(new BlockPos(3, 1, 14));
        patroller.pack().setItem(0, new ItemStack(Items.IRON_SWORD));
        arm(fixture.colony, patroller, "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
        BlockPos from = helper.absolutePos(new BlockPos(3, 0, 14));
        BlockPos to = helper.absolutePos(new BlockPos(20, 0, 14));
        UUID route = fixture.colony.hold(PatrolContent.BEAT,
            new Claimed.Line(List.of(WorldPos.of(fixture.level, from), WorldPos.of(fixture.level, to))))
            .orElseThrow().id();
        PatrolStaffing.of(fixture.colony).configure(fixture.level, route,
            Set.of(LoadoutProfiles.of(fixture.colony).assigned(patroller.id()).orElseThrow().id()), 1);
        PatrolPresence patrol = fixture.colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow();
        patrol.refresh(fixture.level.getServer());
        LaborFixture simulation = fixture.simulate();
        Zombie target = fixture.zombie(new BlockPos(12, 1, 2));
        helper.assertTrue(SpottedTargets.sees(fixture.sentry, target),
            "the sentry can observe the hostile beyond its weapon range");
        helper.assertFalse(patroller.hasLineOfSight(target),
            "the nearby responder needs the sentry's call before it can see around the wall");
        AtomicBoolean called = new AtomicBoolean();
        AtomicBoolean engaged = new AtomicBoolean();
        helper.onEachTick(() -> {
            simulation.tick();
            called.set(called.get() || patrol.responding(patroller.id()));
            engaged.set(engaged.get() || target.getHealth() < target.getMaxHealth());
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(called.get(),
            "a crossbow sentry calls a nearby route patroller for a target beyond its eight-block range"))
            .thenWaitUntil(() -> helper.assertTrue(engaged.get(), "the responder actually engages the called hostile"))
            .thenExecute(target::discard)
            .thenWaitUntil(() -> helper.assertTrue(!patrol.responding(patroller.id())
                && patroller.getZ() > helper.absolutePos(new BlockPos(0, 1, 8)).getZ(),
                "after target loss the same runner returns to the unfinished route"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void mixedProfileKeepsRangedWeaponAtClosePostThreat(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        LoadoutProfiles profiles = LoadoutProfiles.of(fixture.colony);
        profiles.add(profiles.assigned(fixture.sentry.id()).orElseThrow().id(),
            LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD)));
        fixture.sentry.pack().setItem(2, new ItemStack(Items.IRON_SWORD));
        fixture.assign();
        Zombie target = fixture.zombie(new BlockPos(6, 1, 2));
        fixture.sentry.setNoAi(true);
        Node node = fixture.node();
        helper.onEachTick(() -> fixture.tick(node));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(target.getHealth() < target.getMaxHealth(),
            "a mixed profile fires its bow rather than rejecting its preferred close-range sword"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void stationTrustsArrivalAndReleasesOnProfileChangeOrRemoval(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.assign();
        TestStation station = fixture.station();
        Node node = fixture.node();
        fixture.tick(node);
        fixture.move(fixture.sentry, new BlockPos(6, 1, 2));
        fixture.posts.tick(fixture.level.getServer());
        helper.assertValueEqual(station.releases, 0, "the duty tick trusts arrival while the runner handles displacement");
        fixture.move(fixture.sentry, new BlockPos(1, 1, 2));
        fixture.tick(node);
        arm(fixture.colony, fixture.sentry, "Melee", ItemSpec.of(Tags.Items.MELEE_WEAPON_TOOLS));
        fixture.posts.tick(fixture.level.getServer());
        helper.assertValueEqual(station.releases, 0, "a melee profile remains eligible for station operation");
        LoadoutProfiles.of(fixture.colony).assign(List.of(fixture.sentry.id()), Optional.empty());
        fixture.posts.tick(fixture.level.getServer());
        helper.assertValueEqual(station.releases, 1, "losing every assigned loadout revokes the active lease");
        fixture.sentry.pack().setItem(2, new ItemStack(Items.IRON_SWORD));
        Zombie attacker = fixture.zombie(new BlockPos(5, 1, 2));
        fixture.sentry.setLastHurtByMob(attacker);
        helper.assertTrue(fixture.colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow()
            .urges(worker(fixture.sentry, List.of()), fixture.colony.view(fixture.level)).isEmpty(),
            "a resident without a loadout follows the default flee policy");
        fixture.assign();
        node = fixture.node();
        fixture.tick(node);
        STATIONS.remove(station.anchor());
        fixture.posts.tick(fixture.level.getServer());
        helper.assertValueEqual(station.releases, 2, "removing a discovered fixed weapon releases its old handle");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void directStationOperationUsesChosenTarget(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        TestStation station = fixture.station();
        station.indirect = false;
        fixture.move(fixture.sentry, new BlockPos(6, 1, 2));
        Zombie target = fixture.zombie(new BlockPos(11, 1, 2));
        SpottedTargets spots = SpottedTargets.of(fixture.colony);
        helper.assertTrue(spots.report(fixture.sentry, target), "the operator reports a visible hostile");
        WeaponStations.Manning lease = WeaponStations.take(station, worker(fixture.sentry, List.of())).orElseThrow();
        fixture.sessions.add(lease);
        WeaponStations.Engagement shot = lease.fire(spots,
            spots.get(fixture.sentry, target.getUUID()).orElseThrow(), () -> true);
        helper.assertValueEqual(shot, WeaponStations.Engagement.FIRED,
            "the station executes the chosen shot");
        helper.assertValueEqual(station.shots, 1, "the operation reaches the integration");
        helper.assertValueEqual(fixture.sentry.pack().count(Items.ARROW), 31, "the shot spends one round");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "sight_range", timeoutTicks = 100)
    public static void optedInLongRangePatrollerUsesSharedSightWithoutPursuit(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.sentry.setNoAi(true);
        fixture.sentry.pack().getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("test long-range bow"));
        fixture.sentry.pack().setChanged();
        var profiles = LoadoutProfiles.of(fixture.colony);
        UUID assigned = profiles.assigned(fixture.sentry.id()).orElseThrow().id();
        profiles.replace(new LoadoutProfile(assigned, "Long range", List.of(
            LoadoutProfile.Entry.weapon(ItemSpec.exact(fixture.sentry.pack().getItem(0))))));
        ResidentEntity scout = fixture.resident(new BlockPos(35, 1, 5));
        scout.setNoAi(true);
        scout.setNoGravity(true);
        Zombie target = fixture.zombie(new BlockPos(37, 1, 2));
        target.setNoGravity(true);
        SpottedTargets spots = SpottedTargets.of(fixture.colony);
        helper.assertFalse(SpottedTargets.sees(fixture.sentry, target), "the hostile is outside the shooter's sight");
        helper.assertTrue(spots.report(scout, target), "the scout reports a target within the long-range bow's reach");
        PatrolPresence patrol = fixture.colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow();
        patrol.watched(fixture.sentry.id(), fixture.level.getGameTime());
        Worker operator = worker(fixture.sentry, List.of());
        Node strike = patrol.urges(operator, fixture.colony.view(fixture.level)).getFirst().take().get();
        fixture.sessions.add(() -> strike.released(fixture.level, operator, Ending.REVOKED));
        helper.assertTrue(strike.spec().stances().cells().contains(WorldPos.of(fixture.level,
            fixture.sentry.blockPosition())), "shared direct fire uses the existing stance, without pursuit");
        AtomicBoolean fired = new AtomicBoolean();
        helper.onEachTick(() -> {
            ((Workload.Continuous) strike.spec().workload()).holds(fixture.level, operator);
            fired.set(fired.get() || !fixture.level.getEntitiesOfClass(AbstractArrow.class,
                fixture.sentry.getBoundingBox().inflate(48), arrow -> arrow.getOwner() == fixture.sentry).isEmpty());
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(fired.get()
            && fixture.sentry.pack().count(Items.ARROW) == 31,
            "an opted-in direct long-range weapon emits a real projectile and consumes one shared-authorized round"
                + "; arrows=" + fixture.sentry.pack().count(Items.ARROW)
                + "; hand=" + fixture.sentry.getMainHandItem()))
            .thenSucceed();
    }

    // A breech fires what it is given by itself: one exact carried round per shot, at the station's own cycle.
    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void stationLoadsOneExactCarriedRoundPerCycle(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Zombie target = fixture.zombie(new BlockPos(11, 1, 2));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            fixture.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, target.blockPosition()) >= 12,
            "the native target skylight has propagated before reporting visibility"))
            .thenExecute(() -> {
                SpottedTargets spots = SpottedTargets.of(fixture.colony);
                helper.assertTrue(spots.report(fixture.sentry, target), "the defender reports a real hostile");
                ItemStack arrows = fixture.sentry.pack().getItem(1);
                arrows.set(DataComponents.CUSTOM_NAME, Component.literal("physical station rounds"));
                ItemSpec exact = ItemSpec.exact(arrows);
                TestStation station =
                    new TestStation(WorldPos.of(fixture.level, fixture.sentry.blockPosition())) {
                        @Override
                        public double range() {
                            return 32;
                        }

                        @Override
                        public ItemSpec ammunition() {
                            return exact;
                        }
                    };
                try (WeaponStations.Manning lease =
                        WeaponStations.take(station, worker(fixture.sentry, List.of())).orElseThrow()) {
                    Spot observation = spots.get(fixture.sentry, target.getUUID()).orElseThrow();
                    helper.assertValueEqual(lease.fire(spots, observation, () -> true),
                        WeaponStations.Engagement.FIRED, "loading the breech is the shot");
                    helper.assertTrue(Goods.matches(station.loaded, exact),
                        "the breech receives the carried component variant");
                    helper.assertValueEqual(lease.fire(spots, observation, () -> true),
                        WeaponStations.Engagement.COOLDOWN, "the next round waits for the station's cycle");
                    helper.assertValueEqual(fixture.sentry.pack().count(Items.ARROW), 31,
                        "one round leaves the pack per shot");
                }
                fixture.close();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void seatStanceReleasePreservesUnrelatedRiding(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Entity seat = EntityType.BOAT.create(fixture.level);
        Entity other = EntityType.BOAT.create(fixture.level);
        seat.moveTo(fixture.sentry.position());
        other.moveTo(fixture.sentry.position());
        fixture.level.addFreshEntity(seat);
        fixture.level.addFreshEntity(other);
        try {
            helper.assertTrue(Seating.sit(fixture.level, fixture.sentry, new Stances.Seat(seat.getUUID(), 0)),
                "the engine seats the body in the first seat");
            fixture.sentry.stopRiding();
            fixture.sentry.startRiding(other, true);
            Seating.rise(fixture.sentry);
            helper.assertTrue(
                fixture.sentry.getVehicle() == other, "standing up from a stale seat preserves unrelated"
                + " riding");
        } finally {
            seat.discard();
            other.discard();
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void refusedOrThrowingStationEntryRollsBackTheLease(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        TestStation original = fixture.station();
        for (boolean throwing : List.of(false, true)) {
            ResidentEntity body = throwing ? fixture.resident(new BlockPos(1, 1, 3)) : fixture.sentry;
            Worker operator = worker(body, List.of());
            TestStation station = new TestStation(original.anchor()) {
                @Override
                public boolean enter(Mob writer) {
                    helper.assertTrue(Lease.held(this).isPresent(), "entry runs with the lease taken");
                    if (throwing) {
                        throw new IllegalStateException("entry failed halfway");
                    }
                    return false;
                }
            };
            if (throwing) {
                try {
                    WeaponStations.take(station, operator);
                    helper.fail("throwing entry must propagate its failure");
                } catch (IllegalStateException failure) {
                    helper.assertValueEqual(failure.getMessage(), "entry failed halfway",
                        "the original entry failure is preserved");
                }
            } else {
                helper.assertTrue(WeaponStations.take(station, operator).isEmpty(), "refused entry takes no lease");
            }
            helper.assertValueEqual(station.releases, 1, "failed entry releases its integration exactly once");
            helper.assertTrue(Lease.held(station).isEmpty(), "failed entry leaves no lease behind");
            try (WeaponStations.Manning lease = WeaponStations.take(original, operator).orElseThrow()) {
                helper.assertTrue(lease.station() == original, "the exact station can be acquired again");
            }
        }
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void malformedSavedSeatMarkerIsRetainedInsteadOfForgettingDismount(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        try {
            var marker = new net.minecraft.nbt.CompoundTag();
            marker.putUUID("token", UUID.randomUUID());
            fixture.sentry.getPersistentData().put("folkways_core:seat_stance", marker);
            boolean rejected = false;
            try {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                    new net.neoforged.neoforge.event.tick.EntityTickEvent.Post(fixture.sentry));
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "a present marker requires its paired seat UUID");
            helper.assertTrue(fixture.sentry.getPersistentData().contains("folkways_core:seat_stance"),
                "failed validation leaves the marker for recovery");
            fixture.sentry.getPersistentData().remove("folkways_core:seat_stance");
        } finally {
            fixture.close();
        }
        helper.succeed();
    }

    private static final class BowArrow extends Arrow {

        BowArrow(ServerLevel level, LivingEntity shooter) {
            super(level, shooter, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            setDeltaMovement(3, 0, 0);
        }

        void hit(LivingEntity victim) {
            onHitEntity(new EntityHitResult(victim));
        }
    }

    private static class TestStation implements WeaponStations.Station {
        private final WorldPos at;
        private int shots;
        private int releases;
        private ItemStack loaded = ItemStack.EMPTY;
        private boolean indirect = true;

        private UUID entity;

        private TestStation(WorldPos at) {
            this.at = at;
        }

        public WorldPos anchor() {
            return at;
        }

        public String part() {
            return "test_station";
        }

        @Override
        public Optional<UUID> entityId() {
            return Optional.ofNullable(entity);
        }

        public Object leaseKey() {
            return entity != null ? entity : ref();
        }

        public SignalType type() {
            return SignalType.PULSE;
        }

        public boolean valid(ServerLevel level) {
            return at.in(level) && level.hasChunkAt(at.block(level));
        }

        public Write write(ServerLevel level, Mob writer, Value value) {
            return Write.REFUSED;
        }

        public double range() {
            return 64;
        }

        public boolean indirect() {
            return indirect;
        }

        public ItemSpec ammunition() {
            return ItemSpec.of(Items.ARROW);
        }

        // A breech that fires every round it is given at once.
        public Optional<Port<ItemStack>> breech(ServerLevel level) {
            TestStation station = this;
            return Optional.of(new Port<>() {
                public ResourceKind<ItemStack> resource() {
                    return ResourceKind.ITEM;
                }

                public WorldPos anchor() {
                    return at;
                }

                public String part() {
                    return "test_breech";
                }

                public boolean valid(ServerLevel level) {
                    return station.valid(level);
                }

                public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
                    if (offered.isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                    if (!simulate) {
                        shots++;
                        loaded = offered.copyWithCount(1);
                    }
                    return offered.copyWithCount(1);
                }

                public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
                    return ItemStack.EMPTY;
                }
            });
        }

        public void leave(Mob writer) {
            releases++;
        }
    }

    private static final class Fixture {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final Colony colony;
        private final ResidentEntity sentry;
        private final SentryPosts posts;
        private UUID zone;
        private UUID stationEntity;
        private final List<Entity> entities = new ArrayList<>();
        private final List<AutoCloseable> sessions = new ArrayList<>();
        private boolean closed;
        private final List<WorldPos> stationCells = new ArrayList<>();

        private Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            for (int x = 0; x < 28; x++) {
                for (int z = 0; z < 15; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            colony = Colonies.mint(level.getServer());
            sentry = resident(new BlockPos(2, 1, 2));
            sentry.pack().setItem(0, new ItemStack(Items.BOW));
            sentry.pack().setItem(1, new ItemStack(Items.ARROW, 32));
            arm(colony, sentry, "Bow", ItemSpec.of(Items.BOW));
            Set<WorldPos> cells = new LinkedHashSet<>();
            for (int x = 2; x <= 3; x++) {
                for (int z = 2; z <= 3; z++) {
                    cells.add(WorldPos.of(level, helper.absolutePos(new BlockPos(x, 0, z))));
                }
            }
            zone = colony.hold(SentryPosts.ZONE, new Claimed.Area(cells)).orElseThrow().id();
            posts = SentryPosts.of(colony);
            Cleanup.on(helper, this::close);
        }

        private ResidentEntity resident(BlockPos at) {
            ResidentEntity body = PersonBody.RESIDENT.get().create(level);
            move(body, at);
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            entities.add(body);
            return body;
        }

        private Zombie zombie(BlockPos at) {
            Zombie body = EntityType.ZOMBIE.create(level);
            move(body, at);
            // These scenarios exercise sentry behavior, independently of ambient light and its propagation.
            body.setGlowingTag(true);
            body.setNoAi(true);
            body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            body.setHealth(100);
            level.addFreshEntity(body);
            entities.add(body);
            return body;
        }

        private void move(Mob body, BlockPos at) {
            BlockPos absolute = helper.absolutePos(at);
            body.moveTo(absolute.getX() + .5, absolute.getY(), absolute.getZ() + .5);
        }

        private void assign() {
            assign(true);
        }

        private void assign(boolean bow) {
            if (bow) {
                arm(colony, sentry, "Bow", ItemSpec.of(Items.BOW));
            }
            if (stationEntity != null) {
                if (colony.claim(zone).isEmpty()) {
                    zone = colony.hold(SentryPosts.STATION, Claimed.Entity.of(level.getEntity(stationEntity)))
                        .orElseThrow().id();
                }
                PatrolStaffing.of(colony).tick(level.getServer());
            } else {
                PatrolStaffing.of(colony).configure(level, zone,
                    Set.of(LoadoutProfiles.of(colony).assigned(sentry.id()).orElseThrow().id()), 1);
            }
        }

        private void unassign() {
            if (stationEntity != null) {
                colony.release(zone, io.github.izakyl.folkways.core.colony.api.ClaimRelease.LET_GO);
                PatrolStaffing.of(colony).tick(level.getServer());
            } else {
                PatrolStaffing.of(colony).configure(level, zone, Set.of(), 0);
            }
            posts.tick(level.getServer());
        }

        private TestStation station() {
            unassign();
            var marker = EntityType.ARMOR_STAND.create(level);
            BlockPos at = helper.absolutePos(new BlockPos(2, 0, 2));
            marker.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            marker.setNoGravity(true);
            marker.setInvisible(true);
            level.addFreshEntity(marker);
            entities.add(marker);
            stationEntity = marker.getUUID();
            zone = colony.hold(SentryPosts.STATION, Claimed.Entity.of(marker)).orElseThrow().id();
            TestStation station = new TestStation(WorldPos.of(level, helper.absolutePos(new BlockPos(2, 0, 2))));
            station.entity = stationEntity;
            STATIONS.put(station.anchor(), station);
            stationCells.add(station.anchor());
            move(sentry, new BlockPos(1, 1, 2));
            assign();
            return station;
        }

        private Node node() {
            Node node = posts.urges(worker(sentry, List.of()), colony.view(level))
                .getFirst().take().get();
            sessions.add(() -> node.ended(level, Ending.DONE));
            return node;
        }

        private void tick(Node node) {
            ((Workload.Continuous) node.spec().workload()).holds(level, worker(sentry, List.of()));
        }

        private LaborFixture simulate() {
            LaborFixture simulation = new LaborFixture(colony, level);
            sessions.add(simulation);
            return simulation;
        }

        private void wall() {
            for (int z = 0; z < 8; z++) {
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(8, y, z), Blocks.STONE);
                }
            }
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (AutoCloseable attack : sessions) {
                try {
                    attack.close();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
            posts.close();
            stationCells.forEach(STATIONS::remove);
            entities.forEach(Entity::discard);
            colony.raze();
        }
    }

    private static Worker worker(ResidentEntity body, List<Node> route) {
        return TestWorker.of(body)
            .held(need -> body.pack().contents().stream().filter(stack -> Tools.answers(stack, need)).findFirst()
                .orElse(ItemStack.EMPTY))
            .spill(body::spawnAtLocation).route(route);
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
