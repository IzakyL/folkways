package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.LDLib2;
import io.github.izakyl.folkways.FolkwaysClientConfig;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.minecraft.nbt.TagParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

/** Last confirmed drawing settings, kept separately for each delegation on this client. */
public final class RememberedSettings {
    private RememberedSettings() { }

    static ColonySettings of(ResourceLocation delegation, Schema schema) {
        try {
            return load(delegation.toString())
                .map(tag -> ColonySettings.load(Reader.of(tag)).conformedTo(schema))
                .orElseGet(() -> ColonySettings.byDefault(schema));
        } catch (RuntimeException ignored) {
            return ColonySettings.byDefault(schema);
        }
    }

    static void put(ResourceLocation delegation, ColonySettings values) {
        if (!values.keys().isEmpty()) save(delegation.toString(), values.save());
    }

    /** Namespaced keys keep pattern options separate from delegation options. */
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
        if (!enabled() || values.isEmpty()) return;
        Properties saved = read();
        String written = values.toString();
        if (written.equals(saved.getProperty(key))) return;
        saved.setProperty(key, written);
        try {
            Files.createDirectories(path().getParent());
            try (OutputStream out = Files.newOutputStream(path())) {
                saved.store(out, "folkways: last confirmed region, path and pattern settings");
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
