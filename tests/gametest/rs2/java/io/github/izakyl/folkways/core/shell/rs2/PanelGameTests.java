package io.github.izakyl.folkways.core.shell.rs2;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
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

    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "rs2_panel_test");

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
            return colony.service(Rs2Integration.OWNER, NetworkPresence.class).orElseThrow();
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
            return StoreTransfers.take(rig.port(), helper.getLevel(), List.of(new Pull.Matching(ItemSpec.of(item), 1)),
                colony.storeRules(at), true);
        }

        TransferResult put(net.minecraft.world.item.Item item) {
            return StoreTransfers.put(rig.port(), helper.getLevel(), List.of(new ItemStack(item)), colony.storeRules(at), false, true);
        }
    }

    private static NetworkDeviceSettings.NetworkDevice whitelist(NetworkDeviceSettings.NetworkDevice device, String item) {
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
        }).thenIdle(25).thenExecute(() -> {
            helper.assertTrue(run.refused(run.take(Items.WHEAT)), "taking is refused once the panel stops it");
            helper.assertTrue(run.refused(run.put(Items.WHEAT)), "storing is refused once the panel stops it");
            helper.assertTrue(!run.device(run.at).take() && run.device(run.other).take(),
                "the planner no longer offers taking at that device, but still at its neighbour");
            var neighbourRules = run.colony.storeRules(run.other);
            helper.assertTrue(StoreRule.takes(neighbourRules) && StoreRule.stores(neighbourRules),
                "transfers at the neighbouring device keep their own permissions");
            run.set(device -> whitelist(device.toggled("take").toggled("put"), "wheat"), run.at);
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
            var first = tally.ticket(run.colony.id(), depot.network(), ItemSpec.of(Items.OAK_PLANKS),
                4, depot.executors());
            first.limit(depot.taskLimit());
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
            var ticket = depot.tally().ticket(run.colony.id(), depot.network(),
                ItemSpec.of(Items.OAK_PLANKS), 4, depot.executors());
            ticket.limit(depot.taskLimit());
            helper.assertTrue(depot.taskLimit() == 0 && ticket.take().filter(NetworkRefusal.LIMIT::equals).isPresent(),
                "a share of none stops the colony ordering network crafts");
        }).thenSucceed();
    }

}
