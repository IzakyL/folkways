package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LibraryPathGameTests {

    private static final String TEMPLATE = "empty";

    private static final List<String> ESCAPES = List.of(
        "../secret.nbt",
        "..\\secret.nbt",
        "houses/../../secret.nbt",
        "/etc/passwd",
        "\\\\server\\share\\x.nbt",
        "C:/windows/x.nbt",
        "houses//x.nbt",
        "houses/./x.nbt",
        "...",
        "  ",
        "",
        "house\u0000.nbt",
        "hou:se.nbt");

    private LibraryPathGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LibraryPathGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aPlainNameParses(GameTestHelper helper) {
        LibraryPath path = LibraryPath.of("houses/oak cottage.nbt").orElseThrow();
        helper.assertValueEqual(path.segments().size(), 2, "segments");
        helper.assertTrue(path.name().equals("oak cottage.nbt"), "leaf is the file's own name");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void nothingEscapesTheLibraryFolder(GameTestHelper helper) {
        for (String escape : ESCAPES) {
            helper.assertTrue(LibraryPath.of(escape).isEmpty(),
                "a name that must not parse did: " + escape);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void depthIsBounded(GameTestHelper helper) {
        String deep = String.join("/", java.util.Collections.nCopies(LibraryPath.MAX_DEPTH + 1, "a"));
        helper.assertTrue(LibraryPath.of(deep).isEmpty(), "a path past MAX_DEPTH must not parse");
        helper.succeed();
    }
}
