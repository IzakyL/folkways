package io.github.izakyl.folkways.core.shell.api;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Startup-only decisions shared by the mixin plugin and integration installers. */
public enum OptionalIntegrations {
    CREATE("create", "[6.0.7,7.0)"),
    CBC("createbigcannons", "[5.11.7]"),
    FIREFIGHTING("createfirefightingadd", "[0.2.3-beta,)"),
    MODULAR_GOLEMS("modulargolems", "[3.1.43]"),
    EPIC_FIGHT("epicfight", "[21.17.3.1]"),
    CHISELS_AND_BITS("chiselsandbits", "[21.1.33]"),
    COOKERY("kaleidoscope_cookery", "[1.6.0-neoforge+mc1.21.1,)"),
    TAVERN("kaleidoscope_tavern", "[1.2.0-neoforge+mc1.21.1,)"),
    CARTS("astikorcartsredux", "[1.2.2,)"),
    TACZ("tacz", "[1.1.8,)"),
    SUPERB_WARFARE("superbwarfare", "[0.8.9.2,0.8.9.3)"),
    LET_IT_WALK("let_it_walk", "[0.1.5,0.2)");

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-integrations");
    private final String modId;
    private final String verifiedRange;
    private final VersionRange verified;

    OptionalIntegrations(String modId, String verified) {
        this.modId = modId;
        this.verifiedRange = verified;
        try {
            this.verified = VersionRange.createFromVersionSpec(verified);
        } catch (InvalidVersionSpecificationException invalid) {
            throw new IllegalArgumentException(verified, invalid);
        }
    }

    public boolean enabled() {
        return Startup.DECISIONS.stream().anyMatch(decision -> decision.integration() == this && decision.reason() == null) && (this != TAVERN || COOKERY.enabled())
            && (this != FIREFIGHTING && this != CBC || CREATE.enabled());
    }

    public enum Reason {
        MANUAL, INCOMPATIBLE
    }

    public record Decision(OptionalIntegrations integration, String name, String installedVersion,
                           String verifiedRange, Reason reason) {
    }

    record Installed(String name, ArtifactVersion version, boolean allowed) {
    }

    public static List<Decision> disabled() {
        return disabled(Startup.DECISIONS);
    }

    static List<Decision> disabled(List<Decision> decisions) {
        return decisions.stream().filter(decision -> decision.reason() != null).toList();
    }

    static List<Decision> decide(Function<OptionalIntegrations, Installed> installed) {
        return Arrays.stream(values()).map(integration -> {
            Installed mod = installed.apply(integration);
            if (mod == null) {
                return null;
            }
            Reason reason = !mod.allowed() ? Reason.MANUAL
                : integration.verified.containsVersion(mod.version()) ? null : Reason.INCOMPATIBLE;
            return new Decision(integration, mod.name(), mod.version().toString(), integration.verifiedRange, reason);
        }).filter(Objects::nonNull).toList();
    }

    private static final class Startup {
        private static final List<Decision> DECISIONS = read();

        private static List<Decision> read() {
            // Mixin selection takes place before NeoForge loads mod-owned configs.
            var file = FMLPaths.CONFIGDIR.get().resolve("folkways_core-integrations.toml");
            try (var config = CommentedFileConfig.builder(file).sync().build()) {
                config.load();
                boolean changed = false;
                for (var integration : values()) {
                    if (!config.contains(integration.modId)) {
                        config.set(integration.modId, true);
                        config.setComment(integration.modId, "Enable this integration. Requires a restart.");
                        changed = true;
                    }
                }
                if (changed) {
                    config.save();
                }
                var loading = LoadingModList.get();
                List<Decision> decisions = decide(integration -> {
                    var mod = loading.getModFileById(integration.modId);
                    if (mod == null) {
                        return null;
                    }
                    var info = mod.getMods().stream().filter(entry -> entry.getModId().equals(integration.modId))
                        .findFirst().orElseThrow();
                    return new Installed(info.getDisplayName(), info.getVersion(),
                        !Boolean.FALSE.equals(config.getRaw(integration.modId)));
                });
                for (Decision decision : disabled(decisions)) {
                    if (decision.reason() == Reason.INCOMPATIBLE) {
                        LOGGER.warn("Disabled {} integration: installed version {} is outside verified range {}",
                            decision.integration().modId, decision.installedVersion(), decision.verifiedRange());
                    }
                }
                return decisions;
            }
        }
    }
}
