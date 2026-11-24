package io.github.izakyl.folkways.core.shell.create.control;

import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.shell.create.actuator.ComposedActuatorGameTests;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class ControlHubGameTests {
    private static final String NAME = "Test control hub";

    private ControlHubGameTests() {
    }

    @GameTest(template = "empty")
    public static void connectingAndBreakingRestoresReceiver(GameTestHelper helper) {
        var site = new Site(helper);
        helper.startSequence().thenWaitUntil(site::assertReceiverReady).thenExecute(() -> {
            Typewriter hub = site.connect();
            helper.assertTrue(hub.points().equals(List.of(site.channel)), "the placed hub binds the terminal");
            site.fixture.level.destroyBlock(site.hub, false);
        }).thenWaitUntil(site::assertReceiverReady).thenExecute(() -> {
            var receiver = site.fixture.level.getBlockState(site.channel);
            helper.assertTrue(receiver.getBlock() instanceof RedstoneLinkBlock && receiver.getValue(RedstoneLinkBlock.RECEIVER)
                && receiver.getValue(RedstoneLinkBlock.FACING) == Direction.UP, "restore the receiver and its facing");
            helper.assertTrue(Links.eligible(site.fixture.level.getBlockEntity(site.channel)),
                "restore an empty-frequency receiver");
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void unstaffedOutputIsSafe(GameTestHelper helper) {
        var site = new Site(helper);
        helper.startSequence().thenWaitUntil(site::assertReceiverReady).thenExecute(() -> {
            site.connect();
            Signal signal = site.signal();
            var level = site.fixture.level;
            helper.assertTrue(signal.write(level, site.fixture.body.mob(), Value.of(12)) == Signal.Write.REFUSED,
                "a resident away from the seat cannot energize a terminal");
            site.assertOutput(0);
            level.setBlockAndUpdate(site.channel.north(), BuiltInRegistries.BLOCK.get(Terminal.CHAIN_GEARSHIFT).defaultBlockState());
            site.assertOutput(15);
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void seatedResidentWritesAtExactTicks(GameTestHelper helper) {
        timed(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void sequenceRequestsVacantHub(GameTestHelper helper) {
        timed(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void ordinaryWriteRequestsVacantHub(GameTestHelper helper) {
        var site = new Site(helper);
        var labor = site.startLabor();
        helper.onEachTick(labor::tick);
        var service = DefinedFacilities.of(site.fixture.colony).orElseThrow();
        helper.startSequence().thenWaitUntil(site::assertReceiverReady).thenExecute(() -> {
            site.connect();
            site.fixture.level.setBlockAndUpdate(site.channel.north(),
                BuiltInRegistries.BLOCK.get(Terminal.CHAIN_GEARSHIFT).defaultBlockState());
            site.install(service, Map.of("use", "folkways_core:write_all", "with", Map.of("channel", 8)));
            site.assertOutput(15);
            helper.assertTrue(service.run(NAME, "run", Map.of()).isEmpty(), "ordinary write accepted");
        }).thenWaitUntil(() -> {
            helper.assertTrue(service.recent(NAME).filter(result -> result.ending().equals("done")).isPresent(),
                "wait for the write and its closing agreement");
            site.assertOutput(7);
            helper.assertTrue(site.staffed(), "retain the post through the closing agreement");
        }).thenWaitUntil(() -> helper.assertTrue(!site.staffed(), "release the post when the run ends"))
            .thenExecute(() -> site.assertOutput(15)).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void staffedHubPositionsANativeGantry(GameTestHelper helper) {
        var site = new Site(helper);
        var level = site.fixture.level;
        var labor = site.startLabor();
        var service = DefinedFacilities.of(site.fixture.colony).orElseThrow();
        var shaft = site.channel.offset(4, 2, 5);
        var clutch = shaft.east(8);
        var outlet = clutch.south(2).above();
        var y = shaft.above(4);
        var z = shaft.north(2).above(4);
        var hub = new Typewriter[1];
        helper.onEachTick(labor::tick);
        Runnable[] release = {() -> { }};
        Cleanup.on(helper, () -> release[0].run());
        helper.startSequence().thenWaitUntil(site::assertReceiverReady).thenExecute(() -> {
            hub[0] = site.connect();
            site.install(service, Map.of("use", "folkways_core:write_all", "with", Map.of("channel", 15)));
            release[0] = service.posts().request(site.signal().requiredPost(level).orElseThrow());
        }).thenWaitUntil(() -> helper.assertTrue(site.staffed(), "resident takes the hub seat"))
            .thenExecute(() -> {
                level.setBlockAndUpdate(outlet.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(outlet, ControlHub.TERMINAL.get().defaultBlockState());
                hub[0].claim(outlet);
                level.setBlockAndUpdate(clutch.south(), Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                    .setValue(RedstoneWallTorchBlock.FACING, Direction.NORTH));
                ComposedActuatorGameTests.gantry(level, shaft, Direction.EAST, null);
                var pulley = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:rope_pulley")).defaultBlockState();
                level.setBlockAndUpdate(y, pulley);
                level.setBlockAndUpdate(z, pulley);
                var response = Map.of("channel", "channel", "stop", 0, "rates",
                    List.of(Map.of("value", 15, "velocity", 0.03125, "start_ticks", 2, "stop_ticks", 3)));
                var settings = Map.of("frame", Map.of("origin", cells(shaft), "axes",
                    Map.of("x", List.of(1, 0, 0), "y", List.of(0, -1, 0), "z", List.of(0, 0, 1))),
                    "hold", List.of("y", "z"), "response", Map.of("x", response, "y", response, "z", response),
                    "tolerance", 0.25);
                var data = Map.of("name", NAME, "anchor", cells(site.channel),
                    "extent", DefinitionCodec.extent(new FacilityExtent(site.fixture.door.realm(),
                        site.channel.west(), outlet.east(2).south().above(4))),
                    "controls", Map.of("channel", Map.of("part", HubPosts.PART, "offset", cells(outlet.subtract(site.channel))),
                        "x", Map.of("part", "motion", "offset", cells(shaft.subtract(site.channel))),
                        "y", Map.of("part", "motion", "offset", cells(y.subtract(site.channel))),
                        "z", Map.of("part", "motion", "offset", cells(z.subtract(site.channel)))),
                    "routines", Map.of("run", Map.of("source", ScriptedFacilityFixture.POSITION_SOURCE, "with", settings)),
                    "triggers", Map.of("run", Map.of("kind", "manual", "routine", "run")));
                var preview = service.preview(NAME, Optional.of(data));
                helper.assertTrue(preview.problems().isEmpty(), "valid hub gantry: " + preview.problems());
                helper.assertTrue(service.apply(preview).isEmpty(), "install the hub gantry");
            }).thenIdle(8).thenExecute(() -> {
                helper.assertTrue(level.getBlockState(clutch).getValue(BlockStateProperties.POWERED),
                    "the rest circuit powers the clutch before movement: terminal=" + level.getSignal(outlet, Direction.UP)
                        + ", powered=" + level.hasNeighborSignal(clutch) + ", staffed=" + site.staffed());
                var target = shaft.east(2);
                var failures = service.run(NAME, "run", Map.of("pos", Value.of(target.getX(), target.getY(), target.getZ())));
                helper.assertTrue(failures.isEmpty(), "start hub positioning: " + failures);
            }).thenWaitUntil(() -> helper.assertTrue(service.recent(NAME).filter(result ->
                result.ending().equals("done")).isPresent(), "hub positioning: " + service.recent(NAME)
                + ", position=" + ComposedActuatorGameTests.sensor(level, shaft).position()
                + ", level=" + level.getSignal(outlet, Direction.UP) + ", clutch=" + level.getBlockState(clutch)
                + ", shaft=" + level.getBlockState(shaft) + ", staffed=" + site.staffed()))
            .thenExecute(() -> {
                helper.assertTrue(Math.abs(ComposedActuatorGameTests.sensor(level, shaft).position() - 2) <= 0.25,
                    "native gantry reaches the picked column");
                helper.assertTrue(level.getSignal(outlet, Direction.UP) == 0, "the hub restores its resting level");
            }).thenSucceed();
    }

    private static List<Integer> cells(BlockPos pos) {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    private static void timed(GameTestHelper helper, boolean staffFirst) {
        var site = new Site(helper);
        var labor = site.startLabor();
        var service = DefinedFacilities.of(site.fixture.colony).orElseThrow();
        var changes = new ArrayList<Long>();
        var values = new ArrayList<Integer>();
        long[] submitted = {-1};
        Runnable[] release = {() -> { }};
        helper.onEachTick(() -> {
            labor.tick();
            int strength = site.strength();
            if (submitted[0] >= 0 && strength != (values.isEmpty() ? 0 : values.getLast())) {
                if (strength != 0) {
                    helper.assertTrue(site.staffed(), "every energized output needs a seated resident");
                }
                values.add(strength);
                changes.add(site.fixture.level.getGameTime());
            }
        });
        var sequence = helper.startSequence().thenWaitUntil(site::assertReceiverReady).thenExecute(() -> {
            site.connect();
            site.install(service, Map.of("use", "folkways_core:write_sequence",
                "with", Map.of("steps", List.of(step(0, 4), step(7, 12), step(20, 0)))));
            if (staffFirst) {
                var key = site.signal().requiredPost(site.fixture.level).orElseThrow();
                release[0] = service.posts().request(key);
            }
        });
        if (staffFirst) {
            sequence.thenWaitUntil(() -> helper.assertTrue(site.staffed(), "resident takes the requested seat"));
        }
        sequence.thenExecute(() -> {
            if (!staffFirst) {
                helper.assertTrue(!site.staffed(), "the hub starts vacant");
            }
            submitted[0] = labor.census().submitted();
            var problems = service.run(NAME, "run", Map.of());
            helper.assertTrue(problems.isEmpty(), "sequence accepted: " + problems);
        }).thenWaitUntil(() -> helper.assertTrue(values.size() >= 3 && service.recent(NAME)
            .filter(result -> result.ending().equals("done")).isPresent(), "waiting for the sequence to finish"))
            .thenExecute(() -> {
                helper.assertTrue(values.equals(List.of(4, 12, 0)), "unexpected output transitions: " + values);
                helper.assertTrue(changes.get(1) - changes.get(0) == 7 && changes.get(2) - changes.get(0) == 20,
                    "expected writes at ticks 0, 7 and 20: " + changes);
                helper.assertTrue(labor.census().submitted() - submitted[0] == (staffFirst ? 0 : 1),
                    "only the requested duty may submit resident work");
                if (staffFirst) {
                    var level = site.fixture.level;
                    helper.assertTrue(site.signal().write(level, site.fixture.body.mob(), Value.of(9))
                        == Signal.Write.WRITTEN, "a seated resident can energize the terminal");
                    site.assertOutput(9);
                }
                release[0].run();
            }).thenWaitUntil(() -> helper.assertTrue(!site.staffed(), "the resident leaves after the request ends"))
            .thenExecute(() -> site.assertOutput(0)).thenSucceed();
    }

    private static final class Site {
        private final GameTestHelper helper;
        private final FacilityGateFixture fixture;
        private final BlockPos hub;
        private final BlockPos channel;

        Site(GameTestHelper helper) {
            this.helper = helper;
            fixture = new FacilityGateFixture(helper);
            Cleanup.on(helper, fixture::close);
            channel = fixture.lever.at().cell();
            hub = channel.west();
            fixture.level.setBlockAndUpdate(hub.south(), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:red_seat")).defaultBlockState());
            fixture.level.setBlockAndUpdate(channel, BuiltInRegistries.BLOCK.get(Terminal.RECEIVER).defaultBlockState()
                .setValue(RedstoneLinkBlock.FACING, Direction.UP).setValue(RedstoneLinkBlock.RECEIVER, true));
            var receiver = (RedstoneLinkBlockEntity) fixture.level.getBlockEntity(channel);
            receiver.readClient(new CompoundTag(), fixture.level.registryAccess());
        }

        void assertReceiverReady() {
            var level = fixture.level;
            helper.assertTrue(Links.eligible(level.getBlockEntity(channel)),
                "waiting for Create to initialize the empty-frequency receiver at " + channel + ": "
                    + level.getBlockState(channel));
        }

        Typewriter connect() {
            var level = fixture.level;
            var item = (TypewriterItem) ControlHub.TYPEWRITER.get().asItem();
            var player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setYRot(180);
            var stack = new ItemStack(item);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            helper.assertTrue(item.onItemUseFirst(stack, new UseOnContext(player, InteractionHand.MAIN_HAND,
                hit(channel))).consumesAction(), "use the held typewriter on the receiver");
            helper.assertTrue(level.getBlockEntity(channel) instanceof Terminal, "receiver becomes a terminal");
            var place = new UseOnContext(player, InteractionHand.MAIN_HAND, hit(hub.below()));
            helper.assertTrue(item.place(new BlockPlaceContext(place)).consumesAction(), "place the typewriter");
            var writer = (Typewriter) level.getBlockEntity(hub);
            helper.assertTrue(writer != null && writer.seat().equals(hub.south()), "marked face points at the seat");
            return writer;
        }

        LaborFixture startLabor() {
            var resident = (ResidentEntity) fixture.body.mob();
            fixture.colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
            resident.joinColony(fixture.colony.id());
            fixture.colony.entered(resident);
            var labor = new LaborFixture(fixture.colony, fixture.level);
            Cleanup.on(helper, labor::close);
            return labor;
        }

        void install(DefinedFacilities service, Map<String, Object> routine) {
            var extent = new FacilityExtent(fixture.door.realm(), hub, channel.south());
            var data = Map.of("name", NAME, "extent", DefinitionCodec.extent(extent),
                "anchor", List.of(channel.getX(), channel.getY(), channel.getZ()),
                "controls", Map.of("channel", Map.of("part", HubPosts.PART, "offset", List.of(0, 0, 0))),
                "routines", Map.of("run", routine),
                "triggers", Map.of("run", Map.of("kind", "manual", "routine", "run")));
            var preview = service.preview(NAME, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "valid hub facility: " + preview.problems());
            helper.assertTrue(service.apply(preview).isEmpty(), "install the hub facility");
        }

        boolean staffed() {
            return fixture.level.getBlockEntity(channel) instanceof Terminal terminal
                && terminal.staffedBy(fixture.body.mob());
        }

        Signal signal() {
            return Controls.signal(fixture.level, WorldPos.of(fixture.level, channel), HubPosts.PART).orElseThrow();
        }

        int strength() {
            return fixture.level.getSignal(channel, Direction.UP);
        }

        void assertOutput(int expected) {
            helper.assertTrue(strength() == expected, "expected redstone strength " + expected + ", got " + strength());
            helper.assertTrue(signal().feedback().orElseThrow().read(fixture.level).equals(Optional.of(Value.of(expected))),
                "feedback shows the redstone strength");
        }
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
    }

    private static Map<String, Object> step(int tick, int level) {
        return Map.of("control", "channel", "atTick", tick, "value", level);
    }
}
