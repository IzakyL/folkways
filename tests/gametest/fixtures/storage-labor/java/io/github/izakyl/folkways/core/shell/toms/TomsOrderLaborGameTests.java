package io.github.izakyl.folkways.core.shell.toms;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.colony.MemberToggle;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.toms.TomsOrders;
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
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// A resident carries what a terminal request ordered from another colony store into the Tom's network.
@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class TomsOrderLaborGameTests {

    private static final BlockPos STOCK = new BlockPos(2, 1, 6);
    private static final ResourceLocation INGOT = BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if ("toms".equals(System.getProperty("folkways.storage.profile"))) {
            event.register(TomsOrderLaborGameTests.class);
        }
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 1200)
    public static void tomsTerminalRequestIsDeliveredIntoTheNetwork(GameTestHelper helper) {
        for (int x = -5; x <= 15; x++) {
            for (int z = -5; z <= 10; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        new TomsRig(helper);
        helper.setBlock(STOCK, Blocks.CHEST);
        ServerLevel level = helper.getLevel();
        ((Container) level.getBlockEntity(helper.absolutePos(STOCK))).setItem(0, new ItemStack(Items.IRON_INGOT, 20));
        var player = FakePlayerFactory.getMinecraft(level);
        Colony colony = Charters.found(player, "toms delivery");
        WorkDefaults.set(colony, PersonBody.ID, Vocations.HAULING, true, 1);
        AtomicBoolean closed = new AtomicBoolean();
        LaborFixture labor = new LaborFixture(colony, level);
        var body = PersonBody.RESIDENT.get().create(level);
        Cleanup.on(helper, () -> {
            closed.set(true);
            labor.close();
            body.discard();
            colony.raze();
        });
        WorldPos terminal = WorldPos.of(level, helper.absolutePos(TomsRig.TERMINAL));
        helper.startSequence().thenIdle(45).thenExecute(() -> {
            for (BlockPos store : List.of(TomsRig.TERMINAL, STOCK)) {
                helper.assertTrue(MemberToggle.toggleAt(level, colony, helper.absolutePos(store), player)
                    .refusal().isEmpty(), "the book enrolls " + store);
            }
            body.moveTo(helper.absolutePos(new BlockPos(3, 1, 5)).getBottomCenter());
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            colony.entered(body);
            body.pack().setItem(0, new ItemStack(Items.COOKED_BEEF, 32));
            helper.onEachTick(() -> {
                if (!closed.get()) {
                    colony.inventory().ensureObserved();
                    labor.tick();
                }
            });
        }).thenIdle(40).thenExecute(() -> {
            var network = TomsOrders.network(level, terminal).orElseThrow();
            helper.assertTrue(!TomsOrders.place(network, INGOT, 8).refused(), "the request is filed");
        });
        helper.succeedWhen(() -> {
            var found = TomsOrders.network(level, terminal);
            helper.assertTrue(found.isPresent(), "the terminal is enrolled");
            var network = found.get();
            long arrived = network.members().stream().mapToLong(member -> Containers.at(level, member.block(level))
                .map(container -> Goods.countIn(container, ItemSpec.of(Items.IRON_INGOT))).orElse(0L)).sum();
            helper.assertTrue(arrived == 8, "eight ingots reach the network, not more: " + arrived);
            var requests = TomsOrders.requests(network);
            helper.assertTrue(requests.size() == 1 && requests.getFirst().status() == TomsOrders.Status.DELIVERED,
                "the terminal tells the request delivered: " + requests);
        });
    }
}
