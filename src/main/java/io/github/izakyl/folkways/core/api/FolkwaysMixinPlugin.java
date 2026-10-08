package io.github.izakyl.folkways.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public abstract class FolkwaysMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-mixin");

    private static final String VERIFIED_CREATE = "[6.0.0,7.0)";

    // An essential mixin is one Folkways cannot run without: when it is not in force, loading stops.
    public record Degradable(String mixin, String target, String lost, boolean clientOnly, boolean essential) {
        public static Degradable common(String mixin, String target, String lost) {
            return new Degradable(mixin, target, lost, false, false);
        }

        public static Degradable client(String mixin, String target, String lost) {
            return new Degradable(mixin, target, lost, true, false);
        }

        public static Degradable essential(String mixin, String target, String lost) {
            return new Degradable(mixin, target, lost, false, true);
        }
    }

    private static final VersionRange VERIFIED = verifiedRange();

    private static final Map<String, Degradable> DEGRADABLE = new ConcurrentHashMap<>();
    private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> DROPPED = new ConcurrentHashMap<>();
    private static final Set<String> INAPPLICABLE = ConcurrentHashMap.newKeySet();

    private boolean createPresent;

    private String createUnverified;

    protected abstract Set<String> createOnly();

    protected abstract List<Degradable> degradable();

    // Mixins that only make sense beside another mod, by the id of the mod each one needs.
    protected Map<String, String> modOnly() {
        return Map.of();
    }

    @Override
    public final void onLoad(String mixinPackage) {
        ArtifactVersion create = installedCreateVersion();
        createPresent = create != null;
        createUnverified = createPresent && !VERIFIED.containsVersion(create)
            ? "Create " + create + " is outside the verified range " + VERIFIED_CREATE
            : null;
        for (Degradable entry : degradable()) {
            DEGRADABLE.putIfAbsent(entry.mixin(), entry);
        }
    }

    @Override
    public final boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String mixin = simpleName(mixinClassName);
        String needed = modOnly().get(mixin);
        if (needed != null) {
            return LoadingModList.get().getModFileById(needed) != null;
        }
        if (!createOnly().contains(mixin)) {
            return true;
        }
        if (createPresent && createUnverified == null) {
            return true;
        }
        if (createUnverified != null) {
            DROPPED.put(mixin, createUnverified);
        } else {
            INAPPLICABLE.add(mixin);
        }
        return false;
    }

    @Override
    public final void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
        IMixinInfo mixinInfo) {
        APPLIED.add(simpleName(mixinClassName));
    }

    public static void logDegradations() {
        boolean client = FMLEnvironment.dist.isClient();
        List<ModLoadingIssue> fatal = new ArrayList<>();
        for (Degradable entry : DEGRADABLE.values()) {
            if (INAPPLICABLE.contains(entry.mixin()) || (entry.clientOnly() && !client)) {
                continue;
            }
            String reason = DROPPED.get(entry.mixin());
            if (reason == null) {
                if (applied(entry)) {
                    continue;
                }
                reason = "its injection did not apply, most likely a conflict with another mod";
            }
            if (entry.essential()) {
                fatal.add(ModLoadingIssue.error("Folkways is not compatible with the installed Create: mixin "
                    + entry.mixin() + " is not in force (" + reason + "), so " + entry.lost() + "."));
                continue;
            }
            LOGGER.warn("folkways mixin {} is not in force ({}): {}.", entry.mixin(), reason, entry.lost());
        }
        if (!fatal.isEmpty()) {
            throw new ModLoadingException(fatal);
        }
    }

    private static boolean applied(Degradable entry) {
        if (APPLIED.contains(entry.mixin())) {
            return true;
        }
        try {
            Class.forName(entry.target(), false, FolkwaysMixinPlugin.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        } catch (Error broken) {
            // An injection that missed its target throws here; an essential mixin reports that as incompatibility.
            if (!entry.essential()) {
                throw broken;
            }
            LOGGER.error("folkways mixin {} failed to apply", entry.mixin(), broken);
            return false;
        }
        return APPLIED.contains(entry.mixin());
    }

    private static ArtifactVersion installedCreateVersion() {
        var loaded = LoadingModList.get().getModFileById("create");
        if (loaded == null) {
            return null;
        }
        for (IModInfo mod : loaded.getMods()) {
            if ("create".equals(mod.getModId())) {
                return mod.getVersion();
            }
        }
        return null;
    }

    private static VersionRange verifiedRange() {
        try {
            return VersionRange.createFromVersionSpec(VERIFIED_CREATE);
        } catch (InvalidVersionSpecificationException malformed) {
            throw new IllegalStateException(VERIFIED_CREATE + " is not a version range", malformed);
        }
    }

    private static String simpleName(String mixinClassName) {
        return mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
        IMixinInfo mixinInfo) {
    }
}
