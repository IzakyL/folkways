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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PointQueueGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(PointQueueGameTests.class); }

    @GameTest(template = "empty_large", timeoutTicks = 3000)
    public static void clicksAppendDuringTravelWithoutRewindingAndCloseOnlyTheChosenSuffix(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<WorldPos> route = List.of(scene.at(12, 8), scene.at(18, 8), scene.at(18, 16), scene.at(8, 16));
        scene.groups.appendSelectionPoint(scene.people, route.getFirst());
        UnitGroup group = scene.group();
        WorldPos initialCaptain = group.captain().orElseThrow();
        helper.assertTrue(group.pointQueueOpen() && group.pointRoute().equals(List.of(route.getFirst()))
                && !scene.handles().isEmpty(), "the first click owns actual movement immediately");
        helper.onEachTick(() -> group.captain().ifPresent(captain -> group.destinations().values().forEach(slot ->
            helper.assertTrue(slot.cell().distSqr(captain.cell()) <= 3 * 3,
                "every member moves around the current logical captain"))));
        var sequence = helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(!group.captain().orElseThrow().equals(initialCaptain)
                    && group.patrolStop() == 0 && group.phase() == UnitGroup.Phase.LINE,
                "the initial click starts travelling before any second click is sent"))
            .thenExecute(() -> {
                WorldPos movingCaptain = group.captain().orElseThrow();
                List<ResidentCommands.Order> handles = scene.handles();
                for (WorldPos point : route.subList(1, route.size())) {
                    scene.groups.appendSelectionPoint(scene.people.reversed(), point);
                    scene.assertUnchanged(group, handles, movingCaptain, 0);
                }
                scene.groups.appendSelectionPoint(scene.people, route.get(1));
                scene.assertUnchanged(group, handles, movingCaptain, 0);
                helper.assertTrue(group.pointRoute().equals(route) && group.cycleStart() == 1 && !group.pointQueueOpen(),
                    "A-B-C-D-B closes B-C-D while keeping A as an unvisited mandatory prefix");
            });
        for (int stop : new int[] {0, 1, 2, 3, 1, 2}) {
            sequence.thenWaitUntil(() -> helper.assertTrue(group.patrolStop() == stop
                    && group.phase() == UnitGroup.Phase.HOLDING,
                "all members assemble at each queued point in order: " + stop))
                .thenExecute(() -> {
                    helper.assertTrue(group.captain().orElseThrow().equals(route.get(stop)),
                        "the captain reaches the exact mandatory click");
                    scene.assertAssembled(group);
                }).thenIdle(19)
                .thenExecute(() -> helper.assertValueEqual(group.patrolStop(), stop,
                    "every complete assembly remains at its point for twenty ticks"));
        }
        sequence.thenExecute(group::release).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 3800)
    public static void aLateAppendWakesTheLastStopWithoutRepeatingItsDwellOrTimingOut(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        WorldPos first = scene.at(8, 10);
        WorldPos second = scene.at(16, 10);
        scene.groups.appendSelectionPoint(scene.people, first);
        UnitGroup group = scene.group();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(group.phase() == UnitGroup.Phase.HOLDING,
                "the first point is fully assembled"))
            // A completed open endpoint is waiting for input, not a stalled 2400-tick route leg.
            .thenIdle(2450)
            .thenExecute(() -> {
                helper.assertTrue(group.pointQueueOpen() && group.patrolStop() == 0,
                    "the open endpoint retains its complete squad and queue while waiting");
                scene.assertAssembled(group);
                List<ResidentCommands.Order> handles = scene.handles();
                scene.groups.appendSelectionPoint(scene.people, second);
                scene.assertUnchanged(group, handles, first, 0);
            }).thenIdle(2)
            .thenExecute(() -> helper.assertValueEqual(group.patrolStop(), 1,
                "a late append advances immediately using the already completed dwell"))
            .thenWaitUntil(() -> helper.assertTrue(group.patrolStop() == 1 && group.phase() == UnitGroup.Phase.HOLDING,
                "all members reach the appended point without restarting the prefix"))
            .thenExecute(() -> {
                helper.assertTrue(group.captain().orElseThrow().equals(second), "the late point is the new anchor");
                scene.assertAssembled(group);
                group.release();
                helper.assertTrue(!group.pointQueueOpen() && group.pointRoute().isEmpty() && scene.handles().isEmpty(),
                    "release forgets the waiting queue and all of its owned handles");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 3800)
    public static void longCombatPausesTravelBudgetThenReassemblesAndResumesTheSameRoute(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        List<WorldPos> stops = List.of(scene.at(8, 10), scene.at(16, 10));
        PointRoute route = new PointRoute(scene.groups, scene.people, stops, 0, false, true);
        UnitGroup group = scene.groups.create(scene.people);
        group.startPoints(route);
        AtomicBoolean fighting = new AtomicBoolean();
        AtomicLong budgetAtContact = new AtomicLong();
        AtomicBoolean returning = new AtomicBoolean();
        AtomicLong reassembledAt = new AtomicLong(Long.MIN_VALUE);
        AtomicReference<List<ResidentCommands.Order>> combatHandles = new AtomicReference<>();
        // Renew the same short combat leases used by real guards. This isolates route accounting
        // from enemy health, weapons and damage while the real command runner retains every slot.
        helper.onEachTick(() -> {
            if (fighting.get()) { scene.handles().forEach(ResidentCommands.Order::engaging); }
            if (!returning.get()) { return; }
            if (group.patrolStop() == 0) {
                boolean ready = scene.people.stream().allMatch(body -> scene.commands.current(body.id())
                    .filter(order -> order.status() == ResidentCommands.Status.HOLDING && !order.engaged()
                        && !order.yieldingToDanger() && Reach.standingAt(body.mob().position(), order.destination().block(scene.level))).isPresent());
                if (ready) { reassembledAt.compareAndSet(Long.MIN_VALUE, scene.level.getGameTime()); }
                else { reassembledAt.set(Long.MIN_VALUE); }
            } else {
                helper.assertTrue(reassembledAt.get() != Long.MIN_VALUE
                        && scene.level.getGameTime() - reassembledAt.get() >= 20,
                    "leaving the mandatory stop requires twenty physical, combat-free assembly ticks after return");
            }
        });
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(group.patrolStop() == 0 && group.phase() == UnitGroup.Phase.HOLDING,
                "the complete squad first reaches its initial patrol point"))
            .thenExecute(() -> {
                budgetAtContact.set(route.travellingTicks());
                combatHandles.set(scene.handles());
                fighting.set(true);
                scene.handles().forEach(ResidentCommands.Order::engaging);
            }).thenIdle(2450)
            .thenExecute(() -> {
                helper.assertTrue(group.phase() == UnitGroup.Phase.ENGAGING && group.patrolStop() == 0,
                    "combat beyond the complete route timeout keeps the same patrol point active");
                scene.assertUnchanged(group, combatHandles.get(), stops.getFirst(), 0);
                helper.assertTrue(route.travellingTicks() <= budgetAtContact.get() + 1,
                    "active combat leases consume no route travel budget, even before nextStop resets it");
                fighting.set(false);
                returning.set(true);
                Body displaced = scene.people.getLast();
                BlockPos slot = group.destinations().get(displaced.id()).block(scene.level);
                displaced.mob().moveTo(slot.getX() + 1.5, slot.getY(), slot.getZ() + .5);
            })
            .thenWaitUntil(() -> helper.assertTrue(group.patrolStop() == 0 && reassembledAt.get() != Long.MIN_VALUE,
                "the combat lease expires and every survivor physically reforms before the dwell completes"))
            .thenExecute(() -> scene.assertAssembled(group))
            .thenWaitUntil(() -> helper.assertTrue(group.patrolStop() == 1 && group.phase() == UnitGroup.Phase.HOLDING,
                "the original route continues after a long battle without a stale travel timeout"))
            .thenExecute(() -> {
                scene.assertAssembled(group);
                helper.assertTrue(group.pointRoute().equals(stops) && group.captain().orElseThrow().equals(stops.get(1)),
                    "post-combat movement retains the original mandatory points and logical captain");
                group.release();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large")
    public static void invalidAppendsPreserveTheLiveQueueAndSixteenPointsCanStillClose(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        scene.groups.appendSelectionPoint(scene.people, scene.at(5, 14));
        UnitGroup group = scene.group();
        List<ResidentCommands.Order> handles = scene.handles();
        WorldPos captain = group.captain().orElseThrow();
        helper.setBlock(new BlockPos(18, 1, 18), Blocks.STONE);
        for (WorldPos invalid : List.of(scene.at(18, 18), scene.at(150, 10))) {
            scene.assertRejected(() -> scene.groups.appendSelectionPoint(scene.people, invalid));
            scene.assertUnchanged(group, handles, captain, 0);
            helper.assertTrue(group.pointRoute().equals(List.of(scene.at(5, 14))) && group.pointQueueOpen(),
                "unsafe and distant appends preserve the accepted open queue before capacity is reached");
        }
        for (int x = 6; x <= 20; x++) { scene.groups.appendSelectionPoint(scene.people, scene.at(x, 14)); }
        List<WorldPos> points = group.pointRoute();
        helper.assertValueEqual(points.size(), 16, "sixteen distinct accepted clicks remain bounded");
        for (WorldPos invalid : List.of(scene.at(20, 14), scene.at(20, 17))) {
            scene.assertRejected(() -> scene.groups.appendSelectionPoint(scene.people, invalid));
            scene.assertUnchanged(group, handles, captain, 0);
            helper.assertTrue(group.pointRoute().equals(points) && group.cycleStart() == -1 && group.pointQueueOpen(),
                "duplicate-tail and over-capacity clicks cannot alter accepted intent");
        }
        scene.groups.appendSelectionPoint(scene.people, points.get(1));
        helper.assertTrue(group.cycleStart() == 1 && !group.pointQueueOpen() && group.pointRoute().equals(points),
            "closing an existing point does not consume a seventeenth slot");
        scene.assertRejected(() -> scene.groups.appendSelectionPoint(scene.people, scene.at(10, 18)));
        scene.assertUnchanged(group, handles, captain, 0);
        helper.assertValueEqual(group.cycleStart(), 1, "a closed route rejects further intent without reopening");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void anInvalidFirstClickPreservesThePreviousCommandAndNormalMoveReplacesTheWholeQueue(GameTestHelper helper) {
        Scene scene = new Scene(helper);
        WorldPos first = scene.at(10, 10);
        scene.groups.commandSelectionPoints(scene.people, List.of(first), -1);
        UnitGroup original = scene.group();
        List<ResidentCommands.Order> handles = scene.handles();
        WorldPos captain = original.captain().orElseThrow();
        helper.setBlock(new BlockPos(18, 1, 18), Blocks.STONE);
        scene.assertRejected(() -> scene.groups.appendSelectionPoint(scene.people, scene.at(18, 18)));
        scene.assertUnchanged(original, handles, captain, 0);
        helper.assertTrue(!original.pointQueueOpen() && original.pointRoute().equals(List.of(first)),
            "an invalid first append cannot convert or release a normal move");
        scene.groups.appendSelectionPoint(scene.people, scene.at(16, 10));
        scene.assertUnchanged(original, handles, captain, 0);
        helper.assertTrue(original.pointQueueOpen() && original.pointRoute().size() == 2,
            "a valid normal target can become the prefix of the first appended queue");
        WorldPos replacement = scene.at(8, 16);
        scene.groups.commandSelectionPoints(scene.people, List.of(replacement), -1);
        UnitGroup next = scene.group();
        helper.assertTrue(next != original && next.pointRoute().equals(List.of(replacement))
                && next.cycleStart() == -1 && !next.pointQueueOpen(),
            "ordinary right click replaces the complete queue with its new target");
        helper.assertTrue(handles.stream().allMatch(order -> order.status() == ResidentCommands.Status.RELEASED),
            "replacement relinquishes the old handles only after its new batch is accepted");
        helper.succeed();
    }

    private static final class Scene implements GameTestListener {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final UnitGroups groups;
        final ResidentCommands commands;
        final LaborFixture simulation;
        final List<Body> people = new ArrayList<>();

        Scene(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            groups = UnitGroups.of(colony);
            commands = ResidentCommands.of(colony);
            simulation = new LaborFixture(colony, level);
            for (int x = 0; x < 22; x++) {
                for (int z = 0; z < 22; z++) { helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE); }
            }
            for (int n = 0; n < 4; n++) {
                Body body = PersonBody.RESIDENT.get().create(level);
                BlockPos cell = at(2, 7 + n * 2).block(level);
                body.mob().moveTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
                colony.hold(PersonContent.ID, Held.Entity.of(body.mob())).orElseThrow();
                body.joinColony(colony.id());
                people.add(body);
                level.addFreshEntity(body.mob());
            }
            helper.onEachTick(simulation::tick);
            helper.testInfo.addListener(this);
        }

        WorldPos at(int x, int z) { return WorldPos.of(level, helper.absolutePos(new BlockPos(x, 1, z))); }
        UnitGroup group() { return groups.ofResident(people.getFirst().id()).orElseThrow(); }
        List<ResidentCommands.Order> handles() {
            return people.stream().flatMap(body -> commands.current(body.id()).stream()).toList();
        }
        void assertUnchanged(UnitGroup group, List<ResidentCommands.Order> handles, WorldPos captain, int stop) {
            helper.assertTrue(people.stream().allMatch(body -> groups.ofResident(body.id()).orElseThrow() == group)
                    && handles().equals(handles) && group.captain().orElseThrow().equals(captain) && group.patrolStop() == stop,
                "route edits retain actual membership, owned handles, captain and progress");
        }
        void assertRejected(Runnable attempt) {
            try { attempt.run(); helper.fail("invalid append must be rejected"); }
            catch (IllegalArgumentException expected) { }
        }
        void assertAssembled(UnitGroup group) {
            helper.assertTrue(people.stream().allMatch(body -> Reach.standingAt(body.mob().position(),
                group.destinations().get(body.id()).block(level))), "the whole squad physically occupies its slots");
        }
        void close() {
            simulation.close();
            people.forEach(body -> body.mob().discard());
            colony.raze();
        }
        public void testStructureLoaded(GameTestInfo info) { }
        public void testPassed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testFailed(GameTestInfo info, GameTestRunner runner) { close(); }
        public void testAddedForRerun(GameTestInfo original, GameTestInfo rerun, GameTestRunner runner) { }
    }
}
