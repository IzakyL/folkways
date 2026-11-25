package io.github.izakyl.folkways.core.base.persist.upgrade;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class ConfigRenamesGameTests {
    private static final String RELEASED = "folkways_test_released-server.toml";
    private static final String CURRENT = "folkways_test_current-server.toml";

    private ConfigRenamesGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ConfigRenamesGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void releasedConfigsMoveToTheirCurrentNameWithoutOverwriting(GameTestHelper helper) {
        Path instance = folder("instance");
        Path defaults = folder("defaults");
        Path kept = folder("kept");
        Path world = folder("world");
        try {
            write(instance.resolve(RELEASED), "released = 1");
            write(kept.resolve(RELEASED), "released = 2");
            write(kept.resolve(CURRENT), "current = 3");
            ConfigRenames.register(RELEASED, CURRENT, List.of(instance, defaults, kept));

            helper.assertTrue(read(instance.resolve(CURRENT)).equals("released = 1"),
                "the released file takes the current name with its settings");
            helper.assertFalse(Files.exists(instance.resolve(RELEASED)), "nothing is left under the released name");
            helper.assertFalse(Files.exists(defaults.resolve(CURRENT)), "a folder without the file is left alone");
            helper.assertTrue(read(kept.resolve(CURRENT)).equals("current = 3"), "a current file is never overwritten");
            helper.assertTrue(read(kept.resolve(RELEASED)).equals("released = 2"), "the shadowed file is kept, unread");

            write(world.resolve(RELEASED), "released = 4");
            ConfigRenames.world(world);
            helper.assertTrue(read(world.resolve(CURRENT)).equals("released = 4"),
                "a world's override folder is renamed when that world starts");
        } finally {
            Stream.of(instance, defaults, kept, world).forEach(ConfigRenamesGameTests::delete);
        }
        helper.succeed();
    }

    private static Path folder(String name) {
        try {
            return Files.createTempDirectory("folkways-config-" + name);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static void delete(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // A leftover temporary folder does not affect the test.
        }
    }
}
