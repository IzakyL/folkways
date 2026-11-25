package io.github.izakyl.folkways.core.library;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FileNamesGameTests {

    private FileNamesGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FileNamesGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void fileNamesRoundTripWithoutRelaxingResourcePaths(GameTestHelper helper) {
        for (String name : List.of("房子", "Zombie", "Black_Red3", "房子_House3", "blue", "a-b_3.nbt")) {
            String encoded = FileNames.encode(name);
            helper.assertTrue(encoded.matches("[a-z0-9_.-]+"), "strict lowercase path: " + encoded);
            helper.assertValueEqual(FileNames.decode(encoded), name, "original segment");
            if (name.matches("[a-z0-9_.-]+")) {
                helper.assertValueEqual(encoded, name, "ordinary names stay unchanged");
            }
        }
        for (String name : List.of("__utf8_626c7565", "__utf8_xyz", "__utf8_")) {
            helper.assertValueEqual(FileNames.decode(name), name, "ordinary prefix names are not decoded");
        }
        String path = "房子/Zombie.house.nbt";
        helper.assertValueEqual(FileNames.decodePath(FileNames.encodePath(path)), path, "nested file path");
        helper.assertTrue(FileNames.encodePath(path).endsWith(".nbt"), "suffix remains visible");
        helper.succeed();
    }
}
