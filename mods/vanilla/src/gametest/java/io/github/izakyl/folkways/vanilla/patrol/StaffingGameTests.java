package io.github.izakyl.folkways.vanilla.patrol;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.engine.charter.Charter;
import io.github.izakyl.folkways.core.front.engine.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.combat.SpottedTargets;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class StaffingGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StaffingGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void compositionFillsMatchingProfilesAndReportsStableShortages(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity heavy = fixture.resident(2, 3, fixture.heavy);
        ResidentEntity archer = fixture.resident(2, 6, fixture.archer);
        add(fixture, fixture.route, fixture.heavy);
        add(fixture, fixture.route, fixture.heavy);
        add(fixture, fixture.route, fixture.archer);
        helper.assertValueEqual(fixture.staffing.members(fixture.route).size(), 2, "two matching residents fill");
        helper.assertValueEqual(fixture.staffing.shortage(fixture.route), 1,
            "one resident is missing from the shared allowed pool");
        helper.assertValueEqual(fixture.staffing.shortageFor(fixture.heavy), 1, "profiles page shortage aggregate");
        List<PatrolStaffing.Position> before = fixture.staffing.positions(fixture.route);
        fixture.staffing.tick(fixture.level.getServer());
        helper.assertValueEqual(fixture.staffing.positions(fixture.route), before, "filled slots do not reshuffle");
        helper.assertTrue(fixture.staffing.members(fixture.route).containsAll(List.of(heavy.id(), archer.id())),
            "both profile matches belong to the route");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void allowedProfilesAreInterchangeableAndCountZeroStaysConfigured(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity resident = fixture.resident(2, 3, fixture.heavy);
        fixture.staffing.configure(fixture.level, fixture.route, Set.of(fixture.heavy, fixture.archer), 1);
        PatrolStaffing restored = new PatrolStaffing(fixture.colony);
        restored.tick(fixture.level.getServer());
        helper.assertValueEqual(restored.members(fixture.route), List.of(resident.id()), "incumbent survives reload");
        fixture.profiles.assign(List.of(resident.id()), Optional.of(fixture.archer));
        restored.tick(fixture.level.getServer());
        helper.assertValueEqual(restored.members(fixture.route), List.of(resident.id()),
            "another allowed profile remains interchangeable");
        restored.configure(fixture.level, fixture.route, Set.of(), 1);
        helper.assertTrue(restored.members(fixture.route).isEmpty(), "empty allowed set releases membership");
        helper.assertValueEqual(restored.shortage(fixture.route), 1, "total shortage remains visible");
        restored.configure(fixture.level, fixture.route, Set.of(), 0);
        helper.assertTrue(restored.configured(fixture.route), "zero count never restores autonomous legacy laps");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void sentryHeadcountExposesRangedAndCapacityRestrictions(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity archer = fixture.resident(2, 3, fixture.archer);
        UUID zone = fixture.zone();
        fixture.staffing.configure(fixture.level, zone, Set.of(fixture.heavy), 1);
        helper.assertValueEqual(fixture.staffing.positions(zone).getFirst().status(), "ranged_required",
            "all profiles can be selected, but sentry duty still requires ranged equipment");
        fixture.staffing.configure(fixture.level, zone, Set.of(fixture.archer), 2);
        helper.assertValueEqual(SentryPosts.of(fixture.colony).residents(zone), List.of(archer.id()),
            "only safe capacity is filled");
        helper.assertValueEqual(fixture.staffing.positions(zone).get(1).status(), "capacity",
            "excess desired headcount exposes capacity shortage");
        fixture.staffing.configure(fixture.level, fixture.route, Set.of(fixture.archer), 1);
        helper.assertTrue(fixture.staffing.members(fixture.route).isEmpty(), "residents cannot serve twice");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void sentryCompositionRejectsChangedRangedProfileBeforeReconciliation(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity archer = fixture.resident(2, 3, fixture.archer);
        UUID zone = fixture.zone();
        add(fixture, zone, fixture.archer);

        SentryPosts posts = SentryPosts.of(fixture.colony);
        helper.assertTrue(posts.posting(fixture.level, archer.id()), "the matching archer can operate");
        UUID replacement = fixture.profiles.duplicate(fixture.archer).id();
        fixture.profiles.assign(List.of(archer.id()), Optional.of(replacement));
        helper.assertValueEqual(fixture.staffing.members(zone), List.of(archer.id()),
            "the regression exercises a cached filled slot before reconciliation");
        helper.assertFalse(posts.valid(fixture.level, archer.id(), zone),
            "a profile outside the allowed set cannot authorize sentry fire before reconciliation");
        helper.assertTrue(posts.urges(worker(archer), fixture.colony.view(fixture.level)).isEmpty(),
            "a mismatched resident cannot be selected for sentry duty");
        fixture.staffing.tick(fixture.level.getServer());
        helper.assertValueEqual(fixture.staffing.positions(zone).getFirst().status(), "unfilled",
            "an incompatible resident is released");
        fixture.profiles.assign(List.of(archer.id()), Optional.of(fixture.archer));
        fixture.staffing.tick(fixture.level.getServer());
        helper.assertTrue(posts.posting(fixture.level, archer.id()), "restoring the required profile resumes duty");
        fixture.profiles.delete(fixture.archer);
        helper.assertFalse(posts.valid(fixture.level, archer.id(), zone), "a deleted profile revokes operation");
        fixture.staffing.tick(fixture.level.getServer());
        helper.assertValueEqual(fixture.staffing.positions(zone).getFirst().status(), "missing_profile",
            "deleting the required profile leaves a visible shortage");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void sentryPositionsMatchStancesAndReplacedDutyCannotResume(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity archer = fixture.resident(2, 3, fixture.archer);
        UUID missing = fixture.profiles.create("Missing archer").id();
        fixture.profiles.add(missing, LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)));
        Set<WorldPos> cells = new LinkedHashSet<>();
        for (int x = 2; x <= 5; x++) {
            for (int z = 2; z <= 3; z++) {
                cells.add(fixture.at(x, z));
            }
        }
        UUID zone = fixture.colony.hold(SentryPosts.ZONE, new Claimed.Area(cells)).orElseThrow().id();
        add(fixture, zone, missing);
        add(fixture, zone, fixture.archer);
        SentryPosts posts = SentryPosts.of(fixture.colony);
        var post = posts.zone(fixture.level, zone).orElseThrow();
        Node node = posts.urges(worker(archer), fixture.colony.view(fixture.level)).getFirst().take().get();
        BlockPos stance = posts.stances(fixture.level, post).getFirst();
        helper.assertValueEqual(posts.zoneRings(post, fixture.colony.view(fixture.level)).getFirst().centre(),
            stance, "the incumbent stance matches its internal position");
        helper.assertValueEqual(node.spec().site(), new WorkSite.AtBlock(post.at(stance)),
            "the worker destination matches its displayed stance");
        fixture.staffing.configure(fixture.level, zone, Set.of(fixture.archer), 0);
        add(fixture, zone, fixture.archer);
        helper.assertFalse(node.ready(fixture.level), "a replacement slot cannot revive the old duty");
        helper.assertTrue(posts.urges(worker(archer), fixture.colony.view(fixture.level)).getFirst().take().get()
            .ready(fixture.level), "the replacement slot can start its own duty");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void routeTeamEngagesTogetherRegroupsAndResumesUnfinishedStop(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity first = fixture.resident(2, 3, fixture.heavy);
        ResidentEntity second = fixture.resident(2, 6, fixture.heavy);
        add(fixture, fixture.route, fixture.heavy);
        add(fixture, fixture.route, fixture.heavy);
        add(fixture, fixture.route, fixture.archer);
        fixture.simulate(helper);
        AtomicInteger unfinished = new AtomicInteger();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(first.getX() > fixture.x(7)
            && second.getX() > fixture.x(7), "the partially staffed team assembles and starts its route"))
            .thenExecute(() -> {
                helper.assertTrue(first.distanceTo(second) < 7, "the route retains loose team cohesion");
                helper.assertValueEqual(fixture.staffing.shortage(fixture.route), 1,
                    "the partial team keeps its total shortage visible");
                unfinished.set(fixture.patrol.teams().stop(fixture.route));
                fixture.zombie = fixture.zombie(13, 5);
            }).thenWaitUntil(() -> helper.assertValueEqual(fixture.patrol.teams().status(fixture.route), "engaging",
                "one member's hostile sight pauses the entire team"))
            .thenWaitUntil(() -> helper.assertTrue(fixture.patrol.engaging(first.id())
                && fixture.patrol.engaging(second.id()) && fixture.zombie.getHealth() < fixture.zombie.getMaxHealth(),
                "both members actually engage and a native sword damages the hostile"))
            .thenExecute(() -> {
                helper.assertValueEqual(fixture.patrol.teams().stop(fixture.route), unfinished.get(),
                    "combat cannot complete the unfinished stop");
                fixture.zombie.discard();
            }).thenWaitUntil(() -> helper.assertValueEqual(fixture.patrol.teams().status(fixture.route), "reforming",
                "the team returns to its pre-combat formation"))
            .thenWaitUntil(() -> helper.assertTrue(first.getX() > fixture.x(18) && second.getX() > fixture.x(18),
                "both members resume the unfinished route after regrouping")).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void sentryCallDivertsWholeTeamAndRetainsRouteStop(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity first = fixture.resident(2, 3, fixture.heavy);
        ResidentEntity second = fixture.resident(2, 6, fixture.heavy);
        ResidentEntity sentry = fixture.resident(8, 15, fixture.archer);
        add(fixture, fixture.route, fixture.heavy);
        add(fixture, fixture.route, fixture.heavy);
        fixture.zombie = fixture.zombie(9, 18);
        fixture.simulate(helper);
        AtomicInteger unfinished = new AtomicInteger();
        helper.startSequence().thenIdle(30).thenExecute(() -> {
            unfinished.set(fixture.patrol.teams().stop(fixture.route));
            helper.assertTrue(SpottedTargets.of(fixture.colony).report(sentry, fixture.zombie),
                "the sentry supplies a real observation");
            fixture.patrol.call(sentry, fixture.zombie);
            helper.assertTrue(fixture.patrol.responding(first.id()) && fixture.patrol.responding(second.id()),
                "the entire team answers as one responder unit");
        }).thenWaitUntil(() -> helper.assertTrue(first.getZ() > fixture.z(9) && second.getZ() > fixture.z(9),
            "both members physically divert toward the sentry call"))
            .thenExecute(() -> {
                helper.assertValueEqual(fixture.patrol.teams().stop(fixture.route), unfinished.get(),
                    "answering a call preserves the unfinished stop");
                fixture.zombie.discard();
            }).thenWaitUntil(() -> helper.assertTrue(!fixture.patrol.responding(first.id())
                && first.getZ() < fixture.z(8) && second.getZ() < fixture.z(8),
                "both responders reform on the route after the target disappears")).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void routeWithoutHeadcountDoesNotOfferIndividualLap(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.patrol.refresh(fixture.level.getServer());
        helper.assertTrue(fixture.patrol.goals(fixture.colony.view(fixture.level)).isEmpty(),
            "unconfigured routes default to zero residents");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void refillingEarlierSlotRetainsLaterIncumbent(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.resident(2, 3, fixture.heavy);
        fixture.resident(2, 6, fixture.heavy);
        fixture.resident(2, 9, fixture.heavy);
        add(fixture, fixture.route, fixture.heavy);
        add(fixture, fixture.route, fixture.heavy);
        List<PatrolStaffing.Position> before = fixture.staffing.positions(fixture.route);
        UUID dead = before.getFirst().member().orElseThrow();
        fixture.level.getEntity(dead).kill();
        fixture.colony.removed(dead, Entity.RemovalReason.KILLED);
        fixture.staffing.tick(fixture.level.getServer());
        List<PatrolStaffing.Position> after = fixture.staffing.positions(fixture.route);
        helper.assertValueEqual(after.get(1).member(), before.get(1).member(),
            "a later valid incumbent cannot be stolen by an earlier vacancy");
        helper.assertTrue(after.getFirst().member().isPresent() && !after.getFirst().member().get().equals(dead),
            "the idle resident fills only the dead member's slot");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void blockedTeamStopsAndCanRetryItsUnfinishedRoute(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity first = fixture.resident(2, 3, fixture.heavy);
        ResidentEntity second = fixture.resident(2, 6, fixture.heavy);
        first.setNoAi(true);
        second.setNoAi(true);
        add(fixture, fixture.route, fixture.heavy);
        add(fixture, fixture.route, fixture.heavy);
        fixture.simulate(helper);
        helper.startSequence().thenWaitUntil(() -> helper.assertValueEqual(
            fixture.patrol.teams().status(fixture.route), "blocked", "straggler recovery is bounded"))
            .thenExecute(() -> {
                first.setNoAi(false);
                second.setNoAi(false);
                fixture.patrol.teams().retry(fixture.route);
            }).thenWaitUntil(() -> helper.assertTrue(first.getX() > fixture.x(10) && second.getX() > fixture.x(10),
                "retry rebuilds ordinary movement work and continues the same route")).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void staffingPanelRechecksTargetRevisionAndAuthority(GameTestHelper helper)
            throws CommandSyntaxException {
        Fixture fixture = new Fixture(helper);
        var player = FakePlayerFactory.get(fixture.level, new GameProfile(UUID.randomUUID(), "staffing_keeper"));
        Charters.keep(fixture.colony, Charter.founded("staffing test", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(fixture.colony.id()));
        var selected = new java.util.concurrent.atomic.AtomicReference<>(fixture.route);
        StaffingPanel panel = new StaffingPanel(selected::get);
        CompoundTag request = TagParser.parseTag(StaffingPanel.request(panel.snapshot(player), "count").toString());
        request.putString("count", "3");
        panel.apply(player, request);
        helper.assertValueEqual(fixture.staffing.count(fixture.route), 3, "panel edits total headcount");
        boolean refused = false;
        try {
            panel.apply(player, request);
        } catch (RuntimeException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "stale revisions cannot edit staffing");
        request = TagParser.parseTag(StaffingPanel.request(panel.snapshot(player), "count").toString());
        request.putString("count", "4");
        selected.set(fixture.zone());
        refused = false;
        try {
            panel.apply(player, request);
        } catch (RuntimeException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "old popups cannot edit a newly selected target");
        selected.set(fixture.route);
        Charters.keep(fixture.colony, Charter.founded("revoked", UUID.randomUUID()));
        refused = false;
        try {
            panel.apply(player, request);
        } catch (RuntimeException expected) {
            refused = true;
        }
        helper.assertTrue(refused, "every edit rechecks keeper authority");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 350)
    public static void teamCallExpiryRevokesCapturedCombatPermission(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity first = fixture.resident(2, 3, fixture.heavy);
        ResidentEntity second = fixture.resident(2, 6, fixture.heavy);
        ResidentEntity sentry = fixture.resident(8, 15, fixture.archer);
        add(fixture, fixture.route, fixture.heavy);
        add(fixture, fixture.route, fixture.heavy);
        fixture.zombie = fixture.zombie(9, 18);
        fixture.patrol.refresh(fixture.level.getServer());
        fixture.patrol.tick(fixture.level.getServer());
        fixture.patrol.urges(worker(first), fixture.colony.view(fixture.level));
        fixture.patrol.urges(worker(second), fixture.colony.view(fixture.level));
        SpottedTargets.of(fixture.colony).report(sentry, fixture.zombie);
        fixture.patrol.call(sentry, fixture.zombie);
        BooleanSupplier permission = fixture.patrol.teams().permission(worker(first), fixture.zombie);
        helper.assertTrue(permission.getAsBoolean(), "a current team call grants a live combat permission");
        helper.onEachTick(() -> SpottedTargets.of(fixture.colony).report(sentry, fixture.zombie));
        helper.runAfterDelay(FolkwaysVanillaConfig.sentryCallTicks() + 1, () -> {
            helper.assertFalse(permission.getAsBoolean(), "fresh observations cannot extend the captured call lease");
            helper.assertFalse(fixture.patrol.responding(first.id()) || fixture.patrol.responding(second.id()),
                "expiry revokes the entire response before another controller tick");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 260)
    public static void unloadedMemberKeepsGraceThenIdleMatchingResidentRefills(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.resident(2, 3, fixture.heavy);
        fixture.resident(2, 6, fixture.heavy);
        add(fixture, fixture.route, fixture.heavy);
        UUID missing = fixture.staffing.members(fixture.route).getFirst();
        fixture.level.getEntity(missing).remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        fixture.colony.removed(missing, Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.runAfterDelay(2, () -> {
            fixture.staffing.tick(fixture.level.getServer());
            PatrolStaffing.Position slot = fixture.staffing.positions(fixture.route).getFirst();
            helper.assertValueEqual(slot.member().orElseThrow(), missing, "brief unload retains incumbent claim");
            helper.assertValueEqual(slot.status(), "unloaded", "unloaded slot visibly counts as short");
        });
        helper.runAfterDelay(PatrolStaffing.UNLOAD_GRACE + 4, () -> {
            fixture.staffing.tick(fixture.level.getServer());
            UUID replacement = fixture.staffing.members(fixture.route).getFirst();
            helper.assertFalse(replacement.equals(missing), "grace expiry fills from an idle matching resident");
            helper.succeed();
        });
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void stationsFillFirstButNeverStealRouteIncumbents(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity first = fixture.resident(2, 3, fixture.heavy);
        UUID station = fixture.station();
        fixture.staffing.configure(fixture.level, fixture.route, Set.of(fixture.heavy), 1);
        helper.assertValueEqual(fixture.staffing.members(station), List.of(first.id()),
            "a station fills before a route from any assigned loadout, including melee");
        helper.assertTrue(fixture.staffing.members(fixture.route).isEmpty(), "one resident never serves twice");
        fixture.colony.release(station, io.github.izakyl.folkways.core.colony.api.ClaimRelease.LET_GO);
        fixture.staffing.tick(fixture.level.getServer());
        helper.assertValueEqual(fixture.staffing.members(fixture.route), List.of(first.id()),
            "withdrawing a station releases its incumbent");
        UUID next = fixture.station();
        fixture.staffing.tick(fixture.level.getServer());
        helper.assertTrue(fixture.staffing.members(next).isEmpty(), "new stations do not steal valid incumbents");
        ResidentEntity second = fixture.resident(2, 6, fixture.archer);
        fixture.staffing.tick(fixture.level.getServer());
        helper.assertValueEqual(fixture.staffing.members(next), List.of(second.id()),
            "the next idle combat resident fills");
        PatrolStaffing restored = new PatrolStaffing(fixture.colony);
        restored.tick(fixture.level.getServer());
        helper.assertValueEqual(restored.members(next), List.of(second.id()), "station incumbents survive reload");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void allowedSetHasNoPerProfileQuotaAndUsesStableCandidateOrder(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        ResidentEntity first = fixture.resident(2, 3, fixture.archer);
        ResidentEntity second = fixture.resident(2, 6, fixture.archer);
        fixture.staffing.configure(fixture.level, fixture.route, Set.of(fixture.heavy, fixture.archer), 2);
        helper.assertValueEqual(fixture.staffing.members(fixture.route),
            List.of(first.id(), second.id()).stream().sorted().toList(),
            "two residents with the same allowed profile fill in UUID order without per-profile quotas");
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void reducingHeadcountDropsVacanciesBeforeValidIncumbents(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.resident(2, 3, fixture.heavy);
        fixture.resident(2, 6, fixture.heavy);
        fixture.staffing.configure(fixture.level, fixture.route, Set.of(fixture.heavy), 3);
        UUID first = fixture.staffing.members(fixture.route).getFirst();
        UUID retained = fixture.staffing.members(fixture.route).get(1);
        fixture.profiles.assign(List.of(first), Optional.of(fixture.archer));
        UUID station = fixture.station();
        fixture.staffing.configure(fixture.level, fixture.route, Set.of(fixture.heavy), 1);
        helper.assertValueEqual(fixture.staffing.members(fixture.route), List.of(retained),
            "shrinking before reconciliation preserves the valid incumbent instead of a stale claim");
        helper.assertValueEqual(fixture.staffing.members(station), List.of(first),
            "a new station takes the released resident without stealing the route incumbent");
        fixture.close();
        helper.succeed();
    }

    private static void add(Fixture fixture, UUID target, UUID profile) {
        Set<UUID> allowed = new LinkedHashSet<>(fixture.staffing.allowed(target));
        allowed.add(profile);
        fixture.staffing.configure(fixture.level, target, allowed, fixture.staffing.count(target) + 1);
    }

    private static Worker worker(ResidentEntity body) {
        return TestWorker.of(body)
            .held(need -> body.pack().contents().stream().filter(stack -> Tools.answers(stack, need)).findFirst()
                .orElse(ItemStack.EMPTY))
            .spill(body::spawnAtLocation);
    }

    private static final class Fixture {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final Colony colony;
        private final LoadoutProfiles profiles;
        private final PatrolStaffing staffing;
        private final PatrolPresence patrol;
        private final UUID heavy;
        private final UUID archer;
        private final UUID route;
        private final List<Entity> entities = new ArrayList<>();
        private LaborFixture simulation;
        private Zombie zombie;
        private boolean closed;

        private Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            for (int x = 0; x < 28; x++) {
                for (int z = 0; z < 23; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            colony = Colonies.mint(level.getServer());
            profiles = LoadoutProfiles.of(colony);
            heavy = profiles.create("Heavy melee").id();
            profiles.add(heavy, LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD)));
            archer = profiles.create("Archer").id();
            profiles.add(archer, LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)));
            staffing = PatrolStaffing.of(colony);
            patrol = colony.service(PatrolContent.ID, PatrolPresence.class).orElseThrow();
            route = colony.hold(PatrolContent.BEAT, new Claimed.Line(List.of(at(3, 5), at(23, 5))))
                .orElseThrow().id();
            Cleanup.on(helper, this::close);
        }

        private WorldPos at(int x, int z) {
            return WorldPos.of(level, helper.absolutePos(new BlockPos(x, 1, z)));
        }

        private double x(int x) {
            return helper.absolutePos(new BlockPos(x, 1, 0)).getX();
        }

        private double z(int z) {
            return helper.absolutePos(new BlockPos(0, 1, z)).getZ();
        }

        private ResidentEntity resident(int x, int z, UUID profile) {
            ResidentEntity body = PersonBody.RESIDENT.get().create(level);
            move(body, x, z);
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            body.pack().insert(new ItemStack(profile.equals(heavy) ? Items.IRON_SWORD : Items.BOW));
            if (profile.equals(archer)) {
                body.pack().insert(new ItemStack(Items.ARROW, 32));
            }
            profiles.assign(List.of(body.id()), Optional.of(profile));
            entities.add(body);
            return body;
        }

        private void move(Mob body, int x, int z) {
            BlockPos cell = helper.absolutePos(new BlockPos(x, 1, z));
            body.moveTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
        }

        private Zombie zombie(int x, int z) {
            Zombie target = EntityType.ZOMBIE.create(level);
            move(target, x, z);
            target.setNoAi(true);
            target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            level.addFreshEntity(target);
            entities.add(target);
            return target;
        }

        private UUID station() {
            var marker = EntityType.ARMOR_STAND.create(level);
            BlockPos at = helper.absolutePos(new BlockPos(10, 1, 10));
            marker.moveTo(at.getX() + .5, at.getY(), at.getZ() + .5);
            marker.setNoGravity(true);
            level.addFreshEntity(marker);
            entities.add(marker);
            // Station discovery and physical operation are covered separately by integration tests.
            UUID station = colony.hold(SentryPosts.STATION, Claimed.Entity.of(marker)).orElseThrow().id();
            return station;
        }

        private UUID zone() {
            Set<WorldPos> cells = new LinkedHashSet<>();
            for (int x = 2; x <= 3; x++) {
                for (int z = 2; z <= 3; z++) {
                    cells.add(at(x, z).at(at(x, z).cell().below()));
                }
            }
            return colony.hold(SentryPosts.ZONE, new Claimed.Area(cells)).orElseThrow().id();
        }

        private void simulate(GameTestHelper helper) {
            patrol.refresh(level.getServer());
            simulation = new LaborFixture(colony, level);
            helper.onEachTick(simulation::tick);
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (simulation != null) {
                simulation.close();
            }
            entities.forEach(Entity::discard);
            colony.raze();
        }
    }
}
