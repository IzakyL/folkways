package io.github.izakyl.folkways.epicfight.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.izakyl.folkways.epicfight.client.geo.GeoMeshData;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.look.ResidentLook;
import io.github.izakyl.folkways.plugins.person.look.ResidentLooks;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.client.model.Mesh;
import yesman.epicfight.api.client.model.MeshPartDefinition;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.client.mesh.HumanoidMesh;

/** Reuses Folkways' readable resources; it never loads an official YSM player runtime or model package. */
@OnlyIn(Dist.CLIENT)
public final class ResidentGeoMeshes {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-epicfight");
    private static final int MAX_CACHED_MODELS = 32;
    private static final String[] HUMANOID_PARTS = {"head", "torso", "leftArm", "rightArm", "leftLeg",
        "rightLeg", "hat", "jacket", "leftSleeve", "rightSleeve", "leftPants", "rightPants"};
    private static final Map<Key, Optional<Asset>> CACHE = new LinkedHashMap<>(16, 0.75F, true);

    private record Key(ResourceLocation geometry, float scale) {
    }

    private record Asset(ResourceLocation registryName, HumanoidMesh get) implements AssetAccessor<HumanoidMesh> {
        @Override
        public boolean inRegistry() {
            return false;
        }
    }

    private record Part(String partName) implements MeshPartDefinition {
        @Override
        public Mesh.RenderProperties renderProperties() {
            return null;
        }

        @Override
        public Supplier<OpenMatrix4f> getModelPartAnimationProvider() {
            return null;
        }
    }

    private ResidentGeoMeshes() {
    }

    public static boolean supports(ResidentEntity resident) {
        return mesh(resident).isPresent();
    }

    /** Public observation used by the client acceptance scenario, without depending on private EF caches. */
    public static String rendering(ResidentEntity resident) {
        return ResidentLooks.of(resident).appearance() instanceof ResidentLook.Model
            ? supports(resident) ? "converted_geo" : "fallback_skin"
            : "skin";
    }

    public static synchronized Optional<AssetAccessor<HumanoidMesh>> mesh(ResidentEntity resident) {
        if (!(ResidentLooks.of(resident).appearance() instanceof ResidentLook.Model model)) {
            return Optional.empty();
        }
        Key key = new Key(model.geometry(), model.scale());
        Optional<Asset> cached = CACHE.get(key);
        if (cached != null) {
            return cached.map(asset -> asset);
        }
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        if (resources.getResource(model.geometry()).isEmpty()) {
            // The server may still be transferring the pack. Its normal reload will install the file.
            return Optional.empty();
        }
        Optional<Asset> loaded;
        try (InputStream stream = resources.open(model.geometry())) {
            byte[] bytes = stream.readNBytes(GeoMeshData.MAX_SOURCE_CHARS + 1);
            if (bytes.length > GeoMeshData.MAX_SOURCE_CHARS) {
                throw new IllegalArgumentException("geometry exceeds the conversion size limit");
            }
            GeoMeshData.Data data = GeoMeshData.convert(new String(bytes, StandardCharsets.UTF_8), model.scale());
            ResourceLocation name = ResourceLocation.fromNamespaceAndPath("folkways_epicfight",
                "resident_geo/" + Integer.toUnsignedString(key.hashCode(), 16));
            loaded = Optional.of(new Asset(name, build(data)));
            LOGGER.info("converted resident GEO {} ({} faces) to the standard Epic Fight humanoid armature",
                model.geometry(), data.quads());
        } catch (IOException | RuntimeException broken) {
            LOGGER.warn("resident GEO {} uses its fallback skin during Epic Fight actions: {}",
                model.geometry(), broken.toString());
            loaded = Optional.empty();
        }
        CACHE.put(key, loaded);
        while (CACHE.size() > MAX_CACHED_MODELS) {
            Key oldest = CACHE.keySet().iterator().next();
            CACHE.remove(oldest).ifPresent(asset -> release(asset.get()));
        }
        return loaded.map(asset -> asset);
    }

    public static synchronized void clear() {
        CACHE.values().forEach(value -> value.ifPresent(asset -> release(asset.get())));
        CACHE.clear();
    }

    private static void release(HumanoidMesh mesh) {
        if (RenderSystem.isOnRenderThreadOrInit()) {
            mesh.destroy();
        } else {
            RenderSystem.recordRenderCall(mesh::destroy);
        }
    }

    private static HumanoidMesh build(GeoMeshData.Data data) {
        Map<String, Number[]> arrays = new LinkedHashMap<>();
        arrays.put("positions", boxed(data.positions()));
        arrays.put("normals", boxed(data.normals()));
        arrays.put("uvs", boxed(data.uvs()));
        arrays.put("weights", new Float[] {1.0F});
        Integer[] counts = new Integer[data.joints().length];
        Integer[] indices = new Integer[data.joints().length * 2];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = 1;
            indices[i * 2] = data.joints()[i];
            indices[i * 2 + 1] = 0;
        }
        arrays.put("vcounts", counts);
        arrays.put("vindices", indices);
        Map<MeshPartDefinition, List<VertexBuilder>> parts = new LinkedHashMap<>();
        for (String name : HUMANOID_PARTS) {
            parts.put(new Part(name), List.of());
        }
        data.parts().forEach((name, corners) -> {
            int[] triplets = new int[corners.length * 3];
            for (int i = 0; i < corners.length; i++) {
                triplets[i * 3] = corners[i];
                triplets[i * 3 + 1] = corners[i];
                triplets[i * 3 + 2] = corners[i];
            }
            parts.put(new Part(name), VertexBuilder.create(triplets));
        });
        return new HumanoidMesh(arrays, parts, null, null);
    }

    private static Float[] boxed(float[] values) {
        Float[] boxed = new Float[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return boxed;
    }
}
