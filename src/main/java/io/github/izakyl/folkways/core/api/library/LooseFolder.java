package io.github.izakyl.folkways.core.api.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// The server's folder, read as one more data pack: <game>/folkways/<shelf>/<file> is data/local/folkways/<shelf>/<file>.
public final class LooseFolder {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-library");

    public static final String PACK_ID = "folkways/library";

    private LooseFolder() {
    }

    public static void addPackFinder(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        for (Shelves.Shelf shelf : Shelves.all()) {
            try {
                Files.createDirectories(shelf.path());
            } catch (IOException denied) {
                LOGGER.warn("cannot make {}: {}", shelf.path(), denied.getMessage());
            }
        }
        Pack pack = Pack.readMetaAndCreate(
            new PackLocationInfo(PACK_ID, Component.translatable("folkways.library.pack"),
                PackSource.BUILT_IN, Optional.empty()),
            new Pack.ResourcesSupplier() {
                @Override
                public PackResources openPrimary(PackLocationInfo info) {
                    return new Folder(info);
                }

                @Override
                public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                    return new Folder(info);
                }
            },
            PackType.SERVER_DATA,
            new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        }
    }

    // The files on a shelf whose names a resource location cannot hold, so the pack never sees them.
    public static List<String> misnamed(Shelves.Shelf shelf) {
        Path folder = shelf.path();
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<String> misnamed = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String relative = relative(folder, file);
                if (!ResourceLocation.isValidPath(relative)) {
                    misnamed.add(relative);
                }
            });
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.warn("cannot read {}: {}", folder, unreadable.getMessage());
        }
        return misnamed;
    }

    private static String relative(Path folder, Path file) {
        return folder.relativize(file).toString().replace('\\', '/');
    }

    private static Optional<Shelves.Shelf> shelfOf(String path) {
        return Shelves.all().stream()
            .filter(shelf -> path.equals(shelf.folder()) || path.startsWith(shelf.folder() + "/"))
            .findFirst();
    }

    private record Folder(PackLocationInfo info) implements PackResources {

        @Override
        public IoSupplier<InputStream> getRootResource(String... elements) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            if (type != PackType.SERVER_DATA || !location.getNamespace().equals(Shelves.NAMESPACE)) {
                return null;
            }
            String path = location.getPath();
            return shelfOf(path)
                .filter(shelf -> path.length() > shelf.folder().length())
                .map(shelf -> fileOn(shelf, path.substring(shelf.folder().length() + 1)))
                .filter(Files::isRegularFile)
                .map(IoSupplier::create)
                .orElse(null);
        }

        private static Path fileOn(Shelves.Shelf shelf, String inside) {
            Path folder = shelf.path().normalize();
            Path file = folder.resolve(inside).normalize();
            return file.startsWith(folder) ? file : folder;
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            if (type != PackType.SERVER_DATA || !namespace.equals(Shelves.NAMESPACE)) {
                return;
            }
            for (Shelves.Shelf shelf : Shelves.all()) {
                String folder = shelf.folder();
                if (!(folder.equals(path) || folder.startsWith(path + "/") || path.startsWith(folder + "/"))) {
                    continue;
                }
                list(shelf, path, output);
            }
        }

        private static void list(Shelves.Shelf shelf, String path, ResourceOutput output) {
            Path folder = shelf.path();
            if (!Files.isDirectory(folder)) {
                return;
            }
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String inPack = shelf.folder() + "/" + relative(folder, file);
                    if (!inPack.startsWith(path + "/")) {
                        continue;
                    }
                    ResourceLocation id = ResourceLocation.tryBuild(Shelves.NAMESPACE, inPack);
                    if (id != null) {
                        output.accept(id, IoSupplier.create(file));
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                LOGGER.warn("cannot read {}: {}", folder, unreadable.getMessage());
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.SERVER_DATA ? Set.of(Shelves.NAMESPACE) : Set.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
            if (deserializer != PackMetadataSection.TYPE) {
                return null;
            }
            return (T) new PackMetadataSection(Component.translatable("folkways.library.pack"),
                SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA), Optional.empty());
        }

        @Override
        public PackLocationInfo location() {
            return info;
        }

        @Override
        public void close() {
        }
    }
}
