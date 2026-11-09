package io.github.izakyl.folkways.vanilla.person.name;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
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
public final class ResidentNameGameTests {

    private static final String TEMPLATE = "empty";
    private static final int SAMPLES = 200;

    private ResidentNameGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentNameGameTests.class);
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void theDrawSpreadsOverThePool(GameTestHelper helper) {
        Set<String> drawn = new HashSet<>();
        for (int i = 0; i < SAMPLES; i++) {
            UUID resident = new UUID(0x5EEDL, i);
            String name = ResidentNames.of(helper.getLevel().registryAccess(), resident).getString();
            helper.assertTrue(name.split(" ").length == 2, "a default name must have two parts: " + name);
            helper.assertTrue(name.equals(ResidentNames.of(helper.getLevel().registryAccess(), resident).getString()),
                "a resident's name must stay stable across reads");
            drawn.add(name);
        }
        helper.assertTrue(drawn.size() > 1,
            "every resident drew the same name (" + drawn + ")");
        helper.succeed();
    }
}
