package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import io.github.izakyl.folkways.assistant.sandbox.Sandbox;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The calibration reads: {@code mechanism} lays out a built door and {@code probe} works one of its controls in the
 * sandbox, while the door in the world stays exactly as it was. Each probe test has a batch of its own, since the
 * server runs one probe at a time.
 */
@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class MechanismProbeGameTests {

    private static final int WAIT = 1200;

    // A valve handle turns a gearbox, which turns a sticky piston that lifts a plank leaf.
    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = WAIT,
            batch = "mechanism_probe_door")
    public static void valveDoorGraphAndProbeLeaveTheWorldUnchanged(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        Cleanup.on(helper, fixture::close);
        ServerLevel level = helper.getLevel();
        BlockPos valve = helper.absolutePos(new BlockPos(2, 3, 1));
        SurveyFixture.confirm(helper, fixture.player, valve, valve.south(2));
        buildValveDoor(level, valve);
        AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(6, 7, 6);
        CompletableFuture<ToolReply>[] check = new CompletableFuture[1];
        String[] before = new String[1];
        helper.startSequence().thenIdle(10).thenExecute(() -> {
            before[0] = world(level, box);
            JsonObject at = fixture.at(valve);
            check[0] = new ScriptHost().run(fixture.player, "at = " + at + "\n"
                    + "graph = mechanism(at=at)\n"
                    + "discovery = controls(area={\"dimension\": at[\"dimension\"], \"from\": at, \"to\": at})\n"
                    + "handle = {\"x\": at[\"x\"], \"y\": at[\"y\"], \"z\": at[\"z\"], \"part\": \"turn\"}\n"
                    + "forward = probe(control=handle, value=2, at=at, ticks=60, release_tick=45)\n"
                    + "backward = probe(control=handle, value=-2, at=at, ticks=60, release_tick=45)\n"
                    + "result = {\"graph\": graph, \"discovery\": discovery, \"forward\": forward, \"backward\": backward}",
                    new JsonObject());
        }).thenWaitUntil(() -> helper.assertTrue(check[0] != null && check[0].isDone(), "probe to finish"))
            .thenExecute(() -> {
                JsonObject data = AsyncWait.result(helper, check[0]).data();
                helper.assertFalse(data.has("error"), "script runs: " + data);
                JsonObject graph = data.getAsJsonObject("result").getAsJsonObject("graph");
                helper.assertFalse(graph.has("error"), "mechanism reads: " + graph);
                JsonObject discovery = data.getAsJsonObject("result").getAsJsonObject("discovery");
                for (JsonObject result : List.of(graph, discovery)) {
                    var summaries = result.getAsJsonObject("parts");
                    helper.assertTrue(summaries.has("turn") && summaries.get("turn").getAsString().lines().count() == 1
                            && summaries.get("turn").getAsString().contains("Valve Handle"),
                            "the discovered turn part has a one-line summary: " + result);
                }
                JsonObject handle = node(graph, "copper_valve_handle");
                JsonObject lifter = node(graph, "sticky_mechanical_piston");
                helper.assertTrue(handle != null && roles(handle).contains("control")
                        && roles(handle).contains("source") && handle.has("degrees_per_turn"),
                        "the valve handle is a control and a source: " + graph);
                helper.assertTrue(lifter != null && roles(lifter).contains("actuator"),
                        "the piston is an actuator: " + graph);
                String from = handle.get("id").getAsString();
                String to = lifter.get("id").getAsString();
                helper.assertTrue(rows(graph, "edges").stream().anyMatch(edge -> edge.get("from").getAsString()
                        .equals(from) && edge.get("to").getAsString().equals(to)
                        && edge.get("link").getAsString().equals("drives")
                        && edge.has("via") && edge.get("via").toString().contains("gearbox")),
                        "the handle drives the piston through the folded gearbox: " + graph);
                helper.assertTrue(rows(graph, "paths").stream().anyMatch(path -> path.get("from").getAsString()
                        .equals(from) && path.get("to").getAsString().equals(to)
                        && path.get("factor").getAsDouble() != 0),
                        "a path weighs the handle's turn at the piston: " + graph);

                // One direction lifts the leaf; the other pulls a piston that is already in, so nothing moves.
                JsonObject forward = data.getAsJsonObject("result").getAsJsonObject("forward");
                JsonObject backward = data.getAsJsonObject("result").getAsJsonObject("backward");
                for (JsonObject probe : List.of(forward, backward)) {
                    helper.assertFalse(probe.has("error"), "probe runs: " + probe);
                    helper.assertTrue(probe.getAsJsonObject("input").get("result").getAsString().equals("written")
                            && probe.getAsJsonObject("input").has("done_tick"),
                            "the handle was turned in the sandbox: " + probe);
                }
                helper.assertTrue(lifts(forward, to) != lifts(backward, to),
                        "exactly one direction lifts the copy's leaf: " + forward + " " + backward);
                JsonObject lifting = lifts(forward, to) ? forward : backward;
                helper.assertTrue(rows(lifting, "response").stream().anyMatch(row -> row.get("id").getAsString()
                    .equals(to) && row.get("velocity").getAsDouble() > 0 && !row.get("dead_zone").getAsBoolean()
                    && row.get("start_ticks").getAsInt() >= 0 && row.get("stop_ticks").getAsInt() >= 0),
                    "identification measures positive piston travel and timing: " + lifting);
                helper.assertFalse(lifting.getAsJsonArray("summary").toString().contains("nothing observable"),
                        "the summary tells the change: " + lifting);

                helper.assertTrue(world(level, box).equals(before[0]), "the real door is untouched");
                helper.assertTrue(level.getEntitiesOfClass(AbstractContraptionEntity.class, box.inflate(4))
                        .isEmpty(), "no contraption appeared in the world");
                emptied(helper, fixture.player, valve);
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = WAIT,
            batch = "mechanism_probe_routine")
    public static void leverRoutineOpensDoorOnlyInTrial(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        Cleanup.on(helper, fixture::close);
        ServerLevel level = helper.getLevel();
        BlockPos lever = helper.absolutePos(new BlockPos(2, 2, 1));
        BlockPos door = lever.east();
        SurveyFixture.confirm(helper, fixture.player, lever, door);
        level.setBlockAndUpdate(lever.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(door.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
            .setValue(LeverBlock.FACING, Direction.NORTH));
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        level.setBlock(door, lower, 3);
        level.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        BlockPos from = lever.offset(-1, -1, -1);
        BlockPos to = door.offset(1, 2, 1);
        AABB box = new AABB(Vec3.atLowerCornerOf(from), Vec3.atLowerCornerOf(to.offset(1, 1, 1)));
        var extent = new FacilityExtent(Realm.of(level), from, to);
        var definition = Map.of("name", "trial", "extent", DefinitionCodec.extent(extent),
            "anchor", List.of(lever.getX(), lever.getY(), lever.getZ()), "controls",
            Map.of("switch", Map.of("part", "lever", "offset", List.of(0, 0, 0))),
            "routines", Map.of("open", Map.of("source", """
                def step(state, readings, args):
                    if readings["_failure"] != None:
                        return state, [fail(readings["_failure"])]
                    if state == None:
                        return True, [write("switch", True), await("switch", True, 10)]
                    return state, [done]
                """)));
        AtomicReference<CompletableFuture<ToolReply>> reply = new AtomicReference<>();
        String before = world(level, box);
        helper.startSequence().thenExecute(() -> reply.set(new ScriptHost().run(fixture.player,
                "result = probe(routine=\"open\", definition=" + new GsonBuilder().disableHtmlEscaping().create().toJson(definition) + ", args={})",
                new JsonObject())))
            .thenWaitUntil(() -> helper.assertTrue(reply.get().isDone(), "routine trial to finish"))
            .thenExecute(() -> {
                var data = AsyncWait.result(helper, reply.get()).data();
                helper.assertTrue(data.has("result"), "the tool returned a trial: " + data);
                var report = data.getAsJsonObject("result");
                helper.assertTrue(report.getAsJsonObject("routine").get("ending").getAsString().equals("done"),
                    "the routine finished: " + report);
                helper.assertTrue(rows(report.getAsJsonObject("blocks"), "altered").stream().anyMatch(row ->
                    row.get("to").getAsString().contains("iron_door") && row.get("to").getAsString().contains("open=true")),
                    "the door opened in the trial: " + report);
                helper.assertTrue(world(level, box).equals(before), "the blocks in the world are unchanged");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = WAIT)
    public static void mechanismReadsRequireColonyScopeUnlessOperator(GameTestHelper helper) {
        var fixture = new ReadFixture(helper);
        Cleanup.on(helper, fixture::close);
        var near = fixture.at(fixture.chest);
        var far = fixture.at(fixture.chest.north(32));
        var host = new ScriptHost();
        var check = host.run(fixture.player, """
            at = %s
            result = mechanism(at=at)
            """.formatted(far), new JsonObject()).thenAccept(reply -> outsideScope(helper, reply))
            .thenCompose(ignored -> host.run(fixture.player, """
                at = %s
                far = %s
                area = {"dimension": at["dimension"], "from": at, "to": far}
                result = controls(area=area)
                """.formatted(near, far), new JsonObject()))
            .thenAccept(reply -> outsideScope(helper, reply))
            .thenCompose(ignored -> host.run(fixture.player, """
                at = %s
                cell = {"x": at["x"], "y": at["y"], "z": at["z"]}
                area = {"dimension": at["dimension"], "from": cell, "to": cell}
                result = probe(area=area, redstone={"x": at["x"], "y": at["y"], "z": at["z"], "on": True})
                """.formatted(far), new JsonObject()))
            .thenAccept(reply -> outsideScope(helper, reply))
            .thenCompose(ignored -> {
                fixture.permission = 2;
                return host.run(fixture.player, """
                    at = %s
                    far = %s
                    area = {"dimension": at["dimension"], "from": at, "to": far}
                    result = controls(area=area)
                    """.formatted(near, far), new JsonObject());
            }).thenAccept(reply -> scriptData(helper, reply));
        AsyncWait.succeedWhen(helper, check, () -> { }, () -> { });
    }

    private static void outsideScope(GameTestHelper helper, ToolReply reply) {
        var data = reply.data();
        helper.assertTrue(data.has("error") && data.get("error").getAsString().equals("outside_survey"),
                "the unscoped read is refused: " + data);
    }

    // A lever beside an iron door: the probe throws the copy's lever, and the copy's door opens.
    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = WAIT,
            batch = "mechanism_probe_lever")
    public static void leverProbeOpensTheCopiedDoorOnly(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        Cleanup.on(helper, fixture::close);
        ServerLevel level = helper.getLevel();
        BlockPos lever = helper.absolutePos(new BlockPos(2, 2, 1));
        BlockPos door = lever.east();
        SurveyFixture.confirm(helper, fixture.player, lever, door);
        level.setBlockAndUpdate(lever.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(door.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.NORTH));
        BlockState lower = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        level.setBlock(door, lower, 3);
        level.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        level.setBlockAndUpdate(lever.west(), Blocks.COMMAND_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(lever.west().above(), Blocks.CHAIN_COMMAND_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(lever.above(2), Blocks.REPEATING_COMMAND_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(lever.north(), Blocks.STRUCTURE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(lever.north().above(), Blocks.JIGSAW.defaultBlockState());
        AABB box = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(5, 5, 4);
        CompletableFuture<ToolReply>[] check = new CompletableFuture[1];
        String[] before = new String[1];
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            before[0] = world(level, box);
            JsonObject at = fixture.at(lever);
            JsonObject area = new JsonObject();
            area.addProperty("dimension", at.get("dimension").getAsString());
            area.add("from", position(lever.offset(-1, -1, -1)));
            area.add("to", position(door.offset(1, 2, 1)));
            check[0] = new ScriptHost().run(fixture.player, "at = " + at + "\narea = " + area + "\n"
                    + "graph = mechanism(area=area)\n"
                    + "probed = probe(control={\"x\": at[\"x\"], \"y\": at[\"y\"], \"z\": at[\"z\"], "
                    + "\"part\": \"lever\"}, value=True, area=area, ticks=10)\n"
                    + "result = {\"graph\": graph, \"probe\": probed}", new JsonObject());
        }).thenWaitUntil(() -> helper.assertTrue(check[0] != null && check[0].isDone(), "probe to finish"))
            .thenExecute(() -> {
                JsonObject data = AsyncWait.result(helper, check[0]).data();
                helper.assertFalse(data.has("error"), "script runs: " + data);
                JsonObject graph = data.getAsJsonObject("result").getAsJsonObject("graph");
                JsonObject switched = node(graph, "lever");
                JsonObject leaf = node(graph, "iron_door");
                helper.assertTrue(switched != null && roles(switched).contains("control")
                        && leaf != null && roles(leaf).contains("actuator"), "lever and door are read: " + graph);
                helper.assertTrue(rows(graph, "edges").stream().anyMatch(edge -> edge.get("from").getAsString()
                        .equals(switched.get("id").getAsString())
                        && edge.get("to").getAsString().equals(leaf.get("id").getAsString())
                        && edge.get("link").getAsString().equals("powers")), "the lever powers the door: " + graph);

                JsonObject probe = data.getAsJsonObject("result").getAsJsonObject("probe");
                helper.assertFalse(probe.has("error"), "probe runs: " + probe);
                String notes = probe.getAsJsonArray("notes").toString();
                for (String block : List.of("command_block", "chain_command_block", "repeating_command_block",
                        "structure_block", "jigsaw")) {
                    helper.assertTrue(notes.contains("minecraft:" + block + "=1"), "sandbox skipped " + block);
                }
                helper.assertTrue(changed(probe, leaf.get("id").getAsString(), "open")
                        && changed(probe, switched.get("id").getAsString(), "powered"),
                        "the copy's lever powered and its door opened: " + probe);
                helper.assertTrue(world(level, box).equals(before[0])
                        && !level.getBlockState(lever).getValue(LeverBlock.POWERED)
                        && !level.getBlockState(door).getValue(DoorBlock.OPEN),
                        "the real lever and door are untouched");
                emptied(helper, fixture.player, lever);
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = WAIT,
            batch = "mechanism_probe_gantry")
    public static void leverIdentifiesNativeGantryResponse(GameTestHelper helper) {
        var fixture = new ReadFixture(helper);
        Cleanup.on(helper, fixture::close);
        var level = helper.getLevel();
        var shaft = helper.absolutePos(new BlockPos(2, 4, 2));
        var clutch = shaft.east(7);
        var lever = clutch.north();
        SurveyFixture.confirm(helper, fixture.player, shaft, clutch);
        for (int i = 0; i < 6; i++) {
            level.setBlockAndUpdate(shaft.east(i), create("gantry_shaft")
                .setValue(GantryShaftBlock.FACING, Direction.EAST).setValue(GantryShaftBlock.PART,
                    i == 0 ? GantryShaftBlock.Part.START
                        : i == 5 ? GantryShaftBlock.Part.END : GantryShaftBlock.Part.MIDDLE));
        }
        var carriage = create("gantry_carriage").setValue(GantryCarriageBlock.FACING, Direction.UP);
        if (GantryCarriageBlock.getValidGantryShaftAxis(carriage) != Direction.Axis.X) {
            carriage = carriage.cycle(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE);
        }
        level.setBlockAndUpdate(shaft.above(), carriage);
        level.setBlockAndUpdate(lever.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
            .setValue(LeverBlock.POWERED, true));
        level.setBlockAndUpdate(shaft.east(6), create("shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        level.setBlockAndUpdate(clutch, create("clutch").setValue(BlockStateProperties.AXIS, Direction.Axis.X)
            .setValue(BlockStateProperties.POWERED, true));
        level.setBlockAndUpdate(clutch.east(), create("creative_motor")
            .setValue(BlockStateProperties.FACING, Direction.WEST));

        CompletableFuture<ToolReply>[] answer = new CompletableFuture[1];
        helper.startSequence().thenIdle(8).thenExecute(() -> {
            JsonObject area = new JsonObject();
            area.addProperty("dimension", level.dimension().location().toString());
            area.add("from", position(shaft.offset(-1, -1, -2)));
            area.add("to", position(clutch.offset(2, 3, 2)));
            answer[0] = new ScriptHost().run(fixture.player, """
                area = %s
                at = %s
                control = {"x": at["x"], "y": at["y"], "z": at["z"], "part": "lever"}
                result = probe(area=area, control=control, value=False, ticks=80, release_tick=64)
                """.formatted(area, fixture.at(lever)), new JsonObject());
        }).thenWaitUntil(() -> helper.assertTrue(answer[0] != null && answer[0].isDone(), "gantry probe to finish"))
            .thenExecute(() -> {
                var data = AsyncWait.result(helper, answer[0]).data();
                helper.assertTrue(data.has("result"), "gantry probe runs: " + data);
                var probe = data.getAsJsonObject("result");
                helper.assertTrue(probe.has("response") && rows(probe, "response").stream().anyMatch(row ->
                    row.has("velocity") && Math.abs(row.get("velocity").getAsDouble() - 0.03125) < 0.005
                        && !row.get("dead_zone").getAsBoolean() && row.get("start_ticks").getAsInt() < 10
                        && row.get("stop_ticks").getAsInt() < 10 && !row.get("still_moving").getAsBoolean()),
                    "measure gantry rate and latencies: " + probe);
                helper.assertTrue(level.getBlockState(shaft.above()).is(create("gantry_carriage").getBlock())
                    && level.getBlockState(lever).getValue(LeverBlock.POWERED), "the original gantry stays parked");
                emptied(helper, fixture.player, shaft);
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = WAIT,
            batch = "resident_valve_door")
    public static void residentOpensAndClosesCalibratedValveDoor(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "valve-keeper"),
            ClientInformation.createDefault());
        var colony = Charters.found(player, "Valve calibration");
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        var resident = PersonBody.RESIDENT.get().create(level);
        var valve = helper.absolutePos(new BlockPos(5, 3, 9));
        var piston = valve.south(2);
        var leaf = piston.above();
        for (BlockPos floor : BlockPos.betweenClosed(valve.offset(-3, -1, -7), valve.offset(3, -1, 3))) {
            if (!floor.equals(piston.below())) {
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            }
        }
        buildValveDoor(level, valve);
        var start = valve.north(7).getBottomCenter();
        resident.moveTo(start);
        resident.setOnGround(true);
        level.addFreshEntity(resident);
        colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(colony.id());
        colony.entered(resident);
        var labor = new LaborFixture(colony, level);
        Cleanup.on(helper, () -> {
            labor.close();
            resident.discard();
            colony.raze();
        });

        SurveyFixture.confirm(helper, player, valve, piston);
        var services = DefinedFacilities.of(colony).orElseThrow();
        var host = new ScriptHost();
        var at = position(valve);
        at.addProperty("dimension", level.dimension().location().toString());
        var request = new AtomicReference<CompletableFuture<ToolReply>>();
        int[] turns = {0};
        boolean[] turning = {false};
        int[] opening = {0};
        var handle = (ValveHandleBlockEntity) level.getBlockEntity(valve);
        var lifter = (MechanicalPistonBlockEntity) level.getBlockEntity(piston);
        // The default 45° use moves less than half a block, so Create snaps it back when the use ends.
        handle.angleInput.setValue(180);
        helper.onEachTick(() -> {
            labor.tick();
            boolean active = handle.inUse > 0;
            if (active && !turning[0]) {
                turns[0]++;
            }
            if (active) {
                helper.assertTrue(Reach.stanceHolding(resident.position(), Reach.workableCells(level, valve)).isPresent(),
                    "resident stays within reach of the turning handle");
                helper.assertTrue(resident.distanceToSqr(start) >= 1, "resident walked to the handle");
            }
            turning[0] = active;
        });
        helper.startSequence().thenExecuteAfter(10, () -> request.set(host.run(player, """
            at = %s
            control = {"x": at["x"], "y": at["y"], "z": at["z"], "part": "turn"}
            graph = mechanism(at=at)
            forward = probe(control=control, value=2, at=at, ticks=60)
            backward = probe(control=control, value=-2, at=at, ticks=60)
            result = {"graph": graph, "probes": [forward, backward]}
            """.formatted(at), new JsonObject())))
            .thenWaitUntil(AsyncWait.until(helper, () -> request.get().isDone(), "door calibration"))
            .thenExecute(() -> {
                var data = scriptData(helper, AsyncWait.result(helper, request.get()));
                var result = data.getAsJsonObject("result");
                var probes = result.getAsJsonArray("probes");
                var forward = probes.get(0).getAsJsonObject();
                var backward = probes.get(1).getAsJsonObject();
                helper.assertFalse(forward.has("error") || backward.has("error"), "door probes: " + probes);
                var pistonNode = node(result.getAsJsonObject("graph"), "sticky_mechanical_piston");
                helper.assertTrue(pistonNode != null, "mechanism found the lifting piston: " + result);
                String pistonId = pistonNode.get("id").getAsString();
                helper.assertTrue(lifts(forward, pistonId) != lifts(backward, pistonId), "one direction lifts: " + probes);
                opening[0] = lifts(forward, pistonId) ? 2 : -2;
                helper.assertTrue(turns[0] == 0 && level.getBlockState(leaf).is(Blocks.OAK_PLANKS),
                    "calibration leaves the real door closed");
                request.set(approved(helper, player, host, """
                    set("facilities/vault_door/definition", {
                        "name": "vault_door",
                        "extent": {"elements": [{"space": {"dimension": "%s"}, "min": %s, "max": %s}]},
                        "anchor": %s,
                        "controls": {"valve": {"part": "turn", "offset": [0, 0, 0]}},
                        "routines": {
                            "open": {"use": "folkways_core:write_all", "with": {"valve": %d}},
                            "close": {"use": "folkways_core:write_all", "with": {"valve": %d}},
                        },
                        "triggers": {"open": {"kind": "manual", "routine": "open"},
                                     "close": {"kind": "manual", "routine": "close"}},
                        "ui": {"card": [{"button": "open", "icon": "minecraft:iron_door"},
                                        {"button": "close", "icon": "minecraft:iron_bars"}]},
                    })
                    """.formatted(at.get("dimension").getAsString(), coordinates(valve.below(2)),
                        coordinates(piston.above(3)), coordinates(valve), opening[0], -opening[0])));
            }).thenWaitUntil(AsyncWait.until(helper, () -> request.get().isDone(), "definition approval"))
            .thenExecute(() -> {
                AsyncWait.result(helper, request.get());
                resident.moveTo(start);
                resident.setOnGround(true);
                helper.assertTrue(Reach.stanceHolding(resident.position(), Reach.workableCells(level, valve)).isEmpty(),
                    "resident starts outside the handle's reach");
                request.set(approved(helper, player, host,
                    """
                    set("facilities/vault_door/runs/open", {"routine": "open"})
                    """));
            }).thenWaitUntil(AsyncWait.until(helper, () -> request.get().isDone(), "open approval"))
            .thenExecute(() -> AsyncWait.result(helper, request.get()))
            .thenWaitUntil(() -> helper.assertTrue(services.recent("vault_door")
                .filter(run -> run.routine().equals("open") && run.ending().equals("done")).isPresent(),
                "waiting for open after " + turns[0] + " turns: " + services.recent("vault_door")
                    + " " + labor.diagnosis() + " resident=" + resident.position()))
            .thenExecute(() -> {
                helper.assertTrue(turns[0] == 2, "the resident turned the handle twice: " + turns[0]);
                double raised = leafHeight(level, lifter, leaf);
                helper.assertTrue(lifter.offset > 0 && raised > leaf.getY() + .75,
                    "piston extended and raised the plank leaf: offset=" + lifter.offset + " height=" + raised);
                request.set(approved(helper, player, host,
                    """
                    set("facilities/vault_door/runs/close", {"routine": "close"})
                    """));
            }).thenWaitUntil(AsyncWait.until(helper, () -> request.get().isDone(), "close approval"))
            .thenExecute(() -> AsyncWait.result(helper, request.get()))
            .thenWaitUntil(() -> helper.assertTrue(turns[0] == 4 && !turning[0]
                && services.recent("vault_door")
                    .filter(run -> run.routine().equals("close") && run.ending().equals("done")).isPresent()
                && Math.abs(leafHeight(level, lifter, leaf) - (leaf.getY() + .5)) < .05,
                "waiting for the returned leaf after " + turns[0] + " turns: " + services.recent("vault_door")
                    + " offset=" + lifter.offset + " height=" + leafHeight(level, lifter, leaf)))
            .thenSucceed();
    }

    private static double leafHeight(ServerLevel level, MechanicalPistonBlockEntity piston, BlockPos closed) {
        // Create can leave the plank on the piston contraption between turns.
        var moving = piston.movedContraption;
        if (moving != null && moving.isAlive()) {
            return moving.getContraption().getBlocks().entrySet().stream()
                .filter(block -> block.getValue().state().is(Blocks.OAK_PLANKS))
                .mapToDouble(block -> moving.toGlobalVector(Vec3.atCenterOf(block.getKey()), 1).y)
                .findFirst().orElse(Double.NaN);
        }
        for (int rise = 0; rise <= 2; rise++) {
            if (level.getBlockState(closed.above(rise)).is(Blocks.OAK_PLANKS)) {
                return closed.getY() + rise + .5;
            }
        }
        return Double.NaN;
    }

    private static CompletableFuture<ToolReply> approved(GameTestHelper helper, ServerPlayer player, ScriptHost host,
            String source) {
        return host.run(player, source, new JsonObject()).thenCompose(preview -> {
            String revision = scriptData(helper, preview).get("revision").getAsString();
            return host.decide(player, revision, true);
        }).thenApply(reply -> {
            scriptData(helper, reply);
            return reply;
        });
    }

    private static JsonObject scriptData(GameTestHelper helper, ToolReply reply) {
        JsonObject data = reply.data();
        helper.assertFalse(data.has("error"), "script succeeds: " + data);
        return data;
    }

    private static List<Integer> coordinates(BlockPos pos) {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private static void buildValveDoor(ServerLevel level, BlockPos valve) {
        BlockPos gearbox = valve.south();
        BlockPos piston = gearbox.south();
        level.setBlockAndUpdate(valve.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(valve, create("copper_valve_handle")
                .setValue(BlockStateProperties.FACING, Direction.NORTH));
        level.setBlockAndUpdate(gearbox, create("gearbox").setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        level.setBlockAndUpdate(piston, pistonAlongZ());
        for (int depth = 1; depth <= 2; depth++) {
            level.setBlockAndUpdate(piston.below(depth), create("piston_extension_pole")
                    .setValue(BlockStateProperties.FACING, Direction.UP));
        }
        level.setBlockAndUpdate(piston.above(), Blocks.OAK_PLANKS.defaultBlockState());
    }

    private static BlockState create(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", id)).defaultBlockState();
    }

    // Facing up, with its shaft along Z so the gearbox to its north turns it.
    private static BlockState pistonAlongZ() {
        BlockState up = create("sticky_mechanical_piston").setValue(BlockStateProperties.FACING, Direction.UP);
        for (boolean first : List.of(true, false)) {
            BlockState state = up.setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, first);
            if (((IRotate) state.getBlock()).getRotationAxis(state) == Direction.Axis.Z) {
                return state;
            }
        }
        throw new IllegalStateException("No piston state turns along Z");
    }

    /** Every block state and block entity in the box, and the contraptions in it, as one comparable text. */
    private static String world(ServerLevel level, AABB box) {
        StringBuilder text = new StringBuilder();
        BlockPos.betweenClosedStream(box).forEach(pos -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                return;
            }
            text.append(pos.toShortString()).append('=').append(state);
            var block = level.getBlockEntity(pos);
            if (block != null) {
                text.append(block.saveWithoutMetadata(level.registryAccess()));
            }
            text.append('\n');
        });
        // The fixture's resident wanders; what a probe might leave behind is a contraption.
        for (Entity entity : level.getEntitiesOfClass(AbstractContraptionEntity.class, box.inflate(4))) {
            text.append(entity.getType()).append(' ').append(entity.position()).append('\n');
        }
        return text.toString();
    }

    private static void emptied(GameTestHelper helper, ServerPlayer player, BlockPos copied) {
        ServerLevel sandbox = Sandbox.level(player.getServer()).orElseThrow();
        List<Entity> left = new ArrayList<>();
        sandbox.getAllEntities().forEach(left::add);
        helper.assertTrue(left.isEmpty() && sandbox.getBlockState(copied).isAir() && !Sandbox.busy(),
                "the sandbox is empty again: " + left + " " + sandbox.getBlockState(copied));
    }

    // The piston's offset grew, its contraption rose, or the leaf was set down higher.
    private static boolean lifts(JsonObject probe, String piston) {
        boolean extended = rows(probe, "changes").stream().anyMatch(change -> change.get("id").getAsString()
                .equals(piston) && change.has("after") && change.getAsJsonObject("after").has("offset")
                && change.getAsJsonObject("after").get("offset").getAsDouble() > 0);
        boolean rose = rows(probe, "entities").stream().anyMatch(entity -> entity.has("moved")
                && entity.getAsJsonArray("moved").get(1).getAsDouble() > 0);
        return extended || rose || probe.getAsJsonObject("blocks").get("added_count").getAsInt() > 0;
    }

    private static boolean changed(JsonObject probe, String id, String key) {
        return rows(probe, "changes").stream().anyMatch(change -> change.get("id").getAsString().equals(id)
                && change.has("after") && change.getAsJsonObject("after").has(key)
                && !change.getAsJsonObject("after").get(key).equals(change.getAsJsonObject("before").get(key)));
    }

    private static JsonObject node(JsonObject graph, String kind) {
        return rows(graph, "nodes").stream().filter(node -> node.get("kind").getAsString().equals(kind))
                .findFirst().orElse(null);
    }

    private static List<String> roles(JsonObject node) {
        return node.getAsJsonArray("roles").asList().stream().map(JsonElement::getAsString).toList();
    }

    private static List<JsonObject> rows(JsonObject data, String list) {
        JsonArray array = data.getAsJsonArray(list);
        return array == null ? List.of() : array.asList().stream().map(JsonElement::getAsJsonObject).toList();
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject at = new JsonObject();
        at.addProperty("x", pos.getX());
        at.addProperty("y", pos.getY());
        at.addProperty("z", pos.getZ());
        return at;
    }
}
