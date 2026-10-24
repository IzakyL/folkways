package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.plan.Claims;
import io.github.izakyl.folkways.core.engine.plan.Cooldowns;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Planner;
import io.github.izakyl.folkways.core.engine.plan.Scheduler;
import io.github.izakyl.folkways.core.engine.plan.Shown;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.WeaveDelta;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SchedulingRecoveryGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "schedule_recovery");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SchedulingRecoveryGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failedBackgroundSchedulesReportOnlyWhenAppliedOnTheServer(GameTestHelper helper) {
        var data = new io.github.izakyl.folkways.core.engine.colony.ColonyData();
        ColonyLabor labor = new ColonyLabor(data, helper.getLevel().dimension());
        List<LaborIncident> events = new ArrayList<>();
        Consumer<LaborIncident> listener = event -> {
            if (event.colony().equals(data.colonyId())) {
                helper.assertTrue(helper.getLevel().getServer().isSameThread(), "chat incidents belong to the server thread");
                events.add(event);
            }
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        LaborIncident.subscriptions(Set.of(data.colonyId()));
        try {
            Fixture scene = new Fixture(helper, 1, null, Placement.NOWHERE, null, labor);
            scene.enqueue(scene.initial(), scene.crew);
            scene.pipeline.applyReady(helper.getLevel());
            events.clear();
            scene.enqueue(scene.changed(), scene.crew);
            helper.assertTrue(events.isEmpty(), "the worker lane must not send chat directly");
            scene.pipeline.applyReady(helper.getLevel());
            helper.assertTrue(events.size() == 1 && events.getFirst().key().contains("planning/schedule"),
                "the next simulation publication reports the fault without waiting for a heartbeat");
        } finally {
            LaborIncident.subscriptions(Set.of());
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
            labor.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aPartialLayoutRetriesWithoutMessagesAndKeepsItsPublishedDiff(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, 3, null, Placement.NOWHERE, null);
        scene.begin();
        int queried = scene.queries;
        for (int i = 0; i < 100; i++) scene.pipeline.applyReady(helper.getLevel());
        helper.assertValueEqual(scene.queries, queried, "a failing layout cannot spin again in the same simulation tick");
        scene.afterTicks(() -> {
            helper.assertValueEqual(scene.failures, 3, "three separate partial layouts really failed");
            helper.assertValueEqual(scene.published.size(), 2, "only the original and recovered schedules are published");
            Scheduler.Scheduled recovered = scene.published.getLast();
            helper.assertTrue(recovered.all().get(scene.first.id()).nodes().equals(List.of(scene.head.id(), scene.tail.id())),
                "the whole chain is laid once after recovery");
            helper.assertTrue(recovered.changed().get(scene.second.id()).nodes().isEmpty(),
                "the removed old route is diffed against the last successful publication");
            helper.assertTrue(scene.inbox.size() == 0, "recovery neither needs nor invents planner messages");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void changesReceivedDuringBackoffJoinTheCurrentGraphOnce(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, 2, null, Placement.NOWHERE, null);
        scene.begin();
        Vertex added = scene.vertex(scene.end.at(scene.end.cell().offset(2, 0, 0)));
        Resident newcomer = new Resident(UUID.randomUUID(), OWNER);
        List<Crew.Hand> hands = new ArrayList<>(scene.crew.hands());
        hands.add(new Crew.Hand(newcomer, scene.start, 6, 6, 1, List.of(), List.of(), Optional.empty()));
        WeaveDelta next = Shown.delta(Map.of(added.id(), added), List.of(new Before(scene.tail.id(), added.id())),
            List.of(), Map.of(added.id(), newcomer.id()), Set.of());
        int queried = scene.queries;
        scene.enqueue(next, new Crew(hands));
        helper.assertValueEqual(scene.queries, queried, "new inputs cannot bypass recovery backoff");
        scene.afterTicks(() -> {
            helper.assertTrue(scene.published.getLast().all().get(scene.first.id()).nodes()
                .equals(List.of(scene.head.id(), scene.tail.id())),
                "recovery uses the graph including changes received while it waited");
            helper.assertTrue(scene.published.getLast().all().get(newcomer.id()).nodes().equals(List.of(added.id())),
                "the latest crew snapshot and the new node's worker pin are retained");
            helper.assertValueEqual(scene.scheduler.size(), 3, "accepted input vertices are neither lost nor duplicated");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void closingDuringARecoveryRejectsItsLatePublication(GameTestHelper helper) {
        Fixture scene = new Fixture(helper, 1, null, Placement.NOWHERE, null);
        scene.begin();
        List<Runnable> cleanup = new ArrayList<>();
        scene.whenRecovered = () -> scene.pipeline.close(Runnable::run, cleanup::add);
        scene.afterTicks(() -> {
            helper.assertValueEqual(scene.published.size(), 1, "a retired pipeline rejects a recovery already computing");
            helper.assertTrue(!cleanup.isEmpty(), "the old scheduler's cleanup stays on its lane");
            cleanup.forEach(Runnable::run);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void asynchronousSuccessAndRecoveryPublishInOrderAcrossAFailure(GameTestHelper helper) {
        new AsyncRecovery(helper, false).start();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void asynchronousRecoveryFinishingAfterCloseCannotPublish(GameTestHelper helper) {
        new AsyncRecovery(helper, true).start();
    }

    // A real serial lane, not an inline executor. The server only polls signals on simulation ticks; the lane
    // reads immutable fixture inputs and never the level or helper. A listener also cleans up timeout failures.
    private static final class AsyncRecovery {
        final GameTestHelper helper;
        final Fixture scene;
        final boolean closeDuringRecovery;
        final Thread server = Thread.currentThread();
        final ExecutorService pool;
        final Executor lane;
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger finished = new AtomicInteger();
        final AtomicLong simulationTick = new AtomicLong();
        final AtomicLong retrySubmittedAt = new AtomicLong(Long.MIN_VALUE);
        final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        final CountDownLatch inRecovery = new CountDownLatch(1);
        final CountDownLatch releaseRecovery = new CountDownLatch(1);
        final AtomicBoolean firstRecovery = new AtomicBoolean();
        final AtomicBoolean releasedNormally = new AtomicBoolean();
        final List<Boolean> serverPublications = new ArrayList<>();
        boolean successPrecededFailureNotice;
        boolean retired;
        int phase;
        long armedAt;

        AsyncRecovery(GameTestHelper helper, boolean closeDuringRecovery) {
            this.helper = helper;
            this.closeDuringRecovery = closeDuringRecovery;
            scene = new Fixture(helper, 1, null, Placement.NOWHERE, null);
            pool = Executors.newSingleThreadExecutor(task -> {
                Thread worker = new Thread(task, "folkways-test-schedule-recovery");
                worker.setDaemon(true);
                return worker;
            });
            lane = task -> {
                if (submitted.incrementAndGet() == 3) retrySubmittedAt.set(simulationTick.get());
                pool.execute(() -> {
                    try { task.run(); }
                    catch (RuntimeException | Error broken) { workerFailure.compareAndSet(null, broken); }
                    finally { finished.incrementAndGet(); }
                });
            };
            scene.delivered = result -> {
                serverPublications.add(Thread.currentThread() == server);
                if (scene.published.size() == 1) {
                    successPrecededFailureNotice = field(scene.pipeline, "retry") == null;
                }
            };
            scene.whenRecovered = () -> {
                if (firstRecovery.compareAndSet(false, true)) {
                    inRecovery.countDown();
                    if (closeDuringRecovery) {
                        try {
                            if (!releaseRecovery.await(2, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test did not release the recovery signal");
                            }
                            releasedNormally.set(true);
                        } catch (InterruptedException broken) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(broken);
                        }
                    }
                }
            };
            helper.testInfo.addListener(new GameTestListener() {
                public void testStructureLoaded(GameTestInfo test) { }
                public void testPassed(GameTestInfo test, GameTestRunner runner) { abort(); }
                public void testFailed(GameTestInfo test, GameTestRunner runner) { abort(); }
                public void testAddedForRerun(GameTestInfo before, GameTestInfo after, GameTestRunner runner) { abort(); }
            });
        }

        void start() {
            try {
                simulationTick.set(helper.getLevel().getGameTime());
                scene.enqueue(scene.initial(), scene.crew, lane);
                helper.onEachTick(this::tick);
            } catch (RuntimeException | Error broken) {
                abort();
                throw broken;
            }
        }

        void tick() {
            try {
                simulationTick.set(helper.getLevel().getGameTime());
                helper.assertTrue(workerFailure.get() == null, "the serial executor must not lose an uncaught task failure");
                if (phase == 0 && finished.get() >= 1) {
                    // Keep the first success queued for the server while a later layout partially fails.
                    helper.assertTrue(scene.published.isEmpty(), "background success waits for a simulation-tick publication");
                    scene.enqueue(scene.changed(), scene.crew, lane);
                    phase = 1;
                } else if (phase == 1 && finished.get() >= 2) {
                    helper.assertValueEqual(scene.failures, 1, "the second asynchronous layout really failed");
                    helper.assertTrue(scene.partialFailures.equals(List.of(true)), "failure followed a real Tours mutation");
                    helper.assertTrue(scene.published.isEmpty(), "neither worker task applies callbacks itself");
                    armedAt = simulationTick.get();
                    scene.pipeline.applyReady(helper.getLevel());
                    helper.assertValueEqual(scene.published.size(), 1, "the queued success is published before retrying");
                    helper.assertTrue(successPrecededFailureNotice, "made preserves success before its later failure notice");
                    phase = 2;
                } else if (phase == 2) {
                    scene.pipeline.applyReady(helper.getLevel());
                    if (closeDuringRecovery && inRecovery.getCount() == 0) {
                        // The lane is parked inside the replacement Tours. Closing queues cleanup behind it,
                        // then releases it to compute a valid but retired result on that same serial lane.
                        retire();
                        phase = 3;
                    } else if (!closeDuringRecovery && finished.get() >= 3) {
                        scene.pipeline.applyReady(helper.getLevel());
                        helper.assertValueEqual(scene.published.size(), 2, "the recovered result publishes exactly once");
                        retire();
                        phase = 3;
                    }
                } else if (phase == 3 && pool.isTerminated()) {
                    scene.pipeline.applyReady(helper.getLevel());
                    helper.assertTrue(retrySubmittedAt.get() > armedAt, "only a later simulation tick submits the recovery");
                    helper.assertTrue(serverPublications.stream().allMatch(Boolean::booleanValue),
                        "all publications run on the owning server thread");
                    helper.assertTrue(scene.published.getFirst().all().get(scene.second.id()).nodes().equals(List.of(scene.old.id())),
                        "the first publication remains the original successful route");
                    helper.assertValueEqual((Integer) field(scene.pipeline, "scheduleFailures"), 0,
                        "the replacement layout finished successfully before cleanup");
                    if (closeDuringRecovery) {
                        helper.assertTrue(releasedNormally.get(), "the in-flight recovery was released normally after close");
                        helper.assertValueEqual(scene.published.size(), 1, "close rejects the late replacement publication");
                    } else {
                        Scheduler.Scheduled recovered = scene.published.getLast();
                        helper.assertTrue(recovered.all().get(scene.first.id()).nodes().equals(List.of(scene.head.id(), scene.tail.id())),
                            "the second publication is the complete replacement route");
                        helper.assertTrue(recovered.changed().get(scene.second.id()).nodes().isEmpty(),
                            "the recovered diff still removes the earlier published route");
                    }
                    helper.succeed();
                }
            } catch (RuntimeException | Error broken) {
                abort();
                throw broken;
            }
        }

        void retire() {
            if (retired) return;
            retired = true;
            try { scene.pipeline.close(Runnable::run, lane); }
            finally {
                releaseRecovery.countDown();
                pool.shutdown();
            }
        }

        void abort() {
            try { retire(); }
            finally {
                releaseRecovery.countDown();
                pool.shutdownNow();
            }
        }
    }

    // Fail and rebuild the real route projection without a production fault hook or fake scheduler.
    static final class Fixture {
        final GameTestHelper helper;
        final Scheduler scheduler;
        final Inbox inbox = new Inbox();
        final Pipeline pipeline;
        final Resident first = new Resident(UUID.randomUUID(), OWNER);
        final Resident second;
        final WorldPos start;
        final WorldPos end;
        final Vertex old;
        final Vertex head;
        final Vertex tail;
        final Crew crew;
        final Node active;
        final Placement activePlan;
        final List<Scheduler.Scheduled> published = new ArrayList<>();
        final Ways ways;
        int left;
        int failures;
        int queries;
        final List<Boolean> partialFailures = new ArrayList<>();
        Runnable whenRecovered = () -> { };
        Consumer<Scheduler.Scheduled> delivered = ignored -> { };

        Fixture(GameTestHelper helper, int failures, Node active, Placement activePlan, Resident owner) {
            this(helper, failures, active, activePlan, owner, null);
        }

        Fixture(GameTestHelper helper, int failures, Node active, Placement activePlan, Resident owner, ColonyLabor labor) {
            this.helper = helper;
            this.left = failures;
            this.active = active;
            this.activePlan = activePlan;
            scheduler = labor == null ? new Scheduler() : (Scheduler) field(field(labor, "pipeline"), "scheduler");
            pipeline = labor == null ? new Pipeline(inbox, new Planner(new Claims(), new Cooldowns()), scheduler)
                : (Pipeline) field(labor, "pipeline");
            second = owner == null ? new Resident(UUID.randomUUID(), OWNER) : owner;
            start = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
            end = start.at(start.cell().offset(4, 0, 0));
            old = vertex(start);
            head = vertex(start.at(start.cell().offset(2, 0, 0)));
            tail = vertex(end);
            crew = new Crew(List.of(new Crew.Hand(first, start, 6, 6, 1, List.of(), List.of(), Optional.empty()),
                new Crew.Hand(second, start, 6, 6, 1, List.of(), List.of(),
                    active == null ? Optional.empty() : Optional.of(active.id()))));
            ways = new Ways() {
                public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                    return Faring.yes(Journey.afoot(1, goals));
                }
                public Faring anyoneReaches(Set<WorldPos> goals) {
                    return Faring.yes(Journey.afoot(1, goals));
                }
                public Faring reckon(Resident who, WorldPos from, Set<WorldPos> goals) {
                    queries++;
                    if (goals.contains(end)) {
                        if (left > 0) {
                            left--;
                            Fixture.this.failures++;
                            partialFailures.add(false);
                            Object tours = field(scheduler, "tours");
                            partialFailures.set(partialFailures.size() - 1,
                                ((Map<?, ?>) field(tours, "settled")).containsKey(head.id()));
                            throw new IllegalStateException("route provider failed after the first placement");
                        }
                        whenRecovered.run();
                    }
                    return journey(who, from, goals);
                }
            };
        }

        void begin() {
            enqueue(initial(), crew);
            pipeline.applyReady(helper.getLevel());
            helper.assertValueEqual(published.size(), 1, "the fixture first publishes a good route");
            enqueue(changed(), crew);
            pipeline.applyReady(helper.getLevel());
            helper.assertValueEqual(failures, 1, "the real scheduler failed after accepting its changed graph");
            helper.assertTrue(partialFailures.getFirst(), "the first fault happened after an earlier node changed Tours");
            helper.assertValueEqual(published.size(), 1, "a partial layout is not published");
        }

        WeaveDelta initial() {
            return Shown.delta(Map.of(old.id(), old), List.of(), List.of(), Map.of(old.id(), second.id()), Set.of());
        }

        WeaveDelta changed() {
            Map<UUID, Vertex> put = new LinkedHashMap<>();
            put.put(head.id(), head);
            put.put(tail.id(), tail);
            Map<UUID, Optional<UUID>> pins = new LinkedHashMap<>();
            pins.put(head.id(), Optional.of(first.id()));
            pins.put(tail.id(), Optional.of(first.id()));
            if (active != null) {
                put.put(active.id(), new Vertex(active.id(), active, activePlan));
                pins.put(active.id(), Optional.of(second.id()));
            }
            return new WeaveDelta(put, Set.of(old.id()), Set.of(new Before(head.id(), tail.id())), Set.of(),
                Map.of(),
                pins, Set.copyOf(put.keySet()), List.of(), Optional.empty());
        }

        void enqueue(WeaveDelta delta, Crew hands) {
            enqueue(delta, hands, Runnable::run);
        }

        void enqueue(WeaveDelta delta, Crew hands, Executor lane) {
            try {
                var method = Pipeline.class.getDeclaredMethod("lay", Executor.class, Scheduler.Input.class, Consumer.class);
                method.setAccessible(true);
                method.invoke(pipeline, lane,
                    new Scheduler.Input(delta, hands, ways, true, helper.getLevel().getGameTime()),
                    (Consumer<Scheduler.Scheduled>) result -> { published.add(result); delivered.accept(result); });
            } catch (InvocationTargetException broken) {
                throw new AssertionError(broken.getCause());
            } catch (ReflectiveOperationException broken) {
                throw new AssertionError(broken);
            }
        }

        void afterTicks(Runnable done) {
            for (int tick = 1; tick <= 20; tick++) {
                int at = tick;
                helper.runAfterDelay(tick, () -> {
                    try {
                        pipeline.applyReady(helper.getLevel());
                        if (at == 20) {
                            try {
                                helper.assertTrue(partialFailures.stream().allMatch(Boolean::booleanValue),
                                    "every injected failure follows a partial mutation of Tours");
                                done.run();
                            }
                            finally { pipeline.close(Runnable::run, Runnable::run); }
                        }
                    } catch (RuntimeException | Error broken) {
                        pipeline.close(Runnable::run, Runnable::run);
                        throw broken;
                    }
                });
            }
        }

        private Vertex vertex(WorldPos at) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at), Stances.at(at), Workload.Once.of(1)).done();
            Node node = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { throw new AssertionError("scheduling never executes work"); }
            };
            return new Vertex(node.id(), node, new Placement(Set.of(new Stand(at)), List.of(), List.of(), List.of()));
        }
    }

    private static Object field(Object on, String name) {
        try {
            var field = on.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(on);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }
}
