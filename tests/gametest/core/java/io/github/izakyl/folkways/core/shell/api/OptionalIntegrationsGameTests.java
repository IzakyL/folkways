package io.github.izakyl.folkways.core.shell.api;

import com.google.gson.JsonParser;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class OptionalIntegrationsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(OptionalIntegrationsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void reportsOnlyInstalledDisabledIntegrations(GameTestHelper helper) {
        var decisions = OptionalIntegrations.decide(integration -> switch (integration) {
            case CREATE -> installed("Create", "6.0.7", true);
            case CBC -> installed("Create Big Cannons", "6.0.0", true);
            case EPIC_FIGHT -> installed("Epic Fight", "21.17.3.1", false);
            default -> null;
        });
        helper.assertTrue(decisions.size() == 3, "Only installed targets get startup decisions");
        var disabled = OptionalIntegrations.disabled(decisions);
        helper.assertTrue(disabled.equals(List.of(
            new OptionalIntegrations.Decision(OptionalIntegrations.CBC, "Create Big Cannons", "6.0.0",
                "[5.11.7]", OptionalIntegrations.Reason.INCOMPATIBLE),
            new OptionalIntegrations.Decision(OptionalIntegrations.EPIC_FIGHT, "Epic Fight", "21.17.3.1",
                "[21.17.3.1]", OptionalIntegrations.Reason.MANUAL))), "Expected both disable reasons and version details");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void noticeTranslationsExist(GameTestHelper helper) throws IOException {
        for (String locale : List.of("en_us", "zh_cn", "zh_hk", "zh_tw")) {
            String path = "/assets/folkways_core/lang/" + locale + ".json";
            var stream = OptionalIntegrations.class.getResourceAsStream(path);
            helper.assertTrue(stream != null, "Missing " + path);
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var strings = JsonParser.parseReader(reader).getAsJsonObject();
                for (String reason : List.of("manual", "incompatible")) {
                    String key = "folkways.settings.integration." + reason;
                    helper.assertTrue(strings.has(key) && !strings.get(key).getAsString().isBlank(),
                        "Missing " + key + " in " + locale);
                }
            }
        }
        helper.succeed();
    }

    private static OptionalIntegrations.Installed installed(String name, String version, boolean allowed) {
        return new OptionalIntegrations.Installed(name, new DefaultArtifactVersion(version), allowed);
    }
}
