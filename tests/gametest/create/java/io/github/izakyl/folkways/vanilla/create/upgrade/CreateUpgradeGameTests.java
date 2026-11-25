package io.github.izakyl.folkways.vanilla.create.upgrade;

import io.github.izakyl.folkways.core.base.persist.upgrade.DiskUpgradeFixtures;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysVanillaMod.ID)
@PrefixGameTestTemplate(false)
public final class CreateUpgradeGameTests {
    private CreateUpgradeGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CreateUpgradeGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void v5UpgradesCreateRiderFromDisk(GameTestHelper helper) throws Exception {
        DiskUpgradeFixtures.versionFourRiderRoundTrip(helper);
    }
}
