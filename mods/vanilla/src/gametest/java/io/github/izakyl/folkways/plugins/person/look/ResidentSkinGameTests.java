package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentSkinGameTests {

    private static final String TEMPLATE = "empty";

    private ResidentSkinGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentSkinGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aPngOnTheSkinShelfIsALookWhoseTextureTheServerCarries(GameTestHelper helper) {
        Skins files = new Skins();
        files.png("farmers/wren", skin(64, false));
        ModelLibrary library = ModelLibrary.read(files.held);

        helper.assertValueEqual(library.skinsRejected(), java.util.List.of(), "nothing should have been refused");
        ResourceLocation id = id("skin/farmers/wren");
        helper.assertValueEqual(library.entries().keySet(), Set.of(id), "one look, named after its path");
        ResidentLook.Skin skin = (ResidentLook.Skin) library.entries().get(id).appearance();
        helper.assertValueEqual(skin.texture(), id("textures/entity/resident_skins/farmers/wren.png"),
            "the texture sits where the client's cache pack serves it");
        helper.assertTrue(library.assets().containsKey(ModelLibrary.packPath(skin.texture())),
            "and the png itself is carried to the players");
        helper.assertValueEqual(skin.arms(), ResidentLook.Arms.WIDE, "a skin filled to the edge has wide arms");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void armsAreReadOffThePixelsUnlessTheJsonSays(GameTestHelper helper) {
        Skins files = new Skins();
        files.png("slender", skin(64, true));
        files.png("broad", skin(128, true));
        files.text("broad.json", "{\"arms\":\"wide\",\"weight\":3}");
        ModelLibrary library = ModelLibrary.read(files.held);

        ResidentLook slender = library.entries().get(id("skin/slender"));
        ResidentLook broad = library.entries().get(id("skin/broad"));
        helper.assertValueEqual(((ResidentLook.Skin) slender.appearance()).arms(), ResidentLook.Arms.SLIM,
            "a clear last column on the arm's back is a slim skin");
        helper.assertValueEqual(((ResidentLook.Skin) broad.appearance()).arms(), ResidentLook.Arms.WIDE,
            "the json beside it outranks the pixels, at any multiple of 64");
        helper.assertValueEqual(broad.weight(), 3, "and gives the weight");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void whatCannotBeASkinIsTurnedAwayWithAReason(GameTestHelper helper) {
        Skins files = new Skins();
        files.png("legacy", skin64x32());
        files.text("broken.png", "not really a png");
        files.text("lonely.json", "{\"arms\":\"slim\"}");
        files.text("notes.txt", "hello");
        ModelLibrary library = ModelLibrary.read(files.held);

        helper.assertValueEqual(library.entries(), Map.of(), "nothing may reach the pool");
        helper.assertValueEqual(library.skinsRejected().size(), 4,
            "one line for each file; actual=" + library.skinsRejected());
        helper.assertTrue(library.skinsRejected().stream().anyMatch(line -> line.contains("64x32")),
            "the old half-height layout is named by its size; actual=" + library.skinsRejected());
        helper.assertValueEqual(library.rejected(), java.util.List.of(), "and none of it is a model's");
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, path);
    }

    private static byte[] skin(int width, boolean slim) {
        BufferedImage image = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                image.setRGB(x, y, 0xFF808080);
            }
        }
        int scale = width / 64;
        if (slim) {
            for (int x = 54 * scale; x < 56 * scale; x++) {
                for (int y = 20 * scale; y < 32 * scale; y++) {
                    image.setRGB(x, y, 0);
                }
            }
        }
        return png(image);
    }

    private static byte[] skin64x32() {
        return png(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB));
    }

    private static byte[] png(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    private static final class Skins {
        final Map<ResourceLocation, IoSupplier<InputStream>> held = new LinkedHashMap<>();

        void png(String name, byte[] bytes) {
            put(name + ".png", bytes);
        }

        void text(String file, String content) {
            put(file, content.getBytes(StandardCharsets.UTF_8));
        }

        private void put(String file, byte[] bytes) {
            held.put(id(ModelLibrary.SKIN_FOLDER + "/" + file), () -> new ByteArrayInputStream(bytes));
        }
    }
}
