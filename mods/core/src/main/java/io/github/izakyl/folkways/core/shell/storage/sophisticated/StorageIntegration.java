package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.shell.storage.ProxyPort;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.p3pp3rf1y.sophisticatedstorage.block.ControllerBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageIOBlockEntity;

public final class StorageIntegration {

    private StorageIntegration() {
    }

    public static void install() {
        SophisticatedWorkstations.install("sophisticated_storage", (level, pos) ->
            level.getBlockEntity(pos) instanceof StorageBlockEntity storage
                ? Optional.of(storage.getStorageWrapper()) : Optional.empty());
        StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "sophisticated_storage"),
            StorePorts.SPECIFIC, (level, pos) -> {
                var entity = level.getBlockEntity(pos);
                if (entity instanceof StorageBlockEntity storage) {
                    return Optional.of(SophisticatedPort.create(level, pos, storage.getStorageWrapper(),
                        storage::isLocked));
                }
                ControllerBlockEntity controller = entity instanceof ControllerBlockEntity own ? own : null;
                if (entity instanceof StorageIOBlockEntity io) {
                    controller = io.getControllerPos().filter(level::isLoaded)
                        .map(level::getBlockEntity).filter(ControllerBlockEntity.class::isInstance)
                        .map(ControllerBlockEntity.class::cast).orElse(null);
                } else if (controller == null) {
                    return Optional.empty();
                }
                List<WorldPos> members = controller == null ? List.of() : controller.getStoragePositions().stream()
                    .map(member -> WorldPos.of(level, member)).toList();
                return Optional.of(new ProxyPort(WorldPos.of(level, pos), members, controller != null));
            });
    }
}
