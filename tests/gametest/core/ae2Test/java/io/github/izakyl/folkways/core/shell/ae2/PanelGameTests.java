package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.config.CpuSelectionMode;
import appeng.api.config.Settings;
import appeng.core.definitions.AEBlocks;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.internal.StoreTransfers;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.shell.crafting.NetworkDeviceSettings;
import io.github.izakyl.folkways.core.shell.crafting.NetworkPanel;
import io.github.izakyl.folkways.core.shell.crafting.NetworkRefusal;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
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

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_core", "panel_test");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PanelGameTests.class);
    }

    static final class Run {
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
            return colony.service(Ae2Integration.OWNER, NetworkPresence.class).orElseThrow();
        }

        NetworkDeviceSettings settings() {
            return presence().settings;
        }

        void set(UnaryOperator<NetworkDeviceSettings.NetworkDevice> change, WorldPos... devices) {
            for (WorldPos device : devices) {
                settings().set(device, change.apply(settings().at(device)));
            }
            presence().changed();
        }

        Depot depot() {
            return colony.workshopsIn(helper.getLevel().dimension()).stream().filter(Depot.class::isInstance)
                .map(Depot.class::cast).findFirst()
                .orElseThrow(() -> new GameTestAssertException("no ME depot published"));
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
            return StoreTransfers.take(rig.port(), helper.getLevel(), List.of(new Pull.Matching(ItemSpec.of(item), 1)),
                colony.storeRules(at), true);
        }

        TransferResult put(net.minecraft.world.item.Item item) {
            return StoreTransfers.put(rig.port(), helper.getLevel(), List.of(new ItemStack(item)), colony.storeRules(at), false, true);
        }
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
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
            var device = Operations.all(run.colony).stream()
                .filter(facility -> facility.facility().site().equals(run.at))
                .filter(facility -> facility.card().parameter(NetworkPanel.FILTER.key()).isPresent())
                .findFirst().orElseThrow();
            var card = Operations.find(run.colony, device.id()).orElseThrow();
            helper.assertTrue(card.setParameter("take", Value.of(false)).isEmpty(),
                "the card can disable taking");
            helper.assertTrue(card.setParameter("put", Value.of(false)).isEmpty(),
                "the card can disable storing");
        }).thenIdle(25).thenExecute(() -> {
            helper.assertTrue(run.refused(run.take(Items.WHEAT)), "taking is refused once the panel stops it");
            helper.assertTrue(run.refused(run.put(Items.WHEAT)), "storing is refused once the panel stops it");
            helper.assertTrue(!run.device(run.at).take() && run.device(run.other).take(),
                "the planner no longer offers taking at that device, but still at its neighbour");
            var neighbourRules = run.colony.storeRules(run.other);
            helper.assertTrue(StoreRule.takes(neighbourRules) && StoreRule.stores(neighbourRules),
                "transfers at the neighbouring device keep their own permissions");
            var card = Operations.all(run.colony).stream()
                .filter(facility -> facility.facility().site().equals(run.at))
                .filter(facility -> facility.card().parameter(NetworkPanel.FILTER.key()).isPresent())
                .findFirst().orElseThrow();
            helper.assertTrue(card.setParameter("take", Value.of(true)).isEmpty()
                    && card.setParameter("put", Value.of(true)).isEmpty()
                    && card.setParameter("filter_mode", new Value.Text("folkways_core:filter/only")).isEmpty()
                    && card.setParameter(NetworkPanel.FILTER.key(), new Value.Items(
                        List.of(ItemFilter.item(ResourceLocation.withDefaultNamespace("wheat"))))).isEmpty(),
                "the card can enable access and set an item whitelist");
        }).thenIdle(3).thenExecute(() -> {
            helper.assertTrue(!run.refused(run.take(Items.WHEAT)) && run.refused(run.take(Items.CARROT)),
                "a whitelist bounds what may be taken");
            helper.assertTrue(!run.refused(run.put(Items.WHEAT)) && run.refused(run.put(Items.CARROT)),
                "a whitelist bounds what may be stored");
            helper.assertTrue(run.device(run.at).take()
                    && NetworkPanel.admits(run.settings(), run.at, ItemSpec.of(Items.WHEAT))
                    && !NetworkPanel.admits(run.settings(), run.at, ItemSpec.of(Items.CARROT)),
                "the planner sees the same filter");
        }).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 1600)
    public static void orderToggleAndFilterGateCraftingOffers(GameTestHelper helper) {
        Run run = new Run(helper, CraftingGameTests.craftingRig(helper));
        ItemSpec planks = ItemSpec.of(Items.OAK_PLANKS);
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.rig.stock(Items.OAK_LOG, 8);
            run.enroll();
            run.settings().cpus(List.of(run.at), Optional.of(1));
        }).thenWaitUntil(() -> helper.assertTrue(!run.planks().isEmpty(),
            "with a CPU allowed, a cached preview offers the network craft"))
            .thenExecute(() -> run.set(device -> device.toggled("order"), run.at, run.other))
            .thenIdle(3).thenExecute(() -> {
                helper.assertTrue(run.planks().isEmpty() && !NetworkPresence.orders(run.colony, run.at, planks),
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

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void singleCpuNetworkIsLeftToPlayers(GameTestHelper helper) {
        Run run = new Run(helper, CraftingGameTests.craftingRig(helper));
        helper.startSequence().thenIdle(120).thenExecute(run::enroll).thenIdle(25).thenExecute(() -> {
            var network = run.network();
            helper.assertTrue(network.total() == 1 && NetworkPanel.limit(run.settings(), network) == 0
                    && run.depot().cpuLimit() == 0, "a one-CPU network leaves its CPU to players by default");
        }).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void cpuShareQueuesBeyondItAndSkipsPlayerOnlyCpus(GameTestHelper helper) {
        Run run = new Run(helper, CraftingGameTests.craftingRig(helper));
        helper.setBlock(new BlockPos(1, 2, 2), AEBlocks.CRAFTING_STORAGE_1K.block());
        helper.startSequence().thenIdle(120).thenExecute(run::enroll).thenIdle(25).thenExecute(() -> {
            var network = run.network();
            Depot depot = run.depot();
            helper.assertTrue(network.total() == 2 && depot.cpuLimit() == 1 && depot.cpus().size() == 2,
                "two CPUs: the colony may use one by default, of either");
            Tally tally = depot.tally();
            var key = depot.network();
            var first = tally.ticket(run.colony.id(), key, ItemSpec.of(Items.OAK_PLANKS), 4, depot.cpus());
            var second = tally.ticket(run.colony.id(), key, ItemSpec.of(Items.OAK_PLANKS), 4, depot.cpus());
            first.limit(depot.cpuLimit());
            second.limit(depot.cpuLimit());
            helper.assertTrue(first.take().isEmpty(), "the first order takes a CPU");
            helper.assertTrue(second.take().filter(NetworkRefusal.LIMIT::equals).isPresent()
                    && tally.free(depot.cpus().get(0)) != tally.free(depot.cpus().get(1)),
                "the second waits although a CPU is still free");
            helper.assertTrue(run.depot().options(Produce.of(OWNER, depot.site(), ItemSpec.of(Items.OAK_PLANKS), 4,
                Produce.UNBOUNDED), Grown.of(Produce.of(OWNER, depot.site(), ItemSpec.of(Items.OAK_PLANKS), 4,
                Produce.UNBOUNDED))).isEmpty(), "and the planner offers no further network craft");
            first.release(Ending.DROPPED);
            tally.clean();
            helper.assertTrue(second.take().isEmpty(), "the queued order proceeds once the first ends");
            second.release(Ending.DROPPED);
            tally.clean();
            var cpu = (CraftingCPUCluster) run.rig.port().node().getGrid().getCraftingService().getCpus()
                .iterator().next();
            cpu.getConfigManager().putSetting(Settings.CPU_SELECTION_MODE, CpuSelectionMode.PLAYER_ONLY);
        }).thenIdle(25).thenExecute(() -> {
            Depot depot = run.depot();
            helper.assertTrue(depot.cpus().size() == 1
                    && depot.cpus().stream().noneMatch(cpu -> depot.tally().nativeCpu(cpu).getSelectionMode()
                        == CpuSelectionMode.PLAYER_ONLY),
                "the colony orders like a machine and never reserves a player-only CPU");
        }).thenSucceed();
    }

}
