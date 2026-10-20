package io.github.izakyl.folkways.create.elevator;

import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Mob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateElevatorGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(CreateElevatorGameTests.class); }
    private static BlockState create(String name) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", name)).defaultBlockState();
    }
    private record Fixture(BlockPos deck, ElevatorPulleyBlockEntity pulley,
                           CreateElevatorNetwork adapter, ElevatorRef ref, Mob body, Runnable cleanup) { }

    private static Fixture fixture(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos deck = helper.absolutePos(new BlockPos(4, 2, 5));
        Set<Long> forced = new HashSet<>();
        Set<Long> had = Set.copyOf(level.getForcedChunks());
        for (int x = (deck.getX() - 4) >> 4; x <= (deck.getX() + 8) >> 4; x++) {
            for (int z = (deck.getZ() - 5) >> 4; z <= (deck.getZ() + 8) >> 4; z++) {
                long chunk = ChunkPos.asLong(x, z);
                if (!had.contains(chunk) && level.setChunkForced(x, z, true)) forced.add(chunk);
            }
        }
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
            level.setBlockAndUpdate(deck.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        }
        // There is deliberately no seat block anywhere in this real assembled elevator.
        level.setBlockAndUpdate(deck.offset(4, 1, 2), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(deck.offset(4, 2, 2), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(deck.offset(4, 3, 2), create("redstone_contact").setValue(DirectionalBlock.FACING, Direction.EAST));
        level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(deck, deck.offset(4, 3, 4))));
        for (int rise : new int[] {0, 5}) {
            level.setBlockAndUpdate(deck.offset(5, 3 + rise, 2), create("elevator_contact").setValue(DirectionalBlock.FACING, Direction.WEST));
            for (int x = 0; x < 5; x++) for (int z = -3; z < 0; z++) {
                level.setBlockAndUpdate(deck.offset(x, rise, z), Blocks.STONE.defaultBlockState());
            }
        }
        // Keep the pulley below the empty GameTest template ceiling at relative y=13.
        BlockPos pulleyPos = deck.offset(2, 10, 2);
        level.setBlockAndUpdate(pulleyPos, create("elevator_pulley").setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(pulleyPos.west(), create("creative_motor").setValue(DirectionalBlock.FACING, Direction.EAST));
        var pulley = (ElevatorPulleyBlockEntity) level.getBlockEntity(pulleyPos);
        helper.runAfterDelay(10, () -> { if (!pulley.running) pulley.clicked(); });
        var adapter = new CreateElevatorNetwork();
        var ref = new ElevatorRef(level.dimension(), pulleyPos);
        var body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(deck.getX() + 2.5, deck.getY() + 1, deck.getZ() - 1.5);
        level.addFreshEntity(body);
        Runnable cleanup = () -> {
            body.discard();
            forced.forEach(chunk -> level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false));
        };
        helper.runAtTickTime(1195, cleanup);
        return new Fixture(deck, pulley, adapter, ref, body, cleanup);
    }

    private static String assembly(Fixture f) {
        var pulley = f.pulley;
        var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity) pulley.getLevel().getBlockEntity(pulley.getBlockPos().west());
        return "waiting for real elevator assembly: running=" + pulley.running + " speed=" + pulley.getSpeed()
            + " theoretical=" + pulley.getTheoreticalSpeed() + " motor=" + (motor == null ? "missing" : motor.getTheoreticalSpeed())
            + " error=" + (pulley.getLastAssemblyException() == null ? "none" : pulley.getLastAssemblyException().component.getString() + " at " + pulley.getLastAssemblyException().getPosition()) + " attached=" + pulley.getAttachedContraption()
            + " ticking=" + ((net.minecraft.server.level.ServerLevel) pulley.getLevel()).isPositionEntityTicking(pulley.getBlockPos()) + " bodyTicks=" + f.body.tickCount;
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void anUnseatedResidentWalksIntoTheCabinAndRidesBothDirectionsWithoutPositionJumps(GameTestHelper helper) {
        var level = helper.getLevel();
        var f = fixture(helper);
        int[] stage = {0};
        Vec3[] last = {null};
        boolean[] carried = {false};
        helper.succeedWhen(() -> {
            var view = f.adapter.inspect(level.getServer(), f.ref).orElseThrow(() ->
                new net.minecraft.gametest.framework.GameTestAssertException(assembly(f)));
            helper.assertValueEqual(view.floors().size(), 2, "two real landings");
            helper.assertTrue(!view.places().isEmpty(), "standing capacity does not depend on seats");
            helper.assertValueEqual(f.pulley.getAttachedContraption().getContraption().getSeats().size(), 0, "fixture has no seats");
            helper.assertFalse(f.body.isPassenger(), "walking and native collision never mount the resident");
            if (last[0] != null) helper.assertTrue(last[0].distanceToSqr(f.body.position()) < 2.25D,
                "no one-tick boarding, riding or disembarking position jump");
            last[0] = f.body.position();
            if (stage[0] == 0) {
                helper.assertTrue(view.stoppedAt().orElse(-999) == f.deck.getY() + 3, "waiting at lower floor");
                helper.assertTrue(f.adapter.board(level.getServer(), f.ref, 0, f.body), "walking to reserved interior standing place");
                helper.assertTrue(f.body.walkDist > 0.1F, "the resident used its real walking movement");
                helper.assertTrue(f.adapter.occupant(level.getServer(), f.ref, 0).filter(f.body.getUUID()::equals).isPresent(), "physical standing body occupies its place");
                helper.assertFalse(f.adapter.inspect(level.getServer(), f.ref).orElseThrow().freePlaces().contains(0), "physical occupant reduces free capacity");
                helper.assertTrue(f.adapter.target(level.getServer(), f.ref, f.deck.getY() + 8), "call upper floor");
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                carried[0] |= f.body.getY() > f.deck.getY() + 3;
                helper.assertTrue(f.adapter.placeOf(level.getServer(), f.ref, f.body).isPresent(), "ordinary resident remains physically supported while ascending");
                helper.assertTrue(view.stoppedAt().orElse(-999) == f.deck.getY() + 8, "waiting for upper arrival");
                stage[0] = 2;
            }
            if (stage[0] == 2) {
                helper.assertTrue(f.adapter.disembark(level.getServer(), f.ref, 0, f.body), "walk out onto real upper landing");
                helper.assertTrue(Footing.withFeetAt(level, f.body.blockPosition()), "upper exit has a real floor");
                stage[0] = 3;
            }
            if (stage[0] == 3) {
                helper.assertTrue(f.adapter.board(level.getServer(), f.ref, 0, f.body), "walk back into cabin");
                helper.assertTrue(f.adapter.target(level.getServer(), f.ref, f.deck.getY() + 3), "call lower floor");
                stage[0] = 4;
            }
            helper.assertTrue(stage[0] == 4 && view.stoppedAt().orElse(-999) == f.deck.getY() + 3, "waiting for native descending support");
            helper.assertTrue(f.adapter.disembark(level.getServer(), f.ref, 0, f.body), "walk out onto real lower landing");
            helper.assertTrue(carried[0], "native collision carried the ordinary standing resident upstairs");
            helper.assertTrue(f.adapter.placeOf(level.getServer(), f.ref, f.body).isEmpty(), "walking off releases physical occupancy");
            helper.assertTrue(Footing.withFeetAt(level, f.body.blockPosition()), "lower exit has a real floor");
            f.cleanup.run();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void twoUnreservedMobsShareTheCabinAndRestoreItsPhysicalCapacityAfterWalkingOut(GameTestHelper helper) {
        var level = helper.getLevel();
        var f = fixture(helper);
        var other = PersonBody.RESIDENT.get().create(level);
        other.moveTo(f.deck.getX() + 3.5, f.deck.getY() + 1, f.deck.getZ() - 2.5);
        level.addFreshEntity(other);
        helper.runAtTickTime(1195, other::discard);
        int[] stage = {0};
        int[] capacity = {-1};
        boolean[] out = {false, false};
        boolean[] straddled = {false};
        Vec3[] previous = {null, null};
        Mob[] bodies = {f.body, other};
        helper.succeedWhen(() -> {
            var view = f.adapter.inspect(level.getServer(), f.ref).orElseThrow(() ->
                new net.minecraft.gametest.framework.GameTestAssertException(assembly(f)));
            helper.assertTrue(view.places().size() >= 2, "real cabin has distinct standing places");
            if (stage[0] <= 1 && !f.adapter.readyToDepart(level.getServer(), f.ref)) straddled[0] = true;
            helper.assertValueEqual(f.pulley.getAttachedContraption().getContraption().getSeats().size(), 0,
                "neither ordinary Mob has a seat");
            for (int i = 0; i < bodies.length; i++) {
                helper.assertFalse(bodies[i].isPassenger(), "ordinary Mob never mounts a contraption");
                if (previous[i] != null) helper.assertTrue(previous[i].distanceToSqr(bodies[i].position()) < 2.25D,
                    "each Mob walks and rides continuously without a position jump");
                previous[i] = bodies[i].position();
            }
            if (stage[0] == 0) {
                if (capacity[0] < 0) capacity[0] = view.places().size();
                helper.assertTrue(f.adapter.board(level.getServer(), f.ref, 0, f.body),
                    "first external Mob walks to standing place 0");
                helper.assertValueEqual(f.adapter.inspect(level.getServer(), f.ref).orElseThrow().freePlaces().size(),
                    capacity[0] - 1, "unreserved external Mob consumes physical capacity");
                helper.assertTrue(f.adapter.occupant(level.getServer(), f.ref, 0).filter(f.body.getUUID()::equals).isPresent(),
                    "occupancy comes from the standing body without a controller reservation");
                helper.assertFalse(f.adapter.board(level.getServer(), f.ref, 0, other),
                    "second Mob cannot take the physically occupied standing place");
                helper.assertTrue(f.adapter.placeOf(level.getServer(), f.ref, other).isEmpty(),
                    "rejected second Mob remains outside");
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                helper.assertTrue(f.adapter.board(level.getServer(), f.ref, 1, other),
                    "second Mob walks around first Mob to a distinct interior place");
                helper.assertTrue(f.adapter.occupant(level.getServer(), f.ref, 0).filter(f.body.getUUID()::equals).isPresent()
                    && f.adapter.occupant(level.getServer(), f.ref, 1).filter(other.getUUID()::equals).isPresent(),
                    "two physical bodies retain different standing places");
                helper.assertValueEqual(f.adapter.inspect(level.getServer(), f.ref).orElseThrow().freePlaces().size(),
                    capacity[0] - 2, "two external Mobs consume two places");
                helper.assertTrue(straddled[0], "an unreserved Mob crossing the entrance prevents departure");
                helper.assertTrue(f.adapter.readyToDepart(level.getServer(), f.ref), "both bodies fully inside allow departure");
                helper.assertTrue(f.adapter.target(level.getServer(), f.ref, f.deck.getY() + 8), "call upper floor with both Mobs");
                stage[0] = 2;
            }
            if (stage[0] == 2 || stage[0] == 5) {
                for (Mob body : bodies) helper.assertTrue(f.adapter.placeOf(level.getServer(), f.ref, body).isPresent(),
                    "both unmounted Mobs remain supported by the moving native cabin");
                int destination = f.deck.getY() + (stage[0] == 2 ? 8 : 3);
                helper.assertTrue(view.stoppedAt().orElse(-999) == destination, "waiting for two Mob native arrival");
                stage[0]++;
            }
            if (stage[0] == 3 || stage[0] == 6) {
                for (int i = 0; i < bodies.length; i++) {
                    if (!out[i]) out[i] = f.adapter.disembark(level.getServer(), f.ref, i, bodies[i]);
                }
                helper.assertTrue(out[0] && out[1], "both Mobs walk out without blocking each other");
                for (Mob body : bodies) {
                    helper.assertTrue(f.adapter.placeOf(level.getServer(), f.ref, body).isEmpty()
                        && Footing.withFeetAt(level, body.blockPosition()), "each Mob fully leaves onto the actual landing");
                }
                helper.assertValueEqual(f.adapter.inspect(level.getServer(), f.ref).orElseThrow().freePlaces().size(),
                    capacity[0], "walking out restores the complete physical capacity");
                helper.assertTrue(f.adapter.readyToDepart(level.getServer(), f.ref), "both bodies fully outside allow departure");
                if (stage[0] == 6) {
                    other.discard();
                    f.cleanup.run();
                    return;
                }
                out[0] = out[1] = false;
                stage[0] = 4;
            }
            if (stage[0] == 4) {
                boolean first = f.adapter.board(level.getServer(), f.ref, 0, f.body);
                boolean second = f.adapter.board(level.getServer(), f.ref, 1, other);
                helper.assertTrue(first && second, "both Mobs walk back to their different standing places");
                helper.assertTrue(f.adapter.target(level.getServer(), f.ref, f.deck.getY() + 3), "call lower floor with both Mobs");
                stage[0] = 5;
            }
            helper.assertTrue(false, "waiting for two ordinary Mobs to complete their real round trip, stage=" + stage[0]);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void aSpecifiedFartherExitWinsOverTheNearestDisconnectedLanding(GameTestHelper helper) {
        var level = helper.getLevel();
        var f = fixture(helper);
        // This independent southern landing is farther from place 0 than the northern landing.
        // It is outside the glue box and has no route around the cabin to the northern platform.
        for (int z = 5; z <= 7; z++) {
            level.setBlockAndUpdate(f.deck.offset(1, 0, z), Blocks.STONE.defaultBlockState());
        }
        WorldPos desired = WorldPos.of(level, f.deck.offset(1, 1, 5));
        Set<WorldPos> exits = Set.of(desired);
        boolean[] boarded = {false};
        Vec3[] last = {null};
        helper.succeedWhen(() -> {
            var view = f.adapter.inspect(level.getServer(), f.ref).orElseThrow(() ->
                new net.minecraft.gametest.framework.GameTestAssertException(assembly(f)));
            var platform = view.floor(f.deck.getY() + 3).orElseThrow().platform().cells();
            helper.assertTrue(platform.contains(desired), "specified southern exit is a real landing");
            for (var floor : view.floors()) {
                for (WorldPos waiting : floor.waiting().cells()) {
                    BlockPos at = waiting.cell();
                    int dx = Math.max(f.deck.getX() - at.getX(), Math.max(0, at.getX() - f.deck.getX() - 4));
                    int dz = Math.max(f.deck.getZ() - at.getZ(), Math.max(0, at.getZ() - f.deck.getZ() - 4));
                    helper.assertTrue(dx + dz >= 3 && at.getY() == floor.y() - 2
                        && Footing.withFeetAt(level, at),
                        "each landing's waiting floor is real and clear of its projected cabin, regardless of car height");
                }
            }
            helper.assertFalse(f.body.isPassenger(), "exit selection uses real walking without mounting");
            if (last[0] != null) helper.assertTrue(last[0].distanceToSqr(f.body.position()) < 2.25D,
                "walking to the selected exit never changes position discontinuously");
            last[0] = f.body.position();
            if (!boarded[0]) {
                helper.assertTrue(f.adapter.board(level.getServer(), f.ref, 0, f.body), "walk to standing place 0");
                double desiredDistance = Vec3.atBottomCenterOf(desired.cell()).distanceToSqr(f.body.position());
                helper.assertTrue(platform.stream().filter(at -> at.cell().getZ() < f.deck.getZ())
                    .anyMatch(at -> Vec3.atBottomCenterOf(at.cell()).distanceToSqr(f.body.position()) < desiredDistance),
                    "a disconnected northern exit really is nearer to this passenger");
                boarded[0] = true;
            }
            helper.assertTrue(f.adapter.disembark(level.getServer(), f.ref, 0, f.body, exits),
                "walk past the nearer exit toward the specified farther landing");
            helper.assertTrue(f.body.blockPosition().equals(desired.cell()),
                "completion requires the requested exit rather than any nearby platform");
            helper.assertTrue(f.adapter.placeOf(level.getServer(), f.ref, f.body).isEmpty()
                && Footing.withFeetAt(level, f.body.blockPosition()), "the entire body leaves onto a real floor");
            f.cleanup.run();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void aResidentCallsBoardsAndLeavesARealElevatorThroughTheController(GameTestHelper helper) {
        var level = helper.getLevel();
        var f = fixture(helper);
        UUID owner = UUID.randomUUID();
        ElevatorConveyance[] ride = {null};
        boolean[] standing = {false};
        helper.succeedWhen(() -> {
            var view = f.adapter.inspect(level.getServer(), f.ref).orElseThrow(() ->
                new net.minecraft.gametest.framework.GameTestAssertException(assembly(f)));
            var from = view.floor(f.deck.getY() + 3).orElseThrow().platform();
            var to = view.floor(f.deck.getY() + 8).orElseThrow().platform();
            if (ride[0] == null) {
                helper.assertTrue(ElevatorDispatch.claim(level.getServer(), owner, f.ref), "claim real elevator");
                ride[0] = new ElevatorConveyance(new ElevatorFare(owner, f.ref, f.deck.getY() + 3,
                    f.deck.getY() + 8, 100), from, to);
            }
            Going result = ride[0].step(level, f.body, to.cells());
            standing[0] |= f.adapter.placeOf(level.getServer(), f.ref, f.body).isPresent();
            helper.assertFalse(f.body.isPassenger(), "controller never mounts a walking rider");
            helper.assertTrue(result instanceof Going.Arrived, "waiting for genuine controller pickup and delivery: " + result);
            helper.assertTrue(standing[0], "resident really stood on moving cabin");
            helper.assertTrue(to.cells().stream().anyMatch(cell -> cell.cell().equals(f.body.blockPosition()))
                && Footing.withFeetAt(level, f.body.blockPosition()), "resident walks onto actual upper platform");
            ride[0].release(level, f.body);
            ElevatorDispatch.release(level.getServer(), owner, f.ref);
            f.cleanup.run();
        });
    }
}
