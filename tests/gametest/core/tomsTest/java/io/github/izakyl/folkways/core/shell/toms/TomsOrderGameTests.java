package io.github.izakyl.folkways.core.shell.toms;

import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.menu.StorageTerminalMenu;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.colony.MemberToggle;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.orders.Orders;
import io.github.izakyl.folkways.vanilla.toms.TomsOrderNetwork;
import io.github.izakyl.folkways.vanilla.toms.TomsOrders;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// Requests at a Tom's terminal are ordinary once orders into one of the network's member containers.
@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class TomsOrderGameTests {

    private static final BlockPos STOCK = new BlockPos(2, 1, 5);
    private static final BlockPos FURNACE = new BlockPos(4, 1, 5);
    private static final ResourceLocation INGOT = BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT);
    private static final ResourceLocation GLASS = BuiltInRegistries.ITEM.getKey(Items.GLASS);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        // The order book belongs to Vanilla; the native-only run has nothing to order with.
        if (ModList.get().isLoaded("folkways_vanilla")) {
            event.register(TomsOrderGameTests.class);
        }
    }

    private record Rig(Colony colony, FakePlayer player, WorldPos terminal) {
    }

    private static Rig rig(GameTestHelper helper) {
        new TomsRig(helper);
        helper.setBlock(STOCK, Blocks.CHEST);
        helper.setBlock(FURNACE, Blocks.FURNACE);
        ServerLevel level = helper.getLevel();
        ((Container) level.getBlockEntity(helper.absolutePos(STOCK))).setItem(0, new ItemStack(Items.IRON_INGOT, 20));
        FakePlayer player = FakePlayerFactory.getMinecraft(level);
        Colony colony = Charters.found(player, "toms orders");
        AtomicBoolean closed = new AtomicBoolean();
        LaborFixture labor = new LaborFixture(colony, level);
        Cleanup.on(helper, () -> {
            closed.set(true);
            labor.close();
            player.containerMenu = player.inventoryMenu;
            colony.raze();
        });
        helper.onEachTick(() -> {
            if (!closed.get()) {
                colony.inventory().ensureObserved();
                labor.tick();
            }
        });
        return new Rig(colony, player, WorldPos.of(level, helper.absolutePos(TomsRig.TERMINAL)));
    }

    // Once the native network has settled, as a player would find it.
    private static void enroll(GameTestHelper helper, Rig rig) {
        ServerLevel level = helper.getLevel();
        for (BlockPos store : List.of(TomsRig.TERMINAL, STOCK)) {
            var attempt = MemberToggle.toggleAt(level, rig.colony(), helper.absolutePos(store), rig.player());
            helper.assertTrue(attempt.refusal().isEmpty(), "the book enrolls " + store + ": " + attempt);
        }
        rig.colony().hold(WaresContent.STATION, new Claimed.Block(WorldPos.of(level, helper.absolutePos(FURNACE))))
            .orElseThrow();
    }

    private static TomsOrders.Network network(GameTestHelper helper, Rig rig) {
        return TomsOrders.network(helper.getLevel(), rig.terminal()).orElseThrow(
            () -> new AssertionError("the registered terminal is a colony network"));
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void terminalRequestIsOneOnceOrderIntoTheNetwork(GameTestHelper helper) {
        Rig rig = rig(helper);
        helper.startSequence().thenIdle(45).thenExecute(() -> enroll(helper, rig)).thenIdle(60).thenExecute(() -> {
            var network = network(helper, rig);
            helper.assertTrue(network.colony().id().equals(rig.colony().id()), "the colony holding the terminal");
            var offers = TomsOrders.offers(network);
            var ingot = offers.stream().filter(offer -> offer.item().equals(INGOT)).findFirst()
                .orElseThrow(() -> new AssertionError("iron ingots are orderable: " + offers));
            helper.assertTrue(ingot.elsewhere() == 20 && ingot.here() == 0 && ingot.made(),
                "ingots lie in another store and the furnace smelts them: " + ingot);
            helper.assertTrue(offers.stream().anyMatch(offer -> offer.item().equals(GLASS) && offer.made()
                && offer.elsewhere() == 0), "smelted goods with no stock anywhere are orderable: " + offers);
            helper.assertTrue(TomsOrders.place(network, BuiltInRegistries.ITEM.getKey(Items.ELYTRA), 1).refused(),
                "goods the colony neither makes nor holds are refused");

            helper.assertTrue(!TomsOrders.place(network, INGOT, 5).refused(), "first request is filed");
            helper.assertTrue(!TomsOrders.place(network, INGOT, 3).refused(), "second request adds to it");
            List<Orders.Target> targets = Orders.targets(rig.colony());
            helper.assertTrue(targets.size() == 1, "one order for one item: " + targets);
            Orders.Target target = targets.getFirst();
            helper.assertTrue(network.members().contains(target.into()) && !target.standing() && target.high() == 8,
                "a once order for eight into a member of the network: " + target);
            var requests = TomsOrders.requests(network);
            helper.assertTrue(requests.size() == 1 && requests.getFirst().item().equals(INGOT)
                    && requests.getFirst().asked() == 8 && requests.getFirst().high() == 8
                    && requests.getFirst().status() != TomsOrders.Status.DELIVERED,
                "the terminal reads the order back as a pending request: " + requests);

            helper.assertTrue(!TomsOrders.cancel(network, INGOT).refused(), "the request is cancelled");
            helper.assertTrue(Orders.targets(rig.colony()).isEmpty(), "cancelling withdraws the colony order");
            helper.assertTrue(TomsOrders.requests(network).isEmpty(), "and the request with it, not told delivered");
            helper.assertTrue(TomsOrders.cancel(network, INGOT).refused(), "nothing left to cancel");
        }).thenSucceed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void openTerminalMenuOrdersThroughThePayloadPath(GameTestHelper helper) {
        Rig rig = rig(helper);
        helper.startSequence().thenIdle(45).thenExecute(() -> enroll(helper, rig)).thenIdle(60).thenExecute(() -> {
            var closed = TomsOrderNetwork.answer(rig.player(), new TomsOrderNetwork.Ask(TomsOrderNetwork.Action.OPEN,
                "", 0));
            helper.assertTrue(!closed.open(), "without a terminal open there is no board");
            var terminal = (StorageTerminalBlockEntity) helper.getLevel().getBlockEntity(
                helper.absolutePos(TomsRig.TERMINAL));
            rig.player().containerMenu = new StorageTerminalMenu(1, rig.player().getInventory(), terminal);
            var peek = TomsOrderNetwork.answer(rig.player(), new TomsOrderNetwork.Ask(TomsOrderNetwork.Action.PEEK,
                "", 0));
            helper.assertTrue(peek.open() && peek.offers().isEmpty(), "a peek only says the button belongs here");
            var open = TomsOrderNetwork.answer(rig.player(), new TomsOrderNetwork.Ask(TomsOrderNetwork.Action.OPEN,
                "", 0));
            helper.assertTrue(open.open() && open.offers().stream().anyMatch(offer -> offer.item().equals(GLASS)),
                "the board lists smelted goods: " + open.offers());
            var placed = TomsOrderNetwork.answer(rig.player(), new TomsOrderNetwork.Ask(TomsOrderNetwork.Action.PLACE,
                GLASS.toString(), 64));
            helper.assertTrue(placed.went() && placed.requests().size() == 1
                    && placed.requests().getFirst().item().equals(GLASS) && placed.requests().getFirst().asked() == 64,
                "placing answers with the pending request: " + placed.requests() + " " + placed.said());
            var tooMany = TomsOrderNetwork.answer(rig.player(), new TomsOrderNetwork.Ask(TomsOrderNetwork.Action.PLACE,
                GLASS.toString(), 9999));
            helper.assertTrue(!tooMany.went() && tooMany.said().isPresent(), "an order past the cap says why");
            var cancelled = TomsOrderNetwork.answer(rig.player(), new TomsOrderNetwork.Ask(
                TomsOrderNetwork.Action.CANCEL, GLASS.toString(), 0));
            helper.assertTrue(cancelled.went() && cancelled.requests().isEmpty()
                && Orders.targets(rig.colony()).isEmpty(), "cancelling from the board withdraws the order");
        }).thenSucceed();
    }
}
