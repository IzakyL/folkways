package io.github.izakyl.folkways;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

final class NightlyDiagnostics {
    private static final String BACKGROUND = "spark.backgroundProfiler";

    private NightlyDiagnostics() {
    }

    static void install() {
        if (!BuildChannel.automaticDiagnostics()) return;
        installDefaults(FMLPaths.CONFIGDIR.get().resolve("spark/config.json"));
    }

    static void installDefaults(Path sparkConfig) {
        installDefaults(sparkConfig, BuildChannel.automaticDiagnostics());
    }

    static void installDefaults(Path sparkConfig, boolean eligible) {
        if (!eligible || System.getProperty(BACKGROUND) != null
            || System.getenv("SPARK_BACKGROUNDPROFILER") != null) return;

        // This backport defaults to server-only sampling. Enable the client too, while
        // leaving an existing Spark preference and its config file in the user's hands.
        try (var reader = Files.newBufferedReader(sparkConfig)) {
            var config = JsonParser.parseReader(reader);
            if (!config.isJsonObject() || config.getAsJsonObject().has("backgroundProfiler")) return;
        } catch (NoSuchFileException exception) {
            // No saved preference: use a process-local default, never write a saved opt-in.
        } catch (IOException | JsonParseException exception) {
            LogUtils.getLogger().warn("[folkways/diagnostics] Could not read Spark preferences; keeping Spark defaults", exception);
            return;
        }
        System.setProperty(BACKGROUND, "true");
        LogUtils.getLogger().info("[folkways/diagnostics] Development build with Spark: background sampling requested (client and server, 10 ms default); use profiler info to confirm");
    }
}
