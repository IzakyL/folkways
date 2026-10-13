package io.github.izakyl.folkways.plugins.person.look;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
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
        ResidentLooks.installRuntime(looks(library));
        for (String line : library.rejected()) {
            LOGGER.warn("[folkways-models] {}", line);
        }
        if (!library.isEmpty() || !library.rejected().isEmpty()) {
            LOGGER.info("[folkways-models] {} entries, {} files from {}",
                library.entries().size(), library.assets().size(), ModelLibrary.FOLDER);
        }
        return library;
    }

    // Every look that is not a data pack's: the models in the folder, and the maids of an installed mod.
    public static Map<ResourceLocation, ResidentLook> looks(ModelLibrary library) {
        Map<ResourceLocation, ResidentLook> looks = new LinkedHashMap<>(MaidLooks.entries());
        looks.putAll(library.entries());
        return looks;
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
