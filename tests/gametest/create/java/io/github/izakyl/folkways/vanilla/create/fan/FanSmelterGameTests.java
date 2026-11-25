package io.github.izakyl.folkways.vanilla.create.fan;

import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import io.github.izakyl.folkways.core.base.task.ColonyLog;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.facility.api.mechanism.Mechanisms;
import io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.work.support.Staged;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.orders.Orders;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * A Create encased fan blowing through lava onto a depot, defined as a processing workshop: an order for iron ingots is
 * met by a resident who starts the fan through its clutch lever, loads raw iron onto the depot, waits for the stream to
 * blast it and brings the ingots back. With the fan's drive gone the workshop offers nothing and the order waits.
 */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class FanSmelterGameTests {

    private static final String NAME = "Fan smelter";
    private static final int ORDERED = 2;
    private static final ResourceLocation PROCESSING = ResourceLocation.parse("folkways_core:create/air_processing");
    private static final ResourceLocation UNPROCESSED = ResourceLocation.parse("folkways_core:create/air_unprocessed");

    // The routine the guide teaches: start the fan, load the depot, wait out the stream, take the ingots and stop it.
    static final String SMELT = """
        def recipes(input):
            if input.get("heat") != "create:blasting" and input.get("clutch") != True:
                return []
            return [{"id": "smelter:raw_iron", "inputs": [{"item": "minecraft:raw_iron", "count": 1}],
                     "outputs": [{"item": "minecraft:iron_ingot", "count": 1}], "ticks": 200, "scale": 16}]

        def recover(state, failure, input, args):
            return "stopped", [collect("depot"), write("clutch", True)]

        def step(state, input, args):
            if input["_failure"] != None:
                return state, [fail(input["_failure"])]
            if state == None:
                actions = [collect("depot")] if input.get("depot.count", 0) > 0 else []
                return "started", actions + [write("clutch", False),
                                             await("heat", "create:blasting", 200, on_fail="recover")]
            recipe = args["recipe"]
            if state == "started":
                return "loaded", [deliver("depot", recipe["inputs"]),
                                  await("raw", 0, 600, on_fail="recover")]
            if state == "loaded":
                return "collected", [collect("depot", recipe["outputs"], on_fail="recover"),
                                     write("clutch", True)]
            if state == "stopped":
                return state, [fail("The fan's stream stopped before the items were done")]
            return state, [done]
        """;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FanSmelterGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void fanStreamShowsItsProcessingAndDepot(GameTestHelper helper) {
        var rig = new Rig(helper, false);
        WorldPos at = WorldPos.of(rig.level, rig.fan);
        helper.startSequence().thenExecuteAfter(20, () -> {
            helper.assertTrue(rig.processing().equals("none"), "the clutch holds the fan still: " + rig.processing());
            var hints = Readings.hints(rig.level, at);
            helper.assertTrue(hints.stream().anyMatch(hint -> hint.probe().equals(PROCESSING)
                    && hint.at().cell().equals(rig.depot)), "the still fan names the depot in its line: " + hints);
            helper.assertTrue(hints.stream().anyMatch(hint -> hint.probe().equals(UNPROCESSED)
                    && hint.config().equals(Map.of("type", new Value.Text("create:blasting")))),
                "the lava in its line says what the stream would do: " + hints);
            var still = rig.air();
            helper.assertTrue(Boolean.FALSE.equals(still.get("running"))
                    && String.valueOf(still.get("heat_sources")).contains("minecraft:lava")
                    && String.valueOf(still.get("holders_in_line")).contains(rig.cell(rig.depot)),
                "the still fan's node names the lava and the depot in its line: " + still);
            rig.engage();
        }).thenWaitUntil(() -> helper.assertTrue(rig.processing().equals("create:blasting")
                && "create:blasting".equals(Mechanisms.observe(rig.level, rig.fan).get("air_processing")),
            "the turning fan blasts through the lava: " + Mechanisms.observe(rig.level, rig.fan)))
            .thenExecute(() -> {
                var air = rig.air();
                helper.assertTrue(String.valueOf(air.get("zones")).contains("create:blasting")
                        && String.valueOf(air.get("zones")).contains(rig.cell(rig.depot)),
                    "the turning fan's node shows its blasting zone over the depot: " + air);
                var handler = rig.level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    rig.depot, null);
                helper.assertTrue(handler.insertItem(0, new ItemStack(Items.RAW_IRON, 3), false).isEmpty(),
                    "the depot takes the ore");
                helper.assertTrue(rig.read(UNPROCESSED, Map.of("type", new Value.Text("create:blasting")))
                    .equals(Optional.of(Value.of(3))), "three ores wait for the stream");
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.read(UNPROCESSED, Map.of()).equals(Optional.of(Value.of(0)))
                    && rig.stuck().stream().anyMatch(stack -> stack.is(Items.IRON_INGOT) && stack.getCount() == 3),
                "the stream smelts the depot's ore: " + rig.stuck() + " " + rig.read(UNPROCESSED, Map.of())))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void residentSmeltsAnOrderThroughTheFan(GameTestHelper helper) {
        var rig = new Rig(helper, true);
        rig.define();
        helper.startSequence()
            .thenExecuteAfter(10, rig::order)
            .thenWaitUntil(() -> helper.assertTrue(rig.level.getBlockState(rig.lever).getValue(LeverBlock.POWERED) == false
                    && rig.processing().equals("create:blasting"),
                "a resident engages the clutch and the stream blasts through the lava: " + rig.recent()))
            .thenWaitUntil(() -> helper.assertTrue(rig.destination.countItem(Items.IRON_INGOT) >= ORDERED,
                "the ordered ingots reach the destination: " + rig.recent()))
            .thenWaitUntil(() -> helper.assertTrue(rig.facilities.running(NAME).isEmpty(), "the last run ends"))
            .thenWaitUntil(() -> helper.assertTrue(rig.level.getBlockState(rig.lever).getValue(LeverBlock.POWERED),
                "the resident lets the clutch go again once the ingots are out: " + rig.recent()))
            .thenExecute(() -> {
                rig.assertClean();
                helper.assertTrue(rig.stock.countItem(Items.RAW_IRON) == 0
                        && rig.destination.countItem(Items.IRON_INGOT) == ORDERED,
                    "every ore was smelted and every ingot is in the barrel");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void ingotsAreWalkedBackToADistantOreStore(GameTestHelper helper) {
        var rig = new Rig(helper, true, true);
        rig.define();
        helper.startSequence()
            .thenExecuteAfter(10, () -> rig.order(rig.stockAt))
            .thenWaitUntil(() -> helper.assertTrue(rig.stock.countItem(Items.IRON_INGOT) >= ORDERED,
                "the ingots are walked back into the store the ore came from: " + rig.recent() + " " + rig.labor.diagnosis()))
            .thenWaitUntil(() -> helper.assertTrue(rig.facilities.running(NAME).isEmpty(), "the last run ends"))
            .thenExecute(rig::assertClean)
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void residentCanPickUpOreAtTheEdgeOfAStoreStance(GameTestHelper helper) {
        var rig = new Rig(helper, true);
        var chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos other = rig.stockAt.relative(ChestBlock.getConnectedDirection(chest));
        rig.level.setBlock(rig.stockAt, chest, 2);
        rig.level.setBlock(other, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
        var store = WorldPos.of(rig.level, rig.stockAt);
        var stand = new Stand(WorldPos.of(rig.level, rig.stockAt.south(4)));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            rig.colony.inventory().snapshot().store(store).filter(entry -> entry.operations().size() == 2).isPresent(),
            "the two chest halves are available as aliases"))
            .thenExecute(() -> {
                rig.resident.moveTo(rig.stockAt.getX() + 0.5, rig.stockAt.getY(), rig.stockAt.getZ() + 5.1);
                var ore = new Need(ItemSpec.of(Items.RAW_IRON), ORDERED, true);
                var fetch = TransferNode.transitTake(Stash.at(store), ore, Set.of(stand));
                var worker = TestWorker.of(rig.resident, rig.colony.residents().getFirst(), rig.resident.pack());
                helper.assertTrue(WorkStances.standingIn(rig.resident, fetch.spec().stances())
                        && Reach.workableCells(rig.level, rig.stockAt).contains(stand.cell().cell())
                        && !Reach.withinReach(rig.resident.blockPosition(), rig.stockAt),
                    "work can start from this stance even though rounding the resident position loses it");
                var result = fetch.draw(rig.level, worker);
                helper.assertTrue(result instanceof Staged.Taken taken
                        && taken.goods().stream().mapToInt(ItemStack::getCount).sum() == ORDERED,
                    "the resident takes the ore on the first attempt: " + result);
            }).thenSucceed();
    }

    // One run takes a single ore here, so the order of two is met by two runs of the workshop.
    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 3600)
    public static void anOrderLargerThanOneRunIsSmeltedOverSeveralRuns(GameTestHelper helper) {
        var rig = new Rig(helper, true);
        rig.define(SMELT.replace("\"scale\": 16", "\"scale\": 1"));
        helper.startSequence()
            .thenExecuteAfter(10, rig::order)
            .thenWaitUntil(() -> helper.assertTrue(rig.destination.countItem(Items.IRON_INGOT) >= ORDERED,
                "both ingots reach the destination: " + rig.recent() + " " + rig.labor.diagnosis()))
            .thenWaitUntil(() -> helper.assertTrue(rig.facilities.running(NAME).isEmpty(), "the last run ends"))
            .thenExecute(rig::assertClean)
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void stoppedFanLeavesTheOrderWaiting(GameTestHelper helper) {
        var rig = new Rig(helper, true);
        rig.engage();
        rig.level.setBlockAndUpdate(rig.motor, Blocks.AIR.defaultBlockState());
        rig.define();
        helper.startSequence()
            .thenExecuteAfter(10, rig::order)
            .thenExecuteAfter(300, () -> {
                helper.assertTrue(rig.processing().equals("none"), "the still fan blows nothing");
                helper.assertTrue(rig.stock.countItem(Items.RAW_IRON) == ORDERED && rig.destination.isEmpty()
                        && rig.facilities.running(NAME).isEmpty(),
                    "with nothing turning the fan, no resident takes the ore: " + rig.recent());
                rig.assertClean();
                rig.motor();
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.destination.countItem(Items.IRON_INGOT) >= ORDERED,
                "once the drive turns again the order is smelted: " + rig.recent()))
            .thenWaitUntil(() -> helper.assertTrue(rig.facilities.running(NAME).isEmpty(), "the last run ends"))
            .thenExecute(rig::assertClean)
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void manualSmeltingStowsTheCollectedIngot(GameTestHelper helper) {
        var rig = new Rig(helper, true, true);
        rig.stock.setItem(0, new ItemStack(Items.RAW_IRON));
        rig.define();
        var weaveFailure = new AtomicReference<Throwable>();
        String colonyId = rig.colony.id().toString();
        Logger logger = (Logger) LogManager.getLogger("folkways-labor");
        AbstractAppender appender = new AbstractAppender("fan-smelter-" + colonyId, null, null,
                false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (colonyId.equals(event.getContextData().getValue(ColonyLog.KEY))
                        && event.getMessage().getFormat().startsWith("weaving failed")) {
                    weaveFailure.compareAndSet(null, event.getThrown());
                }
            }
        };
        appender.start();
        logger.addAppender(appender);
        Cleanup.on(helper, () -> {
            logger.removeAppender(appender);
            appender.stop();
        });
        helper.startSequence()
            .thenExecuteAfter(10, () -> {
                // A trial passes the recipe a workshop run would get, as a plain dict.
                var recipe = Map.of("inputs", List.of(Map.of("item", "minecraft:raw_iron", "count", 1)),
                    "outputs", List.of(Map.of("item", "minecraft:iron_ingot", "count", 1)));
                var problems = rig.facilities.run(NAME, "smelt", DefinitionCodec.arguments(Map.of("recipe", recipe)));
                helper.assertTrue(problems.isEmpty(), "the manual smelt starts: " + problems);
            })
            .thenWaitUntil(() -> helper.assertTrue(
                rig.facilities.recent(NAME).filter(result -> result.ending().equals("done")).isPresent(),
                "the manual smelt finishes: " + rig.recent()))
            .thenExecute(() -> helper.assertTrue(
                rig.stock.countItem(Items.IRON_INGOT) + rig.destination.countItem(Items.IRON_INGOT) == 1
                    && rig.resident.pack().count(Items.IRON_INGOT) == 0,
                "the ingot is stowed in a colony store, pack ingots=" + rig.resident.pack().count(Items.IRON_INGOT)
                    + ": " + rig.recent() + " " + rig.labor.diagnosis()))
            .thenExecute(rig::assertClean)
            .thenExecuteAfter(1, () -> helper.assertTrue(weaveFailure.get() == null,
                "the planner handles manual run completion without throwing: " + weaveFailure.get()))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void orderUsesAnUnreservedIngotFromThePack(GameTestHelper helper) {
        int ordered = 6;
        var rig = new Rig(helper, true);
        rig.stock.setItem(0, new ItemStack(Items.RAW_IRON, ordered - 1));
        rig.resident.pack().setItem(0, new ItemStack(Items.IRON_INGOT));
        rig.define();
        helper.startSequence()
            .thenExecuteAfter(10, () -> rig.order(rig.destinationAt, ordered))
            .thenWaitUntil(() -> helper.assertTrue(rig.destination.countItem(Items.IRON_INGOT) == ordered,
                "the carried and smelted ingots reach the destination: destination="
                    + rig.destination.countItem(Items.IRON_INGOT) + " stock=" + rig.stock.countItem(Items.IRON_INGOT)
                    + " pack=" + rig.resident.pack().count(Items.IRON_INGOT) + " " + rig.labor.diagnosis()))
            .thenWaitUntil(() -> helper.assertTrue(Orders.targets(rig.colony).isEmpty(), "the order is complete"))
            .thenWaitUntil(() -> helper.assertTrue(rig.facilities.running(NAME).isEmpty(), "the last run ends"))
            .thenExecute(rig::assertClean)
            .thenSucceed();
    }

    private static BlockState create(String path) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", path)).defaultBlockState();
    }

    /**
     * West to east along z = 2: a creative motor, a clutch with its lever on top, the fan facing east, lava walled in
     * by stone, the depot. The colony's ore chest stands south-east, the order's barrel east.
     */
    private static final class Rig {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos motor;
        private final BlockPos lever;
        private final BlockPos fan;
        private final BlockPos depot;
        private final Container stock;
        private final BlockPos stockAt;
        private final Container destination;
        private final BlockPos destinationAt;
        private final ResidentEntity resident;
        private Colony colony;
        private DefinedFacilities facilities;
        private LaborFixture labor;
        private boolean running = true;

        Rig(GameTestHelper helper, boolean colonised) {
            this(helper, colonised, false);
        }

        // `far` stands the ore store well away from the depot, so the ingots are walked back to it.
        Rig(GameTestHelper helper, boolean colonised, boolean far) {
            this.helper = helper;
            level = helper.getLevel();
            for (int x = 0; x < 12; x++) {
                for (int z = 0; z < 22; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            motor = helper.absolutePos(new BlockPos(1, 1, 2));
            BlockPos clutch = motor.east();
            lever = clutch.above();
            fan = clutch.east();
            BlockPos lava = fan.east();
            depot = lava.east();
            level.setBlockAndUpdate(lava.north(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(lava.south(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
            level.setBlockAndUpdate(depot, create("depot"));
            level.setBlockAndUpdate(clutch, create("clutch").setValue(BlockStateProperties.AXIS, Direction.Axis.X));
            level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.POWERED, true));
            level.setBlockAndUpdate(fan, create("encased_fan").setValue(BlockStateProperties.FACING, Direction.EAST));
            motor();
            stockAt = helper.absolutePos(far ? new BlockPos(3, 1, 18) : new BlockPos(7, 1, 5));
            level.setBlockAndUpdate(stockAt, Blocks.CHEST.defaultBlockState());
            stock = (Container) level.getBlockEntity(stockAt);
            stock.setItem(0, new ItemStack(Items.RAW_IRON, ORDERED));
            destinationAt = helper.absolutePos(new BlockPos(9, 1, 2));
            level.setBlockAndUpdate(destinationAt, Blocks.BARREL.defaultBlockState());
            destination = (Container) level.getBlockEntity(destinationAt);
            if (!colonised) {
                resident = null;
                return;
            }
            colony = Colonies.mint(level.getServer());
            resident = PersonBody.RESIDENT.get().create(level);
            labor = new LaborFixture(colony, level);
            Cleanup.on(helper, () -> {
                running = false;
                labor.close();
                resident.discard();
                colony.raze();
            });
            resident.moveTo(helper.absolutePos(new BlockPos(7, 1, 3)).getBottomCenter());
            level.addFreshEntity(resident);
            colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
            resident.joinColony(colony.id());
            colony.entered(resident);
            WorkDefaults.set(colony, PersonBody.ID, WaresContent.CRAFTING, true, 1);
            WorkDefaults.set(colony, PersonBody.ID, Vocations.HAULING, true, 1);
            colony.hold(WaresContent.STORE, new Claimed.Block(WorldPos.of(level, stockAt))).orElseThrow();
            facilities = DefinedFacilities.of(colony).orElseThrow();
            helper.onEachTick(() -> {
                if (running) {
                    labor.tick();
                }
            });
        }

        // The motor turns so that the fan pushes its air east, through the lava and over the depot.
        void motor() {
            level.setBlockAndUpdate(motor, create("creative_motor").setValue(BlockStateProperties.FACING, Direction.EAST));
            ((CreativeMotorBlockEntity) level.getBlockEntity(motor)).generatedSpeed.setValue(64);
            helper.runAfterDelay(4, () -> {
                if (level.getBlockEntity(fan) instanceof EncasedFanBlockEntity blower && blower.getSpeed() != 0
                        && blower.getAirFlowDirection() != Direction.EAST) {
                    ((CreativeMotorBlockEntity) level.getBlockEntity(motor)).generatedSpeed.setValue(-64);
                }
            });
        }

        void engage() {
            level.setBlockAndUpdate(lever, level.getBlockState(lever).setValue(LeverBlock.POWERED, false));
            level.updateNeighborsAt(lever.below(), level.getBlockState(lever).getBlock());
        }

        String processing() {
            return read(PROCESSING, Map.of()).map(value -> ((Value.Text) value).value()).orElse("?");
        }

        void define() {
            define(SMELT);
        }

        void define(String smelt) {
            BlockPos min = motor;
            BlockPos max = depot.above();
            Map<String, Object> controls = new LinkedHashMap<>();
            controls.put("depot", Map.of("part", "folkways_core:item/up", "offset", offset(depot)));
            controls.put("clutch", Map.of("part", "lever", "offset", offset(lever)));
            Map<String, Object> readings = Map.of(
                "heat", Map.of("probe", "folkways_core:create/air_processing", "offset", offset(depot)),
                "raw", Map.of("probe", "folkways_core:create/air_unprocessed", "offset", offset(depot),
                    "type", "create:blasting"));
            Map<String, Object> data = Map.of("name", NAME,
                "extent", DefinitionCodec.extent(new FacilityExtent(WorldPos.of(level, fan).realm(), min, max)),
                "anchor", List.of(fan.getX(), fan.getY(), fan.getZ()),
                "controls", controls, "readings", readings,
                "routines", Map.of("smelt", Map.of("source", smelt)),
                "workshop", Map.of("routine", "smelt"),
                "triggers", Map.of("manual", Map.of("kind", "manual", "routine", "smelt")));
            var preview = facilities.preview(NAME, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty(), "the fan smelter definition is valid: " + preview.problems());
            helper.assertTrue(facilities.apply(preview).isEmpty(), "the fan smelter is defined");
        }

        private List<Integer> offset(BlockPos pos) {
            BlockPos d = pos.subtract(fan);
            return List.of(d.getX(), d.getY(), d.getZ());
        }

        void order() {
            order(destinationAt);
        }

        void order(BlockPos into) {
            order(into, ORDERED);
        }

        void order(BlockPos into, int count) {
            var claim = colony.claimAt(WorldPos.of(level, into))
                .or(() -> colony.hold(WaresContent.STORE, new Claimed.Block(WorldPos.of(level, into)))).orElseThrow();
            var held = HeldFacilities.find(colony, claim.id()).orElseThrow();
            helper.assertTrue(held.setParameter("item", new Value.Items(List.of(ItemFilter.item(
                ResourceLocation.withDefaultNamespace("iron_ingot"))))).isEmpty(), "order iron ingots");
            helper.assertTrue(held.setParameter("count", Value.of(count)).isEmpty(), "set the order count");
            var problems = held.run(net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level), "deliver", Map.of());
            helper.assertTrue(problems.isEmpty(), "the delivery order is filed: " + problems);
        }

        String recent() {
            return facilities.recent(NAME) + " running=" + facilities.running(NAME) + " kept="
                + colony.kept(DefinedFacilities.ID).getCompound(NAME) + " lever="
                + level.getBlockState(lever).getValue(LeverBlock.POWERED) + " heat=" + processing() + " depot=" + stuck()
                + " stalls=" + facilities.stalls(level.dimension());
        }

        void assertClean() {
            helper.assertTrue(stuck().isEmpty(), "nothing is left on the depot: " + stuck());
            var ground = level.getEntitiesOfClass(ItemEntity.class, new AABB(motor).inflate(12));
            helper.assertTrue(ground.isEmpty(), "no items lie on the ground: " + ground);
        }

        private List<ItemStack> stuck() {
            var handler = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, depot, null);
            List<ItemStack> held = new java.util.ArrayList<>();
            for (int slot = 0; handler != null && slot < handler.getSlots(); slot++) {
                if (!handler.getStackInSlot(slot).isEmpty()) {
                    held.add(handler.getStackInSlot(slot));
                }
            }
            return held;
        }

        // The fan's node in the mechanism graph of the whole machine: its stream as the assistant reads it.
        Map<?, ?> air() {
            var graph = Mechanisms.graph(MechanismScan.box(level, motor, depot.above()));
            return graph.nodes().stream().filter(node -> node.at().cell().equals(fan)).findFirst()
                .map(node -> (Map<?, ?>) node.state().get("air")).orElseThrow();
        }

        String cell(BlockPos pos) {
            return List.of(pos.getX(), pos.getY(), pos.getZ()).toString();
        }

        Optional<Value> read(ResourceLocation probe, Map<String, Value> config) {
            return Readings.of(probe, WorldPos.of(level, depot), config).flatMap(reading -> reading.read(level));
        }
    }
}
