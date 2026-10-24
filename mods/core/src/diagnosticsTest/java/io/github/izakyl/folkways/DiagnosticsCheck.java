package io.github.izakyl.folkways;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonParser;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;

public final class DiagnosticsCheck {
    private static final String BACKGROUND = "spark.backgroundProfiler";
    private static int checks;

    public static void main(String[] args) throws Exception {
        boolean nightly = "nightly".equalsIgnoreCase(args[0]);
        boolean development = Boolean.parseBoolean(args[2]);
        require(BuildChannel.nightly() == nightly, "packaged build channel");
        require(BuildChannel.version().equals(args[1]), "packaged version follows mod metadata");
        require(BuildChannel.development(args[1], args[0]) == development, "runtime and packaging version policies agree");
        require(!BuildChannel.automaticDiagnostics(), "Spark on test classpath is not an installed mod");
        require(!FolkwaysConfig.logPlanHeartbeat(), "development without installed Spark does not enable heartbeat");
        checkVersionPolicy();
        checkClientHook(development);
        var saved = CommentedConfig.inMemory();
        saved.set("diagnostics.logPlanHeartbeat", false);
        FolkwaysConfig.SPEC.correct(saved);
        require(Boolean.FALSE.equals(saved.get("diagnostics.logPlanHeartbeat")), "saved heartbeat opt-out survives correction");
        Path directory = Files.createTempDirectory("folkways-diagnostics-");
        String previous = System.getProperty(BACKGROUND);
        Path config = directory.resolve("config.json");
        try {
            System.clearProperty(BACKGROUND);
            NightlyDiagnostics.installDefaults(config);
            require(System.getProperty(BACKGROUND) == null, "no installed Spark means no default mutation");
            NightlyDiagnostics.installDefaults(config, false);
            require(System.getProperty(BACKGROUND) == null, "ineligible build cannot default sampling on");
            NightlyDiagnostics.installDefaults(config, true);
            require("true".equals(System.getProperty(BACKGROUND)), "eligible development and installed Spark default");
            require(!Files.exists(config), "no persistent Spark preference written");

            System.setProperty(BACKGROUND, "false");
            NightlyDiagnostics.installDefaults(config, true);
            require("false".equals(System.getProperty(BACKGROUND)), "explicit JVM opt-out");

            System.clearProperty(BACKGROUND);
            Files.writeString(config, "{\"backgroundProfiler\":false,\"other\":123}");
            NightlyDiagnostics.installDefaults(config, true);
            require(System.getProperty(BACKGROUND) == null, "saved Spark opt-out");
            require(Files.readString(config).equals("{\"backgroundProfiler\":false,\"other\":123}"), "saved config preserved");

            Files.writeString(config, "{}");
            NightlyDiagnostics.installDefaults(config, true);
            require("true".equals(System.getProperty(BACKGROUND)), "config with no profiler preference");

            System.clearProperty(BACKGROUND);
            Files.writeString(config, "invalid-json");
            NightlyDiagnostics.installDefaults(config, true);
            require(System.getProperty(BACKGROUND) == null, "invalid config does not force sampling");
        } finally {
            if (previous == null) System.clearProperty(BACKGROUND);
            else System.setProperty(BACKGROUND, previous);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Diagnostics " + args[0] + ": " + checks + " checks passed");
    }

    private record VersionCase(String version, String channel, boolean development) {
    }

    private static void checkVersionPolicy() {
        var cases = new VersionCase[] {
            new VersionCase("0.1.0-nightly", "release", true),
            new VersionCase("0.1.0-DEV", "release", true),
            new VersionCase("0.1.0-SNAPSHOT.7", "release", true),
            new VersionCase("0.1.0-release", "nightly", false),
            new VersionCase("0.1.0-ReLeAsE2", "nightly", false),
            new VersionCase("0.1.0-rc.1", "nightly", false),
            new VersionCase("0.1.0RC1", "nightly", false),
            new VersionCase("0.1.0-ALPHA2", "nightly", false),
            new VersionCase("0.1.0-beta+nightly", "nightly", false),
            new VersionCase("0.1.0-dev+release", "nightly", false),
            new VersionCase("0.1.0-dev", "beta", false),
            new VersionCase("0.1.0-dev", "RC1", false),
            new VersionCase("0.1.0-releaseNotes", "release", true),
            new VersionCase("0.1.0-alphabet", "release", true),
            new VersionCase("0.1.0-betamax", "release", true),
            new VersionCase("0.1.0-arc", "release", true),
            new VersionCase("0.1.0-20261023-abcdef", "release", true),
            new VersionCase("0.1.0", "release", false),
            new VersionCase("20261023", "release", false),
            new VersionCase("0.1.0", "dev", true),
            new VersionCase("0.1.0", "snapshot", true),
            new VersionCase("0.1.0", "", true),
            new VersionCase("0.1.0", null, true),
            new VersionCase("", "nightly", true),
            new VersionCase("", "release", false),
            new VersionCase(null, null, false),
            new VersionCase(" 0.1.0-dEv ", " Release ", true),
        };
        for (var entry : cases) {
            String label = String.valueOf(entry.version()) + "/" + entry.channel();
            require(BuildChannel.development(entry.version(), entry.channel()) == entry.development(), "version boundary " + label);
            require(BuildChannel.automaticDiagnostics(entry.version(), entry.channel(), true) == entry.development(),
                "installed Spark policy " + label);
            require(!BuildChannel.automaticDiagnostics(entry.version(), entry.channel(), false), "absent Spark policy " + label);
        }
    }

    @SuppressWarnings("deprecation")
    private static void checkClientHook(boolean development) throws Exception {
        String config = "/folkways-diagnostics.mixins.json";
        String mixin = "/io/github/izakyl/folkways/diagnostics/mixin/SparkClientBackgroundMixin.class";
        String plugin = "io.github.izakyl.folkways.diagnostics.SparkDiagnosticsPlugin";
        require((DiagnosticsCheck.class.getResource(config) != null) == development, "client hook config follows version policy");
        require((DiagnosticsCheck.class.getResource(mixin) != null) == development, "client hook class follows version policy");
        require((DiagnosticsCheck.class.getResource("/" + plugin.replace('.', '/') + ".class") != null) == development,
            "optional client hook plugin follows version policy");
        // Every mod has the common metadata path. Anchor it to Core's unique resource so Spark or a
        // classpath dependency cannot satisfy this assertion with a different mod's metadata.
        var anchor = DiagnosticsCheck.class.getResource("/META-INF/folkways/build.properties");
        require(anchor != null, "Core metadata resource anchor");
        try (var input = new URL(anchor, "../neoforge.mods.toml").openStream()) {
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            require(metadata.contains("modId=\"folkways_core\"") || metadata.contains("modId = \"folkways_core\""),
                "diagnostics registration is read from Core's own metadata");
            require(metadata.contains("folkways-diagnostics.mixins.json") == development,
                "client hook registration follows version policy");
        }
        if (!development) return;

        try (var input = DiagnosticsCheck.class.getResourceAsStream(config)) {
            var options = JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            require(plugin.equals(options.get("plugin").getAsString()), "client hook has optional loading guard");
            require(options.getAsJsonArray("client").size() == 1 && !options.has("mixins"), "hook is client-only");
        }
        Object guard = Class.forName(plugin).getConstructor().newInstance();
        require(Boolean.FALSE.equals(guard.getClass().getMethod("shouldApplyMixin", String.class, String.class)
            .invoke(guard, "me.lucko.spark.common.sampler.BackgroundSamplerManager", mixin)),
            "no installed Spark skips the hook before target resolution");
        try (var input = DiagnosticsCheck.class.getResourceAsStream(mixin)) {
            ClassNode hook = new ClassNode();
            new ClassReader(Objects.requireNonNull(input)).accept(hook, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            require(hook.invisibleAnnotations.stream().anyMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Pseudo;")),
                "optional hook target does not require Spark presence");
        }

        // Resolve metadata beside the actual target class, so Core's identically named metadata
        // cannot stand in for the fixed external Spark reference on this test-only runtime.
        var targetResource = DiagnosticsCheck.class.getResource("/me/lucko/spark/common/sampler/BackgroundSamplerManager.class");
        require(targetResource != null, "fixed Spark class anchors its own mod metadata");
        String referenceVersion;
        try (var input = new URL(targetResource, "../../../../../META-INF/neoforge.mods.toml").openStream()) {
            var metadata = new TomlParser().parse(new StringReader(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            List<? extends UnmodifiableConfig> mods = metadata.get("mods");
            require(mods != null, "fixed Spark has actual mod entries");
            var spark = mods.stream().filter(mod -> "spark".equals(mod.get("modId"))).toList();
            require(spark.size() == 1, "reference metadata identifies exactly the Spark mod");
            referenceVersion = spark.getFirst().get("version");
            require("1.10.124".equals(referenceVersion), "verified Spark modVersion comes from its real metadata");
        }
        var supports = guard.getClass().getDeclaredMethod("supportsSpark", String.class, String.class);
        supports.setAccessible(true);
        require(Boolean.TRUE.equals(supports.invoke(null, "spark", referenceVersion)),
            "the actual verified Spark metadata version enables its exact internal client hook");
        for (String version : new String[] { null, "", "1.10.123", "1.10.125", "2.0.0", "1.10.124.0",
                "1.10.124-SNAPSHOT", "1.10.124+custom", "1.10.124-neoforge-1.21.1" }) {
            require(Boolean.FALSE.equals(supports.invoke(null, "spark", version)),
                "unverified or absent Spark modVersion skips the internal hook: " + version);
        }
        require(Boolean.FALSE.equals(supports.invoke(null, null, referenceVersion)), "missing mod id skips the internal hook");
        require(Boolean.FALSE.equals(supports.invoke(null, "other_mod", referenceVersion)), "another mod cannot impersonate the Spark version guard");

        // Check the actual fixed Spark artifact, rather than a fake option holder which misses its client veto.
        try (var input = DiagnosticsCheck.class.getResourceAsStream("/me/lucko/spark/common/sampler/BackgroundSamplerManager.class")) {
            require(input != null, "fixed Spark client lifecycle target is present");
            ClassNode target = new ClassNode();
            new ClassReader(input).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            require(target.fields.stream().anyMatch(field -> field.name.equals("enabled") && field.desc.equals("Z")
                && (field.access & Opcodes.ACC_FINAL) != 0), "fixed Spark client enable field matches hook");
            String constructor = "(Lme/lucko/spark/common/SparkPlatform;Lme/lucko/spark/common/util/config/Configuration;)V";
            var create = target.methods.stream().filter(method -> method.name.equals("<init>")
                && method.desc.equals(constructor)).findFirst();
            require(create.isPresent(), "fixed Spark constructor matches the client hook");
            boolean veto = false;
            for (var instruction : create.orElseThrow().instructions) {
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals("me/lucko/spark/common/platform/PlatformInfo$Type") && field.name.equals("CLIENT")) {
                    veto = true;
                }
            }
            require(veto, "actual Spark artifact contains the client lifecycle veto requiring the hook");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
