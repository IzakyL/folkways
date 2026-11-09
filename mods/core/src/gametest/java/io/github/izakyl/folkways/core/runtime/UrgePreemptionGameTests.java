package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.colony.internal.ColonySnapshot;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Shown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.BodyActions;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.resident.api.TaskTools;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.work.internal.LaborIncident;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UrgePreemptionGameTests {
    private static final ResourceLocation OWNER = id("preemption");
    private static final Map<UUID, List<Urge>> OFFERS = new HashMap<>();
    private static final Set<UUID> ACTION_BLOCKED = new HashSet<>();

    @SubscribeEvent
    public static void declare(Declaring event) {
        BodyActions.register(id("preemption_body_action_gate"), body -> !ACTION_BLOCKED.contains(body.id()));
        event.urges(OWNER, (colony, who, view) -> OFFERS.getOrDefault(colony.id(), List.of()));
        Participations.register(PersonBody.ID, Stake.urge(OWNER), Participation.ALWAYS);
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(UrgePreemptionGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void idleChoiceUsesWeightWithoutPreferringPreemption(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            Urge light = h.urge("light", 0.01, true);
            Urge heavy = h.urge("heavy", 100, false);
            h.offers(light, heavy);
            h.interrupt();
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(heavy.id()),
                "weight chooses idle work, regardless of the preemption flag");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void onlyExplicitPreemptionCanReplaceFiniteOrContinuousWork(GameTestHelper helper) throws Exception {
        for (Workload load : List.of(Workload.Once.of(1000),
                (Workload.Continuous) (level, who) -> true)) {
            try (Harness h = new Harness(helper)) {
                AtomicInteger released = new AtomicInteger();
                Node work = h.node(load, true, released);
                h.start(work);
                Urge heavy = h.urge("heavy", 100, false);
                h.offers(heavy);
                h.interrupt();
                helper.assertTrue(h.runner.holding().orElseThrow().equals(work.id()) && released.get() == 0,
                    "a high weight cannot interrupt either type of running node without permission");
                Urge light = h.urge("light", 0.01, true);
                h.offers(heavy, light);
                h.interrupt();
                h.labor.tick(h.runner, h.level, h.body);
                helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(light.id()) && released.get() == 0,
                    "filter ineligible choices before ranking; a low-weight permitted urge can preempt");
                helper.assertTrue(h.labor.state(work.id()).orElseThrow() == NodeState.WORKING,
                    "suspended colony work keeps its phase");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void personalWorkCanBePreemptedWithoutOscillating(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            Urge first = h.urge("first", 0.1, false);
            h.offers(first);
            h.interrupt();
            Urge forbidden = h.urge("higher", 100, false);
            h.offers(first, forbidden);
            h.interrupt();
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(first.id()),
                "weight alone cannot interrupt personal work either");
            Urge next = h.urge("next", 0.2, true);
            h.offers(first, next);
            h.interrupt();
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(next.id()),
                "a permitted higher-weight choice must interrupt personal work, even below the old threshold");
            h.offers(h.urge("lower", 0.1, true), h.urge("equal", 0.2, true), next);
            h.interrupt();
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(next.id()),
                "lower or tied weights cannot cause personal tasks to alternate");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void withdrawnPersonalWorkYieldsToQueuedWork(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            h.offers(h.urge("wander", 100, false));
            h.interrupt();
            Node job = h.node(Workload.Once.of(1000), true, new AtomicInteger());
            h.queue(job);
            h.offers();
            h.interrupt();
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertTrue(h.runner.holding().orElseThrow().equals(job.id()),
                "withdrawn idle work yields without relying on a magic weight threshold");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unreadyPreemptorDoesNotReleaseCurrentWork(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            AtomicInteger released = new AtomicInteger();
            Node job = h.node(Workload.Once.of(1000), true, released);
            h.start(job);
            Node unready = h.node(Workload.Once.of(1000), false, new AtomicInteger());
            h.offers(new Urge(id("unready"), 100, true, () -> unready));
            h.interrupt();
            helper.assertTrue(h.runner.holding().orElseThrow().equals(job.id()) && released.get() == 0,
                "preemption commits only after the replacement is ready");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void passiveReservationReleasesPlannedWorkButAllowsPersonalUrges(GameTestHelper helper)
            throws Exception {
        for (Workload load : List.of(Workload.Once.of(1000), (Workload.Continuous) (level, who) -> true)) {
            try (Harness h = new Harness(helper)) {
                AtomicInteger released = new AtomicInteger();
                Node job = h.node(load, true, released);
                h.start(job);
                Urge reserve = Urge.reserve(id("reserved"));
                h.offers(reserve);
                helper.assertTrue(h.interrupt(), "reservation is reported to the runner's claim gate");
                helper.assertTrue(h.runner.preempting(), "reservation requests cleanup before returning control");
                helper.assertValueEqual(released.get(), 0, "requesting cleanup does not release the node early");
                h.labor.tick(h.runner, h.level, h.body);
                helper.assertTrue(!h.runner.executing() && h.runner.ownUrge().isEmpty(),
                    "the passive marker does not become an idle node that could compete with combat");
                helper.assertValueEqual(released.get(), 1, "planned work uses its normal release lifecycle once");
                helper.assertTrue(h.labor.state(job.id()).orElseThrow() == NodeState.PENDING,
                    "interrupted work remains retryable");
                h.interrupt();
                helper.assertValueEqual(released.get(), 1, "a continuing reservation never repeats release");
                Urge need = h.urge("fear_or_combat", .001, true);
                h.offers(reserve, need);
                helper.assertTrue(h.interrupt(), "personal work does not remove the labour reservation");
                helper.assertValueEqual(h.runner.ownUrge().orElseThrow(), need.id(),
                    "even a low-weight ordinary personal urge executes while labour is reserved");
                h.interrupt();
                helper.assertValueEqual(h.runner.ownUrge().orElseThrow(), need.id(),
                    "the reservation does not interrupt a personal combat, fear or physiological urge");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void deliberateControlPreemptsAnArbitrarilyHeavyNeedAndKeepsOwnership(GameTestHelper helper)
            throws Exception {
        try (Harness h = new Harness(helper)) {
            Urge need = h.urge("heavy_need", Double.MAX_VALUE, true);
            h.offers(need);
            h.interrupt();
            Urge control = h.control("control");
            h.offers(need, control);
            h.interrupt();
            helper.assertValueEqual(h.runner.pendingUrge().orElseThrow(), control,
                "the control tier wins independently of need weights and waits for cleanup");
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(control.id()),
                "the control tier takes the same runner when its previous holding yields");
            h.offers(need, control, h.urge("other_need", Double.POSITIVE_INFINITY, true));
            h.interrupt();
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(control.id()),
                "an ordinary preemptive need cannot regain the body while deliberate control is active");
            Urge replacement = h.control("replacement_control");
            h.offers(need, replacement);
            h.interrupt();
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(replacement.id()),
                "a new order identity replaces the cancelled order without a larger numeric weight");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void urgesSettleWithoutDuplicatingTheResidentRequest(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            AtomicBoolean settled = new AtomicBoolean();
            AtomicInteger takes = new AtomicInteger();
            AtomicInteger ends = new AtomicInteger();
            h.offers(new Urge(id("settling"), 2.0, true, () -> {
                takes.incrementAndGet();
                NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, WorkSite.at(h.level, h.at), Stances.WHEREVER,
                    Workload.Once.of(0)).done();
                return new Node() {
                    public NodeSpec spec() { return spec; }
                    public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
                    public boolean settled(ServerLevel at) { return settled.get(); }
                    public void ended(ServerLevel at, Ending why) { ends.incrementAndGet(); }
                };
            }));
            h.interrupt();
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertTrue(!h.runner.ownBusiness(), "urge releases resident while result is pending");
            h.interrupt();
            h.settle();
            helper.assertTrue(takes.get() == 1 && ends.get() == 0,
                "pending result must neither succeed early nor duplicate the same request");
            settled.set(true);
            h.settle();
            helper.assertTrue(ends.get() == 1, "urge succeeds after settlement without a worker");
            h.interrupt();
            helper.assertTrue(takes.get() == 2, "a completed urge permits a new request");
            h.runner.letGo(h.level, h.body);
            helper.assertTrue(ends.get() == 2, "interrupted personal work must receive cancellation");
        }
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_test", path);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void outerRevocationReleasesInnerBeforeAnotherStep(GameTestHelper helper) throws Exception {
        for (boolean continuous : List.of(false, true)) {
            try (Harness h = new Harness(helper)) {
                AtomicInteger innerSteps = new AtomicInteger();
                AtomicInteger innerReleases = new AtomicInteger();
                AtomicInteger outerReleases = new AtomicInteger();
                AtomicInteger selections = new AtomicInteger();
                Workload load = continuous ? (Workload.Continuous) (level, who) -> {
                    innerSteps.incrementAndGet();
                    return true;
                } : Workload.Once.of(1000);
                Node piece = h.node(load, true, innerReleases);
                NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER,
                    WorkSite.at(h.level, h.at), Stances.WHEREVER,
                    new Workload.Custom()).done();
                Node outer = new Node() {
                    public NodeSpec spec() {
                        return spec;
                            }

                            public io.github.izakyl.folkways.core.work.api.WorkHolding acquire(
                                io.github.izakyl.folkways.core.work.api.HoldingContext
                                context) {
                                selections.incrementAndGet();
                                return new SequentialHolding(context, List.of(piece), innerReleases::incrementAndGet);
                    }

                    public Outcome commit(ServerLevel level, Worker who) {
                        return Outcome.done();
                    }

                    public void released(ServerLevel level, Worker who, Ending how) {
                        outerReleases.incrementAndGet();
                    }
                };
                h.start(outer);
                h.labor.tick(h.runner, h.level, h.body);
                h.labor.tick(h.runner, h.level, h.body);
                int steps = innerSteps.get();
                int selected = selections.get();
                h.labor.execution().phases().cancelled(outer.id());
                h.labor.tick(h.runner, h.level, h.body);
                h.labor.tick(h.runner, h.level, h.body);
                helper.assertValueEqual(innerSteps.get(), steps, "a revoked outer node stops its inner work first");
                helper.assertValueEqual(selections.get(), selected, "a revoked outer node issues no new piece");
                helper.assertValueEqual(innerReleases.get(), 1, "the inner execution is released once");
                helper.assertValueEqual(outerReleases.get(), 1, "the outer execution is released once");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void interruptionAndPathFailureReportBeforeTheNextHeartbeat(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            List<LaborIncident> events = new ArrayList<>();
            Consumer<LaborIncident> listener = event -> {
                if (event.colony().equals(h.colony.id())) {
                    events.add(event);
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            LaborIncident.subscriptions(Set.of(h.colony.id()));
            try {
                h.body.mob().setCustomName(Component.literal("Pickup witness"));
                Node job = h.node(Workload.Once.of(1000), true, new AtomicInteger());
                h.start(job);
                Urge flee = h.urge("flee", 2, true);
                h.offers(flee);
                h.interrupt();
                helper.assertValueEqual(events.size(), 1, "preemption reports on the same server call");
                helper.assertTrue(events.getFirst().key().contains("urge-preempts/" + flee.id())
                        && events.getFirst().message().getString().contains("Pickup witness")
                        && events.getFirst().message().getString().contains(job.id().toString().substring(0, 8)),
                    "the report identifies the worker, interrupted node and actual preempting urge");
                var failed = BodyRunner.class.getDeclaredMethod("failed", ServerLevel.class,
                    Body.class, Node.class, RefusalKind.class);
                failed.setAccessible(true);
                failed.invoke(h.runner, h.level, h.body, h.runner.interruptedWork().orElseThrow(),
                    LaborRefusal.NO_WAY_THERE);
                helper.assertTrue(events.size() == 2
                        && events.getLast().key().contains(LaborRefusal.NO_WAY_THERE.translationKey()),
                    "a real runner refusal reports immediately without polling a heartbeat");
            } finally {
                LaborIncident.subscriptions(Set.of());
                NeoForge.EVENT_BUS.unregister(listener);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void expiredOneShotUrgeBackoffsDoNotAccumulate(GameTestHelper helper) throws Exception {
        Urges urges = new Urges();
        for (int n = 0; n < 128; n++) {
            urges.refused(id("temporary_" + n), LaborRefusal.WORK_BROKE, 0);
        }
        urges.keenest(List.of(), 100, urge -> true);
        var field = Urges.class.getDeclaredField("cooling");
        field.setAccessible(true);
        helper.assertTrue(((Map<?, ?>) field.get(urges)).isEmpty(),
            "expired refusals must be removed even when a temporary controller never offers its identity again");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void blockedCurrentWorkKeepsOwnershipAndToolWhileBusyTimeFreezes(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            AtomicInteger advances = new AtomicInteger();
            AtomicInteger releases = new AtomicInteger();
            ItemStack tool = h.tool();
            ItemStack expected = tool.copy();
            Node work = h.loanNode(tool, advances, releases);
            h.start(work);
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertValueEqual(advances.get(), 1, "the actual continuous workload borrows its task tool");
            helper.assertTrue(TaskTools.holds(h.body.id(), tool), "the real packed tool is held by this task");
            int busy = h.runner.busyTicks();
            BlockPos feet = h.body.mob().blockPosition();
            BlockPos destination = feet.offset(2, 0, 0);
            var path = new net.minecraft.world.level.pathfinder.Path(List.of(
                new net.minecraft.world.level.pathfinder.Node(feet.getX() + 1, feet.getY(), feet.getZ()),
                new net.minecraft.world.level.pathfinder.Node(destination.getX(), destination.getY(), destination.getZ())), destination, true);
            helper.assertTrue(h.body.mob().getNavigation().moveTo(path, 1), "fixture starts a real native navigation path before blocking");
            helper.assertFalse(h.body.mob().getNavigation().isDone(), "the native path is active before the gate closes");
            ACTION_BLOCKED.add(h.body.id());
            for (int tick = 0; tick < 8; tick++) h.labor.tick(h.runner, h.level, h.body);
            helper.assertTrue(h.runner.holding().orElseThrow().equals(work.id()), "blocked work retains its runner ownership");
            helper.assertTrue(h.labor.state(work.id()).orElseThrow() == NodeState.WORKING, "a temporary gate does not cancel the node");
            helper.assertValueEqual(advances.get(), 1, "blocked ticks never execute the continuous workload");
            helper.assertValueEqual(h.runner.busyTicks(), busy, "paused world ticks do not accrue busy labor time");
            helper.assertTrue(h.body.mob().getNavigation().isDone(), "the gate stops the real native navigation path");
            helper.assertValueEqual(releases.get(), 0, "temporary blocking does not release the owned resource");
            helper.assertTrue(TaskTools.holds(h.body.id(), tool), "the real task tool stays reserved while blocked");
            helper.assertTrue(h.body.pack().getItem(0) == tool && ItemStack.matches(tool, expected), "blocking preserves the actual tool and every component");
            ACTION_BLOCKED.remove(h.body.id());
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertValueEqual(advances.get(), 2, "the same workload resumes when its gate opens");
            helper.assertValueEqual(h.runner.busyTicks(), busy + 1, "busy time resumes for executed work only");
            h.runner.letGo(h.level, h.body);
            helper.assertValueEqual(releases.get(), 1, "normal release closes the original task tool once");
            helper.assertFalse(TaskTools.holds(h.body.id(), tool), "normal release removes the tool reservation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void cancellationWhileBlockedStillReleasesTaskToolExactlyOnce(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            AtomicInteger advances = new AtomicInteger();
            AtomicInteger releases = new AtomicInteger();
            ItemStack tool = h.tool();
            ItemStack expected = tool.copy();
            Node work = h.loanNode(tool, advances, releases);
            h.start(work);
            h.labor.tick(h.runner, h.level, h.body);
            ACTION_BLOCKED.add(h.body.id());
            h.labor.execution().phases().cancelled(work.id());
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertFalse(h.runner.executing(), "terminal ownership is cleared before the temporary execution gate");
            helper.assertValueEqual(releases.get(), 1, "cancelled work receives its real release callback despite blocking");
            helper.assertFalse(TaskTools.holds(h.body.id(), tool), "cancellation releases the live task-tool reservation");
            helper.assertValueEqual(advances.get(), 1, "cancellation cannot advance paused work");
            h.labor.tick(h.runner, h.level, h.body);
            h.labor.tick(h.runner, h.level, h.body);
            h.runner.letGo(h.level, h.body);
            helper.assertValueEqual(releases.get(), 1, "later blocked ticks and repeated letGo cannot release twice");
            helper.assertValueEqual(h.body.pack().count(Items.IRON_AXE), 1, "cleanup neither loses nor duplicates the physical tool");
            helper.assertTrue(ItemStack.matches(h.body.pack().getItem(0), expected), "cleanup preserves all physical tool components and durability");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void blockedIdleRunnerCannotClaimQueuedWorkAndOtherBodiesStayAllowed(GameTestHelper helper) {
        try (Harness h = new Harness(helper); Harness other = new Harness(helper)) {
            AtomicInteger advances = new AtomicInteger();
            Node work = h.node((Workload.Continuous) (level, who) -> { advances.incrementAndGet(); return true; }, true, new AtomicInteger());
            h.queue(work);
            ACTION_BLOCKED.add(h.body.id());
            for (int tick = 0; tick < 8; tick++) h.labor.tick(h.runner, h.level, h.body);
            helper.assertFalse(h.runner.executing(), "blocked idle runner does not begin a new node");
            helper.assertTrue(h.runner.queued().contains(work.id()), "the queued work is preserved for later");
            helper.assertTrue(h.labor.state(work.id()).orElseThrow() == NodeState.PENDING, "blocked idle work remains unclaimed");
            helper.assertValueEqual(h.runner.busyTicks(), 0, "blocked idle time earns no busy ticks");
            helper.assertValueEqual(advances.get(), 0, "blocked idle work never executes");
            Node otherWork = other.node(Workload.Once.of(1000), true, new AtomicInteger());
            other.start(otherWork);
            helper.assertTrue(other.runner.holding().orElseThrow().equals(otherWork.id()), "test gate affects only its explicit resident UUID");
            ACTION_BLOCKED.remove(h.body.id());
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertTrue(h.runner.holding().orElseThrow().equals(work.id()), "opening the gate lets the same idle runner claim queued work");
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertValueEqual(advances.get(), 1, "the newly claimed work runs only after the gate opens");
        }
        helper.succeed();
    }

    private static final class Harness implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ColonyLabor labor;
        final ColonyView view;
        final Body body;
        final BodyRunner runner;
        final BlockPos at;

        Harness(GameTestHelper helper) {
            level = helper.getLevel();
            at = helper.absolutePos(BlockPos.ZERO);
            colony = Colonies.mint(level.getServer());
            ColonyData data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            labor = new ColonyLabor(data, level.dimension());
            view = ColonySnapshot.of(data, level);
            body = PersonBody.RESIDENT.get().create(level);
            runner = new BodyRunner(body.resident().id(), labor.execution());
            labor.run(level, new Lanes(task -> { }, task -> { }));
        }

        Urge control(String name) {
            return new Urge(id(name), 1, true, () -> node(Workload.Once.of(1000), true, new AtomicInteger()),
                Urge.Priority.CONTROL);
        }

        Urge urge(String name, double weight, boolean preempts) {
            return new Urge(id(name), weight, preempts,
                () -> node(Workload.Once.of(1000), true, new AtomicInteger()));
        }

        Node node(Workload load, boolean ready, AtomicInteger releases) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER,
                WorkSite.at(level, at), Stances.WHEREVER, load).done();
            return new Node() {
                public NodeSpec spec() {
                    return spec;
                }

                public boolean ready(ServerLevel level) {
                    return ready;
                }

                public Outcome commit(ServerLevel level, Worker who) {
                    return Outcome.done();
                }

                public void released(ServerLevel level, Worker who, Ending why) {
                    releases.incrementAndGet();
                }
            };
        }

        ItemStack tool() {
            ItemStack tool = new ItemStack(Items.IRON_AXE);
            tool.setDamageValue(7);
            tool.set(DataComponents.CUSTOM_NAME, Component.literal("body action gate task tool"));
            tool.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            body.pack().setItem(0, tool);
            return tool;
        }

        Node loanNode(ItemStack tool, AtomicInteger advances, AtomicInteger releases) {
            UUID task = UUID.randomUUID();
            Workload.Continuous load = (world, who) -> {
                TaskTools.keep(task, body.id(), stack -> stack == tool);
                advances.incrementAndGet();
                return true;
            };
            NodeSpec spec = NodeSpec.of(task, OWNER, WorkSite.at(level, at), Stances.WHEREVER, load).done();
            return new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel world, Worker who) { return Outcome.done(); }
                public void released(ServerLevel world, Worker who, Ending why) {
                    TaskTools.release(task);
                    releases.incrementAndGet();
                }
            };
        }

        void offers(Urge... offered) {
            OFFERS.put(colony.id(), List.of(offered));
        }

        void queue(Node node) {
            PlanningFixture.apply(labor, level, List.of(), Shown.delta(node));
            runner.follow(List.of(node.id()));
        }

        void start(Node node) {
            queue(node);
            labor.tick(runner, level, body);
        }

        boolean interrupt() throws ReflectiveOperationException {
            var method = ColonyLabor.class.getDeclaredMethod("interrupt", ServerLevel.class, ColonyView.class,
                Body.class, BodyRunner.class);
            method.setAccessible(true);
            return (boolean) method.invoke(labor, level, view, body, runner);
        }

        void settle() throws ReflectiveOperationException {
            var method = ColonyLabor.class.getDeclaredMethod("settle", ServerLevel.class);
            method.setAccessible(true);
            method.invoke(labor, level);
        }

        public void close() {
            ACTION_BLOCKED.remove(body.id());
            OFFERS.remove(colony.id());
            runner.letGo(level, body);
            labor.close();
            body.mob().discard();
            colony.raze();
        }
    }
}
