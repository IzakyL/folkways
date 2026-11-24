package io.github.izakyl.folkways.core.base.persist.upgrade;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config file migrations run before NeoForge loads each server config. They apply to the instance and defaults
 * during registration, and to each world's override folder when that world starts. A current file always wins.
 */
public final class ConfigRenames {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-upgrade");
    private static final Map<String, String> RENAMES = new LinkedHashMap<>();
    private static final Map<String, Split> SPLITS = new LinkedHashMap<>();

    private record Split(String released, List<String> keys) {
    }

    private ConfigRenames() {
    }

    /** Move {@code released} to {@code current} in the instance's config folders now, and in each world's later. */
    public static void register(String released, String current) {
        register(released, current, instanceFolders());
    }

    static synchronized void register(String released, String current, List<Path> folders) {
        RENAMES.put(released, current);
        for (Path folder : folders) {
            move(folder, released, current);
            // The mod owning a split may have registered it before the source file's old name.
            SPLITS.forEach((target, split) -> {
                if (split.released().equals(current)) {
                    copy(folder, target, split);
                }
            });
        }
    }

    /** Seed the new config with selected keys from a config that is still used for its remaining settings. */
    public static synchronized void registerSplit(String released, String current, List<String> keys) {
        Split split = new Split(released, List.copyOf(keys));
        SPLITS.put(current, split);
        instanceFolders().forEach(folder -> copy(folder, current, split));
    }

    private static List<Path> instanceFolders() {
        return List.of(FMLPaths.CONFIGDIR.get(),
            FMLPaths.GAMEDIR.get().resolve(FMLConfig.getConfigValue(FMLConfig.ConfigValue.DEFAULT_CONFIG_PATH)));
    }

    /** A world's server config folder, just before NeoForge reads it. */
    public static synchronized void world(Path folder) {
        RENAMES.forEach((released, current) -> move(folder, released, current));
        SPLITS.forEach((target, split) -> copy(folder, target, split));
    }

    private static void copy(Path folder, String current, Split split) {
        Path from = folder.resolve(split.released());
        Path to = folder.resolve(current);
        if (!Files.isRegularFile(from) || Files.exists(to)) {
            return;
        }
        try {
            TomlFormat toml = TomlFormat.instance();
            CommentedConfig source = toml.createParser().parse(from, FileNotFoundAction.THROW_ERROR);
            CommentedConfig target = TomlFormat.newConfig();
            for (String key : split.keys()) {
                if (source.contains(key)) {
                    target.set(key, source.get(key));
                    target.setComment(key, source.getComment(key));
                }
            }
            if (!target.isEmpty()) {
                toml.createWriter().write(target, to, WritingMode.REPLACE_ATOMIC);
                LOGGER.info("Copied config settings from {} to {} in {}", split.released(), current, folder);
            }
        } catch (RuntimeException failed) {
            LOGGER.error("Could not copy config settings from {} to {} in {}", split.released(), current, folder,
                failed);
        }
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
