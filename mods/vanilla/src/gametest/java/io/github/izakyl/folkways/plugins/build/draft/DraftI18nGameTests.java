package io.github.izakyl.folkways.plugins.build.draft;

import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.draw;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.parse;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.refused;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.zone;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftI18nGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftI18nGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aRejectionKeepsItsKeyAndTypedArguments(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                reject("folkways.draft.error.material", translated("folkways.draft.knob.deck"))
            """);
        Drawn.Refused failure = refused(helper, draw(pattern, zone(1, 1, 1)));
        Notice explanation = failure.explanation().orElseThrow();
        helper.assertTrue(explanation.key().equals("folkways.draft.error.material")
            && explanation.args().equals(List.of(Notice.named("folkways.draft.knob.deck"))),
            "both the reason and its material label are translated on the client");
        TranslatableContents outer = (TranslatableContents) failure.component().getContents();
        Component nested = (Component) outer.getArgs()[0];
        helper.assertTrue(nested.getContents() instanceof TranslatableContents,
            "the error detail stays a component, never a Notice.Arg's Java spelling");
        Pattern numeric = parse("""
            def draw(site):
                reject("folkways.draft.error.road.ground", 12, -3)
            """);
        helper.assertTrue(refused(helper, draw(numeric, zone(1, 1, 1))).explanation().orElseThrow().args()
            .equals(List.of(Notice.count(12), Notice.count(-3))), "numeric arguments keep their values");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aWrongMarkNamesTheShapeInThePlayersLanguage(GameTestHelper helper) {
        Pattern pattern = parse("""
            accepts = ["path"]
            def draw(site):
                return []
            """);
        Drawn.Refused failure = refused(helper, draw(pattern, zone(1, 1, 1)));
        helper.assertTrue(failure.why() == DraftRefusal.WRONG_MARK && failure.explanation()
            .filter(notice -> notice.key().equals("folkways.draft.mark.zone")).isPresent(),
            "a marked area's kind is a translation, not the raw zone token");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void customPatternsKeepLiteralFailuresAndRejectBadArguments(GameTestHelper helper) {
        Pattern literal = parse("""
            def draw(site):
                fail("custom diagnostic")
            """);
        Drawn.Refused failure = refused(helper, draw(literal, zone(1, 1, 1)));
        helper.assertTrue(failure.explanation().isEmpty() && failure.detail().contains("custom diagnostic"),
            "existing third-party fail calls still show their own diagnostic");
        for (String arguments : List.of("[]", "1, 2, 3, 4, 5")) {
            Pattern invalid = parse("""
                def draw(site):
                    reject("folkways.draft.error.material", %s)
                """.formatted(arguments));
            helper.assertTrue(refused(helper, draw(invalid, zone(1, 1, 1))).explanation().isEmpty(),
                "invalid reject arguments fail as a script diagnostic");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void chinesePatternWordsFallBackOneKeyAtATime(GameTestHelper helper) {
        Face face = new Face(Map.of(), Map.of(
            "en_us", Map.of("title", "Road", "only_en", "English fallback"),
            "zh_cn", Map.of("title", "道路", "only_cn", "简体回退"),
            "zh_tw", Map.of("title", "道路", "shared", "共用繁體"),
            "zh_hk", Map.of("title", "香港標題")));
        helper.assertTrue(face.word("zh_hk", "title").orElseThrow().equals("香港標題"), "exact locale wins");
        helper.assertTrue(face.word("zh_hk", "shared").orElseThrow().equals("共用繁體"),
            "Hong Kong can use Taiwan wording for a missing key");
        helper.assertTrue(face.word("zh_tw", "only_cn").orElseThrow().equals("简体回退"),
            "an older Chinese-only custom pattern stays readable");
        helper.assertTrue(face.word("zh_hk", "only_en").orElseThrow().equals("English fallback"),
            "English is the final fallback");
        helper.assertTrue(face.word("fr_fr", "title").orElseThrow().equals("Road"),
            "other languages fall back to English");
        helper.assertTrue(face.word("zh_hk", "missing").isEmpty(), "missing words stay absent");
        helper.succeed();
    }
}
