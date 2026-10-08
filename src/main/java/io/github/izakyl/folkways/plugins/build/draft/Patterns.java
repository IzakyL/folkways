package io.github.izakyl.folkways.plugins.build.draft;

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

    public static final String FOLDER = "folkways/pattern";
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

    public static Drawn commission(Pattern pattern, Commission commission) {
        return pattern.drawOn(commission);
    }

    public static void reload(ResourceManager resources) {
        Templates.reload(resources);
        Map<ResourceLocation, String> read = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> files =
            resources.listResources(FOLDER, path -> path.getPath().endsWith(SUFFIX));
        for (Map.Entry<ResourceLocation, Resource> file : files.entrySet()) {
            read(file.getKey(), file.getValue()).ifPresent(source -> read.put(idOf(file.getKey()), source));
        }
        adopt(PatternLibraryPacket.sendable(read, LOGGER));
        LOGGER.info("{} pattern(s) loaded", library.size());
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            PatternPackets.offerToEveryone(server);
        }
    }

    private static void adopt(Map<ResourceLocation, String> read) {
        Map<ResourceLocation, String> kept = Map.copyOf(read);
        Map<ResourceLocation, Pattern> found = new LinkedHashMap<>();
        read.forEach((id, source) -> Pattern.parse(id, source, wanted -> Optional.ofNullable(kept.get(wanted)))
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
