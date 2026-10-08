package io.github.izakyl.folkways.plugins.person.look;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OnlyIn(Dist.CLIENT)
public final class ClientResidentModels {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-models");
    private static final String PACK_ID = "folkways/resident_models";
    private static final String ROOT = "folkways/resident-models";
    private static final String FINGERPRINT_FILE = "fingerprint.txt";
    private static final String ASSETS_DIR = "assets";
    private static final int MAX_LIBRARY_BYTES = ModelLibrary.MAX_LIBRARY_BYTES;
    private static final Pattern PACK_PATH = Pattern.compile("assets(/[a-z0-9_.-]+)+");
    private static final Pattern DOTS_ONLY = Pattern.compile("(^|/)\\.+(/|$)");

    private static Transfer pending;

    private ClientResidentModels() {
    }

    public static Path packRoot() {
        return FMLPaths.GAMEDIR.get().resolve(ROOT);
    }

    public static void addPackFinder(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        Path root = packRoot();
        if (!writeMetadata(root)) {
            return;
        }
        Pack pack = Pack.readMetaAndCreate(
            new PackLocationInfo(PACK_ID, Component.translatable("folkways.models.pack"),
                PackSource.BUILT_IN, Optional.empty()),
            new Pack.ResourcesSupplier() {
                @Override
                public PackResources openPrimary(PackLocationInfo info) {
                    return new PathPackResources(info, root);
                }

                @Override
                public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                    return new PathPackResources(info, root);
                }
            },
            PackType.CLIENT_RESOURCES,
            new PackSelectionConfig(true, Pack.Position.TOP, false));
        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        }
    }

    public static void handleLibrary(ModelLibraryPacket packet) {
        ResidentLooks.installRuntime(packet.pool());
        pending = null;
        if (packet.assets().isEmpty() || packet.fingerprint().equals(fingerprintOnDisk())) {
            return;
        }
        if (!ModList.get().isLoaded("geckolib")) {
            return;
        }
        Transfer transfer = Transfer.of(packet);
        if (transfer == null) {
            return;
        }
        pending = transfer;
        PacketDistributor.sendToServer(new RequestModelAssetsPacket(packet.fingerprint()));
    }

    public static void handleAsset(ModelAssetPacket packet) {
        Transfer transfer = pending;
        if (transfer == null || !transfer.fingerprint.equals(packet.fingerprint())) {
            return;
        }
        if (!transfer.accept(packet)) {
            pending = null;
            LOGGER.warn("[folkways-models] discarding the transfer: {} does not fit what was offered", packet.path());
            return;
        }
        if (transfer.complete()) {
            pending = null;
            install(transfer);
        }
    }
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        pending = null;
        ResidentLooks.forgetRuntime();
    }

    private static void install(Transfer transfer) {
        Path root = packRoot();
        try {
            wipe(root.resolve(ASSETS_DIR));
            for (Map.Entry<String, byte[]> asset : transfer.assemble().entrySet()) {
                Path file = resolve(root, asset.getKey()).orElseThrow();
                Files.createDirectories(file.getParent());
                Files.write(file, asset.getValue());
            }
            Files.writeString(root.resolve(FINGERPRINT_FILE), transfer.fingerprint, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("[folkways-models] could not write the model cache at {}", root, exception);
            return;
        }
        LOGGER.info("[folkways-models] wrote {} files to {}; reloading resources",
            transfer.expected.size(), root);
        Minecraft.getInstance().reloadResourcePacks();
    }

    private static String fingerprintOnDisk() {
        Path file = packRoot().resolve(FINGERPRINT_FILE);
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException exception) {
            return "";
        }
    }

    private static Optional<Path> resolve(Path root, String path) {
        if (!PACK_PATH.matcher(path).matches() || DOTS_ONLY.matcher(path).find()) {
            return Optional.empty();
        }
        Path file = root.resolve(path).normalize();
        return file.startsWith(root.normalize()) ? Optional.of(file) : Optional.empty();
    }

    private static void wipe(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> deepestFirst = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : deepestFirst) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static boolean writeMetadata(Path root) {
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve("pack.mcmeta"), """
                {
                  "pack": {
                    "pack_format": %d,
                    "description": "Folkways resident models"
                  }
                }
                """.formatted(SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES)),
                StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            LOGGER.warn("[folkways-models] could not prepare the model cache at {}", root, exception);
            return false;
        }
    }

    private static final class Transfer {
        private final String fingerprint;
        private final Map<String, Integer> expected;
        private final Map<String, List<byte[]>> arrived = new LinkedHashMap<>();

        private Transfer(String fingerprint, Map<String, Integer> expected) {
            this.fingerprint = fingerprint;
            this.expected = expected;
        }

        static Transfer of(ModelLibraryPacket packet) {
            Map<String, Integer> expected = new LinkedHashMap<>();
            long total = 0;
            for (ModelLibraryPacket.Asset asset : packet.assets()) {
                if (asset.bytes() < 0 || resolve(packRoot(), asset.path()).isEmpty()) {
                    LOGGER.warn("[folkways-models] refusing the offered path {}", asset.path());
                    return null;
                }
                total += asset.bytes();
                expected.put(asset.path(), asset.bytes());
            }
            if (total > MAX_LIBRARY_BYTES) {
                LOGGER.warn("[folkways-models] refusing {} bytes of models, past the {} cap", total, MAX_LIBRARY_BYTES);
                return null;
            }
            return new Transfer(packet.fingerprint(), expected);
        }

        boolean accept(ModelAssetPacket packet) {
            Integer size = expected.get(packet.path());
            if (size == null || packet.index() < 0 || packet.count() <= 0 || packet.index() >= packet.count()) {
                return false;
            }
            List<byte[]> slices = arrived.computeIfAbsent(packet.path(), key -> new ArrayList<>());
            if (packet.index() != slices.size()) {
                return false;
            }
            slices.add(packet.chunk());
            return slices.stream().mapToInt(slice -> slice.length).sum() <= size;
        }

        boolean complete() {
            return expected.entrySet().stream().allMatch(asset -> bytesOf(asset.getKey()) == asset.getValue());
        }

        private int bytesOf(String path) {
            return arrived.getOrDefault(path, List.of()).stream().mapToInt(slice -> slice.length).sum();
        }

        Map<String, byte[]> assemble() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            arrived.forEach((path, slices) -> {
                byte[] whole = new byte[bytesOf(path)];
                int at = 0;
                for (byte[] slice : slices) {
                    System.arraycopy(slice, 0, whole, at, slice.length);
                    at += slice.length;
                }
                files.put(path, whole);
            });
            return files;
        }
    }
}
