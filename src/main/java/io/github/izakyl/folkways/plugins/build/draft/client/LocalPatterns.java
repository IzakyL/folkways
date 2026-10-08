package io.github.izakyl.folkways.plugins.build.draft.client;

import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OnlyIn(Dist.CLIENT)
public final class LocalPatterns {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-patterns");

    private static final String SUFFIX = ".star";
    private static final String NAMESPACE = "local";

    private LocalPatterns() {
    }

    public static Path folder() {
        Path path = Minecraft.getInstance().gameDirectory.toPath().resolve(Patterns.FOLDER);
        try {
            Files.createDirectories(path);
        } catch (IOException denied) {
            LOGGER.warn("cannot make {}: {}", path, denied.getMessage());
        }
        return path;
    }

    public static boolean local(Pattern pattern) {
        return pattern.id().getNamespace().equals(NAMESPACE);
    }

    public static List<Pattern> available() {
        Map<ResourceLocation, Pattern> byId = new LinkedHashMap<>();
        for (Pattern offered : Patterns.all()) {
            byId.put(offered.id(), offered);
        }
        for (Pattern own : scan()) {
            byId.put(own.id(), own);
        }
        return List.copyOf(byId.values());
    }

    private static List<Pattern> scan() {
        Path folder = folder();
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        Map<ResourceLocation, String> own = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(LocalPatterns::isPattern).sorted().toList()) {
                idOf(file).ifPresent(id -> read(file).ifPresent(source -> own.put(id, source)));
            }
        } catch (IOException unreadable) {
            LOGGER.warn("cannot read {}: {}", folder, unreadable.getMessage());
        }
        Map<ResourceLocation, String> offered = Patterns.sources();
        List<Pattern> found = new ArrayList<>();
        own.forEach((id, source) -> Pattern.parse(id, source,
                wanted -> Optional.ofNullable(own.getOrDefault(wanted, offered.get(wanted))))
            .ifPresent(found::add));
        return found;
    }

    private static boolean isPattern(Path file) {
        return Files.isRegularFile(file) && file.getFileName().toString().endsWith(SUFFIX);
    }

    private static Optional<String> read(Path file) {
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            LOGGER.error("{}: {}", file, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<ResourceLocation> idOf(Path file) {
        String name = file.getFileName().toString();
        String stem = name.substring(0, name.length() - SUFFIX.length());
        ResourceLocation id = ResourceLocation.tryBuild(NAMESPACE, stem);
        if (id == null) {
            LOGGER.error("{}: a pattern's file name may hold only a-z, 0-9, _, - and .", file);
        }
        return Optional.ofNullable(id);
    }
}
