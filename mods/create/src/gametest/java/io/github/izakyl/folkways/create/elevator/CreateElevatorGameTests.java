package io.github.izakyl.folkways.create.elevator;

import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.Footing;
import java.util.UUID;
import net.minecraft.world.entity.Mob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CreateElevatorGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CreateElevatorGameTests.class);
    }

    private static BlockState create(String name) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", name)).defaultBlockState();
    }

    private record Fixture(BlockPos deck, ElevatorPulleyBlockEntity pulley,
                           CreateElevatorNetwork adapter, ElevatorRef ref, Mob body) {
    }

    private static Fixture fixture(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos deck = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos pulleyPos = deck.above(9);
        // The contact is two blocks above passenger feet, to catch treating contact Y as platform Y.
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                level.setBlockAndUpdate(deck.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            }
        }
        level.setBlockAndUpdate(deck.offset(1, 1, 1), create("white_seat"));
        level.setBlockAndUpdate(deck.offset(2, 1, 1), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(deck.offset(2, 2, 1), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(deck.offset(2, 3, 1), create("redstone_contact")
            .setValue(DirectionalBlock.FACING, Direction.EAST));
        level.addFreshEntity(new SuperGlueEntity(level, SuperGlueEntity.span(deck, deck.offset(2, 3, 2))));
        for (int rise : new int[] {0, 5}) {
            level.setBlockAndUpdate(deck.offset(3, 3 + rise, 1), create("elevator_contact")
                .setValue(DirectionalBlock.FACING, Direction.WEST));
            // A small landing on the north side, with no platform under the elevator shaft.
            level.setBlockAndUpdate(deck.offset(1, rise, -1), Blocks.STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(pulleyPos, create("elevator_pulley")
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(pulleyPos.west(), create("creative_motor")
            .setValue(DirectionalBlock.FACING, Direction.EAST));
        var pulley = (ElevatorPulleyBlockEntity) level.getBlockEntity(pulleyPos);
        helper.runAfterDelay(10, pulley::clicked);
        var adapter = new CreateElevatorNetwork();
        var ref = new ElevatorRef(level.dimension(), pulleyPos);
        var body = PersonBody.RESIDENT.get().create(level);
        body.setNoAi(true);
        body.moveTo(deck.getX() + 1.5, deck.getY() + 1, deck.getZ() - 0.5);
        level.addFreshEntity(body);
        return new Fixture(deck, pulley, adapter, ref, body);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void anAssembledElevatorKeepsItsPlatformsAndCarriesASeatedResident(GameTestHelper helper) {
        var level = helper.getLevel();
        var fixture = fixture(helper);
        var deck = fixture.deck();
        var pulley = fixture.pulley();
        var adapter = fixture.adapter();
        var ref = fixture.ref();
        var body = fixture.body();
        int[] stage = {0};
        helper.succeedWhen(() -> {
            var view = adapter.inspect(level.getServer(), ref).orElseThrow(() ->
                new net.minecraft.gametest.framework.GameTestAssertException("elevator must assemble: speed=" + pulley.getSpeed() + ", running=" + pulley.running
                    + ", attached=" + pulley.getAttachedContraption() + ", error=" + pulley.getLastAssemblyException()));
            helper.assertTrue(view.floors().size() == 2, "both fixed landings remain discoverable: " + view.floors());
            helper.assertTrue(view.floor(deck.getY() + 3).orElseThrow().platform().cells().stream()
                .allMatch(cell -> cell.cell().getY() == deck.getY() + 1), "contact height is not feet height");
            if (stage[0] == 0) {
                helper.assertTrue(view.stoppedAt().orElse(Integer.MIN_VALUE) == deck.getY() + 3,
                    "waiting for lower floor");
                helper.assertTrue(adapter.board(level.getServer(), ref, 0, body), "resident boards real Create seat");
                helper.assertTrue(adapter.target(level.getServer(), ref, deck.getY() + 8), "call upper floor");
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                helper.assertTrue(view.stoppedAt().orElse(Integer.MIN_VALUE) == deck.getY() + 8,
                    "waiting for upper floor");
                helper.assertTrue(adapter.seatOf(level.getServer(), ref, body).orElse(-1) == 0, "resident stays seated");
                helper.assertTrue(adapter.target(level.getServer(), ref, deck.getY() + 3), "call lower floor");
                stage[0] = 2;
            }
            helper.assertTrue(view.stoppedAt().orElse(Integer.MIN_VALUE) == deck.getY() + 3,
                "waiting for return to lower floor");
            adapter.disembark(level.getServer(), ref, 0, body);
            helper.assertFalse(body.isPassenger(), "resident disembarks after round trip");
            body.discard();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aResidentCallsBoardsAndLeavesARealElevatorThroughTheController(GameTestHelper helper) {
        var level = helper.getLevel();
        var fixture = fixture(helper);
        var body = fixture.body();
        var deck = fixture.deck();
        // The car starts below. This resident waits upstairs and must first call it to the pickup floor.
        body.moveTo(deck.getX() + 1.5, deck.getY() + 6, deck.getZ() - 0.5);
        UUID owner = UUID.randomUUID();
        ElevatorConveyance[] ride = {null};
        boolean[] sawPassenger = {false};
        helper.succeedWhen(() -> {
            var view = fixture.adapter().inspect(level.getServer(), fixture.ref()).orElseThrow(() ->
                new net.minecraft.gametest.framework.GameTestAssertException("waiting for real elevator assembly"));
            helper.assertTrue(view.floors().size() == 2, "both floors are available to routing");
            var from = view.floor(deck.getY() + 8).orElseThrow().platform();
            var to = view.floor(deck.getY() + 3).orElseThrow().platform();
            if (ride[0] == null) {
                helper.assertTrue(ElevatorDispatch.claim(level.getServer(), owner, fixture.ref()), "claim real elevator");
                ride[0] = new ElevatorConveyance(new ElevatorFare(owner, fixture.ref(), deck.getY() + 8,
                    deck.getY() + 3, 100), from, to);
            }
            Going result = ride[0].step(level, body, to.cells());
            sawPassenger[0] |= body.isPassenger();
            helper.assertTrue(result instanceof Going.Arrived, "waiting for controller pickup and delivery: " + result);
            helper.assertTrue(sawPassenger[0], "resident actually rode a Create contraption seat");
            helper.assertFalse(body.isPassenger(), "resident automatically leaves the seat at destination");
            helper.assertTrue(to.cells().stream().anyMatch(cell -> cell.cell().equals(body.blockPosition()))
                && Footing.withFeetAt(level, body.blockPosition()), "resident lands on the actual lower platform");
            ride[0].release(level, body);
            ElevatorDispatch.release(level.getServer(), owner, fixture.ref());
            body.discard();
        });
    }

}
