package io.github.izakyl.folkways.plugins.person.look;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResidentModels {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-models");

    private static volatile ModelLibrary current = ModelLibrary.EMPTY;

    private ResidentModels() {
    }

    public static ModelLibrary current() {
        return current;
    }

    public static ModelLibrary reload(ResourceManager resources) {
        ModelLibrary library = ModelLibrary.scan(resources);
        current = library;
        ResidentLooks.installRuntime(library.entries());
        for (String line : library.rejected()) {
            LOGGER.warn("[folkways-models] {}", line);
        }
        if (!library.isEmpty() || !library.rejected().isEmpty()) {
            LOGGER.info("[folkways-models] {} entries, {} files from {}",
                library.entries().size(), library.assets().size(), ModelLibrary.FOLDER);
        }
        return library;
    }

    public static void forgetServer() {
        current = ModelLibrary.EMPTY;
        ResidentLooks.forgetRuntime();
    }

    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener((ResourceManagerReloadListener) ResidentModels::rescan);
    }

    private static void rescan(ResourceManager resources) {
        ModelLibrary library = reload(resources);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            ResidentModelPackets.offerToEveryone(server, library);
        }
    }
}
