package io.github.izakyl.folkways.core.shell.colossalchests;

import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.shell.storage.CellPort;
import io.github.izakyl.folkways.core.shell.storage.NativeCells;
import io.github.izakyl.folkways.core.shell.storage.StorageTraits;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.cyclops.colossalchests.GeneralConfig;
import org.cyclops.colossalchests.block.ColossalChest;
import org.cyclops.colossalchests.block.ChestMaterial;
import org.cyclops.colossalchests.block.IBlockChestMaterial;
import org.cyclops.colossalchests.blockentity.BlockEntityColossalChest;
import org.cyclops.colossalchests.blockentity.BlockEntityUncolossalChest;

public final class ChestsIntegration {

    private record ChestKey(WorldPos core, BlockEntityColossalChest entity) {
    }

    private ChestsIntegration() {
    }

    public static void install() {
        Ports.registerItem(ResourceLocation.fromNamespaceAndPath("folkways_core", "colossal_chests"),
            Ports.SPECIFIC, ChestsIntegration::at);
    }

    private static Optional<ItemPort> at(ServerLevel level, BlockPos pos) {
        WorldPos home = WorldPos.of(level, pos);
        if (level.getBlockEntity(pos) instanceof BlockEntityUncolossalChest chest) {
            return Optional.of(new CellPort(PortIdentity.block(home), NativeCells.container(chest.getInventory()),
                () -> StorageTraits.PLAIN, true));
        }
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof IBlockChestMaterial material)) {
            return Optional.empty();
        }
        if (!state.hasProperty(ColossalChest.ENABLED) || !state.getValue(ColossalChest.ENABLED)) {
            return Optional.of(NativeCells.refused(home, "disconnected"));
        }
        BlockPos core = core(level, pos, material.getMaterial());
        if (core == null || !level.isLoaded(core)
            || !(level.getBlockEntity(core) instanceof BlockEntityColossalChest chest)
            || !chest.isStructureComplete()) {
            return Optional.of(NativeCells.refused(home, "disconnected"));
        }
        PortIdentity identity = new PortIdentity(new ChestKey(WorldPos.of(level, core), chest), home);
        return Optional.of(new CellPort(identity, NativeCells.container(chest.getInventory()), () -> {
            if (GeneralConfig.creativeChests) {
                return StorageTraits.source(net.minecraft.world.item.ItemStack.EMPTY);
            }
            return chest.isStructureComplete() && level.getBlockEntity(core) == chest
                && core.equals(core(level, pos, material.getMaterial()))
                ? StorageTraits.PLAIN : StorageTraits.unavailable("disconnected");
        }, true));
    }

    private static BlockPos core(ServerLevel level, BlockPos pos, ChestMaterial material) {
        BlockPos[] found = new BlockPos[1];
        var result = material.getChestDetector().detect(level, pos, null, (location, state) -> {
            if (!(state.getBlock() instanceof IBlockChestMaterial member) || member.getMaterial() != material) {
                return Component.translatable("folkways.storage.unusable.disconnected");
            }
            if (state.getBlock() instanceof ColossalChest) {
                found[0] = location.immutable();
            }
            return null;
        }, false);
        // Native ENABLED/size fields can remain stale after a command replaces a wall.
        return result.getError() == null ? found[0] : null;
    }
}
