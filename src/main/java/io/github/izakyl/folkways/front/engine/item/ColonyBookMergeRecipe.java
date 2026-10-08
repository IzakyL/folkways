package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysRecipes;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public final class ColonyBookMergeRecipe extends CustomRecipe {
    public ColonyBookMergeRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return boundColonyId(input) != null && hasExactlyOneEmptyBook(input) && bookCount(input) == 2;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        UUID colonyId = boundColonyId(input);
        ItemStack result = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        if (colonyId != null) {
            ColonyBookItem.linkExistingColony(result, colonyId);
        }
        return result;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (isColonyBook(stack) && ColonyBookItem.boundColonyId(stack).isPresent()) {
                remaining.set(i, stack.copyWithCount(1));
            }
        }
        return remaining;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return FolkwaysRecipes.COLONY_BOOK_MERGE.get();
    }

    private static boolean isColonyBook(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ColonyBookItem;
    }

    private static UUID boundColonyId(CraftingInput input) {
        UUID bound = null;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!isColonyBook(stack)) {
                return null;
            }
            UUID id = ColonyBookItem.boundColonyId(stack).orElse(null);
            if (id != null) {
                if (bound != null) {
                    return null;
                }
                bound = id;
            }
        }
        return bound;
    }

    private static boolean hasExactlyOneEmptyBook(CraftingInput input) {
        int empty = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (isColonyBook(stack) && ColonyBookItem.boundColonyId(stack).isEmpty()) {
                empty++;
            }
        }
        return empty == 1;
    }

    private static int bookCount(CraftingInput input) {
        int books = 0;
        for (int i = 0; i < input.size(); i++) {
            if (isColonyBook(input.getItem(i))) {
                books++;
            }
        }
        return books;
    }
}
