package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.library.Shelves;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Patterns {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    public static final Shelves.Shelf SHELF = new Shelves.Shelf("pattern");

    public static final String FOLDER = SHELF.folder();
    private static final String SUFFIX = ".star";

    private static volatile Map<ResourceLocation, Pattern> library = Map.of();

    private static volatile Map<ResourceLocation, String> sources = Map.of();

    private Patterns() {
    }

    public static Optional<Pattern> find(ResourceLocation id) {
        return Optional.ofNullable(library.get(id));
    }

    public static Map<ResourceLocation, String> sources() {
        return sources;
    }

    public static void acceptFromServer(Map<ResourceLocation, String> offered) {
        if (sharingAJvmWithTheServer()) {
            return;
        }
        adopt(offered);
        LOGGER.info("{} pattern(s) offered by the server", library.size());
    }

    public static List<Pattern> all() {
        return List.copyOf(library.values());
    }

    public static void reload(ResourceManager resources) {
        Templates.reload(resources);
        Map<ResourceLocation, String> read = new LinkedHashMap<>();
        Map<ResourceLocation, String> packs = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> files =
            resources.listResources(FOLDER, path -> path.getPath().endsWith(SUFFIX));
        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            ResourceLocation id = idOf(file.getKey());
            packs.put(id, file.getValue().sourcePackId());
            read(file.getKey(), file.getValue()).ifPresent(source -> read.put(id, source));
        }
        Map<ResourceLocation, String> refused = new LinkedHashMap<>();
        adopt(PatternLibraryPacket.sendable(read, LOGGER), refused);
        report(packs, refused);
        LOGGER.info("{} pattern(s) loaded", library.size());
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            PatternPackets.offerToEveryone(server);
        }
    }

    // A file that defines no draw is a library the others load, and is neither an entry nor turned away.
    private static void report(Map<ResourceLocation, String> packs, Map<ResourceLocation, String> refused) {
        Shelves.Stock.Builder stock = Shelves.Stock.builder();
        library.keySet().forEach(id -> stock.entry(id, packs.getOrDefault(id, "")));
        packs.keySet().stream().filter(id -> !library.containsKey(id) && !sources.containsKey(id))
            .forEach(id -> stock.rejected(id + ": unreadable, or past what the server sends its players"));
        refused.forEach((id, why) -> stock.rejected(id + ": " + why));
        SHELF.report(stock.build());
    }

    private static void adopt(Map<ResourceLocation, String> read) {
        adopt(read, new LinkedHashMap<>());
    }

    private static void adopt(Map<ResourceLocation, String> read, Map<ResourceLocation, String> refused) {
        Map<ResourceLocation, String> kept = Map.copyOf(read);
        Map<ResourceLocation, Pattern> found = new LinkedHashMap<>();
        read.forEach((id, source) -> Pattern.parse(id, source, wanted -> Optional.ofNullable(kept.get(wanted)),
                why -> refused.put(id, why))
            .ifPresent(pattern -> found.put(id, pattern)));
        library = Map.copyOf(found);
        sources = kept;
    }

    private static boolean sharingAJvmWithTheServer() {
        return ServerLifecycleHooks.getCurrentServer() != null;
    }

    private static Optional<String> read(ResourceLocation file, Resource resource) {
        try (InputStream bytes = resource.open()) {
            return Optional.of(new String(bytes.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            LOGGER.error("{}: {}", file, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private static ResourceLocation idOf(ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(),
            path.substring(FOLDER.length() + 1, path.length() - SUFFIX.length()));
    }

    public static long seedOf(Pattern pattern, Hint hint) {
        long spot = switch (hint) {
            case Hint.Zone zone -> zone.min().hashCode();
            case Hint.Path path -> path.points().hashCode();
        };
        return spot * 31L + pattern.id().hashCode();
    }
}
