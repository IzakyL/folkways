package io.github.izakyl.folkways.plugins.person.look;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.ExtraCodecs;

// A plain skin is one png, with a json beside it of the same name when it wants to say its arms or its weight.
final class ResidentSkins {

    private static final String PNG = ".png";
    private static final String JSON = ".json";
    private static final int UNIT = 64;

    private ResidentSkins() {
    }

    static void read(Map<ResourceLocation, IoSupplier<InputStream>> files, Map<ResourceLocation, String> packs,
            Map<ResourceLocation, ResidentLook> entries, Map<String, byte[]> assets, List<String> rejected,
            Map<ResourceLocation, String> from) {
        String folder = ModelLibrary.SKIN_FOLDER + "/";
        for (Map.Entry<ResourceLocation, IoSupplier<InputStream>> file : files.entrySet()) {
            ResourceLocation at = file.getKey();
            String inFolder = at.getPath().substring(folder.length());
            if (inFolder.endsWith(JSON)) {
                ResourceLocation png = at.withPath(at.getPath().substring(0, at.getPath().length() - JSON.length()) + PNG);
                if (!files.containsKey(png)) {
                    rejected.add(at + ": no " + png.getPath().substring(folder.length()) + " beside it");
                }
                continue;
            }
            if (!inFolder.endsWith(PNG)) {
                rejected.add(at + ": a skin is a .png, with an optional .json of the same name");
                continue;
            }
            String stem = inFolder.substring(0, inFolder.length() - PNG.length());
            ResourceLocation json = at.withPath(folder + stem + JSON);
            readSkin(at, stem, file.getValue(), files.get(json), assets, rejected).ifPresent(look -> {
                ResourceLocation id = at.withPath("skin/" + stem);
                entries.put(id, look);
                from.put(id, packs.getOrDefault(at, ""));
            });
        }
    }

    private static Optional<ResidentLook> readSkin(ResourceLocation at, String stem, IoSupplier<InputStream> png,
            IoSupplier<InputStream> json, Map<String, byte[]> assets, List<String> rejected) {
        String label = at.toString();
        byte[] pixels = ModelLibrary.read(Map.of(PNG, png), PNG, label, rejected);
        if (pixels == null) {
            return Optional.empty();
        }
        BufferedImage image = imageOf(pixels);
        if (image == null) {
            rejected.add(label + ": not a png that can be read");
            return Optional.empty();
        }
        int width = image.getWidth();
        if (width % UNIT != 0 || image.getHeight() != width) {
            rejected.add(label + ": a skin is square and a multiple of 64 wide, the 64x64 layout; this one is "
                + width + "x" + image.getHeight());
            return Optional.empty();
        }
        Manifest manifest = Manifest.DEFAULT;
        if (json != null) {
            byte[] written = ModelLibrary.read(Map.of(JSON, json), JSON, label, rejected);
            if (written == null) {
                return Optional.empty();
            }
            try {
                Optional<Manifest> parsed = Manifest.CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString(new String(written, StandardCharsets.UTF_8)))
                    .resultOrPartial(error -> rejected.add(label + ": " + stem + JSON + " - " + error));
                if (parsed.isEmpty()) {
                    return Optional.empty();
                }
                manifest = parsed.get();
            } catch (RuntimeException broken) {
                rejected.add(label + ": " + stem + JSON + " - " + broken.getMessage());
                return Optional.empty();
            }
        }
        if (assets.values().stream().mapToInt(bytes -> bytes.length).sum() + pixels.length
                > ModelLibrary.MAX_LIBRARY_BYTES) {
            rejected.add(label + ": the looks are past " + ModelLibrary.MAX_LIBRARY_BYTES
                + " bytes in total; this skin is not carried");
            return Optional.empty();
        }
        ResourceLocation texture = at.withPath("textures/entity/resident_skins/" + stem + PNG);
        assets.put(ModelLibrary.packPath(texture), pixels);
        ResidentLook.Arms arms = manifest.arms().orElseGet(() -> armsOf(image));
        return Optional.of(new ResidentLook(new ResidentLook.Skin(texture, arms), manifest.weight()));
    }

    private static BufferedImage imageOf(byte[] pixels) {
        try {
            return ImageIO.read(new ByteArrayInputStream(pixels));
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    // A slim arm is three pixels wide, so the last column of the right arm's back is left clear.
    static ResidentLook.Arms armsOf(BufferedImage image) {
        int scale = image.getWidth() / UNIT;
        int alpha = image.getRGB(55 * scale, 20 * scale) >>> 24;
        return alpha == 0 ? ResidentLook.Arms.SLIM : ResidentLook.Arms.WIDE;
    }

    record Manifest(Optional<ResidentLook.Arms> arms, int weight) {

        static final Manifest DEFAULT = new Manifest(Optional.empty(), 1);

        static final Codec<Manifest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResidentLook.Arms.CODEC.optionalFieldOf("arms").forGetter(Manifest::arms),
            ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(Manifest::weight)
        ).apply(instance, Manifest::new));
    }
}
