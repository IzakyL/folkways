package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Lease;
import io.github.izakyl.folkways.core.facility.api.Rig;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.facility.api.Run;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.internal.Refinements;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FacilityGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "facility");

    // Signals the test adapter answers, by cell; it outranks Core's vanilla adapter.
    private static final Map<WorldPos, List<Signal>> EXTRA = new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FacilityGameTests.class);
        Controls.register(ResourceLocation.fromNamespaceAndPath("folkways_test", "facility_parts"), Controls.SPECIFIC,
            (level, pos) -> EXTRA.getOrDefault(pos, List.of()));
    }

    // Adapters' answers for one block are merged; only the same part is decided by priority.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void controlsAtOneBlockMergeByPart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lever = floorWithLever(helper);
        WorldPos at = WorldPos.of(level, lever);
        helper.assertTrue(Controls.signals(level, at).stream().map(Signal::part).toList().equals(List.of("lever")),
            "a lever alone is one signal");
        Signal crank = new Marker(at, "crank");
        Signal override = new Marker(at, "lever");
        EXTRA.put(at, List.of(crank, override));
        Cleanup.on(helper, () -> EXTRA.remove(at));
        List<Signal> found = Controls.signals(level, at);
        helper.assertTrue(found.size() == 2 && found.contains(crank) && found.contains(override),
            "parts from both adapters are kept, the higher priority wins the shared part: " + found);
        helper.assertTrue(Controls.resolve(level, new ControlRef(at, "crank")).equals(Optional.of(crank))
                && Controls.resolve(level, new ControlRef(at, "lever")).equals(Optional.of(override)),
            "a reference resolves its own part");
        EXTRA.put(at, List.of(crank));
        helper.assertTrue(Controls.resolve(level, new ControlRef(at, "lever")).filter(Marker.class::isInstance)
                .isEmpty() && Controls.signals(level, at).size() == 2,
            "a part the higher adapter leaves out falls to the lower one");
        helper.succeed();
    }

    private record Marker(WorldPos anchor, String part) implements Signal {

        @Override
        public SignalType type() {
            return SignalType.PULSE;
        }

        @Override
        public boolean valid(ServerLevel level) {
            return true;
        }

        @Override
        public Optional<Stances> stance(ServerLevel level) {
            return Optional.of(Stances.WHEREVER);
        }

        @Override
        public Write write(ServerLevel level, net.minecraft.world.entity.Mob writer, Value value) {
            return Write.WRITTEN;
        }
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void signalValuesAreCheckedAndAgreeWithinTheirGrid(GameTestHelper helper) {
        SignalType.Real wheel = new SignalType.Real(0, 90, 0.5);
        helper.assertTrue(wheel.check(Value.of(45.5)).isEmpty(), "a number on the grid is taken");
        helper.assertTrue(wheel.check(Value.of(45.25)).isPresent(), "a number off the grid is not");
        helper.assertTrue(wheel.check(Value.of(91)).isPresent(), "a number out of range is not");
        helper.assertTrue(wheel.check(Value.of(true)).isPresent(), "a switch value is not a number");
        helper.assertTrue(wheel.agrees(Value.of(45), Value.of(45.2)) && !wheel.agrees(Value.of(45), Value.of(45.3)),
            "a reading agrees within half a step");
        SignalType.Vector mount = new SignalType.Vector(List.of(new SignalType.Real(-180, 180, 0),
            new SignalType.Real(-10, 60, 0)));
        helper.assertTrue(mount.check(Value.of(30, 15)).isEmpty() && mount.check(Value.of(30)).isPresent()
            && mount.check(Value.of(30, 70)).isPresent(), "a vector has its length and each axis its range");
        helper.assertTrue(SignalType.PULSE.check(Value.PULSE).isEmpty()
            && SignalType.PULSE.agrees(Value.PULSE, Value.of(false)), "a pulse leaves nothing to disagree with");
        helper.assertTrue(SignalType.BOOL.agrees(Value.of(true), Value.of(true))
            && !SignalType.BOOL.agrees(Value.of(true), Value.of(false)), "a switch agrees only with itself");
        boolean thrown = false;
        try {
            new SignalType.Real(1, 0, 0);
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        helper.assertTrue(thrown, "a range runs upwards");
        CompoundTag data = new CompoundTag();
        data.putString("mode", "salvo");
        for (Value value : List.of(Value.PULSE, Value.of(true), Value.of(2.5), Value.of(1, 2, 3),
                new Value.Data(data))) {
            helper.assertTrue(Value.load(value.save()).equals(value), "a value is saved as it was: " + value);
        }
        for (Trigger trigger : List.of(new Trigger.Manual("open"), new Trigger.Standing("tend"),
                new Trigger.Demand("smelt"), new Trigger.Condition("close", Map.of("left", Value.of(true)), Optional.empty(),
                    10, 100, 1),
                new Trigger.Schedule("close", 13000))) {
            helper.assertTrue(Trigger.load(trigger.save()).equals(trigger), "a trigger is saved as it was: " + trigger);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void facilityDataIsSavedAsItWas(GameTestHelper helper) {
        WorldPos anchor = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        FacilityDefinition gate = facility(anchor,
            Map.of("left", new FacilityDefinition.Placed(new BlockPos(-2, 0, 2), "lever"),
                "ammo", new FacilityDefinition.Placed(new BlockPos(1, 0, 0), "folkways_core:item")),
            Map.of("open", new Routine.Use(Routines.WRITE_ALL, Map.of("left", Value.of(true)))),
            List.of(new Trigger.Schedule("open", 0)), Map.of("speed", Value.of(2.0)),
            Map.of("light", new FacilityDefinition.Probed(ResourceLocation.parse("folkways_core:powered"), new BlockPos(0, 1, 0))));
        helper.assertTrue(FacilityDefinition.load(gate.save()).equals(Optional.of(gate)), "a facility is saved as it was");
        helper.assertTrue(FacilityDefinition.fromData(gate.id(), gate.data()).equals(gate), "plain definition round trips");
        helper.succeed();
    }

    // The shape write_all plans: one write per signal not yet showing its target, all independent, each before the
    // run's own end, which waits on every switch and number but not on a pulse.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void writeAllPlansIndependentWritesBeforeOneEnd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldPos at = WorldPos.of(level, helper.absolutePos(BlockPos.ZERO));
        Map<String, Rig.Part> parts = new LinkedHashMap<>();
        parts.put("left", part(at, "left", SignalType.BOOL, Value.of(false)));
        parts.put("right", part(at.at(at.cell().east()), "right", SignalType.BOOL, Value.of(false)));
        parts.put("done", part(at.at(at.cell().west()), "done", SignalType.BOOL, Value.of(true)));
        parts.put("bell", part(at.at(at.cell().north()), "bell", SignalType.PULSE, Value.of(false)));
        Routine.Call call = new Routine.Call(UUID.randomUUID(), OWNER, UUID.randomUUID(), at, Map.of(),
            Map.of("left", Value.of(true), "right", Value.of(true), "done", Value.of(true), "bell", Value.PULSE),
            Map.of(), Optional.of(new Rig(parts)));
        Routine writeAll = Routines.of(Routines.WRITE_ALL).orElseThrow();
        helper.assertTrue(writeAll.check(call).isEmpty(), "the call is a valid write_all");
        Refinement.Change change = writeAll.expand(call).orElseThrow();
        Grown graph = change.replacement();
        List<Node> writes = graph.nodes().stream().filter(node -> !node.id().equals(call.id())).toList();
        helper.assertTrue(graph.nodes().stream().filter(node -> node.id().equals(call.id())).count() == 1,
            "the run ends in a node with its own id");
        helper.assertTrue(writes.size() == 3, "a signal already showing its target is not written again: " + writes);
        Set<Before> expected = new java.util.HashSet<>();
        writes.forEach(write -> expected.add(new Before(write.id(), call.id())));
        helper.assertTrue(Set.copyOf(graph.links()).equals(expected), "writes are independent and come before the end");
        helper.assertTrue(graph.workerGroups().isEmpty() && change.workerGroups().isEmpty(),
            "the plan decides who writes what");
        Routine.Call wrong = new Routine.Call(UUID.randomUUID(), OWNER, UUID.randomUUID(), at, Map.of(),
            Map.of("left", Value.of(3.0)), Map.of(), Optional.of(new Rig(parts)));
        helper.assertTrue(writeAll.check(wrong).isPresent() && writeAll.expand(wrong).isEmpty(),
            "a value the signal does not take is turned down");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aLeaseIsExclusiveAndEndsWithItsResident(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lever = floorWithLever(helper);
        Control control = Controls.resolve(level, new ControlRef(WorldPos.of(level, lever), "lever")).orElseThrow();
        ResidentEntity first = body(helper, new BlockPos(1, 2, 1));
        ResidentEntity second = body(helper, new BlockPos(1, 2, 3));
        Cleanup.on(helper, () -> {
            first.discard();
            second.discard();
        });
        Worker one = worker(first);
        Worker two = worker(second);
        Lease lease = Lease.take(control, one).orElseThrow();
        helper.assertTrue(Lease.take(control, two).isEmpty(), "a control has one writer at a time");
        helper.assertTrue(Lease.take(control, one).filter(again -> again == lease).isPresent(),
            "the holder asking again keeps its lease");
        Lease taken = Lease.take(control, two, true).orElseThrow();
        helper.assertTrue(!lease.open() && taken.open(), "preempting ends the other resident's lease");
        second.discard();
        helper.assertTrue(!taken.open() && Lease.held(control).isEmpty(),
            "a resident leaving the level gives up its lease");
        Lease again = Lease.take(control, one).orElseThrow();
        Lease.release(first.getUUID());
        helper.assertTrue(!again.open(), "releasing a resident ends its leases");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void aResidentThrowsALeverToRunWriteAll(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lever = floorWithLever(helper);
        ResidentEntity body = body(helper, new BlockPos(1, 2, 2));
        Colony colony = Colonies.mint(level.getServer());
        colony.hold(PersonBody.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        LaborFixture labor = new LaborFixture(colony, level);
        Cleanup.on(helper, () -> {
            labor.close();
            body.discard();
            colony.raze();
        });
        helper.onEachTick(labor::tick);
        WorldPos anchor = WorldPos.of(level, helper.absolutePos(BlockPos.ZERO));
        FacilityDefinition facility = facility(anchor,
            Map.of("lever", new FacilityDefinition.Placed(lever.subtract(anchor.cell()), "lever")),
            Map.of("open", new Routine.Use(Routines.WRITE_ALL, Map.of("lever", Value.of(true)))),
            List.of(new Trigger.Manual("open")), Map.of(), Map.of());
        helper.assertTrue(facility.readings(level).get("lever").read(level).equals(Optional.of(Value.of(false))),
            "the lever's feedback reads it off before the run");
        Run run = Routines.run(level, facility, "open", Map.of(), OWNER);
        helper.assertTrue(Refinements.expand(Grown.of(run)).filter(grown -> grown.nodes().size() == 2
                && grown.nodes().stream().anyMatch(node -> node.id().equals(run.id()))).isPresent(),
            "the plan grows a run into one write and the run's end");
        List<Ending> ended = new ArrayList<>();
        Routines.submit(colony, level, facility, "open", Map.of(), OWNER, ended::add);
        helper.succeedWhen(() -> {
            helper.assertTrue(ended.equals(List.of(Ending.DONE)), "the run ends done: " + ended);
            helper.assertTrue(level.getBlockState(lever).getValue(LeverBlock.POWERED), "the lever is on");
            helper.assertTrue(Lease.held(Controls.resolve(level, facility.ref("lever")).orElseThrow()).isEmpty(),
                "the writer let the lever go");
        });
    }

    // A seat stance is the engine's arrival: the writer walks over, sits down, writes seated, and stands up after.
    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void aWriterSitsAtASeatStanceAndStandsUpAfter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        floorWithLever(helper);
        Entity boat = EntityType.BOAT.create(level);
        boat.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 2, 3))));
        level.addFreshEntity(boat);
        WorldPos anchor = WorldPos.of(level, helper.absolutePos(BlockPos.ZERO));
        WorldPos at = WorldPos.of(level, helper.absolutePos(new BlockPos(4, 2, 0)));
        List<Boolean> seated = new ArrayList<>();
        Signal helm = new Helm(at, boat.getUUID(), seated);
        EXTRA.put(at, List.of(helm));
        ResidentEntity body = body(helper, new BlockPos(1, 2, 1));
        Colony colony = Colonies.mint(level.getServer());
        colony.hold(PersonBody.ID, Claimed.Entity.of(body)).orElseThrow();
        body.joinColony(colony.id());
        colony.entered(body);
        LaborFixture labor = new LaborFixture(colony, level);
        Cleanup.on(helper, () -> {
            labor.close();
            EXTRA.remove(at);
            body.discard();
            boat.discard();
            colony.raze();
        });
        helper.onEachTick(labor::tick);
        FacilityDefinition facility = facility(anchor,
            Map.of("helm", new FacilityDefinition.Placed(at.cell().subtract(anchor.cell()), "helm")),
            Map.of("steer", new Routine.Use(Routines.WRITE_ALL, Map.of("helm", Value.of(true)))),
            List.of(new Trigger.Manual("steer")), Map.of(), Map.of());
        List<Ending> ended = new ArrayList<>();
        Routines.submit(colony, level, facility, "steer", Map.of(), OWNER, ended::add);
        helper.succeedWhen(() -> {
            helper.assertTrue(ended.equals(List.of(Ending.DONE)), "the run ends done: " + ended);
            helper.assertTrue(seated.equals(List.of(true)), "the write is made from the seat: " + seated);
            helper.assertTrue(!body.isPassenger() && !Seating.holds(body), "the writer stands up once done");
            helper.assertTrue(Lease.held(helm).isEmpty(), "the writer let the helm go");
        });
    }

    private record Helm(WorldPos anchor, UUID boat, List<Boolean> seated) implements Signal {

        @Override
        public String part() {
            return "helm";
        }

        @Override
        public SignalType type() {
            return SignalType.BOOL;
        }

        @Override
        public boolean valid(ServerLevel level) {
            return level.getEntity(boat) != null;
        }

        @Override
        public Optional<Stances> stance(ServerLevel level) {
            return Optional.of(new Stances.Seat(boat, 0));
        }

        @Override
        public Write write(ServerLevel level, net.minecraft.world.entity.Mob writer, Value value) {
            seated.add(writer.getVehicle() != null && writer.getVehicle().getUUID().equals(boat));
            return Write.WRITTEN;
        }
    }

    private static FacilityDefinition facility(WorldPos anchor, Map<String, FacilityDefinition.Placed> controls,
                                 Map<String, Routine.Use> routines, List<Trigger> triggers,
                                 Map<String, Value> parameters, Map<String, FacilityDefinition.Probed> readings) {
        Map<String, FacilityDefinition.Switch> switches = new LinkedHashMap<>();
        triggers.forEach(trigger -> switches.put(trigger.routine(), new FacilityDefinition.Switch(trigger, true)));
        return new FacilityDefinition(UUID.randomUUID(), anchor, controls, routines, parameters, readings,
            "Gate", new FacilityExtent(anchor.realm(), anchor.cell().offset(-2, 0, 0), anchor.cell().offset(8, 4, 8)),
            new Card(List.of()), List.of(), switches, Optional.empty(), List.of());
    }

    private static Rig.Part part(WorldPos at, String name, SignalType type, Value showing) {
        return new Rig.Part(name, new ControlRef(at, "lever"), new WorkSite.AtBlock(at), Stances.WHEREVER,
            Control.Cost.INSTANT, new Rig.Signalled(type),
            type instanceof SignalType.Pulse ? Optional.empty() : Optional.of(showing));
    }

    private static BlockPos floorWithLever(GameTestHelper helper) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        BlockPos lever = helper.absolutePos(new BlockPos(6, 2, 2));
        helper.getLevel().setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState()
            .setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.POWERED, false));
        return lever;
    }

    private static ResidentEntity body(GameTestHelper helper, BlockPos at) {
        ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(at)));
        helper.getLevel().addFreshEntity(body);
        return body;
    }

    private static Worker worker(ResidentEntity body) {
        return Proxies.of(Worker.class, (proxy, method, args) -> switch (method.getName()) {
            case "resident" -> body.resident();
            case "body" -> body;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError(method.getName());
        });
    }
}
