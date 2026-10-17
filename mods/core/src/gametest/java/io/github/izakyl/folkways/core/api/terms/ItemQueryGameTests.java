package io.github.izakyl.folkways.core.api.terms;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import java.util.List;
import java.util.Optional;
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

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ItemQueryGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ItemQueryGameTests.class);
    }

    // A whole name is that one alone; part of one is any it is part of; stars and marks stand for letters.
    @GameTest(template = "empty")
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
    @GameTest(template = "empty")
    public static void namesAreSearchedButNotRuled(GameTestHelper helper) {
        ItemQuery query = ItemQuery.parse("chicken").orElseThrow();
        helper.assertTrue(query.matches(Items.EGG, "a chicken's egg"), "a search finds the name");
        helper.assertFalse(query.matches(Items.EGG), "a rule does not");
        helper.assertTrue(query.matches(Items.CHICKEN), "a rule finds the id");
        helper.succeed();
    }

    // A rule naming one whole item or tag is that item or tag, kept the cheaper way, and the same rule as ticking it.
    @GameTest(template = "empty")
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
    @GameTest(template = "empty")
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

    private static void expect(GameTestHelper helper, String typed, Item item, boolean found) {
        boolean matched = ItemQuery.parse(typed).orElseThrow().matches(item);
        helper.assertTrue(matched == found, "\"" + typed + "\" " + (found ? "should" : "should not") + " take in "
            + item);
    }
}
