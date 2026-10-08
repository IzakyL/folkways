package io.github.izakyl.folkways.plugins.build.draft;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Templates {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    public static final String FOLDER = "folkways/piece";
    private static final String SUFFIX = ".nbt";

    private static volatile Map<ResourceLocation, Piece> library = Map.of();
    private static volatile Map<ResourceLocation, CompoundTag> files = Map.of();

    private Templates() {
    }

    static Optional<Piece> find(ResourceLocation id) {
        return Optional.ofNullable(library.get(id));
    }

    public static Map<ResourceLocation, CompoundTag> files() {
        return files;
    }

    public static void acceptFromServer(Map<ResourceLocation, CompoundTag> offered) {
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            return;
        }
        adopt(offered);
        LOGGER.info("{} piece(s) offered by the server", library.size());
    }

    public static void reload(ResourceManager resources) {
        Map<ResourceLocation, CompoundTag> read = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> found =
            resources.listResources(FOLDER, path -> path.getPath().endsWith(SUFFIX));
        for (Map.Entry<ResourceLocation, Resource> file : found.entrySet()) {
            read(file.getKey(), file.getValue()).ifPresent(tag -> read.put(idOf(file.getKey()), tag));
        }
        adopt(read);
        LOGGER.info("{} piece(s) loaded", library.size());
    }

    private static void adopt(Map<ResourceLocation, CompoundTag> read) {
        Map<ResourceLocation, Piece> found = new LinkedHashMap<>();
        read.forEach((id, tag) -> {
            try {
                found.put(id, DraftTemplate.pieceOf(tag));
            } catch (RuntimeException unreadable) {
                LOGGER.error("{}: {}", id, unreadable.getMessage());
            }
        });
        library = Map.copyOf(found);
        files = Map.copyOf(read);
    }

    private static Optional<CompoundTag> read(ResourceLocation file, Resource resource) {
        try (InputStream bytes = resource.open()) {
            return Optional.of(NbtIo.readCompressed(bytes, NbtAccounter.create(TemplateLibraryPacket.MOST_BYTES)));
        } catch (IOException notPacked) {
            return plain(file, resource);
        } catch (RuntimeException broken) {
            LOGGER.error("{}: {}", file, broken.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<CompoundTag> plain(ResourceLocation file, Resource resource) {
        try (InputStream bytes = resource.open()) {
            return Optional.of(NbtIo.read(new java.io.DataInputStream(bytes),
                NbtAccounter.create(TemplateLibraryPacket.MOST_BYTES)));
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.error("{}: {}", file, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private static ResourceLocation idOf(ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
            path.substring(FOLDER.length() + 1, path.length() - SUFFIX.length()));
    }
}
