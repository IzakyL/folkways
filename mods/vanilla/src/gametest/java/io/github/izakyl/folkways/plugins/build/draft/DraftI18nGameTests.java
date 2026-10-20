package io.github.izakyl.folkways.plugins.build.draft;

import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.draw;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.parse;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.refused;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.zone;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
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
            lang = {"en_us": {"error.material": "Cannot use %s", "deck": "deck"}}
            def draw(site):
                reject("error.material", translated("deck"))
            """);
        Drawn.Refused failure = refused(helper, draw(pattern, zone(1, 1, 1)));
        Notice explanation = failure.explanation().orElseThrow();
        helper.assertTrue(explanation.key().equals(DraftKit.key(pattern, "error.material"))
            && explanation.args().equals(List.of(Notice.named(DraftKit.key(pattern, "deck")))),
            "both the reason and its material label are translated on the client");
        TranslatableContents outer = (TranslatableContents) failure.component().getContents();
        Component nested = (Component) outer.getArgs()[0];
        helper.assertTrue(nested.getContents() instanceof TranslatableContents,
            "the error detail stays a component, never a Notice.Arg's Java spelling");
        helper.assertTrue(PatternText.component(explanation).getString().equals("Cannot use deck"),
            "the English fallback formats the pattern's translated argument");
        Pattern numeric = parse("""
            lang = {"en_us": {"error.ground": "No ground at %s, %s"}}
            def draw(site):
                reject("error.ground", 12, -3)
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
                lang = {"en_us": {"error.material": "Cannot use material"}}
                def draw(site):
                    reject("error.material", %s)
                """.formatted(arguments));
            helper.assertTrue(refused(helper, draw(invalid, zone(1, 1, 1))).explanation().isEmpty(),
                "invalid reject arguments fail as a script diagnostic");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void localRejectionsRemainDistinctAndSurviveStorageAndTheWire(GameTestHelper helper) {
        Pattern first = parse("""
            lang = {
                "en_us": {"error.shared": "First %s", "material": "timber", "only_en": "English fallback"},
                "zh_cn": {"error.shared": "第一%s", "material": "木材"}
            }
            def draw(site):
                reject("error.shared", translated("material"))
            """);
        Pattern second = parse("""
            lang = {"en_us": {"error.shared": "Second"}}
            def draw(site):
                reject("error.shared")
            """);
        Notice original = refused(helper, draw(first, zone(1, 1, 1))).explanation().orElseThrow();
        Notice other = refused(helper, draw(second, zone(1, 1, 1))).explanation().orElseThrow();
        helper.assertFalse(original.key().equals(other.key()), "patterns with the same local key have separate words");
        helper.assertTrue(PatternText.word("en_us", original.key()).orElseThrow().equals("First %s")
            && PatternText.word("en_us", other.key()).orElseThrow().equals("Second"),
            "neither pattern overwrites the other's wording");
        String englishOnly = DraftKit.key(first, "only_en");
        helper.assertTrue(PatternText.word("zh_hk", englishOnly).orElseThrow().equals("English fallback")
            && PatternText.word("zh_tw", englishOnly).orElseThrow().equals("English fallback"),
            "scoped keys use English when every Chinese dictionary lacks the word");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.encode(buffer);
            Notice network = Notice.decode(buffer);
            Notice stored = Notice.load(original.save());
            helper.assertTrue(network.equals(original) && stored.equals(original),
                "local keys and typed arguments survive both network and saved notices");
            for (Notice restored : List.of(network, stored)) {
                helper.assertTrue(PatternText.component(restored).getString().equals("First timber"),
                    "restored notices retain the English fallback and nested local argument");
                helper.assertTrue(PatternText.word("zh_hk", restored.key()).orElseThrow().equals("第一%s")
                    && PatternText.word("zh_tw", restored.key()).orElseThrow().equals("第一%s"),
                    "restored local keys resolve Chinese fallback for Hong Kong and Taiwan");
                String argument = ((Notice.Arg.Named) restored.args().getFirst()).key();
                helper.assertTrue(PatternText.word("zh_hk", argument).orElseThrow().equals("木材"),
                    "nested local arguments retain the player's Chinese fallback");
            }
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void loadedLibrariesContributeWordsAndTheRootOverridesOneKeyAtATime(GameTestHelper helper) {
        Pattern pattern = DraftKit.parse("library_words", """
            load("example:grading.star", "check_ground")
            lang = {"en_us": {"error.ground": "Root needs %s"}}
            def draw(site):
                check_ground()
            """, Map.of("example:grading", """
            lang = {
                "en_us": {"error.ground": "Library needs %s", "material": "stone"},
                "zh_cn": {"error.ground": "地基需要%s", "material": "石材"}
            }
            def check_ground():
                reject("error.ground", translated("material"))
            """));
        Notice explanation = refused(helper, draw(pattern, zone(1, 1, 1))).explanation().orElseThrow();
        helper.assertTrue(PatternText.component(explanation).getString().equals("Root needs stone"),
            "the root replaces a library message while preserving the library's translated argument");
        helper.assertTrue(PatternText.word("zh_hk", explanation.key()).orElseThrow().equals("地基需要%s"),
            "a root override preserves the library's other languages and Chinese fallback");
        String material = ((Notice.Arg.Named) explanation.args().getFirst()).key();
        helper.assertTrue(PatternText.word("zh_tw", material).orElseThrow().equals("石材"),
            "library-only words are included even when lang is not imported by load");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void missingPatternWordsAreDiagnosticsEvenWhenAGlobalKeyExists(GameTestHelper helper) {
        for (String call : List.of("reject(\"folkways.draft.mark.zone\")",
                "reject(\"error.local\", translated(\"folkways.draft.mark.zone\"))")) {
            Pattern pattern = parse("""
                lang = {"en_us": {"error.local": "Local %%s"}}
                def draw(site):
                    %s
                """.formatted(call));
            Drawn.Refused failure = refused(helper, draw(pattern, zone(1, 1, 1)));
            helper.assertTrue(failure.explanation().isEmpty() && failure.detail().contains("folkways.draft.mark.zone"),
                "missing local words report their key without consulting the mod's global translations");
        }
        Pattern local = parse("""
            lang = {"en_us": {"folkways.draft.mark.zone": "Pattern-owned wording"}}
            def draw(site):
                reject("folkways.draft.mark.zone")
            """);
        Notice explanation = refused(helper, draw(local, zone(1, 1, 1))).explanation().orElseThrow();
        helper.assertTrue(PatternText.component(explanation).getString().equals("Pattern-owned wording"),
            "a local word may share a global key spelling and still belongs to the pattern");
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
