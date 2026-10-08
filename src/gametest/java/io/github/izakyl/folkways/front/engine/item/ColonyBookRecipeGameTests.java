package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ColonyBookRecipeGameTests {
    private static final String TEMPLATE = "empty";

    private ColonyBookRecipeGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ColonyBookRecipeGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void boundPlusEmptyShareColony(GameTestHelper helper) {
        ColonyBookMergeRecipe recipe = new ColonyBookMergeRecipe(CraftingBookCategory.MISC);
        UUID colonyId = UUID.randomUUID();
        ItemStack bound = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        ColonyBookItem.linkExistingColony(bound, colonyId);
        ItemStack empty = new ItemStack(FolkwaysItems.COLONY_BOOK.get());

        CraftingInput input = CraftingInput.of(2, 1, List.of(bound, empty));
        helper.assertTrue(recipe.matches(input, helper.getLevel()), "one bound plus one empty colony book should match the merge recipe");

        ItemStack result = recipe.assemble(input, helper.getLevel().registryAccess());
        helper.assertTrue(result.getItem() instanceof ColonyBookItem, "result should be a colony book");
        helper.assertTrue(ColonyBookItem.boundColonyId(result).map(colonyId::equals).orElse(false),
            "result should bind to the same colonyId as the bound book");

        var remaining = recipe.getRemainingItems(input);
        helper.assertTrue(remaining.get(0).getItem() instanceof ColonyBookItem
            && ColonyBookItem.boundColonyId(remaining.get(0)).map(colonyId::equals).orElse(false),
            "the bound book should stay in the grid, not be consumed");
        helper.assertTrue(remaining.get(1).isEmpty(), "the empty book slot should be consumed");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void rejectsInvalidCombos(GameTestHelper helper) {
        ColonyBookMergeRecipe recipe = new ColonyBookMergeRecipe(CraftingBookCategory.MISC);
        ItemStack empty1 = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        ItemStack empty2 = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        helper.assertFalse(recipe.matches(CraftingInput.of(2, 1, List.of(empty1, empty2)), helper.getLevel()),
            "two empty books have no colony to share; should not match");

        ItemStack bound = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        ColonyBookItem.linkExistingColony(bound, UUID.randomUUID());
        helper.assertFalse(recipe.matches(CraftingInput.of(2, 1, List.of(bound, new ItemStack(Items.PAPER))), helper.getLevel()),
            "a non-colony-book ingredient should not match");

        ItemStack bound2 = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        ColonyBookItem.linkExistingColony(bound2, UUID.randomUUID());
        helper.assertFalse(recipe.matches(CraftingInput.of(2, 1, List.of(bound, bound2)), helper.getLevel()),
            "two bound books with no empty book should not match");
        helper.succeed();
    }
}
