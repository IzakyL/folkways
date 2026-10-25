package io.github.izakyl.folkways.plugins.combat.group;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.labor.LaborFixture;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PointRouteGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(PointRouteGameTests.class); }

    @GameTest(template = "empty_large", timeoutTicks = 1600)
    public static void twoResidentsFollowUsefulPartialPathsAcrossOpenGrass(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people();
        WorldPos goal = scene.at(19, 1, 10);
        // Keep the mandatory click beyond the navigator's horizon while every internal leg
        // remains nearby. This reproduces the 64-block navigator / 128-block command mismatch.
        people.forEach(body -> body.mob().getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(8));
        helper.startSequence().thenIdle(5).thenExecute(() -> {
            var partial = people.getFirst().mob().getNavigation().createPath(Set.of(goal.block(scene.level)), 0);
            helper.assertTrue(partial != null && !partial.canReach() && partial.getNodeCount() > 1,
                "the fixture must offer useful nodes without reaching the distant mandatory click");
            scene.groups.commandSelectionPoints(people, List.of(goal), -1);
        }).thenWaitUntil(() -> helper.assertTrue(scene.group().routeComplete(),
            "both residents finish the distant click and its dwell; phase=" + scene.group().phase()))
            .thenExecute(() -> {
                helper.assertTrue(scene.group().captain().orElseThrow().equals(goal), "partial paths never skip the click");
                scene.assertAtSlots();
                helper.assertTrue(people.stream().allMatch(body -> scene.commands.current(body.id()).orElseThrow().mode()
                        == ResidentCommands.Mode.HOLD), "ordinary travel remains controlled HOLD at its final stop");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 1200)
    public static void intermediateAdvanceDoesNotRequireEveryResidentToHoldExactly(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people();
        scene.groups.commandSelectionPoints(people, List.of(scene.at(19, 1, 10)), -1);
        UnitGroup group = scene.group();
        AtomicReference<WorldPos> previousCaptain = new AtomicReference<>(group.captain().orElseThrow());
        AtomicBoolean previousMoving = new AtomicBoolean(true);
        AtomicBoolean continuousAdvance = new AtomicBoolean();
        helper.onEachTick(() -> {
            WorldPos current = group.captain().orElseThrow();
            if (!current.equals(previousCaptain.get()) && previousMoving.get()) { continuousAdvance.set(true); }
            previousCaptain.set(current);
            previousMoving.set(people.stream().anyMatch(body -> scene.commands.current(body.id())
                .filter(order -> order.status() == ResidentCommands.Status.MOVING
                    && !Reach.standingAt(body.mob().position(), order.destination().block(scene.level))).isPresent()));
            helper.assertTrue(group.phase() != UnitGroup.Phase.FAILED_WAITING,
                "open grass must not fail while members are still making progress");
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(group.routeComplete(), "the squad finishes its route"))
            .thenExecute(() -> {
                helper.assertTrue(continuousAdvance.get(), "at least one intermediate leg advances before exact all-member HOLDING");
                scene.assertAtSlots();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 1800)
    public static void finalDwellCompletesThenAppendKeepsHistoryWithoutRepeatingCompletedStop(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people();
        WorldPos first = scene.at(12, 1, 10);
        WorldPos second = scene.at(19, 1, 10);
        scene.groups.appendSelectionPoint(people, first);
        UnitGroup group = scene.group();
        var generation = group.pointCommandId();
        AtomicLong assembledAt = new AtomicLong(Long.MIN_VALUE);
        helper.onEachTick(() -> {
            if (group.patrolStop() != 0 || !group.captain().orElseThrow().equals(first)) { return; }
            if (scene.allAtSlots()) { assembledAt.compareAndSet(Long.MIN_VALUE, scene.level.getGameTime()); }
            else { assembledAt.set(Long.MIN_VALUE); }
            if (group.routeComplete()) {
                helper.assertTrue(assembledAt.get() != Long.MIN_VALUE
                        && scene.level.getGameTime() - assembledAt.get() >= 20,
                    "an open terminal point completes only after twenty consecutive physically assembled ticks");
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(group.routeComplete(), "the first open point finishes its dwell"))
            .thenIdle(50).thenExecute(() -> {
                scene.groups.appendSelectionPoint(people, second);
                helper.assertTrue(!group.routeComplete(), "an accepted next point clears terminal completion immediately");
                helper.assertTrue(group.pointRoute().equals(List.of(first, second)) && group.pointCommandId().equals(generation),
                    "appending keeps the authored history and the original command generation");
            }).thenWaitUntil(() -> helper.assertValueEqual(group.patrolStop(), 1,
                "an append after completed dwell advances without repeating the completed stop"))
            .thenWaitUntil(() -> helper.assertTrue(group.routeComplete(), "the appended point also completes its dwell"))
            .thenExecute(scene::assertAtSlots).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 1200)
    public static void temporarilyAirborneReferenceWaitsAndRetriesInsteadOfFailingTheSquad(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people();
        scene.groups.commandSelectionPoints(people, List.of(scene.at(19, 1, 10)), -1);
        UnitGroup group = scene.group();
        people.forEach(body -> body.mob().setOnGround(false));
        group.tick();
        helper.assertTrue(group.phase() == UnitGroup.Phase.WAITING_FOR_MEMBER && group.failedMembers().isEmpty(),
            "one temporarily unavailable ground path retains the current command for a later tick");
        people.forEach(body -> body.mob().setOnGround(true));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(group.routeComplete(),
            "the normal runner resumes after ground navigation becomes available"))
            .thenExecute(scene::assertAtSlots).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 2800)
    public static void blockedRouteEndsWithAReasonAndOwnedMembershipCanAcceptAReplacement(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people();
        for (int y = 1; y <= 3; y++) {
            // The surrounding flat GameTest world makes a single wall bypassable. Enclose
            // the target on all four sides while keeping several safe interior assembly cells.
            for (int z = 5; z <= 15; z++) {
                helper.setBlock(new BlockPos(14, y, z), Blocks.STONE);
                helper.setBlock(new BlockPos(21, y, z), Blocks.STONE);
            }
            for (int x = 14; x <= 21; x++) {
                helper.setBlock(new BlockPos(x, y, 5), Blocks.STONE);
                helper.setBlock(new BlockPos(x, y, 15), Blocks.STONE);
            }
        }
        scene.groups.commandSelectionPoints(people, List.of(scene.at(19, 1, 10)), -1);
        UnitGroup group = scene.group();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.FAILED_WAITING,
            "a blocked mandatory point must finish bounded recovery in controlled failure"))
            .thenExecute(() -> {
                helper.assertTrue(group.endReason() == UnitGroup.EndReason.ROUTE_NO_PATH
                        || group.endReason() == UnitGroup.EndReason.ROUTE_MEMBER_BLOCKED
                        || group.endReason() == UnitGroup.EndReason.ROUTE_TIMED_OUT,
                    "route failure reports its specific bounded failure category");
                helper.assertTrue(!group.failedMembers().isEmpty() && !group.routeComplete(),
                    "the failure identifies affected members and never reports the click complete");
                helper.assertTrue(people.stream().allMatch(body -> scene.groups.ofResident(body.id()).orElseThrow() == group),
                    "route failure keeps actual squad membership for a replacement command");
                scene.groups.commandSelectionPoints(people, List.of(scene.at(5, 1, 10)), -1);
            }).thenWaitUntil(() -> helper.assertTrue(scene.group().routeComplete(), "a new nearby command replaces failure waiting"))
            .thenExecute(scene::assertAtSlots).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 1800)
    public static void retainedMissingMemberPreventsMandatoryStopCompletionUntilItReturns(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people();
        WorldPos first = scene.at(12, 1, 10);
        scene.groups.appendSelectionPoint(people, first);
        scene.groups.appendSelectionPoint(people, scene.at(19, 1, 10));
        UnitGroup group = scene.group();
        CompoundTag saved = new CompoundTag();
        Body away = people.get(1);
        AtomicLong reunitedAt = new AtomicLong(Long.MIN_VALUE);
        helper.onEachTick(() -> {
            if (group.patrolStop() == 0 && group.missing().isEmpty() && scene.allAtSlots()) {
                reunitedAt.compareAndSet(Long.MIN_VALUE, scene.level.getGameTime());
            }
            if (group.patrolStop() > 0) {
                helper.assertTrue(reunitedAt.get() != Long.MIN_VALUE
                        && scene.level.getGameTime() - reunitedAt.get() >= 20,
                    "the returned complete squad must finish the mandatory stop's own dwell before advancing");
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(group.captain().orElseThrow().equals(first)
                && group.phase() == UnitGroup.Phase.HOLDING, "the squad first physically assembles at its mandatory point"))
            .thenExecute(() -> {
                away.mob().save(saved);
                away.mob().remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                reunitedAt.set(Long.MIN_VALUE);
            }).thenIdle(40).thenExecute(() -> {
                helper.assertTrue(group.members().contains(away.id()) && group.missing().equals(Set.of(away.id())),
                    "the temporarily unloaded resident remains an actual member");
                helper.assertTrue(group.patrolStop() == 0 && !group.routeComplete()
                        && group.phase() == UnitGroup.Phase.WAITING_FOR_MEMBER && group.waitingMembers() == 1,
                    "present residents cannot finish the missing member's mandatory stop or skip ahead");
                Body returned = (Body) EntityType.loadEntityRecursive(saved, scene.level, entity -> entity);
                scene.bodies.set(1, returned);
                scene.level.addFreshEntity(returned.mob());
            }).thenWaitUntil(() -> helper.assertTrue(group.routeComplete(), "the reunited squad eventually completes the following stop"))
            .thenExecute(scene::assertAtSlots).thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void individualRecoveryNeverReassignsAHealthyMembersExistingTarget(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<Body> people = scene.people();
        WorldPos anchor = scene.at(12, 1, 10);
        PointRoute route = new PointRoute(scene.groups, people, List.of(anchor), -1);
        var previous = new java.util.LinkedHashMap<java.util.UUID, WorldPos>();
        previous.put(people.getFirst().id(), anchor);
        WorldPos healthy = scene.at(10, 1, 10);
        previous.put(people.get(1).id(), healthy);
        // An existing healthy handle still names this slot. The recovery planner must reserve
        // that exact target rather than silently moving its bookkeeping to a nearby safe cell.
        helper.setBlock(new BlockPos(10, 1, 10), Blocks.STONE);
        List<WorldPos> recovered = route.slots(people, anchor, previous, Set.of(), Set.of(people.getFirst().id()));
        helper.assertTrue(recovered.get(1).equals(healthy) && !recovered.getFirst().equals(healthy),
            "only the replaced member may change target; unchanged handles retain their exact reserved cells");
        helper.succeed();
    }

    private static final class Scene implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ResidentCommands commands;
        final UnitGroups groups;
        final LaborFixture simulation;
        final List<Body> bodies = new ArrayList<>();

        Scene(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            commands = ResidentCommands.of(colony);
            groups = UnitGroups.of(colony);
            simulation = new LaborFixture(colony, level);
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 22; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK); }
            }
            helper.onEachTick(simulation::tick);
            helper.testInfo.addListener(this);
        }

        WorldPos at(int x, int y, int z) { return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z))); }

        List<Body> people() {
            for (int z : new int[] {8, 10}) {
                Body body = PersonBody.RESIDENT.get().create(level);
                BlockPos cell = at(2, 1, z).block(level);
                body.mob().moveTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
                body.mob().setOnGround(true);
                colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
                body.joinColony(colony.id());
                bodies.add(body);
                level.addFreshEntity(body.mob());
            }
            return List.copyOf(bodies);
        }

        UnitGroup group() { return groups.ofResident(bodies.getFirst().id()).orElseThrow(); }

        boolean allAtSlots() {
            return bodies.stream().allMatch(body -> commands.current(body.id()).filter(order ->
                order.status() == ResidentCommands.Status.HOLDING
                    && Reach.standingAt(body.mob().position(), order.destination().block(level))).isPresent());
        }

        void assertAtSlots() {
            helper.assertTrue(allAtSlots(), "every member physically holds its own final slot");
            helper.assertValueEqual(new HashSet<>(group().destinations().values()).size(), bodies.size(), "final slots are distinct");
        }

        void close() {
            simulation.close();
            bodies.forEach(body -> body.mob().discard());
            colony.raze();
        }

        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
