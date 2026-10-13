package io.github.izakyl.folkways.plugins.person.look;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.FolkwaysMod;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// The maid models Touhou Little Maid ships, offered as looks wherever that mod is installed. Nothing of them is
// copied or sent: the server offers the ids, and each client draws them out of its own copy of that mod.
public final class MaidLooks {
    public static final String MOD = "touhou_little_maid";

    static final ResourceLocation SHARED_ANIMATIONS = ResourceLocation.fromNamespaceAndPath(
        FolkwaysMod.MOD_ID, "animations/resident_models/maid.animation.json");
    static final String MAID_DEFAULTS = "animation/maid.animation.json";

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-models");
    private static final String PACKS = "tlm_custom_pack";
    private static final String MANIFEST = "maid_model.json";
    private static final String LOOK_PREFIX = "maid/";
    private static final ResourceLocation EVERY = ResourceLocation.fromNamespaceAndPath(MOD, "maids");
    private static final String IDLE = "idle";
    private static final String WALK = "walk";
    private static final String WORK = "swing_hand";
    // The motions a resident plays, by the names Touhou Little Maid's GeckoLib models give them.
    static final List<String> PLAYED = List.of(IDLE, WALK, WORK);

    private static volatile List<Maid> maids;

    private MaidLooks() {
    }

    // geometry and animations are paths under the pack folder; a maid that is not a GeckoLib model names the
    // scripts Touhou Little Maid animates it with instead, and none when it takes that mod's default ones.
    public record Maid(
            ResourceLocation model,
            boolean gecko,
            String geometry,
            List<String> animations,
            ResourceLocation texture,
            float scale) {

        public Maid {
            animations = List.copyOf(animations);
        }

        public ResourceLocation look() {
            return ResourceLocation.fromNamespaceAndPath(MOD, LOOK_PREFIX + model.getNamespace() + "/" + model.getPath());
        }

        public ResourceLocation geometryAt() {
            return generated("geo", ".geo.json");
        }

        public ResourceLocation animationsAt() {
            return gecko ? generated("animations", ".animation.json") : SHARED_ANIMATIONS;
        }

        // GeckoLib looks an animation up by name across every model's file, so a maid's own are named after it.
        public String animation(String name) {
            return "maid." + (gecko ? model.getNamespace() + "." + model.getPath() + "." : "") + name;
        }

        private ResourceLocation generated(String folder, String suffix) {
            return ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID,
                folder + "/resident_models/maid/" + model.getNamespace() + "/" + model.getPath() + suffix);
        }

