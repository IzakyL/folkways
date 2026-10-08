package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftGrowingGameTests {
    private DraftGrowingGameTests() {}

    private static final String COUNTING = """
        accepts = ["zone"]
        knobs = [count("rounds", 1, 8, default = 3)]
        def grow(site):
            n = site.kept.get("n", 0)
            if n >= site.count("rounds"):
                return []
            cell = site.zone.sized(width = 1, height = 1, depth = 1).at(x = n)
            last = n + 1 == site.count("rounds")
            return grown([part("step", box(cell), ["minecraft:stone"])],
                keep = {"n": n + 1, "trail": site.kept.get("trail", []) + [n], "mark": {"at": 1.5, "on": True}},
                last = last)
        """;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftGrowingGameTests.class);
    }

    private static Commission commission(Hint hint, Pattern pattern, Growth growth) {
        return new Commission(hint, DraftKit.Filled.defaulting(pattern.knobs()), World.NONE, Direction.NORTH, 0L,
            growth);
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void roundsCarryWhatTheyKeepUntilGrowAnswersNothing(GameTestHelper helper) {
        Pattern pattern = DraftKit.parse(COUNTING);
        helper.assertTrue(pattern.grows(), "a pattern with grow(site) grows");
        Hint hint = DraftKit.zone(8, 1, 1);
        Growth growth = Growth.first(hint);
        int laid = 0;
        for (int round = 0; round < 3; round++) {
            Round answered = pattern.growOn(commission(hint, pattern, growth));
            if (!(answered instanceof Round.Grew grew)) {
                helper.fail("round " + round + " should grow, got " + answered);
                return;
            }
            helper.assertValueEqual(DraftKit.stateAt(grew.ready().draft(), 0, 0, 0), Blocks.STONE.defaultBlockState(),
                "each round lays its own step");
            helper.assertValueEqual(grew.ready().corner(), new BlockPos(round, 0, 0), "the step moves on each round");
            helper.assertValueEqual(grew.kept().getLong("n"), (long) round + 1, "the count is kept");
            helper.assertValueEqual(grew.kept().getList("trail", 10).size(), round + 1, "the list is kept");
            helper.assertValueEqual(grew.kept().getCompound("mark").getBoolean("on"), true, "a nested dict is kept");
            helper.assertValueEqual(grew.last(), round == 2, "only the final round says it is the last");
            growth = growth.next(grew.kept(), grew.ready().corner(), grew.ready().draft(), hint.anchor());
            helper.assertValueEqual(growth.round(), round + 1, "the round counts up");
            laid++;
        }
        helper.assertValueEqual(laid, 3, "three rounds grew");
        helper.assertTrue(pattern.growOn(commission(hint, pattern, growth)) instanceof Round.Ended,
            "answering no parts ends the growing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void keptValuesReadBackAsTheyWereWritten(GameTestHelper helper) {
        Pattern writer = DraftKit.parse(COUNTING);
        Hint hint = DraftKit.zone(8, 1, 1);
        Round.Grew first = (Round.Grew) writer.growOn(commission(hint, writer, Growth.first(hint)));
        Growth second = Growth.first(hint).next(first.kept(), first.ready().corner(), first.ready().draft(),
            hint.anchor());
        Pattern reader = DraftKit.parse("""
            accepts = ["zone"]
            def grow(site):
                if site.round != 1:
                    fail("round %d" % site.round)
                k = site.kept
                if k["n"] != 1 or k["trail"] != [0] or k["mark"]["at"] != 1.5 or k["mark"]["on"] != True:
                    fail("kept %s" % k)
                return [part("ok", box(site.zone), ["minecraft:stone"])]
            """);
        DraftKit.ready(helper, reader.drawOn(commission(hint, reader, second)));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void grownBelongsToGrowAndAPatternIsOneOrTheOther(GameTestHelper helper) {
        Pattern drawing = DraftKit.parse("""
            accepts = ["zone"]
            def draw(site):
                return grown([part("x", box(site.zone), ["minecraft:stone"])])
            """);
        helper.assertValueEqual(DraftKit.refused(helper, DraftKit.draw(drawing, DraftKit.zone(1, 1, 1))).why(),
            DraftRefusal.PATTERN_FAILED, "draw(site) may not answer grown(...)");
        helper.assertTrue(Pattern.parse(ResourceLocation.withDefaultNamespace("both"), """
            def draw(site):
                return []
            def grow(site):
                return []
            """).isEmpty(), "a pattern with both draw and grow does not load");
        helper.assertTrue(Pattern.parse(ResourceLocation.withDefaultNamespace("keeps_none"), """
            def grow(site):
                return grown([part("x", box(site.zone), ["minecraft:stone"])], keep = {"x": None})
            """).map(pattern -> pattern.growOn(commission(DraftKit.zone(1, 1, 1), pattern,
                Growth.first(DraftKit.zone(1, 1, 1)))) instanceof Round.Stuck).orElse(false),
            "None cannot be kept");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void laterRoundsSeeAsFarAroundWhatWasBuiltAsTheFirstSawAroundTheMark(GameTestHelper helper) {
        Pattern pattern = DraftKit.parse("""
            accepts = ["zone"]
            def grow(site):
                far = 120
                if not site.matches(point(far, 0, 0), "air"):
                    fail("expected air")
                return [part("x", box(site.zone.at(x = far)), ["minecraft:stone"])]
            """);
        Hint hint = DraftKit.zone(1, 1, 1);
        helper.assertValueEqual(DraftKit.refused(helper, pattern.drawOn(commission(hint, pattern,
            Growth.first(hint)))).why(), DraftRefusal.READ_TOO_FAR, "the first round reads only near the mark");
        Draft built = new Draft(Extent.of(1, 1, 1).orElseThrow(), List.of());
        Growth moved = Growth.first(hint).next(new CompoundTag(), new BlockPos(60, 0, 0), built, hint.anchor());
        DraftKit.ready(helper, pattern.drawOn(commission(hint, pattern, moved)));
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void aDrawingKnowsHowHighInTheWorldItsMarkLies(GameTestHelper helper) {
        Pattern pattern = DraftKit.parse("""
            accepts = ["zone"]
            def grow(site):
                if site.altitude != 70:
                    fail("altitude %d" % site.altitude)
                return [part("x", box(site.zone), ["minecraft:stone"])]
            """);
        Hint high = new Hint.Zone(new BlockPos(3, 70, 3), new BlockPos(3, 72, 3));
        DraftKit.ready(helper, pattern.drawOn(commission(high, pattern, Growth.first(high))));
        Hint low = new Hint.Zone(new BlockPos(3, -10, 3), new BlockPos(3, -10, 3));
        helper.assertValueEqual(DraftKit.refused(helper, pattern.drawOn(commission(low, pattern,
            Growth.first(low)))).why(), DraftRefusal.PATTERN_FAILED, "the altitude is where the mark lies");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void marksAndSettingsSurviveBeingWrittenDown(GameTestHelper helper) {
        Hint path = new Hint.Path(List.of(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6), new BlockPos(-7, 8, 9)));
        helper.assertValueEqual(Hint.load(path.save()).orElseThrow(), path, "a path reads back");
        Hint zone = new Hint.Zone(new BlockPos(5, 5, 5), new BlockPos(-1, 0, 2));
        helper.assertValueEqual(Hint.load(zone.save()).orElseThrow(), zone, "a zone reads back");
        Pattern pattern = DraftKit.parse(COUNTING);
        Chosen chosen = Chosen.of(pattern.knobs(), DraftKit.Filled.defaulting(pattern.knobs()).count("rounds", 99));
        helper.assertValueEqual(chosen.count("rounds"), 8, "a count is held within its bounds");
        CompoundTag saved = chosen.save();
        saved.putInt("rounds", -4);
        helper.assertValueEqual(Chosen.load(pattern.knobs(), saved).count("rounds"), 1,
            "a count read back is held within its bounds too");
        helper.assertValueEqual(Chosen.load(pattern.knobs(), new CompoundTag()).count("rounds"), 3,
            "a missing count reads as its default");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void aPatternTooLargeToSendIsLeftOutWhenLoadedAndTheRestKeepTheirOrder(GameTestHelper helper) {
        java.util.Map<ResourceLocation, String> read = new java.util.LinkedHashMap<>();
        read.put(ResourceLocation.withDefaultNamespace("b"), "x = 1");
        read.put(ResourceLocation.withDefaultNamespace("huge"), "#".repeat(PatternLibraryPacket.MAX_SOURCE + 1));
        read.put(ResourceLocation.withDefaultNamespace("a"), "y = 2");
        java.util.Map<ResourceLocation, String> kept = PatternLibraryPacket.sendable(read,
            org.slf4j.LoggerFactory.getLogger("test"));
        helper.assertValueEqual(List.copyOf(kept.keySet()), List.of(ResourceLocation.withDefaultNamespace("b"),
            ResourceLocation.withDefaultNamespace("a")), "the oversized pattern is left out, the rest kept in order");
        helper.succeed();
    }
}
