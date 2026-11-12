package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.core.definitions.AEBlocks;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class NetworkPortGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(NetworkPortGameTests.class);
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void partitionedCellsDoNotPromiseRoomForOtherGoods(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        var drive = (appeng.blockentity.storage.DriveBlockEntity) helper.getLevel()
            .getBlockEntity(helper.absolutePos(NetworkRig.DRIVE));
        ItemStack cell = appeng.core.definitions.AEItems.ITEM_CELL_1K.stack();
        ((appeng.api.storage.cells.IBasicCellItem) cell.getItem()).getConfigInventory(cell)
            .setStack(0, new appeng.api.stacks.GenericStack(AEItemKey.of(Items.WHEAT), 1));
        drive.getInternalInventory().setItemDirect(0, cell);
        helper.runAfterDelay(100, () -> {
            var port = rig.port();
            var reader = port.reader(helper.getLevel(), List.of());
            reader.step(100);
            var where = io.github.izakyl.folkways.core.inventory.api.Stash.at(port.identity().home());
            var stock = io.github.izakyl.folkways.core.plan.internal.Stock.observed(List.of(),
                java.util.Map.of(where, reader.result().room()), java.util.Map.of(), java.util.Map.of());
            helper.assertTrue(stock.roomAt(where, ItemSpec.of(Items.IRON_INGOT)) == 0,
                "partitioned wheat capacity is never promised for iron");
            helper.assertTrue(!port.put(helper.getLevel(), List.of(new ItemStack(Items.IRON_INGOT)),
                    List.of(), false, true).done(), "native simulation remains the transfer gate");
            rig.stock(Items.WHEAT, 8);
            helper.succeed();
        });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void readsLongCountsAndTransfersWholeBatches(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        helper.runAfterDelay(100, () -> {
            rig.stock(Items.WHEAT, 300);
            rig.stock(Items.APPLE, 17);
            NetworkPort port = rig.port();
            helper.assertTrue(Devices.TAKE.location().toString().equals("folkways_core:network_access/take")
                    && Devices.PUT.location().toString().equals("folkways_core:network_access/put")
                    && Devices.CRAFT.location().toString().equals("folkways_core:network_access/craft")
                    && port.operations().size() == 2 && port.crafts(),
                "network device tags grant take, put and craft capabilities");
            var reading = port.reader(helper.getLevel(), List.of());
            helper.assertTrue(!reading.step(1), "one key budget does not read two kinds at once");
            helper.assertTrue(reading.step(1), "second key finishes the bounded reading");
            helper.assertTrue(reading.result().entries().stream().anyMatch(entry -> entry.count() == 300)
                    && reading.result().slots().isEmpty() && reading.result().room() instanceof Room.Pool pool
                    && pool.perKey().isPresent(),
                "network stock has long counts and pooled room, without invented slots");
            List<Pull> batch = List.of(new Pull.Matching(ItemSpec.of(Items.WHEAT), 200),
                new Pull.Exact(new ItemStack(Items.APPLE, 8)));
            helper.assertTrue(port.take(helper.getLevel(), batch, List.of(), true).done()
                    && rig.count(Items.WHEAT) == 300, "simulation leaves the real network intact");
            helper.assertTrue(port.take(helper.getLevel(), batch, List.of(), false).done()
                    && rig.count(Items.WHEAT) == 100 && rig.count(Items.APPLE) == 9, "whole batch is extracted");
            List<Pull> missing = List.of(new Pull.Exact(new ItemStack(Items.APPLE)),
                new Pull.Exact(new ItemStack(Items.DIAMOND)));
            helper.assertTrue(!port.take(helper.getLevel(), missing, List.of(), false).done()
                    && rig.count(Items.APPLE) == 9, "a missing second item does not extract the first");
            List<Pull> repeated = List.of(new Pull.Matching(ItemSpec.of(Items.WHEAT), 70),
                new Pull.Matching(ItemSpec.of(Items.WHEAT), 70));
            helper.assertTrue(!port.take(helper.getLevel(), repeated, List.of(), false).done()
                    && rig.count(Items.WHEAT) == 100, "overlapping requests cannot spend the same stock twice");
            List<ItemStack> goods = List.of(new ItemStack(Items.WHEAT, 12), new ItemStack(Items.APPLE, 4));
            helper.assertTrue(port.put(helper.getLevel(), goods, List.of(), false, true).done()
                    && rig.count(Items.WHEAT) == 100, "whole insertion is simulated without mutation");
            helper.assertTrue(port.put(helper.getLevel(), goods, List.of(), false, false).done()
                    && rig.count(Items.WHEAT) == 112 && rig.count(Items.APPLE) == 13, "whole batch is inserted");
            var rules = List.<StoreRule>of(new StoreRule.Access(port.identity().home(), true, false));
            helper.assertTrue(!port.put(helper.getLevel(), goods, rules, false, false).done()
                    && port.put(helper.getLevel(), goods, rules, true, false).done(),
                "directed insertion bypasses general-storage permissions");
            helper.succeed();
        });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void fullNetworkRejectsWholeInsertion(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        helper.runAfterDelay(100, () -> {
            var port = rig.port();
            port.storage().insert(AEItemKey.of(Items.WHEAT), Long.MAX_VALUE, Actionable.MODULATE,
                Devices.source(port.node()));
            long before = rig.count(Items.WHEAT);
            List<ItemStack> goods = List.of(new ItemStack(Items.WHEAT), new ItemStack(Items.DIAMOND));
            helper.assertTrue(!port.put(helper.getLevel(), goods, List.of(), false, true).done()
                    && !port.put(helper.getLevel(), goods, List.of(), false, false).done()
                    && rig.count(Items.WHEAT) == before && rig.count(Items.DIAMOND) == 0,
                "a full cell leaves every item in the caller's hands");
            helper.succeed();
        });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void identityChangesOnSplitAndPowerFailureIsTransient(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        helper.runAfterDelay(100, () -> {
            Object before = rig.port().identity().key();
            Object other = Devices.at(helper.getLevel(), helper.absolutePos(NetworkRig.OTHER))
                .orElseThrow().identity().key();
            helper.assertTrue(before.equals(other) && before.equals(rig.port().identity().key()),
                "multiple devices have equal stable network identities");
            helper.setBlock(NetworkRig.DRIVE, Blocks.AIR);
            helper.runAfterDelay(40, () -> {
                NetworkPort isolated = Devices.at(helper.getLevel(), helper.absolutePos(NetworkRig.OTHER))
                    .orElseThrow();
                helper.assertTrue(!before.equals(isolated.identity().key()),
                    "splitting changes the surviving grid key");
                helper.assertTrue(isolated.usability() instanceof Usability.Unusable unusable && !unusable.lasting(),
                    "an unpowered network is temporarily unusable");
                helper.setBlock(NetworkRig.DRIVE, AEBlocks.CREATIVE_ENERGY_CELL.block());
                helper.runAfterDelay(80, () -> {
                    helper.assertTrue(Devices.at(helper.getLevel(), helper.absolutePos(NetworkRig.OTHER))
                        .orElseThrow().usability().usable(), "power restoration lifts the transient verdict");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void meChestSharesTheTerminalNetworkIdentity(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        BlockPos chest = new BlockPos(2, 2, 1);
        helper.setBlock(chest, AEBlocks.ME_CHEST.block());
        helper.runAfterDelay(100, () -> {
            NetworkPort port = Devices.at(helper.getLevel(), helper.absolutePos(chest)).orElseThrow();
            helper.assertTrue(port.usability().usable() && port.identity().key().equals(rig.port().identity().key()),
                "a tagged ME chest exposes its connected network, not a separate copy of its cell stock");
            helper.succeed();
        });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core")
    public static void interfacesCannotFallThroughToGenericHandlers(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 2, 2);
        helper.setBlock(pos, AEBlocks.INTERFACE.block());
        var port = StorePorts.at(helper.getLevel(), helper.absolutePos(pos)).orElseThrow();
        helper.assertTrue(!port.usability().usable() && port.operations().isEmpty(),
            "specific rejection protects interface replenishment buffers from fallback adapters");
        helper.succeed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core")
    public static void voidingStorageCannotAcknowledgeAConservingTransfer(GameTestHelper helper) {
        MEStorage voiding = new MEStorage() {
            @Override
            public Component getDescription() {
                return Component.literal("test voiding provider");
            }

            @Override
            public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
                return amount;
            }
        };
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var result = Transfers.put(helper.getLevel(), pos, voiding, IActionSource.empty(),
            List.of(new ItemStack(Items.DIAMOND)), new Room.Pool(0, 0), 0, false);
        helper.assertTrue(!result.done() && StorePorts.condemned(WorldPos.of(helper.getLevel(), pos)).isPresent(),
            "an acknowledged insertion must actually add unchanged goods, or the provider is condemned");
        helper.succeed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core")
    public static void unexpectedInsertionIsCompensatedAndCondemned(GameTestHelper helper) {
        KeyCounter contents = new KeyCounter();
        MEStorage storage = new MEStorage() {
            @Override
            public Component getDescription() {
                return Component.literal("test storage");
            }

            @Override
            public void getAvailableStacks(KeyCounter out) {
                out.addAll(contents);
            }

            @Override
            public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
                if (mode == Actionable.MODULATE) {
                    if (what.equals(AEItemKey.of(Items.APPLE))) {
                        return 0;
                    }
                    contents.add(what, amount);
                }
                return amount;
            }

            @Override
            public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
                long taken = Math.min(contents.get(what), amount);
                if (mode == Actionable.MODULATE) {
                    contents.remove(what, taken);
                }
                return taken;
            }
        };
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        TransferResult result = Transfers.put(helper.getLevel(), pos, storage, IActionSource.empty(),
            List.of(new ItemStack(Items.WHEAT, 3), new ItemStack(Items.APPLE, 2)), new Room.Pool(100, 63), 0, false);
        helper.assertTrue(!result.done() && contents.get(AEItemKey.of(Items.WHEAT)) == 0
                && contents.get(AEItemKey.of(Items.APPLE)) == 0
                && StorePorts.condemned(WorldPos.of(helper.getLevel(), pos)).isPresent(),
            "a second-key discrepancy rolls back the first and persists a verdict");
        helper.succeed();
    }
}