        ResidentLook asLook() {
            return new ResidentLook(
                new ResidentLook.Model(geometryAt(), animationsAt(), texture,
                    animation(IDLE), animation(WALK), Optional.of(animation(WORK)),
                    ResidentLooks.FALLBACK.appearance().skin(), scale),
                1);
        }
    }

    public static boolean isMaid(ResourceLocation look) {
        return look.getNamespace().equals(MOD) && look.getPath().startsWith(LOOK_PREFIX);
    }

    // The key Touhou Little Maid names a maid's model by, for a look that is one.
    public static Optional<String> nameKey(ResourceLocation look) {
        if (!isMaid(look)) {
            return Optional.empty();
        }
        String model = look.getPath().substring(LOOK_PREFIX.length());
        int stroke = model.indexOf('/');
        return stroke <= 0 ? Optional.empty() : Optional.of(
            "model." + model.substring(0, stroke) + "." + model.substring(stroke + 1).replace('/', '.') + ".name");
    }

    // Where the installed Touhou Little Maid keeps a file of its own, under its assets.
    public static Optional<Path> installed(String... path) {
        ModList mods = ModList.get();
        IModFileInfo file = mods == null ? null : mods.getModFileById(MOD);
        if (file == null) {
            return Optional.empty();
        }
        String[] whole = Stream.concat(Stream.of("assets", MOD), Stream.of(path)).toArray(String[]::new);
        return Optional.of(file.getFile().findResource(whole)).filter(Files::exists);
    }

    public static Optional<Path> packs() {
        return installed(PACKS).filter(Files::isDirectory);
    }

    public static List<Maid> all() {
        List<Maid> known = maids;
        if (known == null && ModList.get() == null) {
            return List.of();
        }
        if (known == null) {
            known = packs().map(MaidLooks::read).orElseGet(List::of);
            if (!known.isEmpty()) {
                LOGGER.info("[folkways-models] {} maid models from {}", known.size(), MOD);
            }
            maids = known;
        }
        return known;
    }

    public static Map<ResourceLocation, ResidentLook> entries() {
        Map<ResourceLocation, ResidentLook> entries = new LinkedHashMap<>();
        for (Maid maid : all()) {
            entries.put(maid.look(), maid.asLook());
        }
        return entries;
    }

    // Every maid as one set, then a set for each of the packs they come in.
    public static Map<ResourceLocation, List<ResourceLocation>> sets() {
        Map<ResourceLocation, List<ResourceLocation>> sets = new LinkedHashMap<>();
        for (Maid maid : all()) {
            sets.computeIfAbsent(EVERY, key -> new ArrayList<>()).add(maid.look());
        }
        for (Maid maid : all()) {
            sets.computeIfAbsent(EVERY.withSuffix("/" + maid.model().getNamespace()), key -> new ArrayList<>())
                .add(maid.look());
        }
        return sets;
    }

    static List<Maid> read(Path packs) {
        List<Maid> found = new ArrayList<>();
        for (Path pack : inside(packs)) {
            for (Path namespace : inside(pack.resolve("assets"))) {
                Path manifest = namespace.resolve(MANIFEST);
                if (!Files.isRegularFile(manifest)) {
                    continue;
                }
                try {
                    readManifest(packs, pack, json(manifest), found);
                } catch (IOException | RuntimeException exception) {
                    LOGGER.warn("[folkways-models] {} is unreadable ({})", manifest, exception.getMessage());
                }
            }
        }
        return List.copyOf(found);
    }

    private static void readManifest(Path packs, Path pack, JsonObject manifest, List<Maid> found) {
        JsonElement listed = manifest.get("model_list");
        if (listed == null || !listed.isJsonArray()) {
            return;
        }
        for (JsonElement element : listed.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            ResourceLocation model = ResourceLocation.tryParse(text(entry, "model_id", ""));
            if (model == null || model.getPath().isEmpty()) {
                continue;
            }
            Path geometry = inPack(pack, text(entry, "model",
                model.getNamespace() + ":models/entity/" + model.getPath() + ".json"));
            Path texture = inPack(pack, text(entry, "texture",
                model.getNamespace() + ":textures/entity/" + model.getPath() + ".png"));
            ResourceLocation textureAt = texture == null ? null : ResourceLocation.tryBuild(
                MOD, slashed(packs.getParent().relativize(texture)));
            if (geometry == null || textureAt == null
                    || !Files.isRegularFile(geometry) || !Files.isRegularFile(texture)) {
                LOGGER.warn("[folkways-models] maid {} is skipped: its model or texture is not in the pack", model);
                continue;
            }
            boolean gecko = entry.has("is_gecko") && entry.get("is_gecko").getAsBoolean();
            List<String> animations = new ArrayList<>();
            if (entry.get("animation") instanceof JsonArray named) {
                for (JsonElement name : named) {
                    Path file = gecko ? inPack(pack, name.getAsString()) : null;
                    animations.add(file == null ? name.getAsString() : slashed(packs.relativize(file)));
                }
            }
            float scale = entry.has("render_entity_scale") ? entry.get("render_entity_scale").getAsFloat() : 1.0F;
            found.add(new Maid(model, gecko, slashed(packs.relativize(geometry)), animations, textureAt,
                scale > 0.0F ? scale : 1.0F));
        }
    }

    // A pack names its files the way a resource pack does, "namespace:path", each under its own assets folder.
    private static Path inPack(Path pack, String named) {
        ResourceLocation id = ResourceLocation.tryParse(named);
        if (id == null) {
            return null;
        }
        Path assets = pack.resolve("assets");
        Path file = assets.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
        return file.startsWith(assets) ? file : null;
    }

    static JsonObject json(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String text(JsonObject object, String key, String otherwise) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : otherwise;
    }

    private static String slashed(Path relative) {
        List<String> parts = new ArrayList<>();
        relative.forEach(part -> parts.add(part.toString()));
        return String.join("/", parts);
    }

    private static List<Path> inside(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> listed = Files.list(directory)) {
            return listed.filter(Files::isDirectory).sorted().toList();
        } catch (IOException exception) {
            LOGGER.warn("[folkways-models] {} is unreadable ({})", directory, exception.getMessage());
            return List.of();
        }
    }
}
