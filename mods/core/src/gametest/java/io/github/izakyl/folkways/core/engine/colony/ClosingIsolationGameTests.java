package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyContext;
import io.github.izakyl.folkways.core.api.colony.ColonyLifecycle;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
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
public final class ClosingIsolationGameTests {

    private static final ResourceLocation OWNER = id("closing_fault");
    private static final ResourceLocation OTHER = id("closing_other");
    private static final Map<UUID, Fixture> FIXTURES = new HashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ClosingIsolationGameTests.class);
    }

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.colony(OWNER, ClosingIsolationGameTests::configure);
        event.colony(OTHER, ClosingIsolationGameTests::configure);
    }

    private static void configure(ColonyContext scope) {
        Fixture fixture = FIXTURES.get(scope.colony().id());
        if (fixture == null) {
            return;
        }
        State state = scope.owner().equals(OWNER) ? fixture.first : fixture.other;
        state.context = scope;
        scope.share(state);
        for (Runnable callback : state.callbacks) {
            scope.closed(why -> callback.run());
        }
    }

    @GameTest(template = "empty")
    public static void nestedFailuresStillEndEverySiblingAndParentOnce(GameTestHelper helper) {
        List<String> ended = new ArrayList<>();
        Branch run = new Branch("run", ended);
        Branch colony = run.opened(new Branch("colony", ended));
        Branch first = colony.opened(new Branch("first", ended));
        Branch nested = first.opened(new Branch("nested", ended));
        Branch middle = colony.opened(new Branch("middle", ended));
        Branch last = colony.opened(new Branch("last", ended));
        Branch sibling = run.opened(new Branch("other colony", ended));
        nested.action = () -> { throw new NoClassDefFoundError("injected nested failure"); };
        first.action = () -> {
            run.closed(Closing.SHUTDOWN);
            first.closed(Closing.SHUTDOWN);
            throw new IllegalStateException("injected first failure");
        };
        middle.action = () -> { throw new AssertionError("injected middle failure"); };
        colony.action = () -> { throw new AssertionError("injected parent failure"); };
        run.closed(Closing.SHUTDOWN);
        List<String> expected = List.of("nested", "first", "middle", "last", "colony", "other colony", "run");
        helper.assertTrue(ended.equals(expected), "failures must preserve child-before-parent and sibling order");
        for (Branch branch : List.of(run, colony, first, nested, middle, last, sibling)) {
            helper.assertTrue(branch.over() && detached(branch), "every closed branch must detach its structure");
            branch.closed(Closing.RAZED);
        }
        helper.assertTrue(ended.equals(expected), "reentrant and repeated close must not end a branch twice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fatalErrorsEscapeAfterDetachingAndEndingTheParent(GameTestHelper helper) {
        for (Error fatal : List.of(new VirtualMachineError("injected VM failure") { }, new ThreadDeath())) {
            List<String> ended = new ArrayList<>();
            Branch parent = new Branch("parent", ended);
            Branch child = parent.opened(new Branch("child", ended));
            child.action = () -> { throw fatal; };
            Error caught = null;
            try {
                parent.closed(Closing.SHUTDOWN);
            } catch (Error broken) {
                caught = broken;
            }
            helper.assertTrue(caught == fatal, "fatal VM and thread termination errors must escape unchanged");
            helper.assertTrue(ended.equals(List.of("child", "parent")) && detached(child) && detached(parent),
                "fatal errors must still execute structural finally cleanup and the parent's ending");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedScopeCallbacksStillReleaseTheirOwnerAndKeepOtherOwners(GameTestHelper helper) {
        Fixture fixture = new Fixture();
        ColonyWorks colony = fixture.data.works();
        var level = helper.getLevel();
        WorldPos firstStore = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        WorldPos otherStore = WorldPos.of(level, helper.absolutePos(new BlockPos(2, 1, 1)));
        List<String> heard = new ArrayList<>();
        boolean[] observed = new boolean[2];
        List<UUID> withdrawn = new ArrayList<>();
        List<Node> nodes = List.of(node(helper, OWNER), node(helper, OWNER));
        Node other = node(helper, OTHER);
        fixture.first.callbacks.add(() -> {
            heard.add("first");
            observed[0] = colony.service(OWNER, State.class).isEmpty();
            observed[1] = colony.storesIn(level.dimension()).contains(firstStore);
            ((Stretch) fixture.first.context).closed(Closing.RAZED);
            throw new IllegalStateException("injected first scope failure");
        });
        fixture.first.callbacks.add(() -> {
            heard.add("middle");
            throw new AssertionError("injected middle scope failure");
        });
        fixture.first.callbacks.add(() -> heard.add("last"));
        try {
            fixture.open(helper);
            colony.listen(level.dimension(), new ColonyWorks.Listening() {
                public void submitted(ResourceLocation owner, Grown work) { }
                public void offered() { }
                public void withdrawn(ResourceLocation owner, UUID node) {
                    withdrawn.add(node);
                    if (node.equals(nodes.getFirst().id())) {
                        throw new AssertionError("injected withdrawal failure");
                    }
                }
            });
            publish(colony, OWNER, level, firstStore);
            publish(colony, OTHER, level, otherStore);
            for (Node node : nodes) {
                colony.submit(OWNER, level.dimension(), Grown.of(node), (id, why) -> { });
            }
            colony.submit(OTHER, level.dimension(), Grown.of(other), (id, why) -> { });
            ((Stretch) fixture.first.context).closed(Closing.SHUTDOWN);
            ((Stretch) fixture.first.context).closed(Closing.RAZED);
            helper.assertTrue(heard.equals(List.of("first", "middle", "last")),
                "first and middle failures must not skip later scope callbacks or repeat them");
            helper.assertTrue(observed[0] && observed[1],
                "callbacks must see their service removed and their owner resources still available");
            helper.assertTrue(withdrawn.equals(nodes.stream().map(Node::id).toList()),
                "a failed withdrawal must not skip the owner's remaining work or touch another owner's work");
            helper.assertTrue(colony.service(OWNER, State.class).isEmpty()
                    && colony.service(OTHER, State.class).orElseThrow() == fixture.other,
                "only the closed scope's service must disappear");
            helper.assertTrue(colony.storesIn(level.dimension()).equals(List.of(otherStore))
                    && colony.withheldIn(level.dimension()).equals(java.util.Set.of(otherStore))
                    && colony.rulesIn(level.dimension()).keySet().equals(java.util.Set.of(otherStore))
                    && colony.workshopsIn(level.dimension()).size() == 1
                    && colony.workshopsIn(level.dimension()).getFirst().id().equals(OTHER),
                "the failed owner must release workshops, stores, withheld stores and rules independently");
            helper.assertTrue(colony.storeRules(firstStore).isEmpty(), "published store rules must be released too");
            for (Node node : nodes) {
                colony.submit(OWNER, level.dimension(), Grown.of(node), (id, why) -> { });
            }
            helper.assertTrue(detached((Stretch) fixture.first.context), "a scope closed alone must detach from its colony");
        } finally {
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedListenersStillClearTheColonyAfterItsScopes(GameTestHelper helper) {
        Fixture fixture = new Fixture();
        ColonyWorks colony = fixture.data.works();
        List<String> heard = new ArrayList<>();
        boolean[] observed = new boolean[2];
        fixture.first.callbacks.add(() -> {
            heard.add("scope first");
            throw new AssertionError("injected scope failure");
        });
        fixture.first.callbacks.add(() -> heard.add("scope last"));
        fixture.other.callbacks.add(() -> heard.add("other scope"));
        List<Runnable> remove = new ArrayList<>();
        try {
            fixture.open(helper);
            CompoundTag kept = new CompoundTag();
            kept.putBoolean("before_close", true);
            colony.keep(OWNER, kept);
            WorldPos store = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
            publish(colony, OWNER, helper.getLevel(), store);
            remove.add(listen(colony, () -> {
                heard.add("first listener");
                colony.closed(Closing.RAZED);
                throw new IllegalStateException("injected first listener failure");
            }));
            remove.add(listen(colony, () -> {
                heard.add("middle listener");
                throw new AssertionError("injected middle listener failure");
            }));
            remove.add(listen(colony, () -> {
                heard.add("last listener");
                observed[0] = colony.service(OWNER, State.class).isEmpty()
                    && colony.service(OTHER, State.class).isEmpty();
                observed[1] = !colony.kept(OWNER).isEmpty();
            }));
            colony.closed(Closing.RAZED);
            colony.closed(Closing.RAZED);
            helper.assertTrue(heard.equals(List.of("scope first", "scope last", "other scope",
                "first listener", "middle listener", "last listener")),
                "scope and listener failures must preserve close order and run each callback once");
            helper.assertTrue(observed[0] && observed[1],
                "listeners must run after scopes and before raze clears keeps");
            helper.assertTrue(!colony.settled() && colony.kept(OWNER).isEmpty()
                    && colony.storesIn(helper.getLevel().dimension()).isEmpty()
                    && colony.workshopsIn(helper.getLevel().dimension()).isEmpty(),
                "parent cleanup and raze cleanup must complete after callback failures");
        } finally {
            remove.forEach(Runnable::run);
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fatalScopeCallbackStillReleasesItsOwner(GameTestHelper helper) {
        Fixture fixture = new Fixture();
        ColonyWorks colony = fixture.data.works();
        Error fatal = new VirtualMachineError("injected scope VM failure") { };
        List<String> heard = new ArrayList<>();
        fixture.first.callbacks.add(() -> { throw fatal; });
        fixture.first.callbacks.add(() -> heard.add("later callback"));
        try {
            fixture.open(helper);
            WorldPos store = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
            publish(colony, OWNER, helper.getLevel(), store);
            Error caught = null;
            try {
                ((Stretch) fixture.first.context).closed(Closing.SHUTDOWN);
            } catch (Error broken) {
                caught = broken;
            }
            helper.assertTrue(caught == fatal && heard.isEmpty(), "fatal scope errors must escape without being swallowed");
            helper.assertTrue(detached((Stretch) fixture.first.context)
                    && colony.service(OWNER, State.class).isEmpty()
                    && colony.service(OTHER, State.class).isPresent()
                    && colony.storesIn(helper.getLevel().dimension()).isEmpty()
                    && colony.withheldIn(helper.getLevel().dimension()).isEmpty()
                    && colony.rulesIn(helper.getLevel().dimension()).isEmpty()
                    && colony.workshopsIn(helper.getLevel().dimension()).isEmpty(),
                "structural and owner finally cleanup must run even when a fatal scope callback escapes");
        } finally {
            fixture.close();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedWithdrawalsStillClearWorkOutsideContributionScopes(GameTestHelper helper) {
        ColonyWorks colony = new ColonyData().works();
        var level = helper.getLevel();
        List<Node> nodes = List.of(node(helper, OWNER), node(helper, OWNER), node(helper, OWNER));
        List<UUID> withdrawn = new ArrayList<>();
        List<UUID> delivered = new ArrayList<>();
        CompoundTag kept = new CompoundTag();
        kept.putBoolean("shutdown_keeps", true);
        colony.keep(OWNER, kept);
        colony.listen(level.dimension(), new ColonyWorks.Listening() {
            public void submitted(ResourceLocation owner, Grown work) { }
            public void offered() { }
            public void withdrawn(ResourceLocation owner, UUID node) {
                withdrawn.add(node);
                if (node.equals(nodes.getFirst().id())) {
                    throw new IllegalStateException("injected first withdrawal failure");
                }
                if (node.equals(nodes.get(1).id())) {
                    throw new AssertionError("injected middle withdrawal failure");
                }
            }
        });
        for (Node node : nodes) {
            colony.submit(OWNER, level.dimension(), Grown.of(node), (id, why) -> delivered.add(id));
        }
        colony.closed(Closing.SHUTDOWN);
        colony.closed(Closing.SHUTDOWN);
        for (Node node : nodes) {
            colony.withdraw(OWNER, node.id());
        }
        helper.assertTrue(withdrawn.equals(nodes.stream().map(Node::id).toList()) && delivered.isEmpty(),
            "failed withdrawal listeners must leave no stale asks or repeat callbacks after close");
        helper.assertTrue(colony.kept(OWNER).equals(kept), "shutdown must retain keeps after failed work cleanup");
        helper.succeed();
    }

    private static Runnable listen(Colony expected, Runnable action) {
        return ColonyLifecycle.listen(new ColonyLifecycle.Listener() {
            @Override
            public void closed(Colony colony, Closing why) {
                if (colony == expected) {
                    action.run();
                }
            }
        });
    }

    private static void publish(ColonyWorks colony, ResourceLocation owner, ServerLevel level, WorldPos at) {
        colony.stores(owner, level.dimension(), List.of(at));
        colony.withhold(owner, level.dimension(), List.of(at));
        colony.rule(owner, level.dimension(), List.of(new StoreRule.Access(at, false, false)));
        colony.publish(owner, level.dimension(), List.of(new Workshop() {
            private final UUID key = UUID.randomUUID();
            public UUID key() { return key; }
            public ResourceLocation id() { return owner; }
            public WorkSite site() { return new WorkSite.AtBlock(at); }
        }));
    }

    private static Node node(GameTestHelper helper, ResourceLocation owner) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner,
            WorkSite.at(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)),
            Stances.WHEREVER, Workload.Once.of(1)).done();
        return new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker worker) { return Outcome.done(); }
        };
    }

    private static boolean detached(Stretch stretch) {
        try {
            var outer = Stretch.class.getDeclaredField("outer");
            var inner = Stretch.class.getDeclaredField("inner");
            outer.setAccessible(true);
            inner.setAccessible(true);
            return outer.get(stretch) == null && ((List<?>) inner.get(stretch)).isEmpty();
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways", path);
    }

    private static final class State {
        private ColonyContext context;
        private final List<Runnable> callbacks = new ArrayList<>();
    }

    private static final class Fixture {
        private final ColonyData data = new ColonyData();
        private final State first = new State();
        private final State other = new State();

        private void open(GameTestHelper helper) {
            FIXTURES.put(data.colonyId(), this);
            ColonyRuntime.settle(helper.getLevel().getServer(), data.works());
            helper.assertTrue(first.context != null && other.context != null, "the server must settle fixture scopes");
        }

        private void close() {
            try {
                data.works().closed(Closing.SHUTDOWN);
            } finally {
                FIXTURES.remove(data.colonyId());
                ColonyWays.forget(data.colonyId());
            }
        }
    }

    private static final class Branch extends Stretch {
        private final String name;
        private final List<String> ended;
        private Runnable action = () -> { };

        private Branch(String name, List<String> ended) {
            this.name = name;
            this.ended = ended;
        }

        @Override
        protected void ended(Closing why) {
            ended.add(name);
            action.run();
        }
    }
}
