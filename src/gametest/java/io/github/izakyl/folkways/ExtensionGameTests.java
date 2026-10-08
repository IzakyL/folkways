package io.github.izakyl.folkways;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.ColonyContext;
import io.github.izakyl.folkways.core.api.colony.ColonyLifecycle;
import io.github.izakyl.folkways.core.api.colony.Contributions;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.passage.Passage;
import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.front.api.ui.RegisteringUi;
import io.github.izakyl.folkways.front.engine.colony.ColonyBoards;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.front.ui.UiRegistrations;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExtensionGameTests {

    private static final ResourceLocation LATE_TRADE = id("late_trade");
    private static final ResourceLocation DECLINED_TRADE = id("declined_trade");
    private static final ResourceLocation LATE_PASSAGE = id("late_passage");
    private static final ResourceLocation FRONT = id("standalone_front");
    private static final ResourceLocation DRAWN = id("standalone_ui");
    private static final Delegation ZONE = new Delegation(id("zone"), new Shape.Volume(64));
    private static final ResourceLocation SOURCES = id("direct_sources");
    private static final ResourceLocation SCOPED = id("scoped_state");
    private static final Map<UUID, List<Urge>> URGES = new HashMap<>();
    private static Declaring declarations;
    private static final class State {
        ColonyContext context;
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
    }
    private static Registering sharedEvent;
    private static RegisteringUi uiEvent;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ExtensionGameTests.class);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void declareLate(Declaring event) {
        declarations = event;
        event.urges(SOURCES, (colony, who, view) -> URGES.getOrDefault(colony.id(), List.of()));
        event.colony(SCOPED, scope -> {
            State state = new State();
            state.context = scope;
            scope.share(state);
            scope.tick(server -> state.ticks.incrementAndGet());
            scope.closed(why -> state.closed.incrementAndGet());
        });
        event.vocation(VocationSpec.of(LATE_TRADE));
        event.vocation(VocationSpec.of(DECLINED_TRADE));
        Participations.register(PersonBody.ID, Stake.vocation(DECLINED_TRADE), Participation.NONE);
        event.passage(new Passage() {
            public ResourceLocation id() { return LATE_PASSAGE; }
            public Conveyance aboard(Hop hop) { throw new UnsupportedOperationException(); }
        });
    }

    @SubscribeEvent
    public static void front(Registering event) {
        sharedEvent = event;
        event.enrollment(FRONT, new Enrollment(List.of(ZONE), Schema.none(), List.of(
            PageKind.boarded(FRONT, "test.front", ResourceLocation.withDefaultNamespace("stone")),
            PageKind.boarded(DRAWN, "test.ui", ResourceLocation.withDefaultNamespace("stone")))));
        event.facing(FRONT, colony -> Optional.of(new Facing() {
            @Override
            public Optional<Board> board(ResourceLocation page,
                    io.github.izakyl.folkways.core.api.colony.ColonyView view) {
                return page.equals(FRONT) ? Optional.of(Board.of(List.of())) : Optional.empty();
            }
        }));
    }

    @SubscribeEvent
    public static void ui(RegisteringUi event) {
        uiEvent = event;
        event.page(DRAWN, () -> player -> new UIElement());
    }

    @GameTest(template = "empty")
    public static void participationIncludesLateContentAndKeepsExplicitNone(GameTestHelper helper) {
        helper.assertTrue(Participations.between(PersonBody.ID, Stake.vocation(LATE_TRADE))
            == Participation.OPTIONAL, "late professions must participate in resident defaults");
        helper.assertTrue(Participations.between(PersonBody.ID, Stake.passage(LATE_PASSAGE))
            == Participation.OPTIONAL, "late passages must participate in resident defaults");
        helper.assertTrue(Participations.between(PersonBody.ID, Stake.vocation(DECLINED_TRADE))
            == Participation.NONE, "resident defaults must preserve an explicit NONE");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void frontAndUiCanRegisterWithoutWork(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var colony = Colonies.mint(server);
        try {
            helper.assertTrue(!Contributions.ids().contains(FRONT), "fixture must have no work contribution");
            helper.assertTrue(Facing.of(colony, FRONT).isPresent(), "standalone facing must resolve");
            helper.assertTrue(ColonyBoards.answering(colony, FRONT, Optional.empty(),
                colony.view(helper.getLevel())).isPresent(), "standalone boards must reach the front");
            helper.assertTrue(UiRegistrations.draw(DRAWN).isPresent(), "UI factory must register separately");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void frontRegistrationWindowsClose(GameTestHelper helper) {
        boolean sharedClosed = false;
        boolean uiClosed = false;
        try {
            sharedEvent.enrollment(id("too_late"), Enrollment.none());
        } catch (IllegalStateException expected) {
            sharedClosed = true;
        }
        try {
            uiEvent.page(id("too_late"), () -> player -> new UIElement());
        } catch (IllegalStateException expected) {
            uiClosed = true;
        }
        helper.assertTrue(sharedClosed && uiClosed, "retained events must reject late registration");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void relocationCarriesDrawnZonesAndReopensTheFront(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        var colony = Colonies.mint(server);
        BlockPos old = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos next = helper.absolutePos(new BlockPos(3, 1, 1));
        level.setBlockAndUpdate(old, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(next, Blocks.CHEST.defaultBlockState());
        colony.hold(WaresContent.STORE, new Held.Block(WorldPos.of(level, old)));
        ColonyFront previous = ColonyFront.of(colony);
        UUID zone = previous.addZone(level, ZONE.id(), Set.of(old), ColonySettings.empty()).orElseThrow().id();
        boolean[] remapped = {false};
        Runnable remove = ColonyLifecycle.listen(new ColonyLifecycle.Listener() {
            @Override
            public Runnable relocating(net.minecraft.server.MinecraftServer host,
                    io.github.izakyl.folkways.core.api.colony.Colony moving,
                    Map<WorldPos, WorldPos> moved) {
                if (!moving.id().equals(colony.id())) {
                    return () -> { };
                }
                return () -> remapped[0] = Fronts.of(moving).zonesIn(level).getFirst().cells().contains(next);
            }
        });
        try {
            ColonyData.find(server, colony.id()).orElseThrow().relocate(server,
                Map.of(WorldPos.of(level, old), WorldPos.of(level, next)));
            helper.assertTrue(remapped[0], "a drawn zone must move with the cells it covers");
            helper.assertTrue(ColonyFront.of(colony) != previous, "relocation must invalidate the front cache");
            helper.assertTrue(Fronts.of(colony).zonesIn(level).getFirst().id().equals(zone),
                "relocation must preserve zone identity");
        } finally {
            remove.run();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void submittedWorkAndDirectUrgesReachTheColonyAndScopesClose(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        State state = colony.service(SCOPED, State.class).orElseThrow();
        var body = PersonBody.RESIDENT.get().create(level);
        try {
            var data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            Urge urge = new Urge(SOURCES, Urge.NOW, true, () -> { throw new AssertionError("not executing"); });
            URGES.put(colony.id(), List.of(urge));
            var spec = io.github.izakyl.folkways.core.api.work.NodeSpec.of(UUID.randomUUID(), SOURCES,
                io.github.izakyl.folkways.core.api.work.WorkSite.at(level, helper.absolutePos(BlockPos.ZERO)),
                io.github.izakyl.folkways.core.api.work.Stances.WHEREVER,
                io.github.izakyl.folkways.core.api.work.Workload.Once.of(0)).done();
            var node = new io.github.izakyl.folkways.core.api.work.Node() {
                public io.github.izakyl.folkways.core.api.work.NodeSpec spec() { return spec; }
                public io.github.izakyl.folkways.core.api.work.Outcome commit(
                        net.minecraft.server.level.ServerLevel at,
                        io.github.izakyl.folkways.core.api.work.Worker who) {
                    return io.github.izakyl.folkways.core.api.work.Outcome.done();
                }
            };
            Grown work = Grown.of(node);
            List<io.github.izakyl.folkways.core.api.work.Ending> ended = new java.util.ArrayList<>();
            colony.submit(SOURCES, level.dimension(), work, (any, how) -> ended.add(how));
            helper.assertTrue(ended.isEmpty(), "submitted work must stand without a state hierarchy");
            var worker = (io.github.izakyl.folkways.core.api.work.Worker) java.lang.reflect.Proxy.newProxyInstance(
                ExtensionGameTests.class.getClassLoader(), new Class<?>[] {io.github.izakyl.folkways.core.api.work.Worker.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "resident" -> body.resident();
                    case "body" -> body;
                    case "pack" -> body.pack();
                    case "route" -> List.of(node);
                    default -> throw new AssertionError(method.getName());
                });
            helper.assertTrue(data.works().urges(worker, colony.view(level)).stream()
                .anyMatch(felt -> felt.owner().equals(SOURCES) && felt.urge() == urge),
                "direct urge source must run without shared state");
            helper.assertTrue(colony.service(SOURCES, Object.class).isEmpty(),
                "direct contributions do not need a state object");
            data.works().pulse(level.getServer());
            helper.assertTrue(state.ticks.get() == 1, "optional tick callback runs");
            boolean setupClosed = false;
            try {
                state.context.tick(server -> { });
            } catch (IllegalStateException expected) {
                setupClosed = true;
            }
            helper.assertTrue(setupClosed, "retained setup context must reject late callbacks");
            boolean declaringClosed = false;
            try {
                declarations.urges(id("after_setup"), (owner, who, view) -> List.of());
            } catch (IllegalStateException expected) {
                declaringClosed = true;
            }
            helper.assertTrue(declaringClosed, "retained declaring event must reject late urges");
        } finally {
            URGES.remove(colony.id());
            body.discard();
            colony.raze();
        }
        helper.assertTrue(state.closed.get() == 1, "scope closes exactly once");
        helper.assertTrue(colony.service(SCOPED, State.class).isEmpty(), "closed state must be removed");
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_test", path);
    }
}
