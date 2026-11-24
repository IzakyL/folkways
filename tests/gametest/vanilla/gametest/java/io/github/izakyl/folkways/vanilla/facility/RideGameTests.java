package io.github.izakyl.folkways.vanilla.facility;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.travel.api.Passages;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class RideGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.parse("folkways_test:ride");
    private static final String NAME = "Test ride";
    private static final Map<WorldPos, Shuttle> SHUTTLES = new HashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RideGameTests.class);
        Controls.register(OWNER, Controls.SPECIFIC, (level, pos) -> {
            Shuttle shuttle = SHUTTLES.get(pos);
            return shuttle == null ? List.of() : List.of(shuttle, shuttle.ready());
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void rideCarriesResidentToWorkAndReceivesArguments(GameTestHelper helper) {
        var test = new Fixture(helper);
        test.install("""
            def step(state, readings, args):
                if args["from"] != "ground" or args["to"] != "top" or args["riders"] != ["%s"]:
                    return None, [fail_permanent("unexpected riders or stops")]
                if state == None:
                    return True, [write("cabin", True), await("cabin", True, 100)]
                return state, [done]
            """.formatted(test.gate.body.mob().getUUID()));
        var published = Passages.published(test.gate.level, test.gate.colony.id());
        helper.assertTrue(published.size() == 2 && published.stream().allMatch(hop ->
            hop.fare().arriveBy(10).orElseThrow() == 210), "both directed hops have the declared fare");
        helper.assertTrue(test.gate.route() instanceof Faring.Yes yes
            && yes.by().legs().stream().anyMatch(leg -> leg.hop().isPresent()), "the planner selects the ride");
        test.submitWork(false);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(test.powered() && test.ending instanceof Ending.Done,
                "the resident has yet to finish work: " + test.describe());
        }).thenExecute(() -> {
            helper.assertTrue(test.shuttle.moved && test.recent("done"),
                "the script received the ride arguments and ran: " + test.describe());
            helper.assertTrue(test.sawRun, "the ride appeared in the facility's active runs");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void rideOnlyPublishesWhileAvailable(GameTestHelper helper) {
        var test = new Fixture(helper);
        test.shuttle.available = false;
        test.install("""
            def step(state, readings, args):
                return state, [done]
            """);
        helper.assertTrue(Passages.published(test.gate.level, test.gate.colony.id()).isEmpty(),
            "an unavailable ride publishes no hops");
        helper.assertTrue(test.gate.route() instanceof Faring.No, "the unavailable ride cannot be routed");
        test.shuttle.available = true;
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(Passages.published(test.gate.level, test.gate.colony.id()).size() == 2,
                "a matching reading restores both hops");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void failedRideLetsTheResidentReplan(GameTestHelper helper) {
        var test = new Fixture(helper);
        test.install("""
            def step(state, readings, args):
                return None, [fail_permanent("cabin stalled")]
            """);
        test.submitWork(true);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(test.recent("failed"), "waiting for ride failure: " + test.describe()))
            .thenExecute(() -> {
                test.shuttle.available = false;
                test.gate.powerLever();
            }).thenWaitUntil(() -> {
                helper.assertTrue(test.powered() && test.ending instanceof Ending.Done,
                    "the resident replans and walks through the opened door: " + test.describe());
            }).thenExecute(() -> {
                helper.assertTrue(test.refused && !test.shuttle.moved,
                    "the work finished after the failed ride without using the cabin");
            }).thenSucceed();
    }

    private static final class Fixture {
        final FacilityGateFixture gate;
        final DefinedFacilities facilities;
        final BlockPos work;
        final Shuttle shuttle;
        final LaborFixture labor;
        Ending ending;
        boolean refused;
        boolean sawRun;

        Fixture(GameTestHelper helper) {
            gate = new FacilityGateFixture(helper);
            BlockPos door = gate.door.cell();
            for (BlockPos cell : BlockPos.betweenClosed(door.offset(-4, -1, -3), door.offset(4, 2, 3))) {
                if (Math.abs(cell.getX() - door.getX()) == 4 || Math.abs(cell.getZ() - door.getZ()) == 3) {
                    gate.level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
                }
            }
            var person = (ResidentEntity) gate.body.mob();
            gate.colony.hold(PersonBody.ID, Claimed.Entity.of(person)).orElseThrow();
            person.joinColony(gate.colony.id());
            gate.colony.entered(person);
            labor = new LaborFixture(gate.colony, gate.level);
            helper.onEachTick(labor::tick);
            facilities = DefinedFacilities.of(gate.colony).orElseThrow();
            helper.onEachTick(() ->
                sawRun |= facilities.activeRuns(NAME).containsValue(ScriptedFacilityFixture.ROUTINE));
            BlockPos from = gate.door.cell().west(3);
            BlockPos to = gate.door.cell().east(2);
            work = gate.door.cell().east(3).north();
            gate.level.setBlockAndUpdate(work,
                Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
            shuttle = new Shuttle(gate.door.at(from), to, person);
            SHUTTLES.put(shuttle.anchor(), shuttle);
            Cleanup.on(helper, () -> {
                labor.close();
                SHUTTLES.remove(shuttle.anchor());
                gate.close();
            });
        }

        void install(String source) {
            BlockPos from = shuttle.anchor().cell();
            BlockPos to = shuttle.landing;
            var data = ScriptedFacilityFixture.definition(gate.level, NAME, from, to,
                Map.of("cabin", Map.of("part", "cabin"), "ready", Map.of("part", "ready")),
                Map.of("source", source), Map.of());
            // Both signals share one block, with "ready" reading whether the service is available.
            var ride = Map.of("stops", Map.of("ground", stop(from), "top", stop(to)),
                "routine", ScriptedFacilityFixture.ROUTINE, "ticks", 200, "capacity", 2,
                "available_when", Map.of("ready", true));
            var projections = List.of(Map.of("ride", ride));
            data.put("projections", projections);
            var problems = facilities.apply(facilities.preview(NAME, Optional.of(data)));
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Ride installation failed: " + problems);
            }
            if (!projections.equals(facilities.read(NAME).orElseThrow().data().get("projections"))) {
                throw new IllegalStateException("Ride definition did not round-trip");
            }
        }

        void submitWork(boolean retry) {
            WorldPos site = gate.door.at(work);
            var stances = new Stances.Cells(Set.of(site.at(work.south())));
            var spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(site), stances,
                Workload.Once.of(0)).done();
            Node job = new Node() {
                @Override
                public NodeSpec spec() {
                    return spec;
                }

                @Override
                public Outcome commit(ServerLevel level, Worker who) {
                    level.setBlockAndUpdate(work, level.getBlockState(work).setValue(LeverBlock.POWERED, true));
                    return Outcome.done();
                }

                @Override
                public Recourse failing(ServerLevel level, RefusalKind why) {
                    if (retry && !refused && why == LaborRefusal.NO_WAY_THERE) {
                        refused = true;
                        return Recourse.waitFor(20);
                    }
                    return Recourse.FAIL;
                }
            };
            gate.colony.submit(OWNER, gate.level.dimension(), Grown.of(job), (id, result) -> ending = result);
        }

        boolean powered() {
            return gate.level.getBlockState(work).getValue(LeverBlock.POWERED);
        }

        boolean recent(String ending) {
            return facilities.recent(NAME).filter(result -> result.ending().equals(ending)).isPresent();
        }

        String describe() {
            return gate.body.mob().position() + " work=" + ending + " runs=" + facilities.activeRuns(NAME)
                + " recent=" + facilities.recent(NAME) + " unassigned=" + labor.diagnosis().unassigned();
        }

        private static Map<String, Object> stop(BlockPos cell) {
            return Map.of("cells", List.of(ScriptedFacilityFixture.pos(cell)));
        }
    }

    private static final class Shuttle implements Signal {
        private final WorldPos anchor;
        private final BlockPos landing;
        private final Mob rider;
        boolean available = true;
        boolean moved;

        Shuttle(WorldPos anchor, BlockPos landing, Mob rider) {
            this.anchor = anchor;
            this.landing = landing;
            this.rider = rider;
        }

        @Override
        public WorldPos anchor() {
            return anchor;
        }

        @Override
        public String part() {
            return "cabin";
        }

        @Override
        public SignalType type() {
            return SignalType.BOOL;
        }

        @Override
        public Cost cost() {
            return Cost.REMOTE;
        }

        @Override
        public Optional<Stances> stance(ServerLevel level) {
            return Optional.of(Stances.WHEREVER);
        }

        @Override
        public Optional<Reading> feedback() {
            return Optional.of(level -> Optional.of(Value.of(moved)));
        }

        @Override
        public Write write(ServerLevel level, Mob writer, Value value) {
            rider.setPos(Vec3.atBottomCenterOf(landing));
            moved = true;
            return Write.WRITTEN;
        }

        Signal ready() {
            return new Signal() {
                @Override
                public WorldPos anchor() {
                    return anchor;
                }

                @Override
                public String part() {
                    return "ready";
                }

                @Override
                public SignalType type() {
                    return SignalType.BOOL;
                }

                @Override
                public Optional<Reading> feedback() {
                    return Optional.of(level -> Optional.of(Value.of(available)));
                }

                @Override
                public Write write(ServerLevel level, Mob writer, Value value) {
                    return Write.REFUSED;
                }
            };
        }
    }
}
