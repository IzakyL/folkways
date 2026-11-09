package io.github.izakyl.folkways.core.item.api;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ItemQueryGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ItemQueryGameTests.class);
    }

    // A whole name is that one alone; part of one is any it is part of; stars and marks stand for letters.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void wordsMatchWholeNamesPartsAndPatterns(GameTestHelper helper) {
        expect(helper, "#minecraft:logs", Items.OAK_LOG, true);
        expect(helper, "#minecraft:logs", Items.OAK_PLANKS, false);
        expect(helper, "#logs", Items.BIRCH_LOG, true);
        // "logs" names a tag, so the tag that only ends in it is not asked for.
        expect(helper, "#logs", Items.CRIMSON_STEM, true);
        expect(helper, "#logs_that_burn", Items.CRIMSON_STEM, false);
        expect(helper, "@minecraft", Items.DIRT, true);
        expect(helper, "@mine", Items.DIRT, true);
        expect(helper, "*_planks", Items.OAK_PLANKS, true);
        expect(helper, "*_planks", Items.OAK_LOG, false);
        expect(helper, "minecraft:oak_?og", Items.OAK_LOG, true);
        expect(helper, "oak", Items.OAK_LOG, true);
        // A whole id is that item alone.
        expect(helper, "minecraft:stone", Items.STONE, true);
        expect(helper, "minecraft:stone", Items.STONE_BRICKS, false);
        expect(helper, "stone", Items.STONE_BRICKS, true);
        expect(helper, "#logs planks", Items.OAK_LOG, false);
        expect(helper, "#logs | *_planks", Items.OAK_PLANKS, true);
        helper.assertTrue(ItemQuery.parse("   ").isEmpty(), "nothing typed is no query");
        helper.assertTrue(ItemQuery.parse("@ #").isEmpty(), "a sign alone is no word");
        helper.succeed();
    }

    // A bare word finds a name only in a search: a rule is read on the server, which knows no reader's words.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void namesAreSearchedButNotRuled(GameTestHelper helper) {
        ItemQuery query = ItemQuery.parse("chicken").orElseThrow();
        helper.assertTrue(query.matches(Items.EGG, "a chicken's egg"), "a search finds the name");
        helper.assertFalse(query.matches(Items.EGG), "a rule does not");
        helper.assertTrue(query.matches(Items.CHICKEN), "a rule finds the id");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void displayNameMatchersLeaveRulesAndQueryOperatorsAlone(GameTestHelper helper) {
        var spelling = (java.util.function.BiPredicate<String, String>) (name, word) ->
            name.equals("铁锭") && word.equals("tieding");
        ItemQuery query = ItemQuery.parse("@minecraft TIEDING | minecraft:dirt").orElseThrow();
        helper.assertTrue(query.matches(Items.IRON_INGOT, "铁锭", spelling),
            "the display-name matcher receives each folded bare word after parsing");
        helper.assertTrue(query.matches(Items.DIRT, "泥土", spelling), "an alternative still finds its id");
        helper.assertFalse(query.matches(Items.IRON_INGOT), "a saved rule never uses display-name matching");
        helper.assertFalse(query.items().contains(Items.IRON_INGOT), "rule expansion has the same boundary");

        for (String typed : List.of("minecraft:iron_ingot", "minecraft:stone", "@minecraft",
                "#minecraft:logs", "*_ingot", "*tieding*")) {
            ItemQuery.parse(typed).orElseThrow().matches(Items.IRON_INGOT, "铁锭", (name, word) -> {
                throw new AssertionError("a registry-only term searched a display name: " + typed);
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void missingDisplayNamesNeverReachTheNameMatcher(GameTestHelper helper) {
        helper.assertFalse(ItemQuery.parse("tieding").orElseThrow().matches(Items.IRON_INGOT, null,
            (name, word) -> { throw new AssertionError("a rule has no display name to match"); }),
            "a missing display name is still a registry-only query");
        helper.succeed();
    }

    // A rule naming one whole item or tag is that item or tag, kept the cheaper way, and the same rule as ticking it.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aLoneIdIsKeptAsItsItemOrTag(GameTestHelper helper) {
        ResourceLocation arrow = BuiltInRegistries.ITEM.getKey(Items.ARROW);
        helper.assertTrue(ItemFilter.query("minecraft:arrow").equals(Optional.of(ItemFilter.item(arrow))),
            "an item id is that item");
        helper.assertTrue(ItemFilter.query("#minecraft:logs").equals(Optional.of(ItemFilter.tag(
            ResourceLocation.withDefaultNamespace("logs")))), "a tag id is that tag");
        helper.assertTrue(ItemFilter.query("#logs").map(ItemFilter::query).orElse(false),
            "a tag named without its namespace stays a query");
        helper.assertTrue(ItemFilter.query("minecraft:*arrow").map(ItemFilter::query).orElse(false),
            "a pattern stays a query");
        helper.succeed();
    }

    // A search kept as a rule is saved as it was typed, and stands for every item it finds.
    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void aQueryRuleIsKeptAndStandsForWhatItFinds(GameTestHelper helper) {
        ItemFilter rule = ItemFilter.query("  #minecraft:logs   @minecraft ").orElseThrow();
        helper.assertTrue(rule.text().equals("#minecraft:logs @minecraft"), "kept as typed, spaces evened");
        helper.assertTrue(ItemFilter.read(rule.write()).equals(Optional.of(rule)), "it travels as a line");

        ColonySettings held = ColonySettings.empty()
            .with("food", new ColonySettings.Value.Items(List.of(rule, ItemFilter.item(Items.DIRT
                .builtInRegistryHolder().key().location()))));
        ColonySettings loaded = ColonySettings.load(Reader.of(held.save()));
        helper.assertTrue(loaded.entries("food").equals(held.entries("food")), "saved and loaded the same");

        ItemSpec spec = loaded.items("food").orElseThrow();
        for (Item log : List.of(Items.OAK_LOG, Items.SPRUCE_WOOD, Items.DIRT)) {
            helper.assertTrue(Goods.matches(new ItemStack(log), spec), "the rules take in " + log);
        }
        helper.assertFalse(Goods.matches(new ItemStack(Items.OAK_PLANKS), spec), "and nothing else");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void genericOptionsUseTheSameGrammar(GameTestHelper helper) {
        OptionQuery.Known known = new OptionQuery.Known(Set.of("own", "other"),
            Set.of("archer", "archer_captain", "pack:builder"), Set.of("work:ranged", "work:ranged_elite"),
            Set.of("ranged", "ranged_elite"));
        for (String typed : List.of("archer", "@own #ranged", "@oth | #ranged", "arch?r", "#work:rang*")) {
            helper.assertTrue(OptionQuery.parse(typed).orElseThrow().matches(known, "archer", "own",
                List.of("work:ranged"), "北境弓手", String::contains), "a non-item option matches " + typed);
        }
        for (String typed : List.of("@other", "#ranged_elite", "@own builder", "archer_captain", "arch?")) {
            helper.assertFalse(OptionQuery.parse(typed).orElseThrow().matches(known, "archer", "own",
                List.of("work:ranged"), "北境弓手", String::contains), "a non-item option excludes " + typed);
        }
        helper.assertFalse(OptionQuery.parse("archer").orElseThrow().matches(known, "archer_captain", "own",
            List.of(), "archer captain", String::contains), "a whole option id takes just that one");
        helper.assertTrue(OptionQuery.parse("@own beijing").orElseThrow().matches(known, "archer", "own",
            List.of(), "北境弓手", (name, word) -> name.equals("北境弓手") && word.equals("beijing")),
            "the caller's display matcher receives a bare word inside an expression");
        helper.succeed();
    }

    private static void expect(GameTestHelper helper, String typed, Item item, boolean found) {
        boolean matched = ItemQuery.parse(typed).orElseThrow().matches(item);
        helper.assertTrue(matched == found, "\"" + typed + "\" " + (found ? "should" : "should not") + " take in "
            + item);
    }
}
