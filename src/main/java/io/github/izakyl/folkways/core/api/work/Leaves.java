package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a piece of work is likely to leave in the worker's pack: what a block breaks into, a beast falls to, a line
 * brings up. It is known for sure only once the work is done, so it is no promise of goods to anyone, only room the
 * schedule keeps in the pack, as it does for goods carried. Said too low, what the pack cannot take is dropped where
 * the work is done; said as goods, it shares stacks with the same goods the way the pack does.
 */
public final class Leaves {

    // Goods that cannot be told beforehand. Each one is a cell of its own, shared with nothing.
    public static final ItemSpec UNSEEN = ItemSpec.of(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID,
        "unseen"));

    private Leaves() {
    }

    // Room for goods not known beforehand, a cell each.
    public static List<Amount> room(int cells) {
        return cells <= 0 ? List.of() : List.of(new Amount(UNSEEN, cells));
    }

    // What the blocks break into, about: each its own item, one apiece. A block whose drops are another item, a
    // stone its cobble, takes as many cells all the same; one with no item of its own leaves nothing told.
    public static List<Amount> ofBlocks(Collection<BlockState> broken) {
        Map<ItemSpec, Long> left = new LinkedHashMap<>();
        for (BlockState state : broken) {
            Item item = state.getBlock().asItem();
            if (item != Items.AIR) {
                left.merge(ItemSpec.of(BuiltInRegistries.ITEM.getKey(item)), 1L, Long::sum);
            }
        }
        return amounts(left);
    }

    public static List<Amount> ofStacks(Collection<ItemStack> stacks) {
        Map<ItemSpec, Long> left = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                left.merge(Goods.specOf(stack), (long) stack.getCount(), Long::sum);
            }
        }
        return amounts(left);
    }

    public static List<Amount> merged(Collection<Amount> amounts) {
        Map<ItemSpec, Long> left = new LinkedHashMap<>();
        amounts.forEach(amount -> left.merge(amount.spec(), amount.count(), Long::sum));
        return amounts(left);
    }

    // The pack cells the goods take, stacked as the pack stacks them.
    public static int cells(Collection<Amount> amounts) {
        int cells = 0;
        for (Amount amount : merged(amounts)) {
            cells += cellsOf(amount.spec(), amount.count());
        }
        return cells;
    }

    // Whether the pack has the empty cells the goods take now. Room left on a stack already there is not counted:
    // what is told is about, and a stone told as stone breaks to cobble, which no stack of stone takes.
    public static boolean fit(Container pack, Collection<Amount> amounts) {
        int empty = 0;
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            if (pack.getItem(slot).isEmpty()) {
                empty++;
            }
        }
        return cells(amounts) <= empty;
    }

    // A spec no item answers to, UNSEEN among them, stacks to one.
    private static int cellsOf(ItemSpec spec, long count) {
        int stack = Math.max(1, Goods.stackSize(spec));
        return (int) ((count + stack - 1) / stack);
    }

    private static List<Amount> amounts(Map<ItemSpec, Long> counted) {
        List<Amount> amounts = new ArrayList<>(counted.size());
        counted.forEach((spec, count) -> {
            if (count > 0) {
                amounts.add(new Amount(spec, count));
            }
        });
        return List.copyOf(amounts);
    }
}
