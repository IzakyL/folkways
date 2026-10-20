package io.github.izakyl.folkways.plugins.person.look;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.FolkwaysMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// A client's own installed packs, adapted to the resources the resident renderer reads. No pack is extracted
// or sent over the network; the server and its clients install the same packs independently.
final class MaidModelFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-models");

    private MaidModelFiles() {
    }

    static Map<ResourceLocation, byte[]> make(List<MaidLooks.Maid> maids, JsonObject defaults, boolean models) {
        Map<ResourceLocation, byte[]> files = new LinkedHashMap<>();
        Map<String, JsonObject> languages = new LinkedHashMap<>();
        Set<MaidResources> external = maids.stream()
            .filter(maid -> maid.texture().getNamespace().equals(FolkwaysMod.MOD_ID))
            .map(MaidLooks.Maid::source).collect(Collectors.toSet());
        var sources = new LinkedHashSet<>(maids.stream().map(MaidLooks.Maid::source).toList()).stream()
            .filter(java.util.Objects::nonNull)
            .sorted(Comparator.comparing((MaidResources source) -> external.contains(source))
                .thenComparing(source -> source.root().toString())).toList();
        for (MaidResources source : sources) {
            try {
                source.languages().forEach((locale, words) -> {
                    JsonObject merged = languages.computeIfAbsent(locale, key -> new JsonObject());
                    words.entrySet().forEach(word -> merged.add(word.getKey(), word.getValue()));
                });
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("[folkways-models] maid translations in {} are unreadable ({})", source.root(), exception.toString());
            }
        }
        languages.forEach((locale, words) -> {
            ResourceLocation id = ResourceLocation.tryBuild(FolkwaysMod.MOD_ID, "lang/" + locale + ".json");
            if (id != null) {
                files.put(id, bytes(words));
            }
        });
        if (!models) {
            return files;
        }
        for (MaidLooks.Maid maid : maids) {
            try {
                MaidResources source = maid.source();
                JsonObject model = source.json(maid.geometry());
                List<String> bones = MaidAssets.boneNames(model);
                JsonObject geometry;
                JsonObject animationFile = null;
                if (maid.gecko()) {
                    List<JsonObject> layered = new ArrayList<>(List.of(defaults));
                    for (String file : maid.animations()) {
                        layered.add(source.json(file));
                    }
                    JsonObject animations = MaidAssets.merged(layered);
                    geometry = MaidAssets.geometry(model, MaidAssets.hiddenByLayers(animations, bones));
                    animationFile = MaidAssets.animations(maid, animations);
                } else {
                    geometry = MaidAssets.geometry(model, MaidAssets.hiddenByScripts(maid.animations(), bones));
                }
                byte[] texture = source.bytes(maid.textureFile());
                files.put(maid.geometryAt(), bytes(geometry));
                if (animationFile != null) {
                    files.put(maid.animationsAt(), bytes(animationFile));
                }
                // Built-in textures stay at their existing mod resource ids. External textures are exposed by
                // this pack, since Touhou Little Maid's dynamic TextureManager entries are not pack resources.
                if (maid.texture().getNamespace().equals(FolkwaysMod.MOD_ID)) {
                    files.put(maid.texture(), texture);
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("[folkways-models] maid {} cannot be drawn ({})", maid.model(), exception.toString());
            }
        }
        return files;
    }

    private static byte[] bytes(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
