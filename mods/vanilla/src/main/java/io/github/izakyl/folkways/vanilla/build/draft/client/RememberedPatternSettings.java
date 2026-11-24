package io.github.izakyl.folkways.vanilla.build.draft.client;

import com.lowdragmc.lowdraglib2.LDLib2;
import io.github.izakyl.folkways.core.FolkwaysClientConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.neoforged.fml.loading.FMLPaths;

/** Last confirmed build pattern settings on this client. */
final class RememberedPatternSettings {
    private RememberedPatternSettings() {
    }

    public static java.util.Optional<CompoundTag> load(String key) {
        if (enabled()) {
            String written = read().getProperty(key);
            if (written != null) {
                try {
                    return java.util.Optional.of(TagParser.parseTag(written));
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | RuntimeException ignored) { }
            }
        }
        return java.util.Optional.empty();
    }

    public static void save(String key, CompoundTag values) {
        if (!enabled() || values.isEmpty()) {
            return;
        }
        Properties saved = read();
        String written = values.toString();
        if (written.equals(saved.getProperty(key))) {
            return;
        }
        saved.setProperty(key, written);
        try {
            Files.createDirectories(path().getParent());
            try (OutputStream out = Files.newOutputStream(path())) {
                saved.store(out, "Folkways: last confirmed region, path and pattern settings");
            }
        } catch (IOException | RuntimeException ignored) { }
    }

    private static boolean enabled() {
        return LDLib2.isRemote() && FolkwaysClientConfig.rememberDrawingSettings();
    }

    private static Properties read() {
        Properties saved = new Properties();
        if (Files.isRegularFile(path())) {
            try (InputStream in = Files.newInputStream(path())) {
                saved.load(in);
            } catch (IOException | RuntimeException ignored) { }
        }
        return saved;
    }

    private static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("folkways-drawing-settings.properties");
    }
}
