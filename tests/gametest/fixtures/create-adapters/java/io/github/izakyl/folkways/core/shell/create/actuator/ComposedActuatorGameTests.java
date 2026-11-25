package io.github.izakyl.folkways.core.shell.create.actuator;

import com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlock;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.Motion;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.world.api.Keepouts;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class ComposedActuatorGameTests {

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void nativeChannelsPositionFromTheirStances(GameTestHelper helper) {
        position(helper, false, false);
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void staffedPostPositionsByFeedback(GameTestHelper helper) {
        position(helper, true, false);
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void timedPositionResumesFromLiveTravel(GameTestHelper helper) {
        position(helper, false, true);
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void staffedPositionResumesFromLiveTravel(GameTestHelper helper) {
        position(helper, true, true);
    }

    private static void position(GameTestHelper helper, boolean staffed, boolean reload) {
        var fixture = new FacilityGateFixture(helper);
        var level = fixture.level;
        var colony = fixture.colony;
        var resident = (ResidentEntity) fixture.body.mob();
        colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(colony.id());
        colony.entered(resident);
        var labor = new LaborFixture(colony, level);
        helper.onEachTick(labor::tick);
        Cleanup.on(helper, () -> {
            labor.close();
            fixture.close();
        });

        BlockPos origin = fixture.lever.at().cell().offset(0, 1, 5);
        BlockPos x = origin.east(4);
        BlockPos z = origin.south(6);
        BlockPos y = origin.offset(7, 0, 5);
        BlockPos xLever = x.east(8).north();
        BlockPos zLever = z.south(8).west();
        BlockPos yLever = y.west(2).north();
        for (BlockPos floor : BlockPos.betweenClosed(origin.offset(-3, -2, -7), origin.offset(14, -2, 16))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        gantry(level, x, Direction.EAST, xLever);
        gantry(level, z, Direction.SOUTH, zLever);
        level.setBlockAndUpdate(y, create("rope_pulley").setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
        level.setBlockAndUpdate(y.below(2), Blocks.AIR.defaultBlockState());
        lever(level, yLever);
        level.setBlockAndUpdate(y.west(2), create("clutch").setValue(BlockStateProperties.AXIS, Direction.Axis.X)
            .setValue(BlockStateProperties.POWERED, true));
        level.setBlockAndUpdate(y.west(), create("shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        level.setBlockAndUpdate(y.west(3), create("creative_motor")
            .setValue(BlockStateProperties.FACING, Direction.EAST));
        var anchor = WorldPos.of(level, origin);
        var extent = new FacilityExtent(anchor.realm(), origin.offset(-3, -2, -7), origin.offset(14, 6, 16));
        Map<String, Object> controls = new LinkedHashMap<>();
        controls.put("x", part(origin, x, "motion"));
        controls.put("z", part(origin, z, "motion"));
        controls.put("y", part(origin, y, "motion"));
        controls.put("goX", part(origin, xLever, "lever"));
        controls.put("goZ", part(origin, zLever, "lever"));
        controls.put("goY", part(origin, yLever, "lever"));
        var settings = Map.of(
            "frame", Map.of("origin", cells(origin), "axes", Map.of(
                "x", List.of(1, 0, 0), "z", List.of(0, 0, 1), "y", List.of(0, -1, 0))),
            "response", Map.of("x", rate("goX"), "z", rate("goZ"), "y", rate("goY")),
            "order", List.of("x", "z", "y"), "tolerance", 0.25);
        Map<String, Object> data = new LinkedHashMap<>(Map.of("name", "positioner", "anchor", cells(origin),
            "extent", DefinitionCodec.extent(extent), "controls", controls,
            "routines", Map.of("move", Map.of("source", ScriptedFacilityFixture.POSITION_SOURCE, "with", settings)),
            "triggers", Map.of("move", Map.of("kind", "manual", "routine", "move"))));
        if (staffed) {
            data.put("posts", Map.of("desk", Map.of("stance", Map.of("footing", cells(fixture.body.mob().blockPosition().subtract(origin))),
                "controls", List.of("goX", "goZ", "goY"), "staffing", "standing")));
        }
        var service = DefinedFacilities.of(colony).orElseThrow();
        int zGoal = reload ? 4 : 2;
        BlockPos desk = resident.blockPosition();
        var restarted = new AxisStarts(level, zLever);
        var sequence = helper.startSequence().thenIdle(8).thenExecute(() -> {
            var preview = service.preview("positioner", Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "valid native machine: " + preview.problems());
            helper.assertTrue(service.apply(preview).isEmpty(), "install the machine");
            var outside = service.run("positioner", "move", Map.of("pos", point(origin.east(20))));
            helper.assertTrue(outside.isEmpty(), "submit the position for a worker reach check: " + outside);
        }).thenWaitUntil(() -> helper.assertTrue(service.recent("positioner").flatMap(DefinedFacilities.Result::reason)
            .filter(reason -> reason.endsWith("position_travel")).isPresent(), "reject an unreachable position"));
        if (staffed) {
            sequence.thenWaitUntil(() -> {
                var facility = service.facilities().stream().filter(each -> each.name().equals("positioner"))
                    .findFirst().orElseThrow();
                helper.assertTrue(service.posts().resolve(level, facility).stream().anyMatch(post -> service.posts().staffed(level, post)), "wait for a resident at the controls");
            });
        }
        sequence.thenExecute(() -> {
            var run = service.run("positioner", "move", Map.of("pos", point(origin.offset(2, -2, zGoal))));
            helper.assertTrue(run.isEmpty(), "start positioning: " + run);
        });
        if (reload) {
            sequence.thenWaitUntil(() -> {
                var reading = sensor(level, z);
                helper.assertTrue(reading.moving() && reading.position() >= 1.25 && reading.position() < zGoal,
                    "save during the second axis's native motion");
            }).thenExecute(() -> {
                var saved = colony.kept(DefinedFacilities.ID).copy();
                var request = saved.getCompound("positioner").getCompound("requests").getCompound("move");
                var script = saved.getCompound("positioner").getList("scripts", net.minecraft.nbt.Tag.TAG_COMPOUND)
                    .getCompound(0);
                helper.assertTrue(request.hasUUID("active") && script.contains("state"),
                    "the active script persisted its remaining ordered legs");
                var marker = anchor.at(origin.west(2));
                saved.put("marker", marker.save());
                colony.keep(DefinedFacilities.ID, saved);
                ColonyData.find(level.getServer(), colony.id()).orElseThrow()
                    .relocate(level.getServer(), Map.of(marker, anchor.at(origin.west(3))));
                helper.assertTrue(DefinedFacilities.of(colony).orElseThrow() != service,
                    "reload rebuilds the facility service from its keep");
                helper.onEachTick(() -> {
                    restarted.sample();
                    helper.assertTrue(level.getBlockState(xLever).getValue(LeverBlock.POWERED),
                        "resume leaves the completed X axis stopped");
                    var reading = sensor(level, z);
                    helper.assertTrue(reading.position() >= 1 && reading.position() <= zGoal + 0.25,
                        "resume continues from the remaining Z travel: " + reading.position());
                    if (staffed && reading.moving() && restarted.count > 0) {
                        helper.assertTrue(resident.blockPosition().equals(desk),
                            "the restored resident drives Z from the staffed post");
                    }
                });
            });
        }
        sequence.thenWaitUntil(() -> {
            var current = DefinedFacilities.of(colony).orElseThrow();
            var result = current.recent("positioner");
            helper.assertTrue(result.filter(each -> each.ending().equals("done")).isPresent(),
                "positioning: " + result + ", x=" + sensor(level, x).position()
                    + ", z=" + sensor(level, z).position() + ", y=" + sensor(level, y).position()
                    + ", active=" + current.running("positioner") + ", resident=" + resident.blockPosition()
                    + ", route=" + labor.route(fixture.body));
        })
            .thenExecute(() -> {
                helper.assertTrue(Math.abs(sensor(level, x).position() - 2) <= 0.25, "X at target");
                helper.assertTrue(Math.abs(sensor(level, z).position() - zGoal) <= 0.25, "Z at target");
                helper.assertTrue(Math.abs(sensor(level, y).position() - 2) <= 0.25, "Y at target");
                helper.assertTrue(level.getBlockState(xLever).getValue(LeverBlock.POWERED)
                    && level.getBlockState(zLever).getValue(LeverBlock.POWERED), "the native clutches are stopped");
                if (reload) {
                    helper.assertTrue(restarted.count == 1, "Z starts once after shutdown stopped the active drive");
                }
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 300)
    public static void residentRoutesAroundMovingGantryLoad(GameTestHelper helper) {
        var fixture = new FacilityGateFixture(helper);
        var level = fixture.level;
        var resident = fixture.body.mob();
        Cleanup.on(helper, fixture::close);

        BlockPos rail = fixture.lever.at().cell().offset(1, 3, 9);
        BlockPos input = rail.east(8).north();
        BlockPos crossing = rail.offset(1, -2, 0);
        BlockPos near = crossing.north(4);
        BlockPos goal = crossing.south(4);
        for (BlockPos floor : BlockPos.betweenClosed(crossing.offset(-5, -1, -5), crossing.offset(9, -1, 5))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        gantry(level, rail, Direction.EAST, input);
        level.setBlockAndUpdate(rail.above(2), Blocks.CHEST.defaultBlockState());
        resident.moveTo(near.getBottomCenter());
        var navigation = resident.getNavigation();
        var anchor = WorldPos.of(level, rail);
        var extent = new FacilityExtent(anchor.realm(), crossing.offset(-5, -1, -5), rail.offset(9, 3, 5));
        var data = Map.of("name", "hoist", "anchor", cells(rail), "extent", DefinitionCodec.extent(extent),
            "controls", Map.of("x", part(rail, rail, "motion")),
            "projections", List.of(Map.of("keepout", Map.of("while_moving", List.of("x"), "margin", 0.5))));
        var service = DefinedFacilities.of(fixture.colony).orElseThrow();

        helper.startSequence().thenIdle(8).thenExecute(() -> {
            var preview = service.preview("hoist", Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "valid moving keepout: " + preview.problems());
            helper.assertTrue(service.apply(preview).isEmpty(), "publish the hoist");
            var direct = navigation.createPath(goal, 0);
            helper.assertTrue(direct != null && direct.canReach() && crosses(direct, crossing),
                "the shortest stationary route crosses beneath the load's travel");
            level.setBlockAndUpdate(input, level.getBlockState(input).setValue(LeverBlock.POWERED, false));
        }).thenWaitUntil(() -> {
            var motion = sensor(level, rail);
            helper.assertTrue(motion.moving() && motion.position() >= 1 && motion.position() < 1.5,
                "the native gantry is carrying the load across the walkway");
        }).thenExecute(() -> {
            helper.assertTrue(Keepouts.forbidden(level, crossing), "the live sweep excludes the crossing");
            var detour = navigation.createPath(goal, 0);
            helper.assertTrue(detour != null && detour.canReach() && !crosses(detour, crossing),
                "the resident finds a detour around the live keepout");
            for (int index = 0; index < detour.getNodeCount(); index++) {
                var node = detour.getNode(index);
                helper.assertFalse(Keepouts.forbidden(level, node.x, node.y, node.z), "detour nodes avoid the sweep");
            }
            helper.assertTrue(navigation.moveTo(detour, 1), "the resident starts walking the detour");
        }).thenWaitUntil(() -> {
            var motion = sensor(level, rail);
            helper.assertTrue(motion.moving() && motion.position() >= 4, "the load has crossed the walkway");
        }).thenExecute(() -> {
            helper.assertTrue(Keepouts.forbidden(level, crossing.east(3)), "the live keepout follows the load");
            level.setBlockAndUpdate(input, level.getBlockState(input).setValue(LeverBlock.POWERED, true));
        }).thenWaitUntil(() -> helper.assertTrue(!sensor(level, rail).moving(), "the load stops"))
            .thenWaitUntil(() -> {
                helper.assertFalse(Keepouts.forbidden(level, crossing.east(3)),
                    "the next facility projection clears the keepout under the parked load");
                helper.assertFalse(Keepouts.forbidden(level, crossing), "the crossing stays clear");
            }).thenWaitUntil(() -> helper.assertTrue(resident.distanceToSqr(goal.getBottomCenter()) < 1,
                "the resident finishes walking to the other side")).thenSucceed();

        helper.onEachTick(() -> sensor(level, rail).sweep().ifPresent(sweep -> {
            AABB keepout = sweep.inflate(0.5).setMinY(crossing.getY() - 1);
            helper.assertFalse(keepout.intersects(resident.getBoundingBox()),
                "the resident's body stays outside the swept box during movement");
        }));
    }

    private static boolean crosses(Path path, BlockPos cell) {
        for (int index = 0; index < path.getNodeCount(); index++) {
            if (path.getNodePos(index).equals(cell)) {
                return true;
            }
        }
        return false;
    }

    private static final class AxisStarts {
        private final ServerLevel level;
        private final BlockPos lever;
        private boolean powered = true;
        private int count;

        AxisStarts(ServerLevel level, BlockPos lever) {
            this.level = level;
            this.lever = lever;
        }

        void sample() {
            boolean next = level.getBlockState(lever).getValue(LeverBlock.POWERED);
            if (powered && !next) {
                count++;
            }
            powered = next;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void poweredPistonMovesNativelyWithoutFacility(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 4, 4));
        level.setBlockAndUpdate(at, create("mechanical_piston").setValue(BlockStateProperties.FACING, Direction.EAST)
            .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, true));
        for (int i = 1; i <= 4; i++) {
            level.setBlockAndUpdate(at.west(i), create("piston_extension_pole")
                .setValue(BlockStateProperties.FACING, Direction.EAST));
        }
        level.setBlockAndUpdate(at.above(), create("creative_motor")
            .setValue(BlockStateProperties.FACING, Direction.DOWN));
        var address = WorldPos.of(level, at);
        helper.assertTrue(Controls.signals(level, address).isEmpty(), "the piston has only readings");
        double[] before = {-1};
        helper.startSequence().thenWaitUntil(() -> {
            var piston = (MechanicalPistonBlockEntity) level.getBlockEntity(at);
            helper.assertTrue(piston.running && piston.offset > 0 && piston.offset < 3,
                "Create extends the powered piston");
            before[0] = sensor(level, at).position();
        }).thenIdle(1).thenExecute(() -> {
            var piston = (MechanicalPistonBlockEntity) level.getBlockEntity(at);
            helper.assertTrue(Math.abs(sensor(level, at).position() - before[0] - piston.getMovementSpeed()) < 1e-6,
                "the piston advances exactly by its native motion speed");
        }).thenSucceed();
    }

    public static void gantry(ServerLevel level, BlockPos start, Direction axis, BlockPos input) {
        for (int i = 0; i < 7; i++) {
            level.setBlockAndUpdate(start.relative(axis, i), create("gantry_shaft")
                .setValue(GantryShaftBlock.FACING, axis).setValue(GantryShaftBlock.PART, i == 0
                    ? GantryShaftBlock.Part.START : i == 6 ? GantryShaftBlock.Part.END : GantryShaftBlock.Part.MIDDLE));
        }
        BlockState carriage = create("gantry_carriage").setValue(GantryCarriageBlock.FACING, Direction.UP);
        if (GantryCarriageBlock.getValidGantryShaftAxis(carriage) != axis.getAxis()) {
            carriage = carriage.cycle(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE);
        }
        level.setBlockAndUpdate(start.above(), carriage);
        if (input != null) {
            lever(level, input);
        }
        var clutch = start.relative(axis, 8);
        level.setBlockAndUpdate(clutch, create("clutch").setValue(BlockStateProperties.AXIS, axis.getAxis())
            .setValue(BlockStateProperties.POWERED, level.hasNeighborSignal(clutch)));
        level.setBlockAndUpdate(start.relative(axis, 7), create("shaft")
            .setValue(BlockStateProperties.AXIS, axis.getAxis()));
        level.setBlockAndUpdate(start.relative(axis, 9), create("creative_motor")
            .setValue(BlockStateProperties.FACING, axis.getOpposite()));
    }

    private static void lever(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
            .setValue(LeverBlock.POWERED, true));
    }

    public static Motion sensor(ServerLevel level, BlockPos at) {
        return (Motion) Controls.resolve(level, new ControlRef(WorldPos.of(level, at), "motion")).orElseThrow();
    }

    private static Map<String, Object> rate(String channel) {
        return Map.of("channel", channel, "stop", true, "rates",
            List.of(Map.of("value", false, "velocity", 0.03125, "start_ticks", 2, "stop_ticks", 2)));
    }

    private static Map<String, Object> part(BlockPos origin, BlockPos pos, String part) {
        return Map.of("offset", cells(pos.subtract(origin)), "part", part);
    }

    private static List<Integer> cells(BlockPos pos) {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private static Value point(BlockPos pos) {
        return Value.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockState create(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:" + id)).defaultBlockState();
    }
}
