package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.api.storage.StorageImpl;
import com.refinedmods.refinedstorage.common.content.Blocks;
import com.refinedmods.refinedstorage.common.storagemonitor.StorageMonitorBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.ResourceContainerImpl;
import com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.api.network.impl.node.storage.StorageNetworkNode;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void filteredDisksDoNotPromiseRoomForOtherGoods(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        helper.runAfterDelay(100, () -> {
            var port = rig.port();
            var storage = port.node().getNetwork().getComponent(
                com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent.class).getContainers().stream()
                .map(container -> container.getNode())
                .filter(StorageNetworkNode.class::isInstance)
                .map(StorageNetworkNode.class::cast)
                .findFirst().orElseThrow();
            var config = storage.getStorageConfiguration();
            config.setFilters(java.util.Set.of(new ItemResource(Items.WHEAT)));
            config.setFilterMode(com.refinedmods.refinedstorage.api.resource.filter.FilterMode.ALLOW);
            var where = io.github.izakyl.folkways.core.inventory.api.Stash.at(port.identity().home());
            var room = port.room();
            var stock = io.github.izakyl.folkways.core.plan.internal.Stock.observed(List.of(),
                java.util.Map.of(where, room), java.util.Map.of(), java.util.Map.of());
            helper.assertTrue(stock.roomAt(where, ItemSpec.of(Items.IRON_INGOT)) == 0
                    && stock.roomAt(where, ItemSpec.of(Items.WHEAT)) > 0,
                "allow-filtered disk room is promised only for accepted goods");
            config.setFilterMode(com.refinedmods.refinedstorage.api.resource.filter.FilterMode.BLOCK);
            stock = io.github.izakyl.folkways.core.plan.internal.Stock.observed(List.of(),
                java.util.Map.of(where, port.room()), java.util.Map.of(), java.util.Map.of());
            helper.assertTrue(stock.roomAt(where, ItemSpec.of(Items.WHEAT)) == 0
                    && stock.roomAt(where, ItemSpec.of(Items.IRON_INGOT)) > 0,
                "block-filtered disk room rejects the blocked goods");
            helper.succeed();
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
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

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void fullNetworkRejectsWholeInsertion(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        helper.runAfterDelay(100, () -> {
            var port = rig.port();
            port.storage().insert(new ItemResource(Items.WHEAT), Long.MAX_VALUE, Action.EXECUTE,
                Devices.source(port.node()));
            long before = rig.count(Items.WHEAT);
            helper.assertTrue(before == 1024, "the physical disk is filled to its actual capacity");
            List<ItemStack> goods = List.of(new ItemStack(Items.WHEAT), new ItemStack(Items.DIAMOND));
            helper.assertTrue(!port.put(helper.getLevel(), goods, List.of(), false, true).done()
                    && !port.put(helper.getLevel(), goods, List.of(), false, false).done()
                    && rig.count(Items.WHEAT) == before && rig.count(Items.DIAMOND) == 0,
                "a full cell leaves every item in the caller's hands");
            helper.succeed();
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void identityChangesOnSplitAndPowerFailureIsTransient(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        helper.runAfterDelay(100, () -> {
            Object before = rig.port().identity().key();
            Object other = Devices.at(helper.getLevel(), helper.absolutePos(NetworkRig.OTHER))
                .orElseThrow().identity().key();
            helper.assertTrue(before.equals(other) && before.equals(rig.port().identity().key()),
                "multiple devices have equal stable network identities");
            helper.setBlock(NetworkRig.CABLE, net.minecraft.world.level.block.Blocks.AIR);
            helper.runAfterDelay(40, () -> {
                NetworkPort isolated = Devices.at(helper.getLevel(), helper.absolutePos(NetworkRig.OTHER))
                    .orElseThrow();
                helper.assertTrue(!before.equals(isolated.identity().key()),
                    "splitting changes the surviving grid key");
                helper.assertTrue(isolated.usability() instanceof Usability.Unusable unusable && !unusable.lasting(),
                    "an unpowered network is temporarily unusable");
                helper.setBlock(NetworkRig.CABLE, Blocks.INSTANCE.getCable().getDefault());
                helper.runAfterDelay(80, () -> {
                    helper.assertTrue(Devices.at(helper.getLevel(), helper.absolutePos(NetworkRig.OTHER))
                        .orElseThrow().usability().usable(), "power restoration lifts the transient verdict");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void interfacesCannotFallThroughToGenericHandlers(GameTestHelper helper) {
        for (var block : List.of(Blocks.INSTANCE.getInterface(), Blocks.INSTANCE.getDiskInterface().getDefault())) {
            BlockPos pos = new BlockPos(2, 2, 2);
            helper.setBlock(pos, block);
            var port = StorePorts.at(helper.getLevel(), helper.absolutePos(pos)).orElseThrow();
            helper.assertTrue(!port.usability().usable() && port.operations().isEmpty(),
                "specific rejection protects automatic buffers from fallback adapters");
        }
        helper.succeed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void voidingStorageCannotAcknowledgeAConservingTransfer(GameTestHelper helper) {
        Storage voiding = new StorageImpl() {
            @Override
            public long insert(ResourceKey what, long amount, Action mode, Actor actor) {
                return amount;
            }
        };
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var result = Transfers.put(helper.getLevel(), pos, voiding, Actor.EMPTY,
            List.of(new ItemStack(Items.DIAMOND)), new Room.Pool(0, 0), false);
        helper.assertTrue(!result.done() && StorePorts.condemned(WorldPos.of(helper.getLevel(), pos)).isPresent(),
            "an acknowledged insertion must actually add unchanged goods, or the provider is condemned");
        helper.succeed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void unexpectedInsertionIsCompensatedAndCondemned(GameTestHelper helper) {
        Storage storage = new StorageImpl() {
            @Override
            public long insert(ResourceKey what, long amount, Action mode, Actor actor) {
                if (mode == Action.EXECUTE && what.equals(new ItemResource(Items.APPLE))) {
                    return 0;
                }
                return super.insert(what, amount, mode, actor);
            }
        };
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        TransferResult result = Transfers.put(helper.getLevel(), pos, storage, Actor.EMPTY,
            List.of(new ItemStack(Items.WHEAT, 3), new ItemStack(Items.APPLE, 2)), new Room.Pool(100, 63), false);
        helper.assertTrue(!result.done() && storage.getStored() == 0
                && StorePorts.condemned(WorldPos.of(helper.getLevel(), pos)).isPresent(),
            "a second-key discrepancy rolls back the first and persists a verdict");
        helper.succeed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void quantitiesBeyondIntegerRangeAndNativeEventsRemainExact(GameTestHelper helper) {
        long capacity = 1L << 34;
        long quantity = (long) Integer.MAX_VALUE + 500;
        NetworkRig rig = new NetworkRig(helper, capacity);
        helper.runAfterDelay(100, () -> {
            var port = rig.port();
            long revision = port.revision().orElseThrow();
            rig.stock(Items.WHEAT, quantity);
            var reading = port.reader(helper.getLevel(), List.of());
            helper.assertTrue(reading.step(1) && reading.result().entries().getFirst().count() == quantity,
                "counts above the integer range survive the native snapshot");
            Room.Pool room = (Room.Pool) reading.result().room();
            helper.assertTrue(room.items() == capacity - quantity && room.newKinds() == capacity - quantity,
                "LimitedStorage contributes exact remaining capacity");
            helper.assertTrue(port.revision().orElseThrow() > revision,
                "native RootStorage changes advance revision immediately without polling");
            helper.succeed();
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void configuredVoidingIsTransientAndCannotConsumeGoods(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        helper.runAfterDelay(100, () -> {
            var host = (AbstractNetworkNodeContainerBlockEntity<?>)
                helper.getLevel().getBlockEntity(helper.absolutePos(NetworkRig.DRIVE));
            var storage = (com.refinedmods.refinedstorage.api.network.impl.node.storage.StorageNetworkNode)
                host.getContainerProvider().getContainers().iterator().next().getNode();
            storage.getStorageConfiguration().setVoidExcess(true);
            var port = Devices.at(helper.getLevel(), helper.absolutePos(NetworkRig.TERMINAL)).orElseThrow();
            helper.assertTrue(port.usability() instanceof Usability.Unusable verdict && !verdict.lasting(),
                "void-excess configuration is rejected before any transfer");
            helper.assertTrue(!port.put(helper.getLevel(), List.of(new ItemStack(Items.DIAMOND)), List.of(),
                    true, false).done() && port.storage().getStored() == 0,
                "a directed order cannot bypass unsafe storage");
            storage.getStorageConfiguration().setVoidExcess(false);
            helper.assertTrue(port.usability().usable(), "removing void-excess lifts the transient verdict");
            helper.succeed();
        });
    }


    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void storageMonitorKeepsItsConfiguredItemRestriction(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        BlockPos pos = new BlockPos(6, 3, 2);
        helper.setBlock(pos, Blocks.INSTANCE.getStorageMonitor());
        var monitor = (StorageMonitorBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        helper.runAfterDelay(100, () -> {
            var port = Devices.at(helper.getLevel(), helper.absolutePos(pos)).orElseThrow();
            helper.assertTrue(!port.usability().usable(), "an unconfigured monitor cannot promise item access");
            var filter = ResourceContainerImpl.createForFilter(1);
            filter.change(0, new ItemStack(Items.WHEAT), false);
            var configuration = new net.minecraft.nbt.CompoundTag();
            configuration.put("rf", filter.toTag(helper.getLevel().registryAccess()));
            monitor.readConfiguration(configuration, helper.getLevel().registryAccess());
            helper.assertTrue(port.usability().usable() && port.crafts() && port.operations().size() == 2
                    && port.identity().key().equals(rig.port().identity().key()),
                "a configured interactive monitor shares Grid inventory and tagged capabilities");
            rig.stock(Items.WHEAT, 32);
            rig.stock(Items.APPLE, 10);
            helper.assertTrue(!port.take(helper.getLevel(), List.of(new Pull.Exact(new ItemStack(Items.APPLE))),
                    List.of(), false).done() && rig.count(Items.APPLE) == 10,
                "the monitor cannot extract unrelated goods");
            helper.assertTrue(!port.put(helper.getLevel(), List.of(new ItemStack(Items.APPLE)), List.of(), true, false)
                .done(), "even directed insertion retains the native item restriction");
            helper.assertTrue(port.take(helper.getLevel(), List.of(new Pull.Exact(new ItemStack(Items.WHEAT, 4))),
                    List.of(), false).done() && rig.count(Items.WHEAT) == 28,
                "the configured item can be taken at the monitor");
            helper.succeed();
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void extractionExceptionRestoresEveryEarlierKey(GameTestHelper helper) {
        Storage storage = new StorageImpl() {
            @Override
            public long extract(ResourceKey what, long amount, Action mode, Actor actor) {
                if (mode == Action.EXECUTE && what.equals(new ItemResource(Items.APPLE))) {
                    throw new IllegalStateException("test extraction failure");
                }
                return super.extract(what, amount, mode, actor);
            }
        };
        storage.insert(new ItemResource(Items.WHEAT), 10, Action.EXECUTE, Actor.EMPTY);
        storage.insert(new ItemResource(Items.APPLE), 10, Action.EXECUTE, Actor.EMPTY);
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var result = Transfers.take(helper.getLevel(), pos, storage, Actor.EMPTY,
            List.of(new Pull.Exact(new ItemStack(Items.WHEAT, 3)), new Pull.Exact(new ItemStack(Items.APPLE, 2))),
            item -> true, false);
        helper.assertTrue(!result.done() && storage.getStored() == 20
                && StorePorts.condemned(WorldPos.of(helper.getLevel(), pos)).isPresent(),
            "an execution exception restores the extracted first key and condemns the device");
        helper.succeed();
    }

}
