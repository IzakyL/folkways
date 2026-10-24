package io.github.izakyl.folkways.cookery.dining;

import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteOneByTwoBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.food.FoodBiteThreeByThreeBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** The placed dish, rather than its deliberately flat inventory icon. */
public final class TableFoodModels {
    private TableFoodModels() { }

    public static BlockState placedDish(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof FoodBiteBlock
            && !(item.getBlock() instanceof FoodBiteOneByTwoBlock)
            && !(item.getBlock() instanceof FoodBiteThreeByThreeBlock)
            ? item.getBlock().defaultBlockState() : null;
    }
}
