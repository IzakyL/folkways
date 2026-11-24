package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Prefabs;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.work.api.WorkProgress;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class ProcessingFacilityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ProcessingFacilityGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 900)
    public static void workshopDeliveryCarriesOreInTheResidentsPack(GameTestHelper helper) {
        var rig = new Fixture(helper, false);
        rig.demand();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            rig.stockCount(Items.RAW_IRON) == 0 && rig.furnace.getItem(Machines.INPUT_SLOT).isEmpty()
                && rig.resident.pack().contents().stream().anyMatch(stack -> stack.is(Items.RAW_IRON)),
            "the ore has left the store and is in the resident's pack before reaching the port"))
            .thenWaitUntil(() -> helper.assertTrue(rig.furnace.getItem(Machines.INPUT_SLOT).is(Items.RAW_IRON)
                    && rig.resident.pack().contents().stream().noneMatch(stack -> stack.is(Items.RAW_IRON)),
                "the resident deposited the carried ore into the furnace port"))
            .thenWaitUntil(() -> helper.assertTrue(rig.delivered, "the run supplies the ingot"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 900)
    public static void workshopProtectsReservedOreBeforeAnyDelivery(GameTestHelper helper) {
        var rig = new Fixture(helper, false, """
            def step(state, input, args):
                if input["_failure"] != None:
                    return state, [fail_permanent(input["_failure"])]
                if state == None:
                    return "delivering", [await("start", True, 400)]
                if state == "delivering":
                    return "collecting", [deliver("in", args["recipe"]["inputs"]),
                                          await("out.count", 1, 400), collect("out", args["recipe"]["outputs"])]
                return state, [done()]
            """, true);
        rig.demand();
        boolean[] competing = {false};
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.workshopCheckpoint(),
            "the recipe is booked before the first action can deliver"))
            .thenExecute(() -> rig.demand(Items.RAW_IRON, () -> competing[0] = true))
            .thenExecuteAfter(70, () -> {
                helper.assertTrue(!competing[0] && rig.stockCount(Items.RAW_IRON) == 1
                        && rig.resident.pack().contents().stream().noneMatch(stack -> stack.is(Items.RAW_IRON)),
                    "the unrelated order cannot claim the ore and nobody has picked it up");
                rig.start();
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.delivered && !competing[0],
                "the workshop uses its reserved ore to make the ingot"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 900)
    public static void workshopFailureReleasesUndrawnInputs(GameTestHelper helper) {
        var rig = new Fixture(helper, false, """
            def missing(state, failure, input, args):
                if failure["kind"] != "deliver":
                    return state, [fail_permanent("test.wrong_action")]
                return state, [fail_permanent("test.missing_fuel")]

            def step(state, input, args):
                if input["_failure"] != None:
                    return state, [fail_permanent(input["_failure"])]
                if state == None:
                    return "checking", [await("start", True, 400)]
                return state, [deliver("in", [{"item": "minecraft:coal", "count": 1}], on_fail="missing")]
            """, true);
        UUID order = rig.demand();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.workshopCheckpoint(),
            "the ore is booked before the first delivery"))
            .thenExecuteAfter(40, rig::start)
            .thenWaitUntil(() -> helper.assertTrue(rig.facilities.recent(Fixture.NAME)
                    .flatMap(DefinedFacilities.Result::reason).filter("test.missing_fuel"::equals).isPresent(),
                "a failed ordinary reservation reaches the delivery handler: " + rig.facilities.recent(Fixture.NAME)))
            .thenExecute(() -> {
                rig.colony.withdraw(DefinedFacilities.ID, order);
                helper.assertTrue(rig.stockCount(Items.RAW_IRON) == 1, "the undrawn ore is still in its store");
                rig.demand(Items.RAW_IRON, () -> rig.delivered = true);
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.delivered && rig.stockCount(Items.RAW_IRON) == 0,
                "the released ore is available to a different order"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 900)
    public static void furnaceDemandUsesDeclaredRecipeType(GameTestHelper helper) {
        var rig = new Fixture(helper, false);
        rig.demand();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.delivered,
            "the native smelting catalogue supplies the order: " + rig.facilities.recent(Fixture.NAME)))
            .thenExecute(rig::restore).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 900)
    public static void scriptedRecipeFeedsAResidentOrder(GameTestHelper helper) {
        var rig = new Fixture(helper, true);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            Mechanics.workshops(rig.colony, rig.level.getServer()).stream()
                .flatMap(entry -> entry.facility().stream()).anyMatch(facility -> !facility.explicitRecipes().isEmpty()),
            "the asynchronous recipe catalogue is published"))
            .thenExecute(rig::demand)
            .thenWaitUntil(() -> helper.assertTrue(rig.delivered,
            "the script supplies a recipe and produces an ingot")).thenExecute(() -> {
                var facility = Mechanics.workshops(rig.colony, rig.level.getServer()).stream()
                    .flatMap(entry -> entry.facility().stream()).findFirst().orElseThrow();
                helper.assertTrue(facility.recipeType().isEmpty() && facility.explicitRecipes().size() == 1
                        && facility.explicitRecipes().getFirst().outputs().getFirst().spec()
                            .equals(ItemSpec.of(Items.IRON_INGOT)),
                    "the script publishes its custom recipe");
                rig.restore();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 900)
    public static void awaitFailureHandlerRescuesOre(GameTestHelper helper) {
        var rig = new Fixture(helper, false, """
            def recover(state, failure, input, args):
                if failure["kind"] != "await" or not failure["retryable"]:
                    return state, [fail_permanent("wrong failure")]
                return "rescued", [collect("in", args["recipe"]["inputs"])]

            def step(state, input, args):
                if input["_failure"] != None:
                    return state, [fail_permanent(input["_failure"])]
                if state == None:
                    return "waiting", [deliver("in", args["recipe"]["inputs"]),
                                       await("start", True, 5, on_fail="recover")]
                if state == "rescued":
                    return state, [fail("test.rescued")]
                return state, [fail_permanent("missing handler")]
            """, false);
        rig.demand();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            rig.furnace.getItem(Machines.INPUT_SLOT).is(Items.RAW_IRON), "the ore is delivered"))
            .thenWaitUntil(() -> helper.assertTrue(rig.furnace.getItem(Machines.INPUT_SLOT).isEmpty()
                    && rig.facilities.recent(Fixture.NAME).flatMap(DefinedFacilities.Result::reason)
                        .filter("test.rescued"::equals).isPresent(),
                "the handler rescues the ore and reports a retryable failure"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 900)
    public static void furnaceDemandReloadsAfterDeliveringReservedOre(GameTestHelper helper) {
        var rig = new Fixture(helper, false, SMELT, false);
        rig.demand();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            rig.furnace.getItem(Machines.INPUT_SLOT).is(Items.RAW_IRON)
                && rig.colony.kept(DefinedFacilities.ID).getCompound(Fixture.NAME)
                    .getCompound("workshopRun").getCompound("run").getInt("step") >= 2,
            "the ore has been consumed from the reservation and the furnace is processing"))
            .thenExecute(() -> {
                rig.reload();
                rig.furnace.setItem(Machines.FUEL_SLOT, new ItemStack(Items.COAL));
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.delivered,
                "the restored checkpoint produces an ingot without another ore"))
            .thenExecute(rig::restore).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 900)
    public static void workshopPlansIndependentDeliveries(GameTestHelper helper) {
        var rig = new Fixture(helper, false, """
            def step(state, input, args):
                if input["_failure"] != None:
                    return state, [fail_permanent(input["_failure"])]
                if state == None:
                    return "loaded", [deliver("in", [{"item": "minecraft:raw_iron", "count": 1}]),
                                      deliver("second", [{"item": "minecraft:raw_iron", "count": 1}])]
                if state == "loaded":
                    return "collecting", [deliver("fuel", [{"item": "minecraft:coal", "count": 1}]),
                                           run("folkways_core:write_all", {"start": True}),
                                           collect("second", [{"item": "minecraft:iron_ingot", "count": 1}]),
                                           await("out.count", 1, 600),
                                           collect("out", [{"item": "minecraft:iron_ingot", "count": 1}])]
                return state, [done()]
            """, false, true);
        rig.stock.setItem(1, new ItemStack(Items.COAL));
        ((Container) rig.level.getBlockEntity(rig.at.cell().west())).setItem(1, new ItemStack(Items.IRON_INGOT));
        var jobs = new LinkedHashMap<WorldPos, UUID>();
        Consumer<WorkProgress> record = progress -> {
            if (progress.colony().equals(rig.colony.id()) && progress.step() == WorkProgress.Step.STARTED
                    && progress.node().spec().owner().equals(DefinedFacilities.ID)
                    && !(progress.node().spec().stances() instanceof Stances.Wherever)) {
                jobs.putIfAbsent(progress.node().spec().site().where(), progress.node().id());
            }
        };
        NeoForge.EVENT_BUS.addListener(record);
        Cleanup.on(helper, () -> NeoForge.EVENT_BUS.unregister(record));
        rig.demand();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.delivered, "the run supplies the ingot"))
            .thenExecute(() -> {
                WorldPos second = WorldPos.of(rig.level, rig.at.cell().west());
                helper.assertTrue(jobs.containsKey(rig.at) && jobs.containsKey(second)
                        && !jobs.get(rig.at).equals(jobs.get(second)),
                    "the planner assigned two distinct delivery nodes");
                helper.assertTrue(((Container) rig.level.getBlockEntity(second.block(rig.level)))
                    .getItem(0).is(Items.RAW_IRON), "the second delivery used a reserved ore");
                helper.assertTrue(rig.stockCount(Items.COAL) == 0 && rig.stockCount(Items.IRON_INGOT) == 1,
                    "fuel came from normal stock and the two collections settled the booked output");
                helper.assertTrue(rig.level.getBlockState(rig.at.cell().east())
                    .getValue(net.minecraft.world.level.block.LeverBlock.POWERED), "the library run wrote the signal");
            }).thenSucceed();
    }

    private static final String SMELT = """
        def step(state, input, args):
            if state == "collecting" and input["_failure"] == "folkways.facility.script.script_interrupted":
                state = "smelting"
            elif input["_failure"] != None:
                return state, [fail(input["_failure"])]
            if state == None:
                return "smelting", [deliver("in", args["recipe"]["inputs"]), write("start", True)]
            if state == "smelting":
                return "collecting", [await("out.count", 1, 600), collect("out", args["recipe"]["outputs"])]
            return state, [done]
        """;

    private static final String RECIPES = """
        def recipes(input):
            if "out.count" not in input:
                return []
            return [{"id": "test:iron",
                     "inputs": [{"item": "minecraft:raw_iron", "count": 1}],
                     "outputs": [{"item": "minecraft:iron_ingot", "count": 1}], "ticks": 200}]
        """;

    private static final class Fixture {
        private static final String NAME = "Community smelter";
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final WorldPos at;
        private final Colony colony;
        private final Container furnace;
        private final Container stock;
        private DefinedFacilities facilities;
        private final LaborFixture labor;
        private final ResidentEntity resident;
        private boolean running = true;
        private boolean delivered;

        Fixture(GameTestHelper helper, boolean dynamic) {
            this(helper, dynamic, SMELT + (dynamic ? RECIPES : ""), true);
        }

        Fixture(GameTestHelper helper, boolean dynamic, String source, boolean fueled) {
            this(helper, dynamic, source, fueled, false);
        }

        Fixture(GameTestHelper helper, boolean dynamic, String source, boolean fueled, boolean split) {
            this.helper = helper;
            level = helper.getLevel();
            for (int x = 0; x < 10; x++) {
                for (int z = 0; z < 6; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            BlockPos cell = new BlockPos(3, 1, 2);
            helper.setBlock(cell, Blocks.FURNACE);
            if (split) {
                helper.setBlock(cell.west(), Blocks.CHEST);
            }
            helper.setBlock(cell.east(), Blocks.LEVER.defaultBlockState().setValue(
                net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
            at = WorldPos.of(level, helper.absolutePos(cell));
            var nativeStation = Prefabs.at(level, at.block(level)).orElseThrow();
            helper.assertTrue(Ports.itemAt(level, at).isPresent() && StoreAccess.at(level, at.block(level)).isEmpty()
                    && StoreAccess.owningPart(level, at.block(level)).filter(nativeStation.id()::equals).isPresent(),
                "the furnace slots belong to the native station facility");
            furnace = (Container) level.getBlockEntity(at.block(level));
            if (fueled) {
                furnace.setItem(Machines.FUEL_SLOT, new ItemStack(Items.COAL));
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
            resident.moveTo(helper.absolutePos(new BlockPos(5, 1, 2)).getBottomCenter());
            level.addFreshEntity(resident);
            colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
            resident.joinColony(colony.id());
            colony.entered(resident);
            WorkDefaults.set(colony, PersonBody.ID, WaresContent.CRAFTING, true, 1);
            WorkDefaults.set(colony, PersonBody.ID, Vocations.HAULING, true, 1);
            BlockPos stockCell = new BlockPos(2, 1, 4);
            helper.setBlock(stockCell, Blocks.CHEST);
            BlockPos stockAt = helper.absolutePos(stockCell);
            stock = (Container) level.getBlockEntity(stockAt);
            stock.setItem(0, new ItemStack(Items.RAW_IRON, split ? 2 : 1));
            colony.hold(WaresContent.STORE, new Claimed.Block(WorldPos.of(level, stockAt))).orElseThrow();
            colony.hold(WaresContent.STATION, new Claimed.Block(at)).orElseThrow();
            colony.service(WaresContent.ID, WaresPresence.class).orElseThrow().refresh(level.getServer());
            helper.assertTrue(nativeStation(), "the claimed native furnace publishes work");
            facilities = DefinedFacilities.of(colony).orElseThrow();
            var extent = new FacilityExtent(at.realm(), split ? at.cell().west() : at.cell(), at.cell().east());
            Map<String, Object> controls = new LinkedHashMap<>(Map.of("in", port("up"), "out", port("down"),
                "start", Map.of("part", "lever", "offset", List.of(1, 0, 0))));
            var workshop = new LinkedHashMap<String, Object>(Map.of("routine", "smelt"));
            if (!split && !dynamic) {
                source += """

                    def recipes(input):
                        return recipes_of("minecraft:smelting")
                    """;
            }
            if (split) {
                controls.put("second", Map.of("part", "folkways_core:item/up", "offset", List.of(0, 0, 0)));
                controls.put("in", Map.of("part", "folkways_core:item/up", "offset", List.of(1, 0, 0)));
                controls.put("out", Map.of("part", "folkways_core:item/down", "offset", List.of(1, 0, 0)));
                controls.put("fuel", Map.of("part", "folkways_core:item/north", "offset", List.of(1, 0, 0)));
                controls.put("start", Map.of("part", "lever", "offset", List.of(2, 0, 0)));
                workshop.put("recipes", List.of(Map.of(
                    "inputs", List.of(Map.of("item", "minecraft:raw_iron", "count", 2)),
                    "outputs", List.of(Map.of("item", "minecraft:iron_ingot", "count", 2)), "ticks", 200)));
            }
            Map<String, Object> data = Map.of("name", NAME, "extent", DefinitionCodec.extent(extent),
                "controls", controls, "routines", Map.of("smelt", Map.of("source", source)),
                "workshop", workshop);
            var preview = facilities.preview(NAME, Optional.of(data));
            helper.assertTrue(preview.problems().isEmpty() && preview.covered().size() == 1,
                "the processing definition covers the furnace: " + preview.problems());
            helper.assertTrue(facilities.apply(preview).isEmpty(), "processing facility applied");
            helper.assertTrue(facilities.facilities().getFirst().triggers().contains(new Trigger.Demand("smelt")),
                "the workshop declares a demand trigger");
            helper.assertTrue(Prefabs.at(level, at.block(level)).isEmpty() && !nativeStation(),
                "the covered furnace no longer offers native work");
            helper.assertTrue(StoreAccess.at(level, at.block(level)).isEmpty(),
                "processing ports are not available for ordinary hauling");
            helper.onEachTick(() -> {
                if (running) {
                    labor.tick();
                }
            });
        }

        private static Map<String, Object> port(String side) {
            return Map.of("part", "folkways_core:item/" + side, "offset", List.of(0, 0, 0));
        }

        private boolean nativeStation() {
            return colony.workshopsIn(level.dimension()).stream()
                .anyMatch(shop -> shop.id().equals(WaresContent.ID) && shop.site().where().equals(at));
        }

        int stockCount(Item item) {
            return stock.countItem(item);
        }

        boolean workshopCheckpoint() {
            return colony.kept(DefinedFacilities.ID).getCompound(NAME).contains("workshopRun");
        }

        void start() {
            BlockPos lever = at.cell().east();
            var state = level.getBlockState(lever);
            level.setBlockAndUpdate(lever, state.setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true));
        }

        UUID demand() {
            return demand(Items.IRON_INGOT, () -> delivered = true);
        }

        UUID demand(Item item, Runnable received) {
            WorldPos target = WorldPos.of(level, helper.absolutePos(new BlockPos(7, 1, 2)));
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), DefinedFacilities.ID, new WorkSite.AtBlock(target),
                Stances.at(target), Workload.Once.of(1)).needs(new Need(ItemSpec.of(item), 1, true)).done();
            colony.submit(DefinedFacilities.ID, level.dimension(), Grown.of(new Node() {
                @Override
                public NodeSpec spec() {
                    return spec;
                }

                @Override
                public Outcome commit(ServerLevel world, Worker worker) {
                    helper.assertTrue(worker.supplied().stream().anyMatch(stack -> stack.is(item)),
                        "the demand receives the actual item");
                    received.run();
                    return Outcome.done();
                }
            }), (id, ending) -> { });
            return spec.id();
        }

        void reload() {
            var bag = colony.kept(DefinedFacilities.ID);
            var entry = bag.getCompound(NAME);
            var before = entry.getCompound("workshopRun").getCompound("run");
            entry.remove("workshopRun");
            colony.keep(DefinedFacilities.ID, bag);

            labor.close();
            var saved = colony.kept(DefinedFacilities.ID).getCompound(NAME).getCompound("workshopRun");
            var run = saved.getCompound("run");
            helper.assertTrue(run.hasUUID("id") && run.getUUID("id").equals(before.getUUID("id"))
                    && run.getInt("step") == before.getInt("step"),
                "labor shutdown saves the active run and its committed step");
            var pool = saved.getCompound("reservations");
            helper.assertTrue(Arrays.equals(pool.getLongArray("remaining"), new long[] {0})
                    && Arrays.equals(pool.getLongArray("owed"), new long[] {1}),
                "shutdown saves the spent ore and outstanding ingot in the reservation pool");
            helper.assertTrue(stockCount(Items.RAW_IRON) == 0, "resuming cannot reserve another ore");

            // Refresh closes colony services with SHUTDOWN and rebuilds them from keeps.
            ColonyData.find(level.getServer(), colony.id()).orElseThrow()
                .relocate(level.getServer(), Map.of(at, at));
            var reloaded = DefinedFacilities.of(colony).orElseThrow();
            helper.assertTrue(reloaded != facilities, "the facility service reloaded from the saved checkpoint");
            facilities = reloaded;
            demand();
        }

        void restore() {
            helper.assertTrue(furnace.getItem(Machines.INPUT_SLOT).isEmpty()
                && furnace.getItem(Machines.RESULT_SLOT).isEmpty(), "input spent and actual output removed");
            var removal = facilities.preview(NAME, Optional.empty());
            helper.assertTrue(removal.restored().size() == 1 && facilities.apply(removal).isEmpty(),
                "deletion restores the covered furnace");
            helper.assertTrue(Prefabs.at(level, at.block(level)).isPresent() && nativeStation(),
                "the claimed native furnace offers work again");
            var workshops = Mechanics.workshops(colony, level.getServer());
            helper.assertTrue(workshops.stream().noneMatch(entry -> entry.facility().isPresent()),
                "the removed facility no longer appears in the catalogue");
            helper.assertTrue(workshops.stream().anyMatch(entry -> entry.site().equals(at)
                    && entry.station().filter(station -> station.id().toString().equals("minecraft:furnace")).isPresent()),
                "the restored prefab workshop appears with its station type");
        }
    }
}
