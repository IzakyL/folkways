package io.github.izakyl.folkways.plugins.build.draft.client;

import io.github.izakyl.folkways.library.Shelves;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.Templates;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// A player's own patterns and pieces, read from the same folders a server reads its shelves from.
@OnlyIn(Dist.CLIENT)
public final class LocalPatterns {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    private static final String PATTERN = ".star";
    private static final String STRUCTURE = ".nbt";

    private static volatile Map<ResourceLocation, String> own = Map.of();

    private LocalPatterns() {
    }

    public static Path folder() {
        Path path = Patterns.SHELF.path();
        try {
            Files.createDirectories(path);
        } catch (IOException denied) {
            LOGGER.warn("cannot make {}: {}", path, denied.getMessage());
        }
        return path;
    }

    // The player's own, unless the server offers the very same: then it is the server's, and built by its name.
    public static boolean local(Pattern pattern) {
        String mine = own.get(pattern.id());
        return mine != null && !Objects.equals(mine, Patterns.sources().get(pattern.id()));
    }

    public static List<Pattern> available() {
        Map<ResourceLocation, Pattern> byId = new LinkedHashMap<>();
        for (Pattern offered : Patterns.all()) {
            byId.put(offered.id(), offered);
        }
        for (Pattern mine : scan()) {
            byId.put(mine.id(), mine);
        }
        return List.copyOf(byId.values());
    }

    private static List<Pattern> scan() {
        Map<ResourceLocation, CompoundTag> pieces = new LinkedHashMap<>();
        walk(Templates.SHELF, STRUCTURE, "", (id, file) -> readStructure(file).ifPresent(tag -> pieces.put(id, tag)));
        Templates.adoptOwn(pieces).forEach((id, why) -> LOGGER.error("{}: {}", id, why));

        Map<ResourceLocation, String> sources = new LinkedHashMap<>();
        walk(Patterns.SHELF, PATTERN, "", (id, file) -> readText(file).ifPresent(source -> sources.put(id, source)));
        own = Map.copyOf(sources);

        Map<ResourceLocation, String> offered = Patterns.sources();
        List<Pattern> found = new ArrayList<>();
        sources.forEach((id, source) -> Pattern.parse(id, source,
                wanted -> Optional.ofNullable(sources.getOrDefault(wanted, offered.get(wanted))))
            .ifPresent(found::add));
        return found;
    }

    public static void walk(Shelves.Shelf shelf, String suffix, String prefix, BiConsumer<ResourceLocation, Path> each) {
        Path folder = shelf.path();
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(path -> isFile(path, suffix)).sorted().toList()) {
                idOf(folder, file, suffix, prefix).ifPresent(id -> each.accept(id, file));
            }
        } catch (IOException | RuntimeException unreadable) {
            LOGGER.warn("cannot read {}: {}", folder, unreadable.getMessage());
        }
    }

    private static boolean isFile(Path file, String suffix) {
        return Files.isRegularFile(file) && file.getFileName().toString().endsWith(suffix);
    }

    private static Optional<String> readText(Path file) {
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            LOGGER.error("{}: {}", file, unreadable.getMessage());
            return Optional.empty();
        }
    }

    public static Optional<CompoundTag> readStructure(Path file) {
        return Templates.read(file, IoSupplier.create(file));
    }

    private static Optional<ResourceLocation> idOf(Path folder, Path file, String suffix, String prefix) {
        String relative = folder.relativize(file).toString().replace('\\', '/');
        String stem = relative.substring(0, relative.length() - suffix.length());
        ResourceLocation id = ResourceLocation.tryBuild(Shelves.NAMESPACE, prefix + stem);
        if (id == null) {
            LOGGER.error("{}: a file name may hold only a-z, 0-9, _, - and .", file);
        }
        return Optional.ofNullable(id);
    }
}
