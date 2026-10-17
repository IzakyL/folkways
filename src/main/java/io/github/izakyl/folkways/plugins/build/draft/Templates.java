package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.library.Shelves;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Templates {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    public static final Shelves.Shelf SHELF = new Shelves.Shelf("piece");
    public static final Shelves.Shelf BLUEPRINTS = new Shelves.Shelf("blueprint");

    public static final String FOLDER = SHELF.folder();
    // A blueprint is read beside the pieces and sent with them, under its own path so it never takes a piece's
    // name; it is set down whole from the blueprint page, and no pattern stamps it.
    public static final String BLUEPRINT = "blueprint/";
    private static final String SUFFIX = ".nbt";

    private static volatile Map<ResourceLocation, Piece> library = Map.of();
    private static volatile Map<ResourceLocation, CompoundTag> files = Map.of();
    private static volatile Map<ResourceLocation, Piece> own = Map.of();

    private Templates() {
    }

    static Optional<Piece> find(ResourceLocation id) {
        if (isBlueprint(id)) {
            return Optional.empty();
        }
        Piece offered = library.get(id);
        return Optional.ofNullable(offered != null ? offered : own.get(id));
    }

    // A blueprint on the shelf, as the structure file it was read from.
    public static Optional<CompoundTag> blueprint(ResourceLocation id) {
        return isBlueprint(id) ? Optional.ofNullable(files.get(id)) : Optional.empty();
    }

    public static Map<ResourceLocation, CompoundTag> files() {
        return files;
    }

    public static Set<ResourceLocation> blueprints() {
        return library.keySet().stream().filter(Templates::isBlueprint).collect(Collectors.toUnmodifiableSet());
    }

    public static boolean isBlueprint(ResourceLocation id) {
        return id.getPath().startsWith(BLUEPRINT);
    }

    public static void acceptFromServer(Map<ResourceLocation, CompoundTag> offered) {
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            return;
        }
        library = piecesOf(offered, new LinkedHashMap<>());
        files = Map.copyOf(offered);
        LOGGER.info("{} piece(s) offered by the server", library.size());
    }

    // A player's own pieces, from their folder: drawn on their side and sent as what they drew.
    public static Map<ResourceLocation, String> adoptOwn(Map<ResourceLocation, CompoundTag> read) {
        Map<ResourceLocation, String> refused = new LinkedHashMap<>();
        own = piecesOf(read, refused);
        return refused;
    }

    public static void reload(ResourceManager resources) {
        Map<ResourceLocation, CompoundTag> read = new LinkedHashMap<>();
        Map<ResourceLocation, String> packs = new LinkedHashMap<>();
        Map<ResourceLocation, String> refused = new LinkedHashMap<>();
        readShelf(resources, SHELF, "", read, packs, refused);
        readShelf(resources, BLUEPRINTS, BLUEPRINT, read, packs, refused);
        Map<ResourceLocation, Piece> found = piecesOf(read, refused);
        library = found;
        files = Map.copyOf(read.keySet().stream().filter(found::containsKey)
            .collect(Collectors.toMap(id -> id, read::get)));
        report(SHELF, found, packs, refused, false);
        report(BLUEPRINTS, found, packs, refused, true);
        LOGGER.info("{} piece(s) loaded", library.size());
    }

    private static void report(Shelves.Shelf shelf, Map<ResourceLocation, Piece> found,
            Map<ResourceLocation, String> packs, Map<ResourceLocation, String> refused, boolean blueprints) {
        Shelves.Stock.Builder stock = Shelves.Stock.builder();
        found.keySet().stream().filter(id -> isBlueprint(id) == blueprints)
            .forEach(id -> stock.entry(id, packs.getOrDefault(id, "")));
        refused.forEach((id, why) -> {
            if (isBlueprint(id) == blueprints) {
                stock.rejected(id + ": " + why);
            }
        });
        shelf.report(stock.build());
    }

    private static void readShelf(ResourceManager resources, Shelves.Shelf shelf, String prefix,
            Map<ResourceLocation, CompoundTag> read, Map<ResourceLocation, String> packs,
            Map<ResourceLocation, String> refused) {
        Map<ResourceLocation, Resource> found =
            resources.listResources(shelf.folder(), path -> path.getPath().endsWith(SUFFIX));
        for (Map.Entry<ResourceLocation, Resource> file : found.entrySet()) {
            ResourceLocation id = idOf(shelf, prefix, file.getKey());
            packs.put(id, file.getValue().sourcePackId());
            read(file.getKey(), file.getValue()::open).ifPresentOrElse(tag -> read.put(id, tag),
                () -> refused.put(id, "not a structure file"));
        }
    }

    private static Map<ResourceLocation, Piece> piecesOf(Map<ResourceLocation, CompoundTag> read,
            Map<ResourceLocation, String> refused) {
        Map<ResourceLocation, Piece> found = new LinkedHashMap<>();
        read.forEach((id, tag) -> {
            try {
                found.put(id, DraftTemplate.pieceOf(tag));
            } catch (RuntimeException unreadable) {
                LOGGER.error("{}: {}", id, unreadable.getMessage());
                refused.put(id, String.valueOf(unreadable.getMessage()));
            }
        });
        return Map.copyOf(found);
    }

    public static Optional<CompoundTag> read(Object file, IoSupplier<InputStream> opener) {
        try (InputStream bytes = opener.get()) {
            return Optional.of(NbtIo.readCompressed(bytes, NbtAccounter.create(TemplateLibraryPacket.MOST_BYTES)));
        } catch (IOException notPacked) {
            return plain(file, opener);
        } catch (RuntimeException broken) {
            LOGGER.error("{}: {}", file, broken.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<CompoundTag> plain(Object file, IoSupplier<InputStream> opener) {
        try (InputStream bytes = opener.get()) {
            return Optional.of(NbtIo.read(new java.io.DataInputStream(bytes),
                NbtAccounter.create(TemplateLibraryPacket.MOST_BYTES)));
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.error("{}: {}", file, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private static ResourceLocation idOf(Shelves.Shelf shelf, String prefix, ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
            prefix + path.substring(shelf.folder().length() + 1, path.length() - SUFFIX.length()));
    }
}
