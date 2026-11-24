package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stances;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class CommissionGameTests {
    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "rs2_panel_test");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CommissionGameTests.class);
    }

    // Makes diamonds from ingots, so the colony has one recipe to offer the network.
    private record Bench(UUID key, WorkSite site) implements Workshop {
        @Override
        public ResourceLocation id() {
            return OWNER;
        }

        @Override
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)
                    || produce.wanted().item().filter(ResourceLocation.withDefaultNamespace("diamond")::equals)
                        .isEmpty()) {
                return List.of();
            }
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, site, Stances.WHEREVER, Workload.Once.of(20))
                .needs(new Need(ItemSpec.of(Items.IRON_INGOT), 2))
                .gives(new Amount(ItemSpec.of(Items.DIAMOND), 1)).done();
            return List.of(Refinement.Change.of(Grown.of(new Node() {
                @Override
                public NodeSpec spec() {
                    return spec;
                }

                @Override
                public Outcome commit(ServerLevel level, Worker worker) {
                    throw new AssertionError("no resident works in these tests");
                }
            })));
        }
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void removingGridWithCommissionProviderAttached(GameTestHelper helper) {
        var run = new PanelGameTests.Run(helper, new NetworkRig(helper));
        ItemResource diamond = new ItemResource(Items.DIAMOND);
        helper.runAfterDelay(120, () -> {
            var network = run.rig.port().node().getNetwork();
            var before = Rs2Devices.key(network);
            run.enroll();
            run.colony.publish(OWNER, helper.getLevel().dimension(),
                List.of(new Bench(UUID.randomUUID(), new WorkSite.AtBlock(run.at))));
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                run.rig.port().crafting().getOutputs().contains(diamond), "the colony recipe is offered"))
                .thenExecute(() -> {
                    helper.assertTrue(before.equals(Rs2Devices.key(network)),
                        "the colony provider leaves the network key unchanged");
                    helper.setBlock(NetworkRig.OTHER, Blocks.AIR);
                }).thenWaitUntil(() -> {
                    var graph = network.getComponent(GraphNetworkComponent.class);
                    helper.assertTrue(graph.getContainers().size() == before.members().size() - 1,
                        "RS2 removes the grid from the physical graph");
                    helper.assertTrue(run.rig.port().crafting().getOutputs().contains(diamond),
                        "the remaining grid still offers the colony recipe");
                }).thenSucceed();
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void commissionToggleAndFilterGateColonyPatterns(GameTestHelper helper) {
        var run = new PanelGameTests.Run(helper, new NetworkRig(helper));
        ItemResource diamond = new ItemResource(Items.DIAMOND);
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.enroll();
            run.colony.publish(OWNER, helper.getLevel().dimension(),
                List.of(new Bench(UUID.randomUUID(), new WorkSite.AtBlock(run.at))));
        }).thenWaitUntil(() -> helper.assertTrue(Rs2Integration.attachment(run.colony, Providers.class).orElseThrow().at(run.at).isPresent()
                && run.rig.port().crafting().getOutputs().contains(diamond),
            "by default the network may commission residents through the grid"))
            .thenExecute(() -> run.set(device -> device.toggled("commission"), run.at, run.other))
            .thenIdle(3).thenExecute(() -> {
                helper.assertTrue(Rs2Integration.attachment(run.colony, Providers.class).orElseThrow().at(run.at).isEmpty()
                        && Rs2Integration.attachment(run.colony, Providers.class).orElseThrow().at(run.other).isEmpty()
                        && !run.rig.port().crafting().getOutputs().contains(diamond),
                    "with commissions stopped at every device no colony provider attaches");
                run.set(device -> device.toggled("commission").filtering(List.of(
                    ItemFilter.item(ResourceLocation.withDefaultNamespace("diamond")))), run.at);
            }).thenIdle(3).thenExecute(() -> helper.assertTrue(Rs2Integration.attachment(run.colony, Providers.class).orElseThrow().at(run.at).isPresent()
                    && !run.rig.port().crafting().getOutputs().contains(diamond),
                "a blacklisted recipe is not offered through the device"))
            .thenExecute(() -> run.set(device -> device.filtering(List.of()), run.at))
            .thenWaitUntil(() -> helper.assertTrue(run.rig.port().crafting().getOutputs().contains(diamond),
                "clearing the filter offers the recipe again"))
            .thenSucceed();
    }
}
