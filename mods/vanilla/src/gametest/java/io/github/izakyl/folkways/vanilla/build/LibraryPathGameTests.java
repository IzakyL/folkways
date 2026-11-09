package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.Collections;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
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

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void onlyAPlainNameWithinTheLibraryParses(GameTestHelper helper) {
        LibraryPath path = LibraryPath.of("houses/oak cottage.nbt").orElseThrow();
        helper.assertValueEqual(path.segments().size(), 2, "segments");
        helper.assertTrue(path.name().equals("oak cottage.nbt"), "leaf is the file's own name");
        for (String escape : ESCAPES) {
            helper.assertTrue(LibraryPath.of(escape).isEmpty(), "a name that must not parse did: " + escape);
        }
        String deep = String.join("/", Collections.nCopies(LibraryPath.MAX_DEPTH + 1, "a"));
        helper.assertTrue(LibraryPath.of(deep).isEmpty(), "a path past MAX_DEPTH must not parse");
        helper.succeed();
    }
}
