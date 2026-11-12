package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.shell.crafting.DeviceSettings;
import io.github.izakyl.folkways.core.shell.crafting.NetworkPanel;
import io.github.izakyl.folkways.core.shell.crafting.NetworkRefusal;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class PanelGameTests {

    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "rs2_panel_test");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PanelGameTests.class);
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

    private static final class Run {
        final GameTestHelper helper;
        final NetworkRig rig;
        final Colony colony;
        final WorldPos at;
        final WorldPos other;

        Run(GameTestHelper helper, NetworkRig rig) {
            this.helper = helper;
            this.rig = rig;
            for (int x = 0; x < 10; x++) {
                for (int z = 0; z < 8; z++) {
                    if (helper.getBlockState(new BlockPos(x, 1, z)).isAir()) {
                        helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                    }
                }
            }
            colony = Colonies.mint(helper.getLevel().getServer());
            Cleanup.on(helper, colony::raze);
            at = WorldPos.of(helper.getLevel(), helper.absolutePos(NetworkRig.TERMINAL));
            other = WorldPos.of(helper.getLevel(), helper.absolutePos(NetworkRig.OTHER));
        }

        void enroll() {
            colony.hold(OWNER, new Claimed.Block(at)).orElseThrow();
            colony.stores(OWNER, helper.getLevel().dimension(), List.of(at, other));
        }

        NetworkPresence presence() {
            return colony.service(Rs2Integration.OWNER, NetworkPresence.class).orElseThrow();
        }

        DeviceSettings settings() {
            return presence().settings;
        }

        void set(UnaryOperator<DeviceSettings.Device> change, WorldPos... devices) {
            for (WorldPos device : devices) {
                settings().set(device, change.apply(settings().at(device)));
            }
            presence().changed();
        }

        Depot depot() {
            return colony.workshopsIn(helper.getLevel().dimension()).stream().filter(Depot.class::isInstance)
                .map(Depot.class::cast).findFirst()
                .orElseThrow(() -> new GameTestAssertException("no RS2 depot published"));
        }

        NetworkPanel.Network network() {
            var found = presence().networks.get(at);
            if (found == null) {
                throw new GameTestAssertException("the terminal's network has not been swept yet");
            }
            return found;
        }

        Depot.Device device(WorldPos where) {
            return depot().devices().stream().filter(device -> device.at().equals(where)).findFirst()
                .orElseThrow(() -> new GameTestAssertException("device not in the depot: " + where));
        }

        List<Refinement.Change> planks() {
            Depot depot = depot();
            Produce want = Produce.of(OWNER, depot.site(), ItemSpec.of(Items.OAK_PLANKS), 4, Produce.UNBOUNDED);
            return depot.options(want, Grown.of(want));
        }

        boolean refused(TransferResult result) {
            return result instanceof TransferResult.Refused refused && refused.why() == TransferResult.Why.NOT_ALLOWED;
        }

        TransferResult take(net.minecraft.world.item.Item item) {
            return rig.port().take(helper.getLevel(), List.of(new Pull.Matching(ItemSpec.of(item), 1)),
                colony.storeRules(at), true);
        }

        TransferResult put(net.minecraft.world.item.Item item) {
            return rig.port().put(helper.getLevel(), List.of(new ItemStack(item)), colony.storeRules(at), false, true);
        }
    }

    private static DeviceSettings.Device whitelist(DeviceSettings.Device device, String item) {
        return device.filtering(List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace(item))))
            .toggled("filter_mode");
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void storeAndTakeTogglesAndFilterGateTransfers(GameTestHelper helper) {
        Run run = new Run(helper, new NetworkRig(helper));
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.rig.stock(Items.WHEAT, 16);
            run.rig.stock(Items.CARROT, 16);
            run.enroll();
        }).thenIdle(25).thenExecute(() -> {
            helper.assertTrue(run.device(run.at).take() && !(run.take(Items.WHEAT) instanceof TransferResult.Refused)
                    && !(run.put(Items.WHEAT) instanceof TransferResult.Refused),
                "by default a terminal lets residents store and take as its tags allow");
            run.set(device -> device.toggled("take").toggled("put"), run.at);
        }).thenIdle(3).thenExecute(() -> {
            helper.assertTrue(run.refused(run.take(Items.WHEAT)), "taking is refused once the panel stops it");
            helper.assertTrue(run.refused(run.put(Items.WHEAT)), "storing is refused once the panel stops it");
            helper.assertTrue(!run.device(run.at).take() && run.device(run.other).take(),
                "the planner no longer offers taking at that device, but still at its neighbour");
            run.set(device -> whitelist(device.toggled("take").toggled("put"), "wheat"), run.at);
        }).thenIdle(3).thenExecute(() -> {
            helper.assertTrue(!run.refused(run.take(Items.WHEAT)) && run.refused(run.take(Items.CARROT)),
                "a whitelist bounds what may be taken");
            helper.assertTrue(!run.refused(run.put(Items.WHEAT)) && run.refused(run.put(Items.CARROT)),
                "a whitelist bounds what may be stored");
            helper.assertTrue(run.device(run.at).admits(ItemSpec.of(Items.WHEAT))
                    && !run.device(run.at).admits(ItemSpec.of(Items.CARROT)),
                "the planner sees the same filter");
        }).thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1600)
    public static void orderToggleAndFilterGateCraftingOffers(GameTestHelper helper) {
        Run run = new Run(helper, CraftingGameTests.craftingRig(helper));
        ItemSpec planks = ItemSpec.of(Items.OAK_PLANKS);
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.rig.stock(Items.OAK_LOG, 8);
            run.enroll();
        }).thenWaitUntil(() -> helper.assertTrue(!run.planks().isEmpty(),
            "by default the colony may run one task, and a cached preview offers the network craft"))
            .thenExecute(() -> run.set(device -> device.toggled("order"), run.at, run.other))
            .thenIdle(3).thenExecute(() -> {
                helper.assertTrue(run.planks().isEmpty() && !run.device(run.at).craft()
                        && !NetworkPresence.orders(run.colony, run.at, planks),
                    "with ordering stopped no device offers the craft, and a submitted order is refused");
                run.set(device -> device.toggled("order").filtering(List.of(
                    ItemFilter.item(ResourceLocation.withDefaultNamespace("oak_planks")))), run.at, run.other);
            }).thenIdle(3).thenExecute(() -> {
                helper.assertTrue(run.planks().isEmpty() && !NetworkPresence.orders(run.colony, run.at, planks),
                    "a blacklisted item is neither offered nor ordered");
                run.set(device -> device.filtering(List.of()), run.at, run.other);
            }).thenIdle(3).thenExecute(() -> helper.assertTrue(!run.planks().isEmpty()
                && NetworkPresence.orders(run.colony, run.at, planks), "clearing the filter restores the offer"))
            .thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void taskShareAllowsOneColonyTaskOrNone(GameTestHelper helper) {
        Run run = new Run(helper, CraftingGameTests.craftingRig(helper));
        helper.startSequence().thenIdle(120).thenExecute(run::enroll).thenIdle(25).thenExecute(() -> {
            var network = run.network();
            Depot depot = run.depot();
            helper.assertTrue(network.tasks() && network.total() == 1 && depot.taskLimit() == 1,
                "Refined Storage has no CPUs: by default the colony may run its one task");
            Tally tally = depot.tally();
            long now = helper.getLevel().getGameTime();
            var first = tally.ticket(run.colony.id(), depot.network(), List.of(), now, ItemSpec.of(Items.OAK_PLANKS),
                4, depot.executors(), true);
            first.limit = depot.taskLimit();
            helper.assertTrue(first.take().isEmpty() && tally.crafting(run.colony.id(), depot.network()) == 1,
                "the colony's task is counted against its share");
            Produce want = Produce.of(OWNER, depot.site(), ItemSpec.of(Items.OAK_PLANKS), 4, Produce.UNBOUNDED);
            helper.assertTrue(depot.options(want, Grown.of(want)).isEmpty(),
                "beyond its share the planner offers no further network craft");
            first.release(Ending.DROPPED);
            tally.clean();
            run.settings().cpus(List.of(run.at), Optional.of(0));
            run.presence().changed();
        }).thenIdle(3).thenExecute(() -> {
            Depot depot = run.depot();
            var ticket = depot.tally().ticket(run.colony.id(), depot.network(), List.of(),
                helper.getLevel().getGameTime(), ItemSpec.of(Items.OAK_PLANKS), 4, depot.executors(), true);
            ticket.limit = depot.taskLimit();
            helper.assertTrue(depot.taskLimit() == 0 && ticket.take().filter(NetworkRefusal.LIMIT::equals).isPresent(),
                "a share of none stops the colony ordering network crafts");
        }).thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void commissionToggleAndFilterGateColonyPatterns(GameTestHelper helper) {
        Run run = new Run(helper, new NetworkRig(helper));
        ItemResource diamond = new ItemResource(Items.DIAMOND);
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.enroll();
            run.colony.publish(OWNER, helper.getLevel().dimension(),
                List.of(new Bench(UUID.randomUUID(), new WorkSite.AtBlock(run.at))));
        }).thenWaitUntil(() -> helper.assertTrue(run.presence().provider(run.at).isPresent()
                && run.rig.port().crafting().getOutputs().contains(diamond),
            "by default the network may commission residents through the grid"))
            .thenExecute(() -> run.set(device -> device.toggled("commission"), run.at, run.other))
            .thenIdle(3).thenExecute(() -> {
                helper.assertTrue(run.presence().provider(run.at).isEmpty()
                        && run.presence().provider(run.other).isEmpty()
                        && !run.rig.port().crafting().getOutputs().contains(diamond),
                    "with commissions stopped at every device no colony provider attaches");
                run.set(device -> device.toggled("commission").filtering(List.of(
                    ItemFilter.item(ResourceLocation.withDefaultNamespace("diamond")))), run.at);
            }).thenIdle(3).thenExecute(() -> helper.assertTrue(run.presence().provider(run.at).isPresent()
                    && !run.rig.port().crafting().getOutputs().contains(diamond),
                "a blacklisted recipe is not offered through the device"))
            .thenExecute(() -> run.set(device -> device.filtering(List.of()), run.at))
            .thenWaitUntil(() -> helper.assertTrue(run.rig.port().crafting().getOutputs().contains(diamond),
                "clearing the filter offers the recipe again"))
            .thenSucceed();
    }
}
