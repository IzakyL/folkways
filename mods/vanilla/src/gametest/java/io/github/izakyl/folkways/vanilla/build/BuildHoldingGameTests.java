package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Shown;
import io.github.izakyl.folkways.core.plan.internal.Vertex;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.runtime.ColonyLabor;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class BuildHoldingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuildHoldingGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 360)
    public static void longActionKeepsItsBatchBeyondTheOldHeartbeat(GameTestHelper helper) {
        Harness h = new Harness(helper, true);
        Node stay = h.start(h.first);
        helper.assertTrue(h.site.pieces().stream().anyMatch(piece -> piece.action().job().kind()
                == BuildJob.Kind.LOWER && piece.action().job().laborTicks() == 400),
            "forty scaffold cells supply an explicit four-hundred-tick cleanup action");
        int batch = h.batch(stay);
        boolean[] ticking = {true};
        helper.onEachTick(() -> {
            if (ticking[0]) {
                h.tick();
            }
        });
        helper.startSequence().thenExecuteAfter(300, () -> {
            helper.assertTrue(h.colony.isHeld(stay.id()), "a long action retains its real holding");
            helper.assertValueEqual(h.batch(stay), batch, "a long action never refunds its unspent batch");
            helper.assertValueEqual(h.asked.size(), 1, "a stopped heartbeat never asks for another builder");
            helper.assertValueEqual(h.first.pack().count(Items.STONE), 4, "wind-up has consumed no input");
            helper.assertValueEqual(h.cleanup.stream().filter(cell -> h.level.getBlockState(cell)
                .is(Blocks.SCAFFOLDING)).count(), 40L, "wind-up has not removed any scaffold cell");
            ticking[0] = false;
            h.close();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 360)
    public static void unavailableBodyRetainsHoldingBatchAndMaterials(GameTestHelper helper) {
        Harness h = new Harness(helper);
        Node stay = h.start(h.first);
        Object holding = get(h.runner, "execution");
        int batch = h.batch(stay);
        boolean[] absent = {true};
        helper.onEachTick(() -> {
            if (absent[0]) {
                invoke(h.runner, "away", h.level.getGameTime());
                invoke(h.runner, "checkAbsent", h.level);
                h.site.tick(h.level);
            }
        });
        helper.startSequence().thenExecuteAfter(300, () -> {
            helper.assertTrue(h.colony.isHeld(stay.id()), "body absence does not terminate native work");
            helper.assertValueEqual(h.batch(stay), batch, "body absence retains the full batch");
            helper.assertValueEqual(h.asked.size(), 1, "body absence never asks for a replacement");
            h.assertMaterials();
            absent[0] = false;
            invoke(h.runner, "here");
            helper.assertTrue(get(h.runner, "execution") == holding, "the same execution is still retained");
            h.finish();
            h.close();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 360)
    public static void urgeSuspensionRetainsBatchAndSameResidentResumes(GameTestHelper helper) {
        Harness h = new Harness(helper);
        h.waitForWork(true);
        Node stay = h.start(h.first);
        Object holding = get(h.runner, "execution");
        int batch = h.batch(stay);
        Node urgeNode = h.urge();
        Urge urge = new Urge(BuildContent.ID, 100, true, () -> urgeNode);
        Object personal = get(h.labor, "personal");
        invoke(personal, "ready", urgeNode, h.level);
        invoke(h.runner, "takeover", h.level, h.first, urge, urgeNode);
        h.tick();
        helper.assertTrue(h.colony.isSuspended(stay.id()) && h.colony.isHeld(urgeNode.id()),
            "the urge owns the body while the site retains its suspension");
        boolean[] waiting = {true};
        helper.onEachTick(() -> {
            if (waiting[0]) {
                h.tick();
            }
        });
        helper.startSequence().thenExecuteAfter(300, () -> {
            helper.assertValueEqual(h.batch(stay), batch, "urge suspension never expires the batch");
            helper.assertValueEqual(h.asked.size(), 1, "the suspended builder is never replaced");
            helper.assertValueEqual(h.first.pack().count(Items.STONE), 4, "suspension retains every material");
            waiting[0] = false;
            invoke(h.runner, "letGo", h.level, h.first, "urge-gone");
            invoke(h.runner, "resume", h.level, h.first);
            helper.assertTrue(h.colony.holder(stay.id()).orElseThrow().equals(h.first.id()),
                "the same resident resumes the stay");
            helper.assertTrue(get(h.runner, "execution") == holding, "the original holding resumes");
            h.tick();
            helper.assertTrue(h.colony.isHeld(stay.id()), "suspension spends none of the stay's waiting budget");
            h.waitForWork(false);
            h.finish();
            h.close();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 280)
    public static void revokedStayRefundsOnceAndAnotherResidentBuilds(GameTestHelper helper) {
        endAndReplace(helper, "revoke");
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 280)
    public static void controllerEndRefundsOnceAndAnotherResidentBuilds(GameTestHelper helper) {
        endAndReplace(helper, "control");
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 280)
    public static void controllerOrderRefundsOnceAndAnotherResidentBuilds(GameTestHelper helper) {
        endAndReplace(helper, "order");
    }

    private static void endAndReplace(GameTestHelper helper, String how) {
        Harness h = new Harness(helper);
        Node stay = h.start(h.first);
        if (how.equals("revoke")) {
            invoke(get(h.labor, "phases"), "cancelled", stay.id());
            invoke(h.runner, "check", h.level, h.first);
        } else {
            invoke(h.runner, "controllerTakeover", h.level, h.first, false);
            if (how.equals("order")) {
                h.colony.controllerOrder(h.first);
                h.colony.controllerOrder(h.first);
            } else {
                h.tick();
            }
        }
        h.site.tick(h.level);
        h.duplicateEnd(stay);
        h.site.tick(h.level);
        helper.assertValueEqual(h.batch(stay), 0, "the ended stay refunds its batch");
        helper.assertTrue(!((Map<?, ?>) get(h.site, "stays")).containsKey(stay.id()),
            "the ended stay has no remaining turn");
        helper.assertValueEqual(h.first.pack().count(Items.STONE), 4, "end never copies or loses unspent goods");
        if (!how.equals("revoke")) {
            helper.assertValueEqual(h.withdrawn.getOrDefault(stay.id(), 0), 1,
                "controller end retires the old node once");
        }
        boolean[] waiting = {true};
        helper.onEachTick(() -> {
            if (waiting[0]) {
                h.site.tick(h.level);
            }
        });
        helper.startSequence().thenExecuteAfter(201, () -> {
            waiting[0] = false;
            helper.assertValueEqual(h.asked.size(), 2, "one replacement batch is asked for");
            h.duplicateEnd(stay);
            h.site.tick(h.level);
            helper.assertValueEqual(h.asked.size(), 2, "late duplicate endings never refund the replacement");
            ItemStack transfer = h.first.pack().removeItem(0, 4);
            h.second.pack().setItem(0, transfer);
            Node next = h.start(h.second);
            helper.assertTrue(!stay.id().equals(next.id())
                && h.colony.holder(next.id()).orElseThrow().equals(h.second.id()),
                "a different resident acquires the replacement stay");
            h.assertMaterials();
            h.finish();
            h.close();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void privateBuildActionsNeverEnterPersonalPhases(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            Node stay = h.start(h.first);
            int before = h.first.pack().count(Items.STONE);
            for (int tick = 0; tick < 100 && h.colony.isHeld(stay.id()); tick++) {
                for (NodeState state : NodeState.values()) {
                    helper.assertTrue(((Set<?>) invoke(get(h.labor, "personal"), "in", state)).isEmpty(),
                        "building registers no personal phase");
                    for (Object id : (Set<?>) invoke(get(h.labor, "phases"), "in", state)) {
                        helper.assertTrue(id.equals(stay.id()), "only the outer stay enters planning phases");
                    }
                }
                h.tick();
            }
            helper.assertTrue(h.first.pack().count(Items.STONE) < before,
                "the phase assertions observed real material-consuming construction");
            h.finish();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void hardSuspendResumeConservesEveryMaterial(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            Node stay = h.start(h.first);
            int batch = h.batch(stay);
            for (int turn = 0; turn < 5; turn++) {
                invoke(h.runner, "preemptHard", h.level, h.first,
                    Disposition.SUSPEND, PreemptReason.URGE);
                h.site.tick(h.level);
                helper.assertValueEqual(h.batch(stay), batch, "hard suspension retains the batch");
                h.assertMaterials();
                invoke(h.runner, "resume", h.level, h.first);
                h.tick();
                h.assertMaterials();
            }
            h.finish();
        }
        helper.succeed();
    }

    private static Object get(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static Object invoke(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                continue;
            }
            boolean fits = true;
            for (int at = 0; at < arguments.length; at++) {
                Class<?> type = method.getParameterTypes()[at];
                fits &= type.isPrimitive() || arguments[at] == null || type.isInstance(arguments[at]);
            }
            if (!fits) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(target, arguments);
            } catch (InvocationTargetException broken) {
                throw new AssertionError(broken.getCause());
            } catch (ReflectiveOperationException broken) {
                throw new AssertionError(broken);
            }
        }
        throw new AssertionError("no method " + name);
    }

    private static final class Harness implements BuildSite.Asks, AutoCloseable {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final Colony colony;
        private final ColonyLabor labor;
        private final Body first;
        private final Body second;
        private Body body;
        private Object runner;
        private final BuildSite site;
        private final Map<BlockPos, BlockState> targets = new LinkedHashMap<>();
        private final List<Node> asked = new ArrayList<>();
        private final List<BlockPos> cleanup = new ArrayList<>();
        private final Map<UUID, Integer> withdrawn = new HashMap<>();
        private final Map<UUID, BiConsumer<UUID, Ending>> callbacks = new HashMap<>();

        Harness(GameTestHelper helper) {
            this(helper, false);
        }

        Harness(GameTestHelper helper, boolean cleaning) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            labor = new ColonyLabor(ColonyData.find(level.getServer(), colony.id()).orElseThrow(), level.dimension());
            first = PersonBody.RESIDENT.get().create(level);
            second = PersonBody.RESIDENT.get().create(level);
            first.pack().setItem(0, new ItemStack(Items.STONE, 4));
            for (int x = 0; x <= 12; x++) {
                for (int z = 0; z <= 12; z++) {
                    level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            for (int x = 4; x <= 7; x++) {
                targets.put(helper.absolutePos(new BlockPos(x, 1, 5)), Blocks.STONE.defaultBlockState());
            }
            TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
            if (cleaning) {
                targets.forEach((cell, state) -> level.setBlock(cell, state, 3));
                BlockPos base = helper.absolutePos(new BlockPos(9, 1, 9));
                for (int height = 0; height < 40; height++) {
                    BlockPos cell = base.above(height);
                    cleanup.add(cell);
                    level.setBlock(cell, ScaffoldAction.state(), 3);
                }
                temporary.added(cleanup.stream().map(cell -> WorldPos.of(level, cell)).toList());
            }
            site = new BuildSite(UUID.randomUUID(), this,
                new BuildTarget(WorldPos.of(level, targets.keySet().iterator().next()).realm(), targets), temporary);
            site.start(level, Set.of());
            if (cleaning) {
                site.tick(level);
            }
        }

        @Override
        public Optional<Colony> colony() {
            return Optional.of(colony);
        }

        @Override
        public Optional<List<ItemStack>> pack(Worker worker) {
            return pack(worker.resident().id());
        }

        @Override
        public Optional<List<ItemStack>> pack(UUID resident) {
            Body held = resident.equals(first.id()) ? first : second;
            List<ItemStack> items = new ArrayList<>();
            for (int slot = 0; slot < held.pack().getContainerSize(); slot++) {
                items.add(held.pack().getItem(slot).copy());
            }
            return Optional.of(items);
        }

        @Override
        public void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended) {
            asked.addAll(work.nodes());
            work.nodes().forEach(node -> callbacks.put(node.id(), ended));
        }

        @Override
        public void withdraw(UUID node) {
            withdrawn.merge(node, 1, Integer::sum);
        }

        Node start(Body resident) {
            body = resident;
            runner = invoke(get(labor, "runners"), "of", body.id());
            Node node = asked.getLast();
            BlockPos stand = node.spec().stances().cells().iterator().next().block(level);
            body.mob().setPos(Vec3.atBottomCenterOf(stand));
            PlanningFixture.apply(labor, level, List.of(), Shown.delta(
                Map.of(node.id(), new Vertex(node.id(), node, Placement.NOWHERE)), List.of(), List.of()));
            invoke(runner, "follow", List.of(node.id()));
            tick();
            tick();
            helper.assertTrue(colony.isHeld(node.id()) && !colony.isApproaching(node.id()),
                "the real engine acquired the site at its stance");
            return node;
        }

        void waitForWork(boolean waiting) {
            try {
                var field = BuildSite.Piece.class.getDeclaredField("restUntil");
                field.setAccessible(true);
                for (BuildSite.Piece piece : site.pieces()) {
                    field.setLong(piece, waiting ? level.getGameTime() + 10_000 : Long.MIN_VALUE);
                }
            } catch (ReflectiveOperationException broken) {
                throw new AssertionError(broken);
            }
        }

        Node urge() {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), BuildContent.ID, WorkSite.at(level, targets.keySet()
                .iterator().next()), Stances.WHEREVER, Workload.Once.of(10_000)).done();
            return new Node() {
                public NodeSpec spec() {
                    return spec;
                }

                public Outcome commit(ServerLevel level, Worker who) {
                    return Outcome.done();
                }
            };
        }

        int batch(Node node) {
            return ((Set<?>) get(node, "batch")).size();
        }

        void duplicateEnd(Node node) {
            Worker worker = (Worker) invoke(labor, "hands", first, Placement.NOWHERE);
            node.released(level, worker, Ending.DROPPED);
            node.ended(level, Ending.REVOKED);
            callbacks.get(node.id()).accept(node.id(), Ending.DROPPED);
        }

        void tick() {
            invoke(labor, "tick", runner, level, body);
            site.tick(level);
        }

        void assertMaterials() {
            int placed = (int) targets.keySet().stream().filter(cell -> level.getBlockState(cell).is(Blocks.STONE))
                .count();
            helper.assertValueEqual(placed + first.pack().count(Items.STONE) + second.pack().count(Items.STONE), 4,
                "placed and carried materials remain conserved");
        }

        void finish() {
            for (int tick = 0; tick < 100 && !site.complete(); tick++) {
                tick();
                assertMaterials();
            }
            helper.assertTrue(site.complete(), "the retained or replacement holding finishes the site");
            helper.assertValueEqual(first.pack().count(Items.STONE) + second.pack().count(Items.STONE), 0,
                "each placement consumes exactly one material");
        }

        @Override
        public void close() {
            site.close();
            cleanup.forEach(cell -> level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3));
            invoke(labor, "close");
            first.mob().discard();
            second.mob().discard();
            colony.raze();
        }
    }
}
