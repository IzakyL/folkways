package io.github.izakyl.folkways.assistant.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

record AssistantSettings(String provider, String baseUrl, String model, Set<String> autoApprove, Boolean imageInput,
        String id) {

    AssistantSettings {
        provider = provider == null || provider.isBlank() ? "openai-compatible" : provider;
        autoApprove = autoApprove == null ? Set.of() : Set.copyOf(autoApprove);
        // Models are assumed to see images; a saved false stays, for a text-only model.
        imageInput = imageInput == null || imageInput;
    }

    AssistantSettings(String provider, String baseUrl, String model, Set<String> autoApprove, Boolean imageInput) {
        this(provider, baseUrl, model, autoApprove, imageInput, null);
    }

    AssistantSettings(String provider, String baseUrl, String model, Set<String> autoApprove) {
        this(provider, baseUrl, model, autoApprove, null);
    }

    AssistantSettings effective() {
        String providerOverride = System.getProperty("folkways.assistant.provider");
        String baseUrlOverride = System.getProperty("folkways.assistant.baseUrl");
        String modelOverride = System.getProperty("folkways.assistant.model");
        var fields = new StringJoiner(", ");
        if (providerOverride != null) {
            fields.add("provider");
        }
        if (baseUrlOverride != null) {
            fields.add("baseUrl");
        }
        if (modelOverride != null) {
            fields.add("model");
        }
        if (fields.length() > 0 && !overrideWarned) {
            overrideWarned = true;
            LOGGER.warn("Assistant settings overridden by JVM system properties: {}", fields);
        }

        String selected = providerOverride == null ? provider : providerOverride;
        AssistantSettings defaults = selected.equals(provider) ? this : preset(selected, autoApprove);
        String endpoint = baseUrlOverride == null ? defaults.baseUrl() : baseUrlOverride;
        return new AssistantSettings(selected, endpoint, modelOverride == null ? defaults.model() : modelOverride,
                autoApprove, defaults.imageInput(), selected.equals(provider) && sameOrigin(baseUrl, endpoint) ? id : null);
    }

    static boolean sameOrigin(String first, String second) {
        try {
            URI a = URI.create(first);
            URI b = URI.create(second);
            return a.getScheme() != null && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                    && a.getScheme().equalsIgnoreCase(b.getScheme()) && port(a) == port(b);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    static AssistantSettings preset(String provider, Set<String> autoApprove) {
        return switch (provider) {
            case "deepseek" -> new AssistantSettings(provider, "https://api.deepseek.com", "deepseek-v4-flash",
                    autoApprove);
            case "anthropic" -> new AssistantSettings(provider, "https://api.anthropic.com", "claude-sonnet-5-5",
                    autoApprove);
            case "google" -> new AssistantSettings(provider, "https://generativelanguage.googleapis.com/v1beta",
                    "gemini-flash-latest", autoApprove);
            default -> new AssistantSettings("openai-compatible", "https://api.openai.com/v1", "", autoApprove);
        };
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Overrides only come from JVM flags, so naming them once per process is enough. */
    private static volatile boolean overrideWarned;

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("folkways-assistant.json");

    record Entry(String id, String provider, String name, String baseUrl, String model, Boolean imageInput) {

        AssistantSettings settings(Set<String> approvals) {
            return new AssistantSettings(provider, baseUrl, model, approvals, imageInput, id);
        }

        static Entry from(AssistantSettings settings) {
            return new Entry(settings.id() == null ? UUID.randomUUID().toString() : settings.id(),
                    settings.provider(), null, settings.baseUrl(), settings.model(), settings.imageInput());
        }
    }

    record Config(List<Entry> entries, String selectedEntryId, Set<String> autoApprove) {

        Config {
            entries = entries == null ? List.of() : List.copyOf(entries);
            autoApprove = autoApprove == null ? Set.of() : Set.copyOf(autoApprove);
        }

        AssistantSettings active() throws IOException {
            Entry selected = entries.stream().filter(entry -> entry.id().equals(selectedEntryId)).findFirst()
                    .orElseThrow(() -> new IOException("Invalid selected assistant entry"));
            return selected.settings(autoApprove);
        }

        void save() throws IOException {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, JSON.toJson(this) + "\n");
        }
    }

    static Config loadConfig() throws IOException {
        if (!Files.exists(FILE)) {
            Entry entry = Entry.from(preset("openai-compatible", Set.of()));
            return new Config(List.of(entry), entry.id(), Set.of());
        }
        return parse(Files.readString(FILE));
    }

    static Config parse(String content) throws IOException {
        try {
            Config config = JSON.fromJson(content, Config.class);
            if (config == null || config.entries().isEmpty()
                    || config.entries().stream().anyMatch(entry -> entry.id() == null || entry.provider() == null
                            || entry.baseUrl() == null || entry.model() == null)) {
                throw new IOException("Invalid assistant settings");
            }
            config.active();
            return config;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid assistant settings", failure);
        }
    }

    static AssistantSettings load() throws IOException {
        return loadConfig().active();
    }

}
