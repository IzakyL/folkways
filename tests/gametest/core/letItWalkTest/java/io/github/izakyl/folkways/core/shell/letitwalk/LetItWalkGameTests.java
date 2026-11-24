package io.github.izakyl.folkways.core.shell.letitwalk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import dev.letitwalk.content.WalkerCoreBlock;
import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.letitwalk.demo.DemoWalkers;
import dev.letitwalk.walker.WalkerAssembler;
import dev.letitwalk.walker.WalkerLimbs;
import dev.letitwalk.walker.WalkerTracker;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import io.github.izakyl.folkways.assistant.sandbox.Probe;
import io.github.izakyl.folkways.assistant.sandbox.Sandbox;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.SelectedTests;
import io.github.izakyl.folkways.core.travel.api.Passages;
import io.github.izakyl.folkways.core.travel.api.TrafficFeedback;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Trip;
import io.github.izakyl.folkways.core.travel.internal.Urgency;
import io.github.izakyl.folkways.core.travel.internal.graph.Roamer;
import io.github.izakyl.folkways.core.travel.internal.graph.Roaming;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class LetItWalkGameTests {

    // Sable steps in floats: the pads stay near the origin, apart from each other and from the test templates.
    private static final int PAD_Y = 100;
    // Let it Walk!'s demo spider: the core, its motor, a seat and the pilot controls stand in a row along the deck.
    private static final int CONTROLS_AHEAD_OF_CORE = 3;
    private static final int SETTLE = 80;
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-let-it-walk-tests");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        SelectedTests.register(event, LetItWalkGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 350)
    public static void pilotHeightRaisesAndLowersTheHull(GameTestHelper helper) {
        var level = helper.getLevel();
        var walker = Walker.build(helper, new BlockPos(1664, PAD_Y, 64));
        double[] raised = new double[1];
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            var height = Controls.signal(level, walker.controls(), "height").orElseThrow();
            helper.assertTrue(height.write(level, null, Value.of(.45)) == Signal.Write.WRITTEN,
                "the pilot accepts a height target");
        }).thenIdle(45).thenExecute(() -> {
            var height = Controls.signal(level, walker.controls(), "height").orElseThrow();
            var offset = (Value.Real) height.feedback().orElseThrow().read(level).orElseThrow();
            raised[0] = Walkers.position(walker.walkerCore(level)).orElseThrow().y;
            helper.assertTrue(Math.abs(offset.value() - .45) <= .05 && raised[0] > walker.start()[0].y + .15,
                "the height reading and hull rise with the native raise input");
            helper.assertTrue(height.write(level, null, Value.of(-.45)) == Signal.Write.WRITTEN,
                "the pilot accepts a lower height target");
        }).thenIdle(55).thenExecute(() -> {
            var height = Controls.signal(level, walker.controls(), "height").orElseThrow();
            var offset = (Value.Real) height.feedback().orElseThrow().read(level).orElseThrow();
            var at = Walkers.position(walker.walkerCore(level)).orElseThrow();
            helper.assertTrue(Math.abs(offset.value() + .45) <= .05 && at.y < raised[0] - .3,
                "the height reading and hull fall with the native lower input");
        }).thenSucceed();
    }

    // A box drawn in the world round the pilot controls covers that corner of the hull, read in the hull's own cells.
    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 350)
    public static void aWorldBoxNamesThePartOfTheHullItCovers(GameTestHelper helper) {
        var level = helper.getLevel();
        var walker = Walker.build(helper, new BlockPos(2432, PAD_Y, 64));
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            var controls = walker.controls().cell();
            var frame = WorldSpaces.frame(level, walker.hull()).orElseThrow();
            var middle = BlockPos.containing(frame.toWorld(Vec3.atCenterOf(controls)));
            var overlaps = WorldSpaces.overlapping(level, middle.offset(-1, -1, -1), middle.offset(1, 1, 1));
            var hull = overlaps.stream().filter(overlap -> overlap.frame().id().equals(walker.hull()))
                .findFirst().orElse(null);
            helper.assertTrue(hull != null, "the box names the hull: " + overlaps);
            var covered = hull.local();
            var whole = frame.bounds().orElseThrow();
            helper.assertTrue(covered.isInside(controls), "the covered part holds the controls: " + covered
                + " for " + controls);
            helper.assertTrue(covered.getXSpan() <= 4 && covered.getYSpan() <= 4 && covered.getZSpan() <= 4
                && whole.intersects(covered) && !covered.equals(whole), "only that corner of " + whole
                + " is covered: " + covered);
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 350)
    public static void pilotLeanTiltsTheHull(GameTestHelper helper) {
        var level = helper.getLevel();
        var walker = Walker.build(helper, new BlockPos(1920, PAD_Y, 64));
        var up = new Vec3(0, 1, 0);
        Vec3[] upright = new Vec3[1];
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            upright[0] = walker.find(level).orElseThrow().logicalPose().transformNormal(up);
            var lean = Controls.signal(level, walker.controls(), "lean").orElseThrow();
            helper.assertTrue(lean.write(level, null, Value.of(.25, -.15)) == Signal.Write.WRITTEN,
                "the pilot accepts roll and pitch");
        }).thenIdle(40).thenExecute(() -> {
            var lean = Controls.signal(level, walker.controls(), "lean").orElseThrow();
            var reading = (Value.Vector) lean.feedback().orElseThrow().read(level).orElseThrow();
            var tilted = walker.find(level).orElseThrow().logicalPose().transformNormal(up);
            helper.assertTrue(Math.abs(reading.values().get(0) - .25) < .001
                && Math.abs(reading.values().get(1) + .15) < .001 && tilted.distanceTo(upright[0]) > .08,
                "native roll and pitch tilt the hull: " + reading + ", up changed from " + upright[0] + " to "
                    + tilted);
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 350)
    public static void pilotLimbMovesTheEndEffectorAndReleases(GameTestHelper helper) {
        var level = helper.getLevel();
        var walker = Walker.build(helper, new BlockPos(2176, PAD_Y, 64));
        Vec3[] first = new Vec3[1];
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            var limb = Controls.signal(level, walker.controls(), "limb/1").orElseThrow();
            helper.assertTrue(limb.write(level, null, Value.of(0, .65)) == Signal.Write.WRITTEN,
                "the pilot selects the first leg");
        }).thenIdle(40).thenExecute(() -> {
            var limb = Controls.signal(level, walker.controls(), "limb/1").orElseThrow();
            var leg = WalkerLimbs.limb(walker.walkerCore(level), 1);
            helper.assertTrue(leg.manualFoot != null, "the native pilot holds the end effector");
            first[0] = leg.manualFoot;
            helper.assertTrue(limb.write(level, null, Value.of(.55, .55)) == Signal.Write.WRITTEN,
                "the held leg accepts a new aim");
        }).thenIdle(40).thenExecute(() -> {
            var core = walker.walkerCore(level);
            var leg = WalkerLimbs.limb(core, 1);
            var limb = Controls.signal(level, walker.controls(), "limb/1").orElseThrow();
            var reading = ((Value.Data) limb.feedback().orElseThrow().read(level).orElseThrow()).tag();
            var reported = new Vec3(reading.getDouble("x"), reading.getDouble("y"), reading.getDouble("z"));
            // The reading is where the joints have carried the end, which the held aim pulls along.
            var points = leg.built.chain.forward(leg.built.angles, null);
            var tip = points[points.length - 1];
            var transformed = walker.find(level).orElseThrow().logicalPose().transformPosition(
                Vec3.atLowerCornerOf(core.getBlockPos()).add(tip[0], tip[1], tip[2]));
            helper.assertTrue(leg.manualFoot.distanceTo(first[0]) > .15 && reported.distanceTo(transformed) < .01
                && !reading.getBoolean("arm"), "the leg moves and reports its world-space end");
            helper.assertTrue(limb.write(level, null, null) == Signal.Write.WRITTEN && core.manualLimb == 0,
                "a null target releases the native pilot's limb");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1600)
    public static void pilotControlsWalkTheWalkerToAPoint(GameTestHelper helper) {
        var level = helper.getLevel();
        var pad = new BlockPos(640, PAD_Y, 64);
        var walker = Walker.build(helper, pad);
        var writer = helper.spawn(EntityType.PIG, new BlockPos(0, 2, 0));
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            var drive = Controls.signal(level, walker.controls(), WalkerDrive.PART).orElseThrow();
            var park = Controls.signal(level, walker.core(), WalkerPark.PART).orElseThrow();
            helper.assertTrue(Controls.at(level, walker.controls()).stream().map(Control::part).toList()
                .contains(WalkerDrive.PART), "the pilot controls aboard the hull offer the drive");
            helper.assertTrue(drive.valid(level) && drive.cost() instanceof Control.Cost.UntilRead,
                "a powered walker can be driven, a crank turned until it arrives");
            helper.assertTrue(read(level, walker.controls(), "powered").equals(Value.of(true)), "motor turns the core");
            helper.assertTrue(park.write(level, writer, Value.of(true)) == Signal.Write.WRITTEN
                && read(level, walker.core(), "parked").equals(Value.of(true)) && !drive.valid(level),
                "a parked walker cannot be driven");
            helper.assertTrue(park.write(level, writer, Value.of(false)) == Signal.Write.WRITTEN
                && drive.valid(level), "unparked, it can be driven again");
            helper.assertTrue(drive.write(level, writer, Value.of(1, 2)) == Signal.Write.REFUSED,
                "a destination has three coordinates");
        }).thenWaitUntil(() -> {
            var target = walker.target(new BlockPos(-6, 0, 18));
            var drive = Controls.signal(level, walker.controls(), WalkerDrive.PART).orElseThrow();
            helper.assertTrue(drive.write(level, writer, target) == Signal.Write.WRITTEN, "the walker takes the keys");
            var at = read(level, walker.controls(), "position");
            helper.assertTrue(at.equals(target), "still walking: at " + at + " for " + target);
        }).thenExecute(() -> {
            var core = walker.walkerCore(level);
            var at = Walkers.position(core).orElseThrow();
            LOGGER.info("Pilot controls walked the walker from {} to {} by tick {}", walker.start()[0], at,
                helper.getTick());
            var target = walker.world(new BlockPos(-6, 0, 18));
            helper.assertTrue(at.subtract(target).horizontalDistance() <= WalkerDrive.ARRIVED,
                "the walker stands at its destination: " + at);
            helper.assertTrue(core.isAssembled() && !core.tippingOver, "and still stands on its legs");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 600)
    public static void nearDestinationPreservesHeading(GameTestHelper helper) {
        Vec3 corner = new Vec3(4, PAD_Y, 2);
        helper.assertFalse(LetItWalkIntegration.positionReading(corner.add(.9, 0, .9), corner)
            .equals(Value.of(corner.x, corner.y, corner.z)), "the block grid does not report a premature arrival");
        checkDriveHeading(helper, new BlockPos(2688, PAD_Y, 64), 4, 2, 3);
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1000)
    public static void farDestinationTurnsThenHoldsHeading(GameTestHelper helper) {
        checkDriveHeading(helper, new BlockPos(2944, PAD_Y, 64), -8, -14, 15);
    }

    private static void checkDriveHeading(GameTestHelper helper, BlockPos pad, double ahead, double left,
                                          double tolerance) {
        var level = helper.getLevel();
        var walker = Walker.build(helper, pad);
        Vec3[] startHeading = new Vec3[1];
        Vec3[] target = new Vec3[1];
        boolean near = Math.hypot(ahead, left) < 8;
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            Vec3 heading = Walkers.heading(walker.walkerCore(level)).orElseThrow();
            startHeading[0] = heading;
            target[0] = walker.start()[0].add(heading.scale(ahead))
                .add(new Vec3(heading.z, 0, -heading.x).scale(left));
        }).thenWaitUntil(() -> {
            var drive = Controls.signal(level, walker.controls(), WalkerDrive.PART).orElseThrow();
            var destination = Value.of(target[0].x, target[0].y, target[0].z);
            helper.assertTrue(drive.write(level, null, destination) == Signal.Write.WRITTEN, "the walker takes the drive");
            var at = read(level, walker.controls(), "position");
            helper.assertTrue(at.equals(destination), "still walking: at " + at + " for " + destination);
        }).thenExecuteAfter(24, () -> {
            var core = walker.walkerCore(level);
            Vec3 at = Walkers.position(core).orElseThrow();
            Vec3 heading = Walkers.heading(core).orElseThrow();
            Vec3 expected = near ? startHeading[0] : target[0].subtract(walker.start()[0]).normalize();
            double error = at.subtract(target[0]).horizontalDistance();
            double yawChange = headingDifference(startHeading[0], heading);
            double yawError = headingDifference(expected, heading);
            LOGGER.info("Walker {} drive settled: arrival error {} blocks, yaw change {} degrees, heading error {} degrees",
                near ? "near" : "far", error, yawChange, yawError);
            helper.assertTrue(error <= WalkerDrive.ARRIVED, "settled within arrival tolerance: " + error);
            helper.assertTrue(yawError < tolerance, "finished on the expected heading: " + yawError + " degrees off");
            helper.assertTrue(core.isAssembled() && !core.tippingOver, "still standing on its legs");
        }).thenSucceed();
    }

    private static double headingDifference(Vec3 from, Vec3 to) {
        return Math.toDegrees(Math.atan2(Math.abs(to.x * from.z - to.z * from.x), from.dot(to)));
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 2400)
    public static void residentAboardWalksTheWalkerWhereTheCardSends(GameTestHelper helper) {
        var level = helper.getLevel();
        var pad = new BlockPos(896, PAD_Y, 64);
        var walker = Walker.build(helper, pad);
        var colony = Colonies.mint(level.getServer());
        var resident = PersonBody.RESIDENT.get().create(level);
        var labor = new LaborFixture(colony, level);
        Cleanup.on(helper, () -> {
            labor.close();
            resident.discard();
            colony.raze();
        });
        var name = "Spider";
        var services = DefinedFacilities.of(colony).orElseThrow();
        double[] lowest = {Double.MAX_VALUE};
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            // On the deck beside the pilot controls, where a crew stands.
            var deck = walker.controls().at(walker.controls().cell().relative(Direction.SOUTH.getClockWise()));
            resident.moveTo(WorldSpaces.world(level, deck).orElseThrow().add(0, .02, 0));
            resident.setOnGround(true);
            level.addFreshEntity(resident);
            colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
            resident.joinColony(colony.id());
            colony.entered(resident);
            helper.onEachTick(labor::tick);
            var extent = FacilityExtent.structure(level, walker.hull()).orElseThrow();
            var core = walker.core().cell().subtract(walker.controls().cell());
            var data = Map.<String, Object>of("name", name, "extent", DefinitionCodec.extent(extent),
                "anchor", xyz(walker.controls().cell()),
                "controls", Map.of("destination", Map.of("part", WalkerDrive.PART, "offset", List.of(0, 0, 0)),
                    "park", Map.of("part", WalkerPark.PART, "offset", xyz(core))),
                "routines", Map.of("walk", Map.of("use", "folkways_core:move_to")),
                "triggers", Map.of("order", Map.of("kind", "manual", "routine", "walk")),
                "ui", Map.of("card", List.of(Map.of("target", "walk", "pick", "pos", "default", true))));
            var preview = services.preview(name, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "the walker validates as a facility: " + preview.problems());
            helper.assertTrue(services.apply(preview).isEmpty(), "walker facility installed");
            var problems = services.run(name, "walk", Map.of("target", walker.target(new BlockPos(5, 0, 16))));
            helper.assertTrue(problems.isEmpty(), "walk submitted: " + problems);
        }).thenWaitUntil(() -> {
            lowest[0] = Math.min(lowest[0], resident.getY());
            helper.assertTrue(services.recent(name).filter(result -> result.ending().equals("done")).isPresent(),
                "waiting for the walk: " + services.recent(name) + " " + labor.diagnosis() + " resident at "
                    + resident.position() + " walker at " + Walkers.position(walker.walkerCore(level)));
        }).thenExecute(() -> {
            var at = Walkers.position(walker.walkerCore(level)).orElseThrow();
            LOGGER.info("A resident walked the walker from {} to {} by tick {}; the resident rode at {}",
                walker.start()[0], at, helper.getTick(), resident.position());
            helper.assertTrue(at.subtract(walker.world(new BlockPos(5, 0, 16))).horizontalDistance()
                <= WalkerDrive.ARRIVED, "the resident walked the walker to the picked block: " + at);
            helper.assertTrue(resident.isAlive() && WorldSpaces.at(resident).realm().equals(walker.controls().realm()),
                "the resident rode the deck all the way, at " + resident.position());
            helper.assertTrue(lowest[0] > PAD_Y + 2, "and never fell off it: lowest " + lowest[0]);
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1600)
    public static void residentsKeepTheirFootingAndSeatAboardAWalkingWalker(GameTestHelper helper) {
        var level = helper.getLevel();
        var pad = new BlockPos(1152, PAD_Y, 64);
        var walker = Walker.build(helper, pad);
        var colony = Colonies.mint(level.getServer());
        var walking = PersonBody.RESIDENT.get().create(level);
        var seated = PersonBody.RESIDENT.get().create(level);
        var writer = helper.spawn(EntityType.PIG, new BlockPos(0, 2, 0));
        Cleanup.on(helper, () -> {
            walking.discard();
            seated.discard();
            colony.raze();
        });
        Trip[] trip = new Trip[1];
        Set<WorldPos> goal = new HashSet<>();
        double[] lowest = {Double.MAX_VALUE, Double.MAX_VALUE};
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            var facing = level.getBlockState(walker.corePlot()[0]).getValue(WalkerCoreBlock.FACING);
            var seat = walker.corePlot()[0].relative(facing, 2);
            helper.assertTrue(level.getBlockState(seat).getBlock() instanceof SeatBlock, "the demo spider has a seat");
            seated.moveTo(WorldSpaces.world(level, WorldSpaces.at(level, seat)).orElseThrow());
            level.addFreshEntity(seated);
            SeatBlock.sitDown(level, seat, seated);
            helper.assertTrue(seated.getVehicle() instanceof SeatEntity, "a resident sits in the walker's seat");
            // From the back corner of the deck to the front corner on the other side, past the core and the seat.
            var core = walker.core();
            var right = facing.getClockWise();
            var from = core.at(core.cell().relative(right, 2).relative(facing, -2));
            var to = core.at(core.cell().relative(right, -2).relative(facing, 6));
            walking.moveTo(WorldSpaces.world(level, from).orElseThrow().add(0, .02, 0));
            walking.setOnGround(true);
            level.addFreshEntity(walking);
            var resident = Bodies.of(walking).orElseThrow();
            resident.locomotion().fit(walking);
            goal.add(to);
            var roaming = new Roaming(level);
            var route = roaming.answered(ways -> ways.journey(resident.resident(), from, goal),
                List.of(new Roamer(resident.routeKind(), resident.locomotion(), walking)),
                Passages.published(level, colony.id()), level.getGameTime());
            helper.assertTrue(route instanceof Faring.Yes, "a way across the deck: " + route);
            trip[0] = Trip.to(roaming::ways, resident.resident(), resident.locomotion(), walking, goal, 1,
                LaborRefusal.NO_WAY_THERE, Urgency.WALKING, TrafficFeedback.NONE).orElseThrow();
            trip[0].setOut(level, walking, goal);
        }).thenWaitUntil(() -> {
            var drive = Controls.signal(level, walker.controls(), WalkerDrive.PART).orElseThrow();
            helper.assertTrue(drive.write(level, writer, walker.target(new BlockPos(0, 0, 24)))
                == Signal.Write.WRITTEN, "the walker walks on");
            lowest[0] = Math.min(lowest[0], walking.getY());
            lowest[1] = Math.min(lowest[1], seated.getY());
            Going step = trip[0].step(level, walking, goal);
            helper.assertFalse(step instanceof Going.Failed, "the deck walk failed at " + trip[0].describe());
            helper.assertTrue(step instanceof Going.Arrived, "the resident still crosses the moving deck: "
                + step + " " + trip[0].describe() + " at " + WorldSpaces.at(walking) + " for " + goal);
        }).thenExecute(() -> {
            trip[0].release(level, walking);
            var moved = Walkers.position(walker.walkerCore(level)).orElseThrow().subtract(walker.start()[0])
                .horizontalDistance();
            LOGGER.info("A resident crossed the deck while the walker walked {} blocks, by tick {}", moved,
                helper.getTick());
            helper.assertTrue(moved > 3, "the walker walked while the resident crossed: " + moved);
            helper.assertTrue(WorldSpaces.at(walking).realm().equals(walker.core().realm()),
                "the walking resident stayed aboard");
        }).thenWaitUntil(() -> {
            var drive = Controls.signal(level, walker.controls(), WalkerDrive.PART).orElseThrow();
            var target = walker.target(new BlockPos(0, 0, 24));
            drive.write(level, writer, target);
            lowest[1] = Math.min(lowest[1], seated.getY());
            helper.assertTrue(read(level, walker.controls(), "position").equals(target), "the walker walks on");
        }).thenExecute(() -> {
            helper.assertTrue(seated.isAlive() && seated.getVehicle() instanceof SeatEntity,
                "the seated resident kept the seat the whole walk");
            helper.assertTrue(walking.isAlive() && lowest[0] > PAD_Y + 2 && lowest[1] > PAD_Y + 2,
                "no one fell off the deck: " + lowest[0] + ", " + lowest[1]);
        }).thenSucceed();
    }

    // The sandbox copies the hull, its legs and the pad under them; the copy walks there while the real walker stands.
    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 1200,
            batch = "assistant_probe_walker")
    public static void probeWalksACopyOfTheWalkerWhileTheRealOneStands(GameTestHelper helper) {
        var level = helper.getLevel();
        var pad = new BlockPos(1408, PAD_Y, 64);
        var walker = Walker.build(helper, pad);
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "probe-test"),
            ClientInformation.createDefault());
        @SuppressWarnings("unchecked")
        CompletableFuture<JsonObject>[] probe = new CompletableFuture[1];
        Vec3[] real = new Vec3[2];
        helper.startSequence().thenExecuteAfter(SETTLE, () -> {
            walker.settled(helper);
            var hull = walker.find(level).orElseThrow();
            real[0] = Walkers.position(walker.walkerCore(level)).orElseThrow();
            real[1] = new Vec3(hull.logicalPose().position().x(), hull.logicalPose().position().y(),
                hull.logicalPose().position().z());
            var ahead = walker.target(new BlockPos(0, 0, 10));
            var request = new Probe.Request(MechanismScan.structure(level, walker.hull()),
                new Probe.Input.Write(walker.controls(), WalkerDrive.PART, Optional.of(ahead)), Probe.MAX_TICKS);
            probe[0] = Sandbox.probe(player, request);
        }).thenWaitUntil(() -> helper.assertTrue(probe[0] != null && probe[0].isDone(), "the probe to finish"))
            .thenExecute(() -> {
                JsonObject data = probe[0].join();
                LOGGER.info("Walker probe: {}", data);
                helper.assertFalse(data.has("error"), "the probe runs: " + data);
                helper.assertTrue(data.getAsJsonObject("input").get("result").getAsString().equals("written"),
                    "the copy's pilot controls took the destination: " + data);
                var structures = data.getAsJsonArray("structures").asList().stream()
                    .map(JsonElement::getAsJsonObject).toList();
                helper.assertTrue(structures.size() > 1, "the hull came with its legs: " + structures);
                var copiedHull = structures.stream().filter(row -> row.get("id").getAsString()
                    .equals(walker.hull().toString())).findFirst().orElse(null);
                helper.assertTrue(copiedHull != null && !copiedHull.has("ended_tick"),
                    "the copied hull stayed whole: " + structures);
                var moved = copiedHull.getAsJsonArray("moved");
                helper.assertTrue(moved.get(2).getAsDouble() > 1,
                    "the copy walked south, towards its destination: " + copiedHull);
                helper.assertTrue(data.getAsJsonArray("summary").toString().contains("structure "
                    + walker.hull().toString().substring(0, 8) + " moved"), "the summary tells the walk: " + data);

                var core = walker.walkerCore(level);
                var now = Walkers.position(core).orElseThrow();
                var hull = walker.find(level).orElseThrow();
                var pose = new Vec3(hull.logicalPose().position().x(), hull.logicalPose().position().y(),
                    hull.logicalPose().position().z());
                helper.assertTrue(now.distanceTo(real[0]) < .25 && pose.distanceTo(real[1]) < .25
                    && core.isAssembled() && !core.tippingOver, "the real walker stood still: " + real[0] + " -> "
                    + now + ", hull " + real[1] + " -> " + pose);
                helper.assertTrue(corePos(hull).equals(core.getBlockPos()),
                    "the real walker is still known by its hull");
                helper.assertTrue(Walkers.core(level, walker.controls()).filter(found -> found == core).isPresent(),
                    "its pilot controls still drive the real walker");
                ServerLevel sandbox = Sandbox.level(level.getServer()).orElseThrow();
                var left = SubLevelContainer.getContainer(sandbox).getAllSubLevels();
                List<Entity> entities = new ArrayList<>();
                sandbox.getAllEntities().forEach(entities::add);
                helper.assertTrue(left.isEmpty() && entities.isEmpty() && !Sandbox.busy(),
                    "no copy is left in the sandbox: " + left + " " + entities);
            }).thenSucceed();
    }

    private static BlockPos corePos(SubLevel hull) {
        return WalkerTracker.coreOf(hull);
    }

    /** A demo spider standing on a stone pad, facing south. */
    public record Walker(ServerLevel level, BlockPos pad, Vec3[] start, UUID[] hullId, BlockPos[] corePlot) {

        public static Walker build(GameTestHelper helper, BlockPos pad) {
            return build(helper, pad, (level, deck) -> { });
        }

        /** As {@link #build(GameTestHelper, BlockPos)}, with more blocks put beside the deck (at its level) first. */
        public static Walker build(GameTestHelper helper, BlockPos pad,
                                   java.util.function.BiConsumer<ServerLevel, BlockPos> extra) {
            ServerLevel level = helper.getLevel();
            for (int cx = pad.getX() - 40 >> 4; cx <= pad.getX() + 40 >> 4; cx++) {
                for (int cz = pad.getZ() - 40 >> 4; cz <= pad.getZ() + 60 >> 4; cz++) {
                    int x = cx;
                    int z = cz;
                    if (level.setChunkForced(x, z, true)) {
                        Cleanup.on(helper, () -> level.setChunkForced(x, z, false));
                    }
                }
            }
            for (int x = -24; x < 24; x++) {
                for (int z = -20; z < 48; z++) {
                    level.setBlock(pad.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
                    for (int y = 1; y < 24; y++) {
                        level.setBlock(pad.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            var walker = new Walker(level, pad, new Vec3[1], new UUID[1], new BlockPos[1]);
            var core = DemoWalkers.place(level, pad.above(), Direction.SOUTH, DemoWalkers.Type.BUILT_SPIDER,
                pad.getY() + 1);
            // The demo spider's deck stands six blocks over the ground.
            extra.accept(level, pad.above(7));
            var built = WalkerAssembler.assemble(level, core);
            Cleanup.on(helper, () -> walker.remove(level));
            helper.assertTrue(level.getBlockState(pad.above()).isAir() || built != null, "the demo spider was built");
            return walker;
        }

        Optional<SubLevel> find(ServerLevel level) {
            var box = new BoundingBox3d(pad.getX() - 30, pad.getY() - 4, pad.getZ() - 30,
                pad.getX() + 30, pad.getY() + 40, pad.getZ() + 60);
            for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, box)) {
                if (WalkerTracker.coreOf(sub) != null || sub.getUniqueId().equals(hullId[0])) {
                    return Optional.of(sub);
                }
            }
            return Optional.empty();
        }

        public void settled(GameTestHelper helper) {
            ServerLevel level = helper.getLevel();
            SubLevel hull = find(level).orElse(null);
            helper.assertTrue(hull != null, "the demo spider stands on its own hull");
            BlockPos core = WalkerTracker.coreOf(hull);
            helper.assertTrue(core != null && level.getBlockEntity(core) instanceof WalkerCoreBlockEntity be
                && be.isAssembled(), "the walker core ticks on its hull");
            hullId[0] = hull.getUniqueId();
            corePlot[0] = core;
            start[0] = Walkers.position(walkerCore(level)).orElseThrow();
        }

        public UUID hull() {
            return hullId[0];
        }

        WalkerCoreBlockEntity walkerCore(ServerLevel level) {
            return (WalkerCoreBlockEntity) level.getBlockEntity(corePlot[0]);
        }

        WorldPos core() {
            return WorldSpaces.at(level, corePlot[0]);
        }

        public WorldPos controls() {
            var facing = level.getBlockState(corePlot[0]).getValue(WalkerCoreBlock.FACING);
            return WorldSpaces.at(level, corePlot[0].relative(facing, CONTROLS_AHEAD_OF_CORE));
        }

        /** A block on the pad, offset from where the walker's core stood when it settled. */
        Vec3 world(BlockPos offset) {
            return new Vec3(Math.floor(start[0].x) + offset.getX(), pad.getY() + 1 + offset.getY(),
                Math.floor(start[0].z) + offset.getZ());
        }

        Value target(BlockPos offset) {
            Vec3 at = world(offset);
            return Value.of(at.x, at.y, at.z);
        }

        void remove(ServerLevel level) {
            var container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }
            var core = hullId[0] == null ? null : find(level).map(WalkerTracker::coreOf)
                .filter(at -> level.getBlockEntity(at) instanceof WalkerCoreBlockEntity).orElse(null);
            if (core != null && level.getBlockEntity(core) instanceof WalkerCoreBlockEntity be) {
                be.legs.forEach(leg -> leg.built.segments.forEach(id -> {
                    var segment = container.getSubLevel(id);
                    if (segment != null) {
                        container.removeSubLevel(segment, SubLevelRemovalReason.REMOVED);
                    }
                }));
            }
            find(level).ifPresent(hull -> container.removeSubLevel(hull, SubLevelRemovalReason.REMOVED));
        }
    }

    private static Value read(ServerLevel level, WorldPos at, String probe) {
        return Readings.of(LetItWalkIntegration.id(probe), at).orElseThrow().read(level).orElseThrow();
    }

    private static List<Integer> xyz(BlockPos cell) {
        return List.of(cell.getX(), cell.getY(), cell.getZ());
    }
}
