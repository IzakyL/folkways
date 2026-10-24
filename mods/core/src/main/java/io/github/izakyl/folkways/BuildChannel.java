package io.github.izakyl.folkways;

import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import net.neoforged.fml.ModList;

final class BuildChannel {
    private static final Properties BUILD = read();
    private static final Pattern WORD = Pattern.compile("[a-z]+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> FORMAL = Set.of("release", "rc", "beta", "alpha");
    private static final Set<String> PREVIEW = Set.of("rc", "beta", "alpha");

    private BuildChannel() {
    }

    static boolean nightly() {
        return "nightly".equalsIgnoreCase(channel());
    }

    static String channel() {
        return BUILD.getProperty("channel", "");
    }

    static String version() {
        var mods = ModList.get();
        if (mods != null) {
            for (var mod : mods.getMods()) {
                if (FolkwaysMod.CORE_ID.equals(mod.getModId())) return mod.getVersion().toString();
            }
        }
        return BUILD.getProperty("version", "");
    }

    static boolean automaticDiagnostics() {
        var mods = ModList.get();
        // A jar on a test/compile classpath is not an installed mod. This index is available before
        // constructors, unlike ModList.isLoaded(), whose container index may still be being assembled.
        return automaticDiagnostics(version(), channel(), mods != null && mods.getModFileById("spark") != null);
    }

    static boolean automaticDiagnostics(String version, String channel, boolean sparkPresent) {
        return sparkPresent && development(version, channel);
    }

    static boolean development(String version, String channel) {
        String actual = version == null ? "" : version.strip();
        String fallback = channel == null ? "" : channel.strip();
        if (hasWord(actual, FORMAL) || hasWord(fallback, PREVIEW)) return false;
        // Explicit development/custom version labels override the default release build channel.
        // Match whole letter runs: rc1/alpha2 are labels, alphabet/releaseNotes are ordinary words.
        if (WORD.matcher(actual).find()) return true;
        if (!fallback.isEmpty()) return !hasWord(fallback, FORMAL);
        return !actual.isEmpty();
    }

    private static boolean hasWord(String value, Set<String> words) {
        return WORD.matcher(value).results().anyMatch(match -> words.contains(match.group().toLowerCase(Locale.ROOT)));
    }

    private static Properties read() {
        var properties = new Properties();
        try (var stream = BuildChannel.class.getResourceAsStream("/META-INF/folkways/build.properties")) {
            if (stream != null) properties.load(stream);
        } catch (IOException exception) {
            // Missing metadata does not itself opt into diagnostics.
        }
        return properties;
    }
}
