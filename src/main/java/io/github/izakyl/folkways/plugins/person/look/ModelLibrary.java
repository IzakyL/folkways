package io.github.izakyl.folkways.plugins.person.look;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ExtraCodecs;

public record ModelLibrary(
        String fingerprint,
        Map<ResourceLocation, ResidentLook> entries,
        Map<String, byte[]> assets,
        List<String> rejected) {

    public static final ModelLibrary EMPTY = new ModelLibrary("", Map.of(), Map.of(), List.of());

    static final int MAX_ASSET_BYTES = 8 * 1024 * 1024;
    static final int MAX_LIBRARY_BYTES = 32 * 1024 * 1024;

    public static final String FOLDER = "folkways/resident_model";

    private static final String GEOMETRY_FILE = "main.json";
    private static final String ANIMATION_FILE = "main.animation.json";
    private static final String TEXTURE_DIR = "textures";
    private static final String MANIFEST_FILE = "folkways.json";
    private static final String PNG_SUFFIX = ".png";

    private static final String YSM_IDLE = "idle";
    private static final String YSM_WALK = "walk";
    private static final String YSM_WORK = "swing_hand";

    public ModelLibrary {
        entries = Map.copyOf(entries);
        assets = Map.copyOf(assets);
        rejected = List.copyOf(rejected);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public static ModelLibrary scan(ResourceManager resources) {
        Map<ResourceLocation, IoSupplier<InputStream>> files = new LinkedHashMap<>();
        resources.listResources(FOLDER, path -> true).forEach((id, resource) -> files.put(id, resource::open));
        return read(files);
    }

    public static ModelLibrary read(Map<ResourceLocation, IoSupplier<InputStream>> files) {
        Map<ResourceLocation, Map<String, IoSupplier<InputStream>>> byModel = new TreeMap<>();
        for (Map.Entry<ResourceLocation, IoSupplier<InputStream>> file : files.entrySet()) {
            String path = file.getKey().getPath();
            if (!path.startsWith(FOLDER + "/")) {
                continue;
            }
            String inFolder = path.substring(FOLDER.length() + 1);
            int slash = inFolder.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            ResourceLocation model = ResourceLocation.fromNamespaceAndPath(
                file.getKey().getNamespace(), inFolder.substring(0, slash));
            byModel.computeIfAbsent(model, key -> new TreeMap<>()).put(inFolder.substring(slash + 1), file.getValue());
        }
        Map<ResourceLocation, ResidentLook> entries = new LinkedHashMap<>();
        Map<String, byte[]> assets = new TreeMap<>();
        List<String> rejected = new ArrayList<>();
        byModel.forEach((model, contents) -> readModel(model, contents, entries, assets, rejected));
        return new ModelLibrary(fingerprintOf(assets), entries, assets, rejected);
    }

    private static void readModel(ResourceLocation model, Map<String, IoSupplier<InputStream>> contents,
            Map<ResourceLocation, ResidentLook> entries, Map<String, byte[]> assets, List<String> rejected) {
        String label = model.toString();
        String name = model.getPath();
        byte[] geometry = read(contents, GEOMETRY_FILE, label, rejected);
        byte[] animations = read(contents, ANIMATION_FILE, label, rejected);
        if (geometry == null || animations == null) {
            return;
        }
        Set<String> animationNames = animationNamesIn(animations);
        if (animationNames.isEmpty()) {
            rejected.add(label + ": " + ANIMATION_FILE + " declares no animations");
            return;
        }
        Manifest manifest = readManifest(contents, label, rejected);
        if (manifest == null) {
            return;
        }
        String missing = firstMissing(animationNames, manifest);
        if (missing != null) {
            rejected.add(label + ": " + MANIFEST_FILE + " names animation '" + missing + "', which "
                + ANIMATION_FILE + " does not have; it has " + animationNames);
            return;
        }
        String idle = pick(manifest.idleAnimation(), YSM_IDLE, animationNames);
        String walk = pick(manifest.walkAnimation(), YSM_WALK, animationNames);
        Optional<String> work = manifest.workAnimation().isPresent()
            ? manifest.workAnimation()
            : Optional.of(YSM_WORK).filter(animationNames::contains);
        List<String> textures = texturesIn(contents, label, rejected);
        if (textures.isEmpty()) {
            return;
        }

        ResourceLocation geometryAt = model.withPath("geo/resident_models/" + name + ".geo.json");
        ResourceLocation animationsAt = model.withPath("animations/resident_models/" + name + ".animation.json");
        Map<String, byte[]> staged = new TreeMap<>();
        staged.put(packPath(geometryAt), geometry);
        staged.put(packPath(animationsAt), animations);
        Map<ResourceLocation, ResidentLook> stagedEntries = new LinkedHashMap<>();
        for (String texture : textures) {
            String stem = texture.substring(TEXTURE_DIR.length() + 1, texture.length() - PNG_SUFFIX.length());
            byte[] pixels = read(contents, texture, label, rejected);
            if (pixels == null) {
                return;
            }
            ResourceLocation textureAt =
                model.withPath("textures/entity/resident_models/" + name + "/" + stem + PNG_SUFFIX);
            staged.put(packPath(textureAt), pixels);
            stagedEntries.put(
                model.withPath("model/" + name + "/" + stem),
                new ResidentLook(
                    new ResidentLook.Model(geometryAt, animationsAt, textureAt, idle, walk, work,
                        manifest.fallback().orElse(ResidentLooks.FALLBACK.appearance().skin())),
                    manifest.weight()));
        }

        int already = assets.values().stream().mapToInt(bytes -> bytes.length).sum();
        int adding = staged.values().stream().mapToInt(bytes -> bytes.length).sum();
        if (already + adding > MAX_LIBRARY_BYTES) {
            rejected.add(label + ": the models are past " + MAX_LIBRARY_BYTES + " bytes in total; this model "
                + "(" + adding + " bytes) is not carried");
            return;
        }
        assets.putAll(staged);
        entries.putAll(stagedEntries);
    }

    private static String firstMissing(Set<String> animationNames, Manifest manifest) {
        for (Optional<String> asked : List.of(manifest.idleAnimation(), manifest.walkAnimation(), manifest.workAnimation())) {
            if (asked.isPresent() && !animationNames.contains(asked.get())) {
                return asked.get();
            }
        }
        return null;
    }

    private static String pick(Optional<String> asked, String preferred, Set<String> names) {
        if (asked.isPresent()) {
            return asked.get();
        }
        if (names.contains(preferred)) {
            return preferred;
        }
        return names.stream().filter(name -> name.contains(preferred)).findFirst()
            .orElseGet(() -> names.iterator().next());
    }

    private static List<String> texturesIn(Map<String, IoSupplier<InputStream>> contents, String model,
            List<String> rejected) {
        List<String> found = contents.keySet().stream()
            .filter(path -> path.startsWith(TEXTURE_DIR + "/") && path.endsWith(PNG_SUFFIX)
                && path.indexOf('/', TEXTURE_DIR.length() + 1) < 0)
            .sorted()
            .toList();
        if (found.isEmpty()) {
            rejected.add(model + ": " + TEXTURE_DIR + "/ holds no .png, so the model has no skin to wear");
        }
        return found;
    }

    private static Set<String> animationNamesIn(byte[] animations) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(animations, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return Set.of();
            }
            JsonElement declared = parsed.getAsJsonObject().get("animations");
            if (declared == null || !declared.isJsonObject()) {
                return Set.of();
            }
            return new LinkedHashSet<>(declared.getAsJsonObject().keySet());
        } catch (RuntimeException exception) {
            return Set.of();
        }
    }

    private static Manifest readManifest(Map<String, IoSupplier<InputStream>> contents, String model,
            List<String> rejected) {
        if (!contents.containsKey(MANIFEST_FILE)) {
            return Manifest.DEFAULT;
        }
        byte[] bytes = read(contents, MANIFEST_FILE, model, rejected);
        if (bytes == null) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return Manifest.CODEC.parse(JsonOps.INSTANCE, parsed)
                .resultOrPartial(error -> rejected.add(model + ": " + MANIFEST_FILE + " — " + error))
                .orElse(null);
        } catch (RuntimeException exception) {
            rejected.add(model + ": " + MANIFEST_FILE + " — " + exception.getMessage());
            return null;
        }
    }

    private static byte[] read(Map<String, IoSupplier<InputStream>> contents, String file, String model,
            List<String> rejected) {
        IoSupplier<InputStream> opener = contents.get(file);
        if (opener == null) {
            rejected.add(model + ": no " + file);
            return null;
        }
        try (InputStream stream = opener.get()) {
            byte[] bytes = stream.readNBytes(MAX_ASSET_BYTES + 1);
            if (bytes.length > MAX_ASSET_BYTES) {
                rejected.add(model + ": " + file + " is past the " + MAX_ASSET_BYTES + " bytes a single file may be");
                return null;
            }
            return bytes;
        } catch (IOException exception) {
            rejected.add(model + ": " + file + " is unreadable (" + exception.getMessage() + ")");
            return null;
        }
    }

    public static String packPath(ResourceLocation asset) {
        return "assets/" + asset.getNamespace() + "/" + asset.getPath();
    }

    private static String fingerprintOf(Map<String, byte[]> assets) {
        if (assets.isEmpty()) {
            return "";
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the platform", exception);
        }
        assets.forEach((path, bytes) -> {
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
        });
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    record Manifest(
            Optional<String> idleAnimation,
            Optional<String> walkAnimation,
            Optional<String> workAnimation,
            int weight,
            Optional<ResidentLook.Skin> fallback) {

        static final Manifest DEFAULT = new Manifest(Optional.empty(), Optional.empty(), Optional.empty(), 1, Optional.empty());

        static final Codec<Manifest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("idle_animation").forGetter(Manifest::idleAnimation),
            Codec.STRING.optionalFieldOf("walk_animation").forGetter(Manifest::walkAnimation),
            Codec.STRING.optionalFieldOf("work_animation").forGetter(Manifest::workAnimation),
            ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(Manifest::weight),
            ResidentLook.Skin.CODEC.codec().optionalFieldOf("fallback").forGetter(Manifest::fallback)
        ).apply(instance, Manifest::new));
    }

}
