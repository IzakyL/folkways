package io.github.izakyl.folkways.plugins.person.look;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.FolkwaysMod;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// The resource pack the maids are drawn from: nothing on disk, each file made on the spot from the model the
// installed Touhou Little Maid holds. Their textures need no making; they are read where that mod keeps them.
@OnlyIn(Dist.CLIENT)
public final class ClientMaidModels {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-models");
    private static final String PACK_ID = "folkways/maid_models";
    private static final Set<String> FOLDERS = Set.of("geo", "animations");
    private static final String MADE_UNDER = "/resident_models/maid/";

    private static SoftReference<Map<ResourceLocation, byte[]>> kept = new SoftReference<>(null);

    private ClientMaidModels() {
    }

    public static void addPackFinder(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES || !ModList.get().isLoaded("geckolib")
                || MaidLooks.all().isEmpty()) {
            return;
        }
        Pack pack = Pack.readMetaAndCreate(
            new PackLocationInfo(PACK_ID, Component.translatable("folkways.models.maids"),
                PackSource.BUILT_IN, Optional.empty()),
            new Pack.ResourcesSupplier() {
                @Override
                public PackResources openPrimary(PackLocationInfo info) {
                    return new Made(info);
                }

                @Override
                public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                    return new Made(info);
                }
            },
            PackType.CLIENT_RESOURCES,
            new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        }
    }

    // Kept only as long as memory allows: the files are asked for at each resource reload and not between.
    private static synchronized Map<ResourceLocation, byte[]> files() {
        Map<ResourceLocation, byte[]> files = kept.get();
        if (files == null) {
            files = MaidLooks.packs().map(ClientMaidModels::make).orElseGet(Map::of);
            kept = new SoftReference<>(files);
        }
        return files;
    }

    // A maid whose files cannot be made is left out, and is then drawn in its fallback skin.
    private static Map<ResourceLocation, byte[]> make(Path packs) {
        long began = System.nanoTime();
        JsonObject defaults = MaidLooks.installed(MaidLooks.MAID_DEFAULTS.split("/"))
            .map(ClientMaidModels::readOrEmpty)
            .orElseGet(JsonObject::new);
        Map<ResourceLocation, byte[]> files = new LinkedHashMap<>();
        for (MaidLooks.Maid maid : MaidLooks.all()) {
            try {
                JsonObject model = MaidLooks.json(packs.resolve(maid.geometry()));
                List<String> bones = MaidAssets.boneNames(model);
                if (maid.gecko()) {
                    List<JsonObject> layered = new ArrayList<>(List.of(defaults));
                    for (String file : maid.animations()) {
                        layered.add(MaidLooks.json(packs.resolve(file)));
                    }
                    JsonObject animations = MaidAssets.merged(layered);
                    files.put(maid.geometryAt(),
                        bytes(MaidAssets.geometry(model, MaidAssets.hiddenByLayers(animations, bones))));
                    files.put(maid.animationsAt(), bytes(MaidAssets.animations(maid, animations)));
                } else {
                    files.put(maid.geometryAt(),
                        bytes(MaidAssets.geometry(model, MaidAssets.hiddenByScripts(maid.animations(), bones))));
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("[folkways-models] maid {} cannot be drawn ({})", maid.model(), exception.toString());
            }
        }
        LOGGER.info("[folkways-models] made {} files for {} maids in {} ms",
            files.size(), MaidLooks.all().size(), (System.nanoTime() - began) / 1_000_000);
        return files;
    }

    private static JsonObject readOrEmpty(Path file) {
        try {
            return MaidLooks.json(file);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("[folkways-models] {} is unreadable ({})", file, exception.toString());
            return new JsonObject();
        }
    }

    private static byte[] bytes(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isMade(String path) {
        int slash = path.indexOf('/');
        return slash > 0 && FOLDERS.contains(path.substring(0, slash)) && path.startsWith(MADE_UNDER, slash);
    }

    private record Made(PackLocationInfo info) implements PackResources {

        @Override
        public IoSupplier<InputStream> getRootResource(String... elements) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            if (type != PackType.CLIENT_RESOURCES || !location.getNamespace().equals(FolkwaysMod.MOD_ID)
                    || !isMade(location.getPath())) {
                return null;
            }
            byte[] file = files().get(location);
            return file == null ? null : () -> new ByteArrayInputStream(file);
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            if (type != PackType.CLIENT_RESOURCES || !namespace.equals(FolkwaysMod.MOD_ID)
                    || !FOLDERS.contains(path.split("/", 2)[0])) {
                return;
            }
            files().forEach((location, file) -> {
                if (location.getPath().startsWith(path + "/")) {
                    output.accept(location, () -> new ByteArrayInputStream(file));
                }
            });
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            return type == PackType.CLIENT_RESOURCES ? Set.of(FolkwaysMod.MOD_ID) : Set.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
            if (deserializer != PackMetadataSection.TYPE) {
                return null;
            }
            return (T) new PackMetadataSection(Component.translatable("folkways.models.maids"),
                SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES), Optional.empty());
        }

        @Override
        public PackLocationInfo location() {
            return info;
        }

        @Override
        public void close() {
        }
    }
}
