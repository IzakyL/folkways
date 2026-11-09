package io.github.izakyl.folkways.core.base.persist.upgrade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config files a release named differently. A mod registers each released name with its current one before
 * registering the config; the file then moves wherever NeoForge reads server configs: the instance config folder
 * and its defaults now, and a world's override folder when that world starts. A current file always wins.
 */
public final class ConfigRenames {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-upgrade");
    private static final Map<String, String> RENAMES = new ConcurrentHashMap<>();

    private ConfigRenames() {
    }

    /** Move {@code released} to {@code current} in the instance's config folders now, and in each world's later. */
    public static void register(String released, String current) {
        register(released, current, List.of(FMLPaths.CONFIGDIR.get(),
            FMLPaths.GAMEDIR.get().resolve(FMLConfig.getConfigValue(FMLConfig.ConfigValue.DEFAULT_CONFIG_PATH))));
    }

    static void register(String released, String current, List<Path> folders) {
        RENAMES.put(released, current);
        folders.forEach(folder -> move(folder, released, current));
    }

    /** A world's server config folder, just before NeoForge reads it. */
    public static void world(Path folder) {
        RENAMES.forEach((released, current) -> move(folder, released, current));
    }

    static void move(Path folder, String released, String current) {
        Path from = folder.resolve(released);
        Path to = folder.resolve(current);
        if (!Files.isRegularFile(from)) {
            return;
        }
        if (Files.exists(to)) {
            LOGGER.warn("Both {} and {} exist in {}; {} is used and {} is left unread", released, current, folder,
                current, released);
            return;
        }
        try {
            Files.move(from, to);
            LOGGER.info("Renamed config {} to {} in {}", released, current, folder);
        } catch (IOException failed) {
            LOGGER.error("Could not rename config {} to {} in {}; its settings stay at their defaults", released,
                current, folder, failed);
        }
    }
}
