package io.github.izakyl.folkways.core.shell.letitwalk;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.drill.DrillBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.letitwalk.ModContent;
import dev.letitwalk.content.LegJointBlock;
import dev.letitwalk.content.LegJointBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import io.github.izakyl.folkways.assistant.sandbox.Probe;
import io.github.izakyl.folkways.assistant.sandbox.Sandbox;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.SelectedTests;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A player-built arm on Let it Walk!'s demo spider: a drill head of three Create drills hanging from a two-hinge limb
 * at the nose. Calibrated from trials of the limb, as the assistant does, a facility script steers the drills through a
 * picked box in a dirt ridge. The test fails unless every cell of a box in reach is dug, a box the drill head cannot fit
 * inside is left alone, a box starting right under the shoulder is still dug, a box longer than the arm reaches is dug
 * by walking the spider on between sweeps, and nothing outside the box is dug in any of them.
 */
@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class WalkerArmDigGameTests {

    private static final int PAD_Y = 100;
    private static final int SETTLE = 80;
    private static final int SAMPLE = 40;
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-walker-arm-dig-tests");

    /** Fit the arm in hull space and visit the picked ridge through open space. */
    static final String DIG = """
        load("@std//ik", "to_world")
        load("@std//sweep", "sweep", "sweep_step")

        def step(state, readings, args):
            options = dict(args, local=True, reach=[1.4, 0.7, 0.7], coverage=[1.4, 0.04, 0.04])
            if not args["walks"]:
                return sweep(state, readings, options)
            if state == None:
                state = {"sweep": None, "worked": [], "walks": 0}
            options["skip"] = state["worked"]
            batch, actions = sweep_step(state["sweep"], readings, options)
            if actions:
                return dict(state, sweep=batch), actions
            if not batch["left"]:
                return state, [done]
            if state["walks"] >= args["walks"]:
                return state, [fail_permanent("%d cells are still out of reach" % len(batch["left"]))]
            ahead = to_world(readings["_frame"], [0.5, 0, 3.5])
            return {"sweep": None, "worked": state["worked"] + batch["worked"], "walks": state["walks"] + 1}, [
                run("folkways_core:move_to", {"drive": "destination", "target": ahead})]
        """;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        SelectedTests.register(event, WalkerArmDigGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 4800,
            batch = "walker_arm_dig")
    public static void residentDigsAPickedBoxWithACalibratedArmAndNothingElse(GameTestHelper helper) {
        // The box takes the near three rows of the ridge, three wide over the arm's plane.
        dig(helper, new BlockPos(2688, PAD_Y, 64), new BlockPos(-1, 1, 6), new BlockPos(1, 3, 8), Run.IN_REACH);
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 4800,
            batch = "walker_arm_dig_near")
    public static void anArmDigsABoxStartingRightUnderItsShoulderAndNothingElse(GameTestHelper helper) {
        // One row starts too close to the shoulder for a straight approach from above it.
        dig(helper, new BlockPos(3072, PAD_Y, 64), new BlockPos(-1, 1, 5), new BlockPos(1, 3, 8), Run.IN_REACH);
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 2400,
            batch = "walker_arm_dig_misaligned")
    public static void anArmLeavesABoxItsToolCannotFitInsideUntouched(GameTestHelper helper) {
        // One column to the side, the three-wide drill head would always cut a column outside the box.
        dig(helper, new BlockPos(2816, PAD_Y, 64), new BlockPos(0, 1, 6), new BlockPos(2, 3, 9), Run.MISALIGNED);
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 14000,
            batch = "walker_arm_dig_walking")
    public static void residentWalksTheSpiderOnToDigABoxLongerThanTheArmReaches(GameTestHelper helper) {
        // Ten rows deep, the box is three times what the arm reaches from one spot.
        dig(helper, new BlockPos(2944, PAD_Y, 64), new BlockPos(-1, 1, 6), new BlockPos(1, 3, 15), Run.WALKING);
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 22000,
            batch = "walker_arm_dig_long_ridge")
    public static void residentWalksTheSpiderOnToDigA153CellRidge(GameTestHelper helper) {
        // The walker stands one block above the stone pad, with the tool starting above the dirt ridge.
        dig(helper, new BlockPos(3200, PAD_Y, 64), new BlockPos(-1, 1, 6), new BlockPos(1, 3, 22), Run.WALKING);
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 4800,
            batch = "walker_arm_dig_trial")
    public static void trialDigsOnlyThePickedBox(GameTestHelper helper) {
        dig(helper, new BlockPos(2688, PAD_Y, 64), new BlockPos(-1, 1, 6), new BlockPos(1, 3, 8), Run.TRIAL);
    }

    enum Run { IN_REACH, MISALIGNED, WALKING, TRIAL }

    /**
     * Calibrates the arm as the assistant does, through trials of the limb on a copy, installs the arm as a facility
     * with {@link #DIG} and runs it on the box from {@code low} to {@code high} (relative to the pad).
     */
    private static void dig(GameTestHelper helper, BlockPos pad, BlockPos low, BlockPos high, Run kind) {
        boolean reachable = kind != Run.MISALIGNED;
        var level = helper.getLevel();
        var walker = LetItWalkGameTests.Walker.build(helper, pad, WalkerArmDigGameTests::armWithDrills);
        var colony = Colonies.mint(level.getServer());
        var resident = kind == Run.TRIAL ? null : PersonBody.RESIDENT.get().create(level);
        var labor = kind == Run.TRIAL ? null : new LaborFixture(colony, level);
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "arm-probe"),
            ClientInformation.createDefault());
        Cleanup.on(helper, () -> {
            if (labor != null) {
                labor.close();
                resident.discard();
            }
            colony.raze();
        });
        var ground = pad.getY() + 1;
        // A dirt ridge ahead of the nose, three high, from a row before the box to well beyond it.
        // Walking, the ridge is only as wide as the box, so the spider's legs straddle it rather than climb onto it.
        int side = kind == Run.WALKING ? 1 : 2;
        var ridge = new BlockPos[] {pad.offset(-side, 1, 5), pad.offset(side, 3, high.getZ() + 5)};
        var box = new BlockPos[] {pad.offset(low.getX(), low.getY(), low.getZ()),
            pad.offset(high.getX(), high.getY(), high.getZ())};
        var name = "Arm drill";
        var services = DefinedFacilities.of(colony).orElseThrow();
        // Each control swept from the same starting aim [0.3, 0]: fwd at up 0, then up at fwd 0.3, and one below the
        // start. The last aim is too short for the limb, which holds it pushed out to its ring: the trials take the aim
        // the limb reports holding, so that sample still fits.
        double[][] aims = {{.3, 0}, {.45, 0}, {.6, 0}, {.3, .15}, {.3, .3}, {.3, -.15}, {.1, .1}};
        List<List<Object>> trials = new ArrayList<>();
        @SuppressWarnings("unchecked")
        CompletableFuture<JsonObject>[] trial = new CompletableFuture[1];
        GameTestSequence sequence = helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            helper.assertTrue(drillsSpinning(level, pad), "the drill head spins on the arm");
            BlockPos.betweenClosed(ridge[0], ridge[1]).forEach(cell -> level.setBlock(cell,
                (cell.getY() == ridge[1].getY() ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 3));
        });
        for (double[] aim : aims) {
            sequence = sequence.thenExecute(() -> trial[0] = Sandbox.probe(player, new Probe.Request(
                MechanismScan.structure(level, walker.hull()), new Probe.Input.Write(walker.controls(), "limb/1",
                    Optional.of(Value.of(aim[0], aim[1]))), SAMPLE)))
                .thenWaitUntil(() -> helper.assertTrue(trial[0].isDone(), "the trial of limb/1 at " + aim[0] + ", "
                    + aim[1]))
                .thenExecute(() -> {
                    var data = trial[0].join();
                    helper.assertFalse(data.has("error"), "the trial runs: " + data);
                    var end = data.getAsJsonObject("input").getAsJsonObject("reading");
                    helper.assertTrue(end != null && end.get("arm").getAsDouble() == 1, "limb 1 is the arm: " + data);
                    var at = List.<Object>of(end.get("x").getAsDouble(), end.get("y").getAsDouble(),
                        end.get("z").getAsDouble());
                    var local = end.getAsJsonArray("local").asList().stream()
                        .map(one -> (Object) one.getAsDouble()).toList();
                    LOGGER.info("limb/1 at {} reads {}, {} in the hull's space", aim, at, local);
                    var held = end.getAsJsonArray("aim").asList().stream().map(one -> (Object) one.getAsDouble())
                        .toList();
                    trials.add(List.of(held, local));
                });
        }
        var target = Map.of("target", Value.of(box[0].getX(), box[0].getY(), box[0].getZ(),
            box[1].getX(), box[1].getY(), box[1].getZ()));
        var real = BlockPos.betweenClosedStream(ridge[0].offset(-2, -1, -3), ridge[1].offset(2, 0, 2))
            .map(BlockPos::immutable).toList();
        var before = new ArrayList<BlockState>();
        sequence.thenExecute(() -> {
            helper.assertTrue(count(level, ridge, box, ground).insideDug() == 0
                && count(level, ridge, box, ground).outsideDug() == 0, "the trials dug nothing in the world");
            var extent = FacilityExtent.structure(level, walker.hull()).orElseThrow();
            var data = Map.<String, Object>of("name", name, "extent", DefinitionCodec.extent(extent),
                "anchor", List.of(walker.controls().cell().getX(), walker.controls().cell().getY(),
                    walker.controls().cell().getZ()),
                "controls", Map.of("arm", Map.of("part", "limb/1", "offset", List.of(0, 0, 0)),
                    "destination", Map.of("part", "destination", "offset", List.of(0, 0, 0))),
                "routines", Map.of("dig", Map.of("source", DIG, "with",
                    Map.of("fits", trials, "walks", kind == Run.WALKING ? 10 : 0))),
                "triggers", Map.of("dig", Map.of("kind", "manual", "routine", "dig")),
                "ui", Map.of("card", List.of(Map.of("target", "dig", "pick", "box"))));
            var preview = services.preview(name, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "the arm validates as a facility: " + preview.problems());
            if (kind == Run.TRIAL) {
                real.forEach(pos -> before.add(level.getBlockState(pos)));
                var facility = FacilityDefinition.fromData(UUID.randomUUID(), data);
                var input = new Probe.Input.Routine(facility, "dig", target, Map.of(), Optional.empty());
                trial[0] = Sandbox.probe(player, Probe.Request.routine(level.getServer(), input, Probe.MAX_ROUTINE_TICKS));
                return;
            }
            var deck = walker.controls().at(walker.controls().cell().relative(Direction.SOUTH.getClockWise()));
            resident.moveTo(WorldSpaces.world(level, deck).orElseThrow().add(0, .02, 0));
            resident.setOnGround(true);
            level.addFreshEntity(resident);
            colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
            resident.joinColony(colony.id());
            colony.entered(resident);
            helper.onEachTick(labor::tick);
            helper.assertTrue(services.apply(preview).isEmpty(), "arm facility installed");
            var problems = services.run(name, "dig", target);
            helper.assertTrue(problems.isEmpty(), "dig submitted: " + problems);
        });
        if (kind == Run.TRIAL) {
            sequence.thenWaitUntil(() -> helper.assertTrue(trial[0].isDone(), "waiting for the routine trial"))
                .thenExecute(() -> {
                    var report = trial[0].join();
                    helper.assertTrue(report.has("routine") && report.getAsJsonObject("routine")
                        .get("ending").getAsString().equals("done"), "the trial finished: " + report);
                    var removed = report.getAsJsonObject("blocks").getAsJsonArray("removed");
                    BlockPos.betweenClosed(box[0], box[1]).forEach(pos -> {
                        JsonArray at = new JsonArray();
                        at.add(pos.getX());
                        at.add(pos.getY());
                        at.add(pos.getZ());
                        helper.assertTrue(removed.asList().stream().anyMatch(row ->
                            at.equals(row.getAsJsonObject().get("at"))), "trial removed " + pos + ": " + report);
                    });
                    helper.assertTrue(report.getAsJsonObject("outside").get("count").getAsInt() == 0,
                        "nothing outside the box changed: " + report);
                    helper.assertTrue(real.stream().map(level::getBlockState).toList().equals(before),
                        "the world ridge and ground are unchanged");
                }).thenSucceed();
            return;
        }
        sequence.thenWaitUntil(() -> helper.assertTrue(services.recent(name).isPresent(),
            "waiting for the dig: " + count(level, ridge, box, ground) + " " + labor.diagnosis()))
            .thenExecute(() -> {
                var result = services.recent(name).orElseThrow();
                var counts = count(level, ridge, box, ground);
                LOGGER.info("The arm's dig ended by tick {}: {} ({})", helper.getTick(), counts, result);
                helper.assertTrue(counts.outsideDug() == 0 && counts.groundDug() == 0,
                    "nothing outside the box is dug: " + counts);
                if (reachable) {
                    helper.assertTrue(result.ending().equals("done"), "the dig finished: " + result + " " + counts);
                    helper.assertTrue(counts.insideStanding() == 0, "every cell of the box is dug: " + counts);
                } else {
                    helper.assertTrue(!result.ending().equals("done") && result.reason().orElse("")
                        .contains("reach"), "the dig says the box is out of reach: " + result);
                    helper.assertTrue(counts.insideDug() == 0, "the arm leaves the box alone: " + counts);
                }
            }).thenSucceed();
    }

    /** The arm: a hinge at the nose, a three-block upper arm, an elbow hinge and the drill head hanging below it. */
    static void armWithDrills(ServerLevel level, BlockPos deck) {
        var casing = ModContent.KLAXIUM_CASING.get().defaultBlockState();
        var shoulder = deck.south(5);
        level.setBlock(shoulder, ModContent.LEG_HINGE.get().defaultBlockState()
            .setValue(LegJointBlock.FACING, Direction.SOUTH), 3);
        level.setBlock(shoulder.south(), casing, 3);
        level.setBlock(shoulder.south(2), casing, 3);
        var elbow = shoulder.south(3);
        level.setBlock(elbow, ModContent.LEG_HINGE.get().defaultBlockState()
            .setValue(LegJointBlock.FACING, Direction.DOWN), 3);
        // At rest the forearm folds back under the upper arm, so the elbow can open all the way.
        if (level.getBlockEntity(elbow) instanceof LegJointBlockEntity joint) {
            joint.setRestOffset(60);
        }
        level.setBlock(elbow.below(), casing, 3);
        for (int side = -1; side <= 1; side++) {
            var motor = elbow.below(2).east(side);
            level.setBlock(motor, create("creative_motor").defaultBlockState()
                .setValue(DirectionalKineticBlock.FACING, Direction.DOWN), 3);
            if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity spinning) {
                spinning.generatedSpeed.setValue(128);
            }
            level.setBlock(elbow.below(3).east(side), create("mechanical_drill").defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.DOWN), 3);
        }
    }

    private static net.minecraft.world.level.block.Block create(String name) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", name));
    }

    private static boolean drillsSpinning(ServerLevel level, BlockPos pad) {
        int spinning = 0;
        var near = new BoundingBox3d(pad.getX() - 8, pad.getY(), pad.getZ(), pad.getX() + 8, pad.getY() + 12,
            pad.getZ() + 16);
        for (var sub : Sable.HELPER.getAllIntersecting(level, near)) {
            var plot = sub.getPlot().getBoundingBox();
            for (var at : BlockPos.betweenClosed(plot.minX(), plot.minY(), plot.minZ(), plot.maxX(), plot.maxY(),
                    plot.maxZ())) {
                if (level.getBlockEntity(at) instanceof DrillBlockEntity drill && drill.getTheoreticalSpeed() != 0) {
                    spinning++;
                }
            }
        }
        return spinning == 3;
    }

    record Counts(int insideDug, int insideStanding, int outsideDug, int groundDug, List<BlockPos> stray) {
    }

    /** The ridge cell by cell, inside the box or out, and the ground under and around it. */
    static Counts count(ServerLevel level, BlockPos[] ridge, BlockPos[] box, int ground) {
        int insideDug = 0;
        int insideStanding = 0;
        int outsideDug = 0;
        List<BlockPos> stray = new ArrayList<>();
        for (var cell : BlockPos.betweenClosed(ridge[0], ridge[1])) {
            boolean inside = cell.getX() >= box[0].getX() && cell.getX() <= box[1].getX()
                && cell.getY() >= box[0].getY() && cell.getY() <= box[1].getY()
                && cell.getZ() >= box[0].getZ() && cell.getZ() <= box[1].getZ();
            boolean dug = level.getBlockState(cell).isAir();
            if (inside && dug) {
                insideDug++;
            } else if (inside) {
                insideStanding++;
                stray.add(cell.immutable());
            } else if (dug) {
                outsideDug++;
                stray.add(cell.immutable());
            }
        }
        int groundDug = 0;
        for (var cell : BlockPos.betweenClosed(ridge[0].getX() - 2, ground - 1, ridge[0].getZ() - 3,
                ridge[1].getX() + 2, ground - 1, ridge[1].getZ() + 2)) {
            if (level.getBlockState(cell).isAir()) {
                groundDug++;
                stray.add(cell.immutable());
            }
        }
        return new Counts(insideDug, insideStanding, outsideDug, groundDug, stray);
    }
}
