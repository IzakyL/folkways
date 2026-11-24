package io.github.izakyl.folkways.vanilla.create.lane;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.FacilityRefusal;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Crew;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.plan.internal.Planned;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Situation;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.plan.internal.Vertex;
import io.github.izakyl.folkways.core.testing.SelectedTests;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class LaneGameTests {

    private static final ResourceLocation LANE = ResourceLocation.fromNamespaceAndPath("folkways_core", "lane");
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "belt");
    private static final ItemSpec ORE = ItemSpec.of(Items.RAW_IRON);
    private static final int COUNT = 4;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        SelectedTests.register(event, LaneGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 450)
    public static void poweredBeltFeedsWorkshop(GameTestHelper helper) {
        var scene = new Scene(helper);
        helper.runAfterDelay(15, () -> {
            scene.install(true);
            Vertex arrival = scene.arrival();
            helper.assertFalse(arrival.node().ready(scene.level), "an empty outlet must wait");
            helper.succeedWhen(() -> {
                if (scene.furnace.getItem(0).isEmpty() && scene.furnace.getItem(2).isEmpty()) {
                    helper.assertTrue(arrival.node().ready(scene.level),
                        "waiting for the powered belt to supply the outlet");
                    helper.assertTrue(scene.input.isEmpty(), "the belt extracted the source stock");
                    scene.execute(arrival);
                    scene.execute(scene.plan.weave().vertices().get(scene.workshop.id()));
                }
                helper.assertTrue(scene.furnace.getItem(2).is(Items.IRON_INGOT),
                    "the workshop uses the ore collected from the outlet");
                scene.close();
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 280)
    public static void brokenBeltTimesOutAndReplansOnFoot(GameTestHelper helper) {
        var scene = new Scene(helper);
        helper.runAfterDelay(15, () -> {
            scene.install(true);
            Vertex arrival = scene.arrival();
            scene.level.destroyBlock(scene.beltStart.east(3), false);
            helper.runAfterDelay(161, () -> {
                var refusal = arrival.node().refusal(scene.level);
                helper.assertTrue(refusal.equals(Optional.of(FacilityRefusal.UNSETTLED)),
                    "a broken belt fails through the outlet's arrival deadline");
                // Refill any ore the disconnected funnel ejected before the fallback round.
                scene.input.setItem(0, new ItemStack(Items.RAW_IRON, COUNT));
                helper.runAfterDelay(1, () -> {
                    scene.removeLane();
                    scene.solve(List.of(new Message.Failed(arrival.id(), refusal.orElseThrow())));
                    scene.carryOnFoot();
                    scene.close();
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 80)
    public static void undeclaredBeltLeavesResidentHaulingInPlace(GameTestHelper helper) {
        var scene = new Scene(helper);
        helper.runAfterDelay(15, () -> {
            scene.install(false);
            scene.carryOnFoot();
            scene.close();
            helper.succeed();
        });
    }

    private static BlockState create(String path) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", path)).defaultBlockState();
    }

    private static final class Scene implements AutoCloseable {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final Colony colony;
        private final PlanningFixture fixture;
        private final Crew crew;
        private final PlanningFixture.Rounds rounds = new PlanningFixture.Rounds();
        private final BlockPos beltStart;
        private final WorldPos source;
        private final WorldPos destination;
        private final Container input;
        private final Container output;
        private final Container furnace;
        private final Node workshop;
        private final TestWorker worker;
        private final Stands stands;
        private Planned plan;
        private boolean declared;

        Scene(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            fixture = new PlanningFixture(helper);
            beltStart = helper.absolutePos(new BlockPos(3, 1, 3));
            BlockPos beltEnd = beltStart.east(6);
            source = WorldPos.of(level, beltStart.west().above());
            destination = WorldPos.of(level, beltEnd.east().above());
            BlockPos furnacePos = beltEnd.east(2);
            for (BlockPos floor : BlockPos.betweenClosed(beltStart.offset(-2, -1, -2), beltEnd.offset(3, -1, 2))) {
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            }
            for (WorldPos chest : List.of(source, destination)) {
                level.setBlockAndUpdate(chest.cell().below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(chest.cell(), Blocks.CHEST.defaultBlockState());
            }
            level.setBlockAndUpdate(furnacePos, Blocks.FURNACE.defaultBlockState());
            input = Containers.at(level, source.cell()).orElseThrow();
            output = Containers.at(level, destination.cell()).orElseThrow();
            furnace = Containers.at(level, furnacePos).orElseThrow();
            furnace.setItem(1, new ItemStack(Items.COAL));
            worldBelt(beltEnd);

            WorldPos work = WorldPos.of(level, furnacePos);
            WorldPos footing = work.at(furnacePos.north());
            stands = Stands.building().container(Stash.at(source), Set.of(new Stand(source.at(source.cell().north()))))
                .container(Stash.at(destination), Set.of(new Stand(footing))).done();
            var hand = fixture.crew.hands().getFirst();
            crew = new Crew(List.of(new Crew.Hand(hand.who(), footing, hand.packCells(), hand.packCapacity(),
                hand.pace(), hand.licences(), hand.tools(), Optional.empty())));
            workshop = new Node() {
                private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER,
                        new WorkSite.AtBlock(work), Stances.at(footing), Workload.Once.of(0))
                    .needs(new Need(ORE, COUNT, true)).done();

                @Override
                public NodeSpec spec() {
                    return spec;
                }

                @Override
                public Outcome commit(ServerLevel level, Worker worker) {
                    furnace.setItem(0, worker.supplied().getFirst());
                    return Outcome.done();
                }
            };
            worker = TestWorker.of(fixture.worker.body(), fixture.worker.resident(), fixture.worker.pack());
        }

        private void worldBelt(BlockPos end) {
            var shaft = create("shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
            level.setBlockAndUpdate(beltStart, shaft);
            level.setBlockAndUpdate(end, shaft);
            BeltConnectorItem.createBelts(level, beltStart, end);
            level.setBlockAndUpdate(beltStart.north(), create("creative_motor")
                .setValue(DirectionalBlock.FACING, Direction.SOUTH));
            level.setBlockAndUpdate(beltStart.above(), create("andesite_belt_funnel")
                .setValue(BeltFunnelBlock.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BeltFunnelBlock.SHAPE, BeltFunnelBlock.Shape.PUSHING));
            level.setBlockAndUpdate(end.above(), create("andesite_belt_funnel")
                .setValue(BeltFunnelBlock.HORIZONTAL_FACING, Direction.WEST));
            helper.runAfterDelay(5, () -> {
                var motor = (CreativeMotorBlockEntity) level.getBlockEntity(beltStart.north());
                var belt = (BeltBlockEntity) level.getBlockEntity(beltStart);
                motor.generatedSpeed.setValue(belt.getMovementFacing() == Direction.EAST ? 128 : -128);
            });
        }

        private void install(boolean declare) {
            colony.stores(OWNER, level.dimension(), List.of(source, destination));
            input.setItem(0, new ItemStack(Items.RAW_IRON, COUNT));
            declared = declare;
            if (declare) {
                var facilities = DefinedFacilities.of(colony).orElseThrow();
                var extent = new FacilityExtent(source.realm(), source.cell().below(), destination.cell());
                var preview = facilities.preview("belt", Optional.of(Map.of(
                    "name", "belt", "extent", DefinitionCodec.extent(extent),
                    "anchor", List.of(source.cell().getX(), source.cell().getY(), source.cell().getZ()),
                    "controls", Map.of(
                        "input", Map.of("offset", List.of(0, 0, 0), "part", "folkways_core:item"),
                        "output", Map.of("offset", List.of(8, 0, 0), "part", "folkways_core:item")),
                    "projections", List.of(Map.of("lane", Map.of("from", "input", "to", "output",
                        "automatic", true, "latency", 80, "items", List.of("minecraft:raw_iron"),
                        "rate", Map.of("ticks", 10, "count", 16)))))));
                helper.assertTrue(preview.problems().isEmpty(), "lane validates: " + preview.problems());
                helper.assertTrue(facilities.apply(preview).isEmpty(), "lane installs");
                helper.assertTrue(colony.transports().entries().size() == 1, "lane publishes both endpoint stances");
            }
            solve(List.of());
        }

        private void removeLane() {
            var facilities = DefinedFacilities.of(colony).orElseThrow();
            helper.assertTrue(facilities.apply(facilities.preview("belt", Optional.empty())).isEmpty(), "lane is removed");
            declared = false;
        }

        private void solve(List<Message> events) {
            var stock = declared ? new Stock(List.of(), Map.of())
                : new Stock(List.of(new Stock.Holding(Stash.at(source), input.getItem(0))),
                    Map.of(Stash.at(source), 20, Stash.at(destination), 20));
            var situation = new Situation(fixture.ways, stock, colony.workshopsIn(level.dimension()), stands,
                () -> colony.transports().in(level.dimension()), level.getGameTime());
            plan = rounds.solve(situation, crew, List.of(Grown.of(workshop)), events, level.getGameTime());
        }

        private Vertex arrival() {
            var nodes = plan.weave().vertices().values().stream()
                .filter(vertex -> vertex.node().spec().owner().equals(LANE)).toList();
            helper.assertTrue(nodes.size() == 1 && plan.weave().vertices().size() == 2,
                "automatic lane only adds an outlet collection: " + plan.weave().vertices().values().stream()
                    .map(vertex -> vertex.node().spec().owner()).toList());
            Vertex arrival = nodes.getFirst();
            helper.assertTrue(arrival.node().planned(level).isEmpty(), "arrival watch is armed");
            return arrival;
        }

        private void carryOnFoot() {
            var nodes = plan.weave().vertices().values().stream()
                .filter(vertex -> !vertex.id().equals(workshop.id())).toList();
            helper.assertTrue(nodes.size() == 1 && !nodes.getFirst().node().spec().owner().equals(LANE),
                "one ordinary carry supplies the workshop: " + nodes);
            execute(nodes.getFirst());
            execute(plan.weave().vertices().get(workshop.id()));
            helper.assertTrue(furnace.getItem(0).getCount() == COUNT && input.isEmpty() && output.isEmpty(),
                "ore was carried directly from the source to the workshop");
        }

        private void execute(Vertex vertex) {
            worker.placement(vertex.plan()).within(new Worker.Store(source, input), new Worker.Store(destination, output));
            var outcome = Commit.run(level, vertex.node(), worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "work commits: " + outcome);
            for (ItemStack stack : ((Outcome.Done) outcome).made()) {
                ((net.minecraft.world.SimpleContainer) worker.pack()).addItem(stack);
            }
        }

        @Override
        public void close() {
            rounds.closed();
            colony.raze();
        }
    }
}
