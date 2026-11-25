package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Room;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyStocks;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.shell.storage.ProxyPort;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

public final class ExternalStorageChecks {

    private static final ResourceLocation OWNER = ResourceLocation.parse("folkways_core:external_storage_test");
    private static final ItemStack WHEAT = new ItemStack(Items.WHEAT, 7);

    private static final Map<WorldPos, List<WorldPos>> PROXIES = new HashMap<>();

    private final GameTestHelper helper;
    private final Colony colony;
    private final BlockPos chest;
    private final WorldPos store;

    public ExternalStorageChecks(GameTestHelper helper, BlockPos chest, BlockPos terminal) {
        this.helper = helper;
        helper.setBlock(chest, Blocks.CHEST);
        this.chest = helper.absolutePos(chest);
        var level = helper.getLevel();
        Containers.at(level, this.chest).orElseThrow().setItem(0, WHEAT.copy());
        store = WorldPos.of(level, this.chest);
        var device = WorldPos.of(level, helper.absolutePos(terminal));
        colony = enroll(helper, List.of(store, device));
    }

    public static Colony enroll(GameTestHelper helper, List<WorldPos> points) {
        var level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        Cleanup.on(helper, colony::raze);
        points.forEach(at -> colony.hold(OWNER, new Claimed.Block(at)).orElseThrow());
        colony.stores(OWNER, level.dimension(), points);
        return colony;
    }

    public static void registerProxyPort() {
        Ports.registerItem(OWNER, Ports.SPECIFIC, (level, pos) -> {
            WorldPos at = WorldPos.of(level, pos);
            return Optional.ofNullable(PROXIES.get(at)).map(members -> new ProxyPort(at, members, true));
        });
    }

    public static void registerProxyHandler(RegisterCapabilitiesEvent event) {
        event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, entity, side) -> {
            if (!(level instanceof ServerLevel server)) {
                return null;
            }
            var members = PROXIES.get(WorldPos.of(server, pos));
            return members == null ? null : new CombinedInvWrapper(members.stream()
                .map(at -> new InvWrapper(Containers.at(level, at.block(server)).orElseThrow()))
                .toArray(InvWrapper[]::new));
        }, Blocks.LODESTONE);
    }

    public static Colony proxy(GameTestHelper helper, BlockPos attachment, BlockPos terminal, Runnable attach) {
        var level = helper.getLevel();
        BlockPos one = attachment.west();
        BlockPos two = attachment.east();
        helper.setBlock(one, Blocks.CHEST);
        helper.setBlock(two, Blocks.CHEST);
        helper.setBlock(attachment, Blocks.LODESTONE);
        WorldPos first = WorldPos.of(level, helper.absolutePos(one));
        WorldPos second = WorldPos.of(level, helper.absolutePos(two));
        WorldPos controller = WorldPos.of(level, helper.absolutePos(attachment));
        WorldPos device = WorldPos.of(level, helper.absolutePos(terminal));
        Containers.at(level, first.block(level)).orElseThrow().setItem(0, WHEAT.copy());
        Containers.at(level, second.block(level)).orElseThrow().setItem(0, WHEAT.copy());
        PROXIES.put(controller, List.of(first, second));
        Cleanup.on(helper, () -> PROXIES.remove(controller));
        var colony = enroll(helper, List.of(first, second, controller, device));
        helper.runAfterDelay(40, () -> {
            attach.run();
            helper.runAfterDelay(100, () -> {
                InventoryObservations.publish(colony, level);
                for (WorldPos at : List.of(first, second, controller)) {
                    helper.assertTrue(StoreAccess.at(level, at.block(level)).isEmpty()
                        && colony.inventory().snapshot().store(at).isEmpty(), "attached proxy owns " + at);
                }
                assertStock(helper, colony, 2L * WHEAT.getCount());
                PROXIES.put(controller, List.of(first));
                level.invalidateCapabilities(controller.block(level));
                helper.runAfterDelay(100, () -> {
                    InventoryObservations.publish(colony, level);
                    helper.assertTrue(StoreAccess.candidate(level, second.block(level))
                        && colony.inventory().snapshot().store(second).filter(entry -> entry.usability().usable())
                            .isPresent(), "a removed member becomes an ordinary store again");
                    assertStock(helper, colony, 2L * WHEAT.getCount());
                    helper.succeed();
                });
            });
        });
        return colony;
    }

    public static void assertStock(GameTestHelper helper, Colony colony, long wheat) {
        long stock = ColonyStocks.available(colony, helper.getLevel().getServer(), Goods.specOf(WHEAT));
        helper.assertTrue(stock == wheat, "expected " + wheat + " wheat in colony stock, found " + stock);
    }

    public Colony colony() {
        return colony;
    }

    public void attached(Room.Pool room) {
        long free = 27 * 64L - WHEAT.getCount();
        helper.assertTrue(room.items() == free && room.perKey().orElseThrow().get(Goods.specOf(WHEAT)) == free,
            "the network includes the chest's shared room and can insert wheat through its root: " + room.items());
        var level = helper.getLevel();
        InventoryObservations.publish(colony, level);
        helper.assertTrue(!StoreAccess.candidate(level, chest) && StoreAccess.at(level, chest).isEmpty()
            && colony.inventory().snapshot().store(store).isEmpty(), "the attached enrolled chest is suppressed");
        assertStock(helper, colony, WHEAT.getCount());
    }

    public void detached() {
        var level = helper.getLevel();
        InventoryObservations.publish(colony, level);
        helper.assertTrue(StoreAccess.candidate(level, chest) && StoreAccess.at(level, chest).isPresent()
            && colony.inventory().snapshot().store(store).filter(entry -> entry.usability().usable()).isPresent(),
            "the original enrollment becomes an ordinary store when detached");
        assertStock(helper, colony, WHEAT.getCount());
    }
}
