package io.github.izakyl.folkways.core.shell.toms;

import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import com.tom.storagemod.block.AbstractStorageTerminalBlock.TerminalPos;
import com.tom.storagemod.block.entity.InventoryCableConnectorBlockEntity;
import com.tom.storagemod.block.entity.InventoryConnectorBlockEntity;
import com.tom.storagemod.block.entity.StorageTerminalBlockEntity;
import com.tom.storagemod.inventory.BlockFilter;
import com.tom.storagemod.inventory.IInventoryAccess;
import com.tom.storagemod.inventory.MultiInventoryAccess;
import com.tom.storagemod.inventory.NetworkInventory;
import com.tom.storagemod.inventory.PlatformFilteredInventoryAccess;
import com.tom.storagemod.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.shell.mixin.TomsInventoryAccessor;
import io.github.izakyl.folkways.core.shell.storage.NativeCells;
import io.github.izakyl.folkways.core.shell.storage.ProxyPort;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class TomsIntegration {

    private static final Set<String> REFUSED = Set.of("inventory_proxy", "level_emitter", "inventory_interface",
        "basic_inventory_hopper", "open_crate", "filing_cabinet");

    private TomsIntegration() {
    }

    public static void install() {
        StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "toms_storage"),
            StorePorts.SPECIFIC, TomsIntegration::at);
    }

    private static Optional<StorePort> at(ServerLevel level, BlockPos pos) {
        WorldPos home = WorldPos.of(level, pos);
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        if (!id.getNamespace().equals("toms_storage")) {
            return Optional.empty();
        }
        if (REFUSED.contains(id.getPath())) {
            return Optional.of(NativeCells.refused(home,
                id.getPath().equals("filing_cabinet") ? "filtered" : "buffer"));
        }
        var entity = level.getBlockEntity(pos);
        IInventoryAccess access;
        if (entity instanceof InventoryConnectorBlockEntity connector) {
            access = connector.getMergedHandler();
        } else if (entity instanceof InventoryCableConnectorBlockEntity cable) {
            if (cable.hasBeacon()) {
                return Optional.of(NativeCells.refused(home, "remote"));
            }
            access = cable.getMergedHandler();
        } else if (entity instanceof StorageTerminalBlockEntity terminal) {
            var state = terminal.getBlockState();
            Direction facing = state.getValue(AbstractStorageTerminalBlock.FACING);
            TerminalPos placement = state.getValue(AbstractStorageTerminalBlock.TERMINAL_POS);
            if (placement == TerminalPos.UP) {
                facing = Direction.UP;
            } else if (placement == TerminalPos.DOWN) {
                facing = Direction.DOWN;
            }
            NetworkInventory inventory = new NetworkInventory();
            inventory.onLoad(level, pos.relative(facing), facing.getOpposite(), terminal);
            inventory.scanNetwork(level, pos);
            access = inventory.getAccess(level, pos);
        } else {
            return Optional.empty();
        }
        if (BlockFilter.getFilterAt(level, pos) != null) {
            return Optional.of(NativeCells.refused(home, "filtered"));
        }
        if (!(access instanceof MultiInventoryAccess merged)) {
            return Optional.of(NativeCells.refused(home, "disconnected"));
        }
        List<WorldPos> members = new ArrayList<>();
        for (IInventoryAccess member : merged.getConnected()) {
            if (member instanceof PlatformFilteredInventoryAccess) {
                return Optional.of(NativeCells.refused(home, "filtered"));
            }
            if (!(member instanceof BlockInventoryAccess block)) {
                return Optional.of(NativeCells.refused(home, "unmapped"));
            }
            var cache = ((TomsInventoryAccessor) block).folkways$inventory();
            if (cache == null || cache.level() != level) {
                return Optional.of(NativeCells.refused(home, "remote"));
            }
            members.add(WorldPos.of(level, cache.pos()));
        }
        return Optional.of(new ProxyPort(home, members, !members.isEmpty()));
    }
}
