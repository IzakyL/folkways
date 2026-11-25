package io.github.izakyl.folkways.core.facility.api;

import static io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture.FACILITY;
import static io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture.ROUTINE;
import static io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture.definition;
import static io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture.pos;

import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyContext;
import io.github.izakyl.folkways.core.facility.api.control.Motion;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.script.api.Profile;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.PerfBudget;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.starlark.java.eval.Starlark;
import org.slf4j.Logger;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class FacilityScriptGameTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String STEPS = "folkways.facility.script.script_steps";

    private static final Map<WorldPos, Motion> MOTIONS = new HashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FacilityScriptGameTests.class);
        Controls.registerMotion((level, pos) -> Optional.ofNullable(MOTIONS.get(pos)));
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void sampledReadingDrivesGateCardAndPath(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, false);
        var data = rig.routine("""
            def step(state, readings, args):
                return state, [done]

            def path(readings, args):
                return [[0, 0, 0], [readings.get("derived", 0), 0, 0]]
            """);
        String gate = "def when(readings): return readings.get('derived') == 2";
        String enabled = "def when(readings): return readings.get('derived') == 0";
        data.put("parameters", Map.of("weight", 2));
        data.put("readings", Map.of(
            "state", Map.of("probe", "folkways_core:block_state", "offset", List.of(0, 0, 0)),
            "derived", Map.of("source", """
                def read(readings, params):
                    return params["weight"] if readings["state"]["properties"]["powered"] else 0
                """)));
        data.put("projections", List.of(Map.of("gate", Map.of("cells", List.of(pos(rig.left.east())),
            "axes", List.of("x"), "open_when", gate))));
        data.put("ui", Map.of("overlay", List.of(Map.of("path", ROUTINE)),
            "card", List.of(Map.of("button", ROUTINE, "enabled_when", enabled))));
        rig.install(data);
        var facility = rig.service.facilities().getFirst();
        var operating = new DefinedOperating(rig.service, facility);
        var button = (io.github.izakyl.folkways.core.card.api.Card.Element.Button) facility.card().elements().getFirst();
        helper.assertTrue(rig.service.disabled(FACILITY, button).equals(Optional.of("unknown")),
            "the initial card state waits for the worker");
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(rig.service.disabled(FACILITY, button).isEmpty(), "the cached predicate enables the button");
            helper.assertTrue(facility.projectionReadings(rig.level).isEmpty(), "the gate starts closed");
        }).thenExecute(() -> rig.power(rig.left, true)).thenWaitUntil(() -> {
            var readings = Readings.snapshot(rig.level, facility.readings(rig.level));
            helper.assertTrue(Value.of(2).equals(readings.get("derived")), "the script reads generic block state");
            var projections = facility.projections(rig.level, facility.projectionReadings(rig.level));
            helper.assertTrue(projections.stream().anyMatch(Contribution.Hop.class::isInstance), "the cached gate opens");
            helper.assertTrue(rig.service.disabled(FACILITY, button).equals(Optional.of("state")), "the button disables");
            helper.assertTrue(operating.overlays(rig.level).stream().anyMatch(overlay ->
                overlay instanceof Operating.Overlay.Path path && path.points().getLast().x == 2),
                "the cached path ends at the derived coordinate");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void standardPositionMovesACalibratedAxis(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        WorldPos site = WorldPos.of(rig.level, rig.left);
        var axis = new Motion() {
            private double extension;

            @Override
            public WorldPos anchor() {
                return site;
            }

            @Override
            public String part() {
                return "axis";
            }

            @Override
            public double position() {
                return extension;
            }

            @Override
            public double maximum() {
                return 8;
            }

            @Override
            public boolean moving() {
                return rig.powered(rig.right);
            }

            @Override
            public Optional<Vec3> contraption() {
                return Optional.empty();
            }

            @Override
            public Optional<AABB> sweep() {
                return Optional.empty();
            }

            void tick() {
                if (rig.powered(rig.right)) {
                    extension += 0.25;
                }
            }
        };
        MOTIONS.put(site, axis);
        Cleanup.on(helper, () -> MOTIONS.remove(site));
        helper.onEachTick(axis::tick);
        var data = rig.routine(ScriptedFacilityFixture.POSITION_SOURCE);
        data.put("controls", Map.of("axis", Map.of("part", "axis", "offset", List.of(0, 0, 0)),
            "right", Map.of("part", "lever", "offset", pos(rig.right.subtract(rig.left)))));
        var settings = Map.of("frame", Map.of("origin", List.of(0, 0, 0), "axes", Map.of("axis", List.of(1, 0, 0))),
            "response", Map.of("axis", Map.of("channel", "right", "stop", false, "rates", List.of(
                Map.of("value", true, "velocity", 0.25, "start_ticks", 0, "stop_ticks", 0)))), "tolerance", 0.51);
        data.put("routines", Map.of(ROUTINE, Map.of("source", ScriptedFacilityFixture.POSITION_SOURCE, "with", settings)));
        data.put("ui", Map.of("card", List.of(Map.of("readout", ROUTINE + ".current", "as", "text"))));
        rig.install(data);
        rig.run(Map.of("target", Value.of(2, 0, 0)));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(rig.recent("done"), "position run: " + rig.service.recent(FACILITY)
                + ", extension=" + axis.position() + ", state=" + rig.state());
            helper.assertTrue(Math.abs(axis.position() - 2.0) <= 0.51 && !axis.moving(), "the calibrated axis stops");
            var facility = rig.service.facilities().getFirst();
            var readings = Readings.snapshot(rig.level, facility.readings(rig.level));
            helper.assertTrue(readings.containsKey(ROUTINE + ".bounds")
                && readings.get(ROUTINE + ".current").equals(Value.of(axis.position(), 0, 0)),
                "the readings hook publishes bounds and final position");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void recipesOfPublishesSelectedNativeRecipes(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, false);
        var data = rig.routine("""
            def recipes(input):
                return [row for row in recipes_of("minecraft:smelting")
                        if row["id"] == "minecraft:iron_ingot_from_smelting_raw_iron"]

            def step(state, readings, args):
                return state, [done]
            """);
        data.put("workshop", Map.of("routine", ROUTINE));
        rig.install(data);
        helper.startSequence().thenWaitUntil(() -> {
            var workshop = Mechanics.workshops(rig.colony, rig.level.getServer()).stream()
                .filter(entry -> entry.facility().isPresent()).findFirst();
            var iron = ResourceLocation.parse("minecraft:iron_ingot");
            helper.assertTrue(workshop.flatMap(Mechanics.WorkshopEntry::facility)
                .filter(details -> details.explicitRecipes().size() == 1)
                .map(details -> details.explicitRecipes().getFirst().outputs().getFirst().spec().item())
                .filter(Optional.of(iron)::equals).isPresent(),
                "the workshop publishes the script-selected iron recipe: " + rig.service.recent(FACILITY));
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void largeSweepPlansItsFirstLegWithinTheStepBudget(GameTestHelper helper) {
        var compiled = FacilityScripts.compile(new FacilityScripts.Source("""
            load("@std//sweep", "sweep_step")

            box = [4, 4, 5, 11, 7, 12]
            target = {"around": box, "blocked": [
                [x, y, z] for x in range(4, 12) for y in range(4, 8) for z in range(5, 13)]}
            approach = {"around": [0, 0, 0, 15, 15, 15],
                        "blocked": target["blocked"] + [
                            [x, y, z] for x in range(16) for y in range(3) for z in range(16)]}
            readings = {"_failure": None, "_target": target, "_target_approach": approach,
                        "arm": {"x": 7.5, "y": 5.5, "z": 0.5}}
            args = {"target": box, "reach": [0.1, 0.1, 0.1],
                    "fits": [[[0, 0, 0], [0.5, 0.5, 0.5]], [[1, 0, 0], [1.5, 0.5, 0.5]],
                             [[0, 1, 0], [0.5, 1.5, 0.5]], [[0, 0, 1], [0.5, 0.5, 1.5]]]}

            def step():
                batch, actions = sweep_step(None, readings, args)
                return not batch["finished"] and not batch["working"] and len(actions) == 1
            """, "step"));
        compiled.runtime().evaluate(compiled.program(), (module, thread) -> {
            long before = thread.getExecutedSteps();
            var result = Starlark.call(thread, module.getGlobal("step"), List.of(), Map.of());
            LOGGER.info("Large sweep first call used {} interpreter steps", thread.getExecutedSteps() - before);
            helper.assertTrue(Boolean.TRUE.equals(result), "the sweep planned a leg from outside the target");
            return result;
        });
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void removedDefinitionReleasesOwnership(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var service = DefinedFacilities.of(colony).orElseThrow();
        var at = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        var extent = FacilityExtent.blocks(List.of(at));
        var data = Map.of("name", FACILITY, "extent", DefinitionCodec.extent(extent),
            "anchor", pos(at.cell()));
        try {
            var added = service.apply(service.preview(FACILITY, Optional.of(data)));
            helper.assertTrue(added.isEmpty(), "definition was added: " + added);
            helper.assertTrue(Facilities.ownerOf(level, at).filter(FacilityDefinition.class::isInstance).isPresent(),
                "the definition owns the block");

            var removed = service.apply(service.preview(FACILITY, Optional.empty()));
            helper.assertTrue(removed.isEmpty(), "definition was removed: " + removed);
            helper.assertTrue(Facilities.ownerOf(level, at).isEmpty(), "the block is unowned immediately after removal");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void irregularExtentOwnsOnlyListedBlocksAndEntity(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var service = DefinedFacilities.of(colony).orElseThrow();
        var inside = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        var outside = inside.at(helper.absolutePos(new BlockPos(2, 1, 1)));
        var anchor = inside.at(helper.absolutePos(new BlockPos(3, 1, 1)));
        level.setBlockAndUpdate(inside.block(level), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(outside.block(level), Blocks.CHEST.defaultBlockState());
        var cart = net.minecraft.world.entity.EntityType.MINECART.create(level);
        cart.moveTo(outside.block(level).above().getBottomCenter());
        level.addFreshEntity(cart);
        try {
            colony.stores(DefinedFacilities.ID, level.dimension(), List.of(inside, outside));
            var extent = new FacilityExtent(java.util.Set.of(new FacilityExtent.Block(inside),
                new FacilityExtent.Block(anchor), new FacilityExtent.Entity(cart.getUUID())));
            var data = Map.of("name", "irregular", "extent", DefinitionCodec.extent(extent),
                "anchor", pos(anchor.cell()));
            var problems = service.apply(service.preview("irregular", Optional.of(data)));
            helper.assertTrue(problems.isEmpty(), "the irregular extent is valid: " + problems);
            helper.assertTrue(Facilities.ownerOf(level, inside).filter(FacilityDefinition.class::isInstance).isPresent()
                    && Facilities.ownerOf(level, cart.getUUID()).filter(FacilityDefinition.class::isInstance).isPresent(),
                "the facility owns its chest without an item control and owns the listed cart");
            helper.assertTrue(Facilities.ownerOf(level, outside).filter(ContainerFacility.class::isInstance).isPresent(),
                "the intervening chest belongs to its own container");
            service.apply(service.preview("irregular", Optional.empty()));
            helper.assertTrue(Facilities.ownerOf(level, inside).filter(ContainerFacility.class::isInstance).isPresent(),
                "removing the definition restores the enrolled chest");
        } finally {
            cart.discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void standardModulesRespectProfileStepLimits(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath("folkways_core", "std_test");
        var runtime = new ScriptRuntime(Profile.draftPattern(Duration.ofSeconds(5), Map.of(),
            ResourceLocation::tryParse, (module, message) -> { }));
        var result = runtime.evaluate(runtime.compile(id, """
            load("@std//math", "clamp", "sqrt")
            load("@std//text", "table", "truncate")

            result = (clamp(sqrt(25), 0, 4) == 4 and truncate("stone", 3) == "st…"
                      and table([{"block": "stone"}]) == "block\\nstone")
            """), (module, thread) -> module.getGlobal("result"));
        helper.assertTrue(Boolean.TRUE.equals(result), "draft patterns can load the shared modules");

        var condition = FacilityScripts.compile(new FacilityScripts.Source("""
            load("@std//text", "table")

            def when(readings):
                return table([{"n": n} for n in range(1000000)]) != ""
            """, "when"));
        try {
            condition.matches(Map.of(), false, Map.of());
            helper.fail("a standard module call must exhaust the condition budget");
        } catch (ScriptFault fault) {
            helper.assertTrue(fault.kind() == ScriptFault.Kind.STEPS, "standard module call reported " + fault);
        }

        var denied = runtime.compile(id, """
            load("@std//tree", "find")
            result = find("*")
            """);
        try {
            runtime.evaluate(denied, (module, thread) -> module.getGlobal("result"));
            helper.fail("draft patterns cannot load the agent tree module");
        } catch (ScriptFault fault) {
            helper.assertTrue(fault.kind() == ScriptFault.Kind.LOAD, "tree import reported " + fault);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aToolOutsideThePickedBoxIsObservedWhateverItsControlIsCalled(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(origin.getX(), origin.getY(),
            origin.getZ(), origin.getX() + 2, origin.getY() + 2, origin.getZ() + 2);
        var end = new net.minecraft.nbt.CompoundTag();
        end.putDouble("x", origin.getX() + 1.5);
        end.putDouble("y", origin.getY() + 6.5);
        end.putDouble("z", origin.getZ() - 3.5);
        var approach = FacilityScriptRuns.approachBounds(level, box, Map.of("limb", new Value.Data(end)), Map.of());
        helper.assertTrue(approach.isPresent(), "a tool above and before the box widens what is observed");
        var cell = net.minecraft.core.BlockPos.containing(origin.getX() + 1.5, origin.getY() + 6.5, origin.getZ() - 3.5);
        helper.assertTrue(approach.get().isInside(cell), "the tool's own cell is observed: " + approach.get());
        helper.assertTrue(approach.get().isInside(new net.minecraft.core.BlockPos(origin.getX(), origin.getY(),
            origin.getZ() + 2)), "the box stays observed");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void agentScriptsKeepTheirSourceCapButNotTheStandardModules(GameTestHelper helper) {
        Profile profile = Profile.agent();
        ScriptRuntime agent = new ScriptRuntime(profile);
        var id = ResourceLocation.fromNamespaceAndPath("folkways_core", "agent_std_test");
        var result = agent.evaluate(agent.compile(id, """
            load("@std//ik", "fit_straight")

            result = fit_straight != None
            """), (module, thread) -> module.getGlobal("result"));
        helper.assertTrue(Boolean.TRUE.equals(result), "an agent script loads @std//ik");
        try {
            agent.evaluate(agent.compile(id, "load(\"@std//sweep\", \"sweep\")\nresult = sweep"),
                (module, thread) -> module.getGlobal("result"));
            helper.fail("an agent script cannot load the routine-only sweep");
        } catch (ScriptFault fault) {
            helper.assertTrue(fault.kind() == ScriptFault.Kind.LOAD
                && String.valueOf(fault.getMessage()).contains("facility routine"), "sweep load reported " + fault);
        }
        try {
            agent.compile(id, "x = 1\n" + "#".repeat(profile.maxSource()));
            helper.fail("an agent's own script stays under the source cap");
        } catch (ScriptFault fault) {
            helper.assertTrue(fault.kind() == ScriptFault.Kind.SOURCE_SIZE, "oversized source reported " + fault);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void fullAgentLaneDoesNotDelayFacilityConditions(GameTestHelper helper) {
        Profile profile = Profile.agent();
        ScriptRuntime agent = new ScriptRuntime(profile);
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("folkways_core", "lane_test");
        var script = agent.compile(id, "result = True");
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<?>[] agents = new CompletableFuture<?>[profile.maxInFlight()];
        try {
            for (int index = 0; index < agents.length; index++) {
                agents[index] = agent.submit(() -> agent.evaluate(script, (module, thread) -> {
                    release.await();
                    return null;
                }));
            }
            try {
                agent.submit(() -> true);
                helper.fail("a full agent lane must refuse admission immediately");
            } catch (ScriptFault busy) {
                helper.assertTrue(busy.kind() == ScriptFault.Kind.BUSY, "a full lane reports busy");
            }
            var program = FacilityScripts.compile(new FacilityScripts.Source("def when(readings):\n    return True",
                "when"));
            var condition = FacilityScripts.submit(true, () -> program.matches(Map.of(), false, Map.of()));
            AsyncWait.succeedWhen(helper, condition, () -> {
                helper.assertTrue(condition.join().matches(), "the facility condition ran");
                for (var pending : agents) {
                    helper.assertTrue(!pending.isDone(), "the condition ran before any agent slot was released");
                }
            }, release::countDown);
        } catch (RuntimeException | AssertionError failure) {
            release.countDown();
            throw failure;
        }
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void scriptRoutineLoadsColonyLibrary(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        ColonyScripts.write(rig.colony, "gate", "def target(): return True");
        rig.install(rig.routine("""
            load("//scripts/gate", "target")
            load("@std//math", "dist")

            def step(state, readings, args):
                if state == None:
                    return True, [write("right", target() and dist([0, 0, 0], {"x": 3, "y": 4, "z": 0}) == 5)]
                return state, [done]
            """));
        rig.run(Map.of());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(rig.recent("done"), "waiting for the library-backed script");
            helper.assertTrue(rig.powered(rig.right), "the library supplied the control target");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void scriptRoutineSolvesCalibratedJointChain(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.install(rig.routine("""
            load("@std//ik", "chain", "fit_prismatic", "forward", "solve")
            load("@std//math", "dist")

            def step(state, readings, args):
                if state != None:
                    return state, [done]
                slide = fit_prismatic([[10, [0, 0, 0]], [12, [0, 0, 4]], [14, [0, 0, 8]]])
                bearing = {"kind": "revolute", "pivot": [0, 0, 0], "axis": [0, 0, 1],
                           "min": 0, "max": 3, "per_unit": -0.5, "zero": 30}
                mechanism = chain([slide, bearing], [2, 0, 0])
                result = solve(mechanism, [0, 2, 4], start=[10, 30], direction=[0, 1, 0])
                controls = result["controls"]
                tip = forward(mechanism, controls)
                valid = (result["reached"] and result["error"] < 0.0001
                         and dist([controls[0], controls[1], 0], [12, 26.858407, 0]) < 0.0002
                         and dist(tip, [0, 2, 4]) < 0.0001)
                return True, [write("right", valid)]
            """));
        rig.run(Map.of());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(rig.recent("done"), "waiting for the IK-backed script");
            helper.assertTrue(rig.powered(rig.right), "the IK module returned controls that reach the target");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void scriptRoutineFitsStraightJointChain(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.install(rig.routine("""
            load("@std//ik", "fit_straight", "solve")
            load("@std//math", "dist")

            def step(state, readings, args):
                if state != None:
                    return state, [done]
                mechanism = fit_straight([[[0, 0], [1, 2, 3]], [[1, 0], [3, 3, 3]],
                                          [[0, 1], [1, 1, 6]], [[1, 1], [3, 2, 6]]])
                result = solve(mechanism, [5, 1, 12], tolerance=0.00001)
                c = result["controls"]
                valid = result["reached"] and dist([c[0], c[1], 0], [2, 3, 0]) < 0.0001
                return True, [write("right", valid)]
            """));
        rig.run(Map.of());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(rig.recent("done"), "waiting for the straight-chain script");
            helper.assertTrue(rig.powered(rig.right), "the fitted chain returned the expected controls");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void scriptRoutineSweepsAnOffsetPlane(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.install(rig.routine("""
            load("@std//ik", "fit_straight", "forward")
            load("@std//sweep", "sweep_step")
            load("@std//math", "floor")

            def step(state, readings, args):
                if state == True:
                    return state, [done]
                fits = [[[0, 0], [0, 2.5, 0]], [[1, 0], [1, 2.5, 0]], [[0, 1], [0, 2.5, 1]]]
                chain = fit_straight(fits)
                trigger = {"control": "right", "value": True, "atTick": 3}
                options = {"target": [0, 0, 0, 2, 0, 1], "fits": fits, "reach": [0.2, 0.2, 0.2],
                           "offset": [0, -2, 0], "dwell": 5, "at_stop": [trigger]}
                if state == None:
                    state = {"batch": None, "arm": {"x": 0.5, "y": 2.5, "z": 0.5}, "seen": []}
                target = {"around": [-1, -1, -1, 3, 1, 2], "blocked": []}
                sample = dict(readings, _target=target, arm=state["arm"])
                batch, actions = sweep_step(state["batch"], sample, options)
                if not actions:
                    valid = batch["finished"] and not batch["left"] and len(batch["worked"]) == 6
                    return True, [write("right", valid and len(state["seen"]) == 6)]
                if actions[0][0] != "write_sequence":
                    return True, actions
                steps = actions[0][1]
                x, y, z = forward(chain, steps[0]["value"])
                seen = state["seen"]
                if steps[1]["control"] == "right":
                    if steps[1] != trigger or x < 0.2 or x > 2.8 or y < 2.2 or y > 2.8 or z < 0.2 or z > 1.8:
                        return True, [fail_permanent("the trigger did not fire inside the plane")]
                    key = "%d,%d" % (int(floor(x)), int(floor(z)))
                    if key not in seen:
                        seen = seen + [key]
                return {"batch": batch, "arm": {"x": x, "y": y, "z": z}, "seen": seen}, [write("right", False)]
            """));
        rig.run(Map.of());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(rig.recent("done"), "waiting for the offset sweep");
            helper.assertTrue(rig.powered(rig.right), "every cell got a trigger within the box");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void scriptRoutineSweepsBelowStandingCellsFromTheSide(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        var near = helper.absolutePos(new BlockPos(4, 2, 4));
        var far = near.south(2);
        var roof = List.of(near.above(), near.above().south(), far.above());
        roof.forEach(cell -> helper.getLevel().setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState()));
        rig.install(rig.routine("""
            load("@std//sweep", "sweep_step")
            load("@std//ik", "fit_straight", "forward")
            load("@std//math", "dist", "floor")

            def touches(cell, start, end, reach):
                return all([max(start[i], end[i]) + reach[i] > cell[i] and
                            min(start[i], end[i]) - reach[i] < cell[i] + 1 for i in range(3)])

            def step(state, readings, args):
                if state == True:
                    return state, [done]
                x, y, z, _, _, far = args["target"]
                blocked = [[x, y + 1, row] for row in range(z, far + 1)]
                around = [x - 1, y - 1, z - 1, x + 1, y + 2, far + 1]
                target = readings["_target"]
                if target["around"] != around or not all([cell in target["blocked"] for cell in blocked]):
                    return True, [fail_permanent("the box snapshot is missing the roof or its border")]
                # The upper stops are all inside the ring's hole. The end starts above the roof.
                fits = [[[0, 0], [x + 0.5, y + 1.5, z + 1.5]],
                        [[1, 0], [x + 0.5, y + 2.5, z + 1.5]],
                        [[0, 1], [x + 0.5, y + 1.5, z + 5.5]]]
                options = dict(args, fits=fits, reach=[0.2, 0.3, 0.2], ring=[0.35, 2], dwell=5)
                if state == None:
                    state = {"batch": None, "at": [x + 0.5, y + 2.5, z + 0.5], "seen": [], "side": False}
                px, py, pz = state["at"]
                sample = dict(readings, arm={"x": px, "y": py, "z": pz})
                batch, actions = sweep_step(state["batch"], sample, options)
                seen = state["seen"]
                if not actions:
                    valid = seen == [z, z + 1, far] and len(batch["worked"]) == 3 and len(batch["left"]) == 3
                    return True, [write("right", valid and state["side"] and bool(batch.get("reason")))]
                if actions[0][0] != "write_sequence":
                    return True, actions
                at = forward(fit_straight(fits), actions[0][1][0]["value"])
                if dist(at, state["at"]) > 1.000001 or any([
                        touches(cell, state["at"], at, options["reach"]) for cell in target["blocked"]]):
                    return True, [fail_permanent("a waypoint jumped or touched the standing roof")]
                cell = int(floor(at[2]))
                side = state["side"] or (at[2] < z and at[1] <= y + 1.5)
                if cell >= z and at[1] + options["reach"][1] <= y + 1 and (not seen or cell != seen[-1]):
                    seen = seen + [cell]
                return {"batch": batch, "at": at, "seen": seen, "side": side}, [write("right", False)]
            """));
        rig.run(Map.of("target", Value.of(near.getX(), near.getY(), near.getZ(), far.getX(), far.getY() + 1,
            far.getZ())));
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(rig.recent("done"), "waiting for the sweep below the roof");
            helper.assertTrue(rig.powered(rig.right), "the sweep took the open side and left the roof standing");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void scriptBranchesOnReadingsAndRunsMultipleBatches(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.power(rig.left, true);
        rig.install(rig.routine("""
            def step(state, readings, args):
                if readings["_failure"] != None:
                    return state, [fail(readings["_failure"])]
                if state == None:
                    selected = "right" if readings["left"] else "left"
                    return {"selected": selected}, [write(selected, args["value"]), await(selected, True, 60)]
                if "returning" not in state:
                    return {"returning": True}, [run("folkways_core:write_all", {state["selected"]: False})]
                return state, [done]
            """));
        rig.run(Map.of("value", Value.of(true)));
        helper.startSequence().thenWaitUntil(() ->
            helper.assertTrue(rig.powered(rig.right), "the first batch writes the branch selected by left")
        ).thenWaitUntil(() -> {
            helper.assertTrue(rig.recent("done"), "waiting for script to finish");
            helper.assertTrue(rig.powered(rig.left) && !rig.powered(rig.right),
                "the second batch closed the selected signal through write_all");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void scriptReceivesAwaitTimeoutAndFails(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.install(rig.routine("""
            def step(state, readings, args):
                if state == None:
                    return "waiting", [await("left", True, 5)]
                if readings["_failure"] == None:
                    return state, [done]
                return state, [fail("saw " + readings["_failure"])]
            """));
        rig.run(Map.of());
        helper.succeedWhen(() -> {
            helper.assertTrue(rig.recent("failed"), "waiting for the timeout to reach step");
            var reason = rig.service.recent(FACILITY).orElseThrow().reason();
            helper.assertTrue(reason.equals(Optional.of("saw " + FacilityRefusal.UNSETTLED.translationKey())),
                "step gets the plan node's specific failure, got " + reason);
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void awaitFailureHandlerRunsBeforeTheNextStep(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.install(rig.routine("""
            def recover(state, failure, input, args):
                if failure["action_index"] != 1 or failure["kind"] != "await" or not failure["retryable"]:
                    return state, [fail_permanent("wrong failure")]
                return failure["reason"], [write("right", False)]

            def step(state, input, args):
                if state == None:
                    return "waiting", [write("right", True), await("left", True, 5, on_fail="recover")]
                return state, [fail("recovered " + state)]
            """));
        rig.run(Map.of());
        helper.succeedWhen(() -> {
            helper.assertTrue(rig.recent("failed"), "the script reported a retryable failure");
            helper.assertTrue(!rig.powered(rig.right), "the handler wrote the safe value");
            helper.assertTrue(rig.service.recent(FACILITY).orElseThrow().reason()
                .equals(Optional.of("recovered " + FacilityRefusal.UNSETTLED.translationKey())),
                "the handler received the failed action and its reason");
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void scriptReloadsFromCommittedState(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, false);
        rig.install(rig.routine("""
            def step(state, readings, args):
                if state == None:
                    if readings["left"]:
                        return None, [fail("restarted without checkpoint")]
                    return {"n": 7, "text": "汉字"}, []
                if state["n"] == 7 and state["text"] == "汉字" and readings["left"]:
                    return state, [done]
                return state, []
            """));
        rig.run(Map.of());
        DefinedFacilities[] restarted = new DefinedFacilities[1];
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            rig.state().contains("\"n\":7"), "waiting for a committed checkpoint")
        ).thenExecute(() -> {
            var saved = rig.colony.kept(DefinedFacilities.ID);
            rig.service.apply(rig.service.preview(FACILITY, Optional.empty()));
            rig.power(rig.left, true);
            rig.colony.keep(DefinedFacilities.ID, saved);
            // Install a fresh service over the on-disk representation and let it take a new snapshot of the world.
            DefinedFacilities.install(Proxies.of(ColonyContext.class, (proxy, method, args) -> switch (method.getName()) {
                case "colony" -> rig.colony;
                case "server" -> rig.level.getServer();
                case "share" -> {
                    restarted[0] = (DefinedFacilities) args[0];
                    yield null;
                }
                case "tick" -> {
                    @SuppressWarnings("unchecked")
                    Consumer<MinecraftServer> tick = (Consumer<MinecraftServer>) args[0];
                    helper.onEachTick(() -> tick.accept(rig.level.getServer()));
                    yield null;
                }
                case "closed" -> {
                    @SuppressWarnings("unchecked")
                    Consumer<Closing> close = (Consumer<Closing>) args[0];
                    rig.beforeClose = () -> close.accept(Closing.SHUTDOWN);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            }));
        }).thenWaitUntil(() -> helper.assertTrue(restarted[0].recent(FACILITY)
            .filter(result -> result.ending().equals("done")).isPresent(), "restored run finishes from its checkpoint")
        ).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void scriptStepLimitDiscardsUncommittedStep(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.install(rig.routine("""
            def step(state, readings, args):
                if state == None:
                    return {"count": 1}, [write("left", True)]
                for i in range(100000):
                    state["count"] += 1
                return state, [write("left", False)]
            """));
        rig.run(Map.of());
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(rig.state().equals("{\"count\":1}"), "the first step commits");
        }).thenWaitUntil(() -> {
            helper.assertTrue(rig.recent("stalled"), "waiting for the step limit fault");
            helper.assertTrue(rig.service.recent(FACILITY).orElseThrow().reason().equals(Optional.of(STEPS)),
                "the execution step limit interrupted step");
            helper.assertTrue(rig.powered(rig.left) && rig.state().isEmpty(),
                "the interrupted step produced no writes or persisted continuation");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void conditionScriptTriggersARealRun(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.install(rig.condition("""
            def when(readings):
                return readings["left"] and not readings["right"]
            """));
        helper.startSequence().thenExecuteAfter(12, () -> {
            helper.assertTrue(!rig.powered(rig.right) && rig.service.running(FACILITY).isEmpty(),
                "condition stayed false");
            rig.power(rig.left, true);
        }).thenWaitUntil(() -> helper.assertTrue(rig.powered(rig.right) && rig.recent("done"),
            "the script condition submitted a write_all run")).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void conditionStepLimitPausesAndReportsFault(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, false);
        rig.install(rig.condition("""
            def when(readings):
                total = 0
                for i in range(100000):
                    total += i
                return True
            """));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.recent("failed"), "waiting for script fault"))
            .thenExecuteAfter(20, () -> {
                var recent = rig.service.recent(FACILITY).orElseThrow();
                helper.assertTrue(recent.reason().equals(Optional.of(STEPS)), "the fault reports its step limit");
                helper.assertTrue(!rig.service.triggerStates(FACILITY).get("auto")
                        && rig.colony.kept(DefinedFacilities.ID).getCompound(FACILITY).getCompound("paused").getBoolean("auto")
                        && rig.level.getGameTime() - recent.time() >= 20 && rig.service.running(FACILITY).isEmpty(),
                    "the paused trigger stayed paused past two more beats and its pause was saved");
                helper.assertTrue(rig.service.toggle(FACILITY, "auto", true).isEmpty()
                        && rig.service.triggerStates(FACILITY).get("auto"),
                    "an explicit enable clears the pause");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void scriptsRejectSyntaxErrorsAndMissingEntryPointsAtSave(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, false);
        assertRefused(helper, rig, rig.routine("def step("), "routines.operate.source", "script_syntax");
        assertRefused(helper, rig, rig.routine("def other():\n    return None"), "routines.operate.source",
            "script_function");
        assertRefused(helper, rig, rig.condition("def when("), "triggers.auto.when", "script_syntax");
        assertRefused(helper, rig, rig.condition("def other():\n    return False"), "triggers.auto.when",
            "script_function");
        helper.assertTrue(rig.service.list().isEmpty(), "invalid scripts did not install a facility");
        helper.succeed();
    }

    private static void assertRefused(GameTestHelper helper, ScriptedFacilityFixture rig, Map<String, Object> definition,
                                      String path, String reason) {
        var preview = rig.service.preview(FACILITY, Optional.of(definition));
        var problems = rig.service.apply(preview);
        helper.assertTrue(problems.stream().anyMatch(problem -> problem.path().equals(path)
                && problem.reason().equals(reason)), "expected " + reason + " at " + path + ", got " + problems);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "facilityScriptBenchmark",
        timeoutTicks = 650)
    public static void oneHundredScriptConditionsStayWithinTickBudget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        Cleanup.on(helper, colony::raze);
        DefinedFacilities service = DefinedFacilities.of(colony).orElseThrow();
        var monitor = new ConditionBenchmark();
        service.profileConditions(monitor);
        String script = """
            def when(readings):
                total = 0
                for i in range(63):
                    total += i
                return readings.get("powered", False) and total < 0
            """;
        for (int i = 0; i < 100; i++) {
            BlockPos at = helper.absolutePos(new BlockPos(i % 10, 2, i / 10));
            String name = "Condition " + i;
            var data = definition(level, name, at, at, Map.of(), Map.of(
                "source", "def step(s, r, a): return s, [done]", "with", Map.of()),
                Map.of("auto", Map.of("kind", "condition", "routine", ROUTINE, "when", script,
                    "period", 10, "enabled", true)));
            data.put("readings", Map.of("powered", Map.of("probe", "folkways_core:powered",
                "offset", List.of(0, 0, 0))));
            var problems = service.apply(service.preview(name, Optional.of(data)));
            helper.assertTrue(problems.isEmpty(), "benchmark facility failed validation: " + problems);
        }
        helper.runAfterDelay(40, () -> monitor.begin(level.getServer().getTickCount() + 1));
        helper.runAfterDelay(510, () -> {
            monitor.verify(helper);
            helper.succeed();
        });
    }

    private static final class ConditionBenchmark implements DefinedFacilities.ConditionProfile {

        private static final int WINDOW_TICKS = 150;
        private static final int WINDOWS = 3;

        private final long[] main = new long[WINDOW_TICKS * WINDOWS];
        private final long[] worker = new long[main.length];
        private final int[] evaluations = new int[main.length];
        private final long[] steps = new long[WINDOWS];
        private int start = Integer.MAX_VALUE;

        void begin(int tick) {
            start = tick;
        }

        @Override
        public void tick(int tick, long nanos) {
            if (inRange(tick)) {
                main[tick - start] = nanos;
            }
        }

        @Override
        public void evaluated(int tick, long steps, long nanos) {
            if (inRange(tick)) {
                worker[tick - start] += nanos;
                evaluations[tick - start]++;
                this.steps[(tick - start) / WINDOW_TICKS] += steps;
            }
        }

        private boolean inRange(int tick) {
            return tick >= start && tick - start < main.length;
        }

        void verify(GameTestHelper helper) {
            Window best = null;
            for (int i = 0; i < WINDOWS; i++) {
                int from = i * WINDOW_TICKS;
                int to = from + WINDOW_TICKS;
                var mainStats = Arrays.stream(main, from, to).summaryStatistics();
                var workerStats = Arrays.stream(worker, from, to).summaryStatistics();
                int completed = Arrays.stream(evaluations, from, to).sum();
                long p95 = Arrays.stream(main, from, to).sorted().toArray()[(int) Math.ceil(WINDOW_TICKS * 0.95) - 1];
                long averageSteps = completed == 0 ? 0 : steps[i] / completed;
                var window = new Window(i + 1, mainStats.getAverage(), p95, mainStats.getMax());
                LOGGER.info("FacilityDefinition script benchmark window {}: {} ticks, {} evaluations, "
                        + "{} steps/evaluation; {}; worker summed mean/max {} / {} µs per tick",
                    window.number(), WINDOW_TICKS, completed, averageSteps, window.timing(),
                    Math.round(workerStats.getAverage() / 1_000), workerStats.getMax() / 1_000);
                helper.assertTrue(mainStats.getMin() > 0 && completed >= 1_350,
                    "all ticks measured and at least 90% of the 10 evaluations/tick expected at 10-tick cadence");
                helper.assertTrue(averageSteps >= 180 && averageSteps <= 220, "benchmark executes about 200 steps");
                helper.assertTrue(Arrays.stream(evaluations, from, to).max().orElseThrow()
                    <= FacilityScripts.CONDITION_ADMISSIONS, "admissions respect the global per-tick cap");
                if (best == null || window.p95() < best.p95()) {
                    best = window;
                }
            }
            LOGGER.info("FacilityDefinition script benchmark selected window {}: {}", best.number(), best.timing());
            // An ordinary server tick has 50 ms. Keep the service below 2% on average and 4% at p95,
            // including facility bookkeeping, snapshots and application of results.
            PerfBudget.check(helper, "FacilityScriptGameTests#oneHundredScriptConditionsStayWithinTickBudget",
                best.mean(), best.p95(), best.max(), 1_000_000, 2_000_000);
        }

        private record Window(int number, double mean, long p95, long max) {

            String timing() {
                return PerfBudget.timing(mean, p95, max);
            }
        }
    }
}
