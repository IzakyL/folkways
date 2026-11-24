package io.github.izakyl.folkways.vanilla.facility;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.shell.structure.SablePassage;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.travel.api.Passages;
import io.github.izakyl.folkways.core.travel.api.TrafficFeedback;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Trip;
import io.github.izakyl.folkways.core.travel.internal.Urgency;
import io.github.izakyl.folkways.core.travel.internal.graph.Roamer;
import io.github.izakyl.folkways.core.travel.internal.graph.Roaming;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class SableGateGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SableGateGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void residentCrossesAnOpenFacilityGateAboardSable(GameTestHelper helper) {
        var level = helper.getLevel();
        // Sable's physics uses float positions. GameTest templates can spawn millions of blocks from the origin.
        BlockPos door = new BlockPos(40, 100, 40);
        int chunkX = door.getX() >> 4;
        int chunkZ = door.getZ() >> 4;
        if (level.setChunkForced(chunkX, chunkZ, true)) {
            Cleanup.on(helper, () -> level.setChunkForced(chunkX, chunkZ, false));
        }
        List<BlockPos> blocks = new ArrayList<>();
        for (BlockPos floor : BlockPos.betweenClosed(door.offset(-3, -1, -2), door.offset(3, -1, 2))) {
            level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            blocks.add(floor.immutable());
        }
        for (BlockPos wall : BlockPos.betweenClosed(door.offset(0, 0, -2), door.offset(0, 2, 2))) {
            if (wall.getZ() != door.getZ()) {
                level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
                blocks.add(wall.immutable());
            }
        }
        var lower = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        level.setBlock(door, lower, 2);
        level.setBlockAndUpdate(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        level.setBlockAndUpdate(door.west(), Blocks.LEVER.defaultBlockState()
            .setValue(LeverBlock.FACE, AttachFace.FLOOR));
        blocks.addAll(List.of(door, door.above(), door.west()));

        var ship = SubLevelAssemblyHelper.assembleBlocks(level, door.below(), blocks, BoundingBox3i.from(blocks));
        var physics = SubLevelPhysicsSystem.require(level);
        physics.updatePose(ship);
        physics.getPipeline().resetVelocity(ship);
        var center = ship.getPlot().getCenterBlock();
        var localAnchor = new Vector3d(center.getX() + .5, center.getY() + .5, center.getZ() + .5);
        var fixed = physics.getPipeline().addConstraint(ship, null, new FixedConstraintConfiguration(localAnchor,
            ship.logicalPose().transformPosition(new Vector3d(localAnchor)),
            new Quaterniond(ship.logicalPose().orientation()).conjugate()));

        var colony = Colonies.mint(level.getServer());
        var person = PersonBody.RESIDENT.get().create(level);
        Cleanup.on(helper, () -> {
            person.discard();
            colony.raze();
            var container = SubLevelContainer.getContainer(level);
            if (container.getSubLevel(ship.getUniqueId()) == ship) {
                fixed.remove();
                container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
            }
        });
        var realm = new Realm.Frame(ship.getUniqueId());
        var space = WorldSpaces.frame(level, ship.getUniqueId()).orElseThrow();
        var gate = new WorldPos(realm, BlockPos.containing(space.toLocal(Vec3.atCenterOf(door))));
        var lever = gate.at(gate.cell().west());
        var start = gate.at(gate.cell().west(3));
        var goal = gate.at(gate.cell().east(3));
        person.setPos(WorldSpaces.world(level, start).orElseThrow().add(0, .02, 0));
        person.setOnGround(true);
        level.addFreshEntity(person);
        var resident = Bodies.of(person).orElseThrow();
        resident.locomotion().fit(person);

        var extent = FacilityExtent.structure(level, ship.getUniqueId()).orElseThrow();
        var facilities = DefinedFacilities.of(colony).orElseThrow();
        var preview = facilities.preview("Deck gate", Optional.of(Map.of(
            "name", "Deck gate", "extent", DefinitionCodec.extent(extent), "anchor", pos(lever.cell()),
            "controls", Map.of("lever", Map.of("part", "lever", "offset", List.of(0, 0, 0))),
            "projections", List.of(Map.of("gate", Map.of("cells", List.of(pos(gate.cell()), pos(gate.above().cell())),
                "axes", List.of("x"), "open_when", Map.of("lever", true)))))));
        var problems = facilities.apply(preview);
        helper.assertTrue(problems.isEmpty(), "deck gate installed: " + problems);
        helper.assertFalse(level.getBlockState(gate.block(level)).getValue(DoorBlock.OPEN), "gate starts closed");

        var roaming = new Roaming(level);
        var goals = Set.of(goal);
        var roamers = List.of(new Roamer(resident.routeKind(), resident.locomotion(), person));
        var route = roaming.answered(ways -> ways.journey(resident.resident(), start, goals),
            roamers, Passages.published(level, colony.id()), level.getGameTime());
        helper.assertTrue(route instanceof Faring.No, "the closed gate blocks the deck route");

        // The shore is lower than the deck and has an air gap, so the walking passage cannot reach it.
        WorldPos shore = WorldPos.of(level.dimension(), door.east(5).below(2));
        level.setBlockAndUpdate(shore.cell().below(), Blocks.STONE.defaultBlockState());
        Cleanup.on(helper, () -> level.removeBlock(shore.cell().below(), false));
        colony.hold(SablePassage.ID, new Claimed.Block(goal.at(goal.cell().below()))).orElseThrow();
        var passage = Passages.of(SablePassage.ID).orElseThrow();
        var crossings = passage.hopsIn(colony);

        BlockPos control = lever.block(level);
        level.setBlockAndUpdate(control, level.getBlockState(control).setValue(LeverBlock.POWERED, true));
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(gate.block(level)).getValue(DoorBlock.OPEN), "the door opened");
            var hops = Passages.published(level, colony.id());
            helper.assertValueEqual(hops.size(), 2, "the open deck gate publishes two directions");
            roaming.advance(roamers, hops, level.getGameTime());
            var openRoute = roaming.answered(ways -> ways.journey(resident.resident(), start, goals),
                roamers, hops, level.getGameTime());
            helper.assertTrue(openRoute instanceof Faring.Yes yes
                && yes.by().legs().stream().anyMatch(leg -> leg.hop().isPresent()), "route uses the deck gate hop: " + openRoute);
            var trip = Trip.to(roaming::ways, resident.resident(), resident.locomotion(), person, goals, 1,
                LaborRefusal.NO_WAY_THERE, Urgency.WALKING, TrafficFeedback.NONE)
                .orElseThrow();
            trip.setOut(level, person, goals);
            helper.startSequence().thenWaitUntil(() -> {
                Going result = trip.step(level, person, goals);
                helper.assertFalse(result instanceof Going.Failed, "trip failed at " + trip.describe()
                    + " from " + space.toLocal(person.position()));
                helper.assertTrue(result instanceof Going.Arrived, "resident is still walking");
            }).thenExecute(() -> {
                trip.release(level, person);
                helper.assertTrue(space.toLocal(person.position()).x > gate.cell().getX() + 2,
                    "resident reached the far side in the deck's local coordinates");
                helper.assertTrue(level.getBlockState(gate.block(level)).getValue(DoorBlock.OPEN), "door remains open");
                helper.assertTrue(level.getBlockState(control).getValue(LeverBlock.POWERED), "lever remains powered");

                var jump = crossings.stream()
                    .filter(hop -> hop.boarding().cells().contains(goal) && hop.landing().cells().contains(shore))
                    .findFirst().orElseThrow();
                helper.assertTrue(crossings.stream().anyMatch(hop -> hop.boarding().equals(jump.landing())
                    && hop.landing().equals(jump.boarding())), "the gap has a crossing in both directions");
                person.setPos(WorldSpaces.world(level, goal).orElseThrow().add(0, .02, 0));
                roaming.advance(roamers, crossings, level.getGameTime());
                var across = roaming.answered(ways -> ways.journey(resident.resident(), goal, Set.of(shore)),
                    roamers, crossings, level.getGameTime());
                helper.assertTrue(across instanceof Faring.Yes yes
                    && yes.by().legs().stream().anyMatch(leg -> leg.hop().isPresent()),
                    "the travel graph routes across the gap: " + across);
                var landing = passage.aboard(jump);
                helper.assertTrue(landing.step(level, person, Set.of(shore)) instanceof Going.Arrived,
                    "the resident lands in one step");
                helper.assertTrue(WorldSpaces.at(person).equals(shore), "the resident is now on the shore");
            }).thenSucceed();
        });
    }

    private static List<Integer> pos(BlockPos cell) {
        return List.of(cell.getX(), cell.getY(), cell.getZ());
    }
}
