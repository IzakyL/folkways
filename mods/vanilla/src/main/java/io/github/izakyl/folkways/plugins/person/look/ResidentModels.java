package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.library.Shelves;
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
        for (String line : library.skinsRejected()) {
            LOGGER.warn("[folkways-models] {}", line);
        }
        report(library);
        if (!library.isEmpty() || !library.rejected().isEmpty()) {
            LOGGER.info("[folkways-models] {} entries, {} files from {}",
                library.entries().size(), library.assets().size(), ModelLibrary.FOLDER);
        }
        return library;
    }

    private static void report(ModelLibrary library) {
        Shelves.Stock.Builder models = Shelves.Stock.builder().rejected(library.rejected());
        Shelves.Stock.Builder skins = Shelves.Stock.builder().rejected(library.skinsRejected());
        library.packs().forEach((id, pack) -> (id.getPath().startsWith("skin/") ? skins : models).entry(id, pack));
        ModelLibrary.SHELF.report(models.build());
        ModelLibrary.SKINS.report(skins.build());
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
