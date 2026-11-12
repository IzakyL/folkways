package io.github.izakyl.folkways.core.item.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** What a block is called, for cards and pages. */
public final class BlockNames {

    private static final List<BiFunction<Level, BlockPos, Optional<ItemStack>>> PICTURES = new CopyOnWriteArrayList<>();

    private BlockNames() {
    }

    /**
     * Lets an integration say what stands at a cell when its block alone would not: an AE2 terminal is a part on a
     * cable bus, whose block is only "Cable and/or Bus".
     */
    public static void picture(BiFunction<Level, BlockPos, Optional<ItemStack>> picture) {
        PICTURES.add(picture);
    }

    /** The item that shows what stands at a cell: what an integration says, or the block's own item. */
    public static ItemStack pictureAt(Level level, BlockPos pos) {
        for (var picture : PICTURES) {
            Optional<ItemStack> said = picture.apply(level, pos);
            if (said.isPresent() && !said.get().isEmpty()) {
                return said.get();
            }
        }
        return new ItemStack(level.getBlockState(pos).getBlock());
    }

    /** What the thing standing at a cell is called: what an integration says, or the block's own name. */
    public static Component at(Level level, BlockPos pos) {
        for (var picture : PICTURES) {
            Optional<ItemStack> said = picture.apply(level, pos);
            if (said.isPresent() && !said.get().isEmpty()) {
                return said.get().getHoverName();
            }
        }
        return of(level.getBlockState(pos).getBlock());
    }

    /**
     * The block's own name, or its item's when the block's key has no translation: some mods (Storage Drawers) name
     * only the item, building it from parts, and would otherwise show the raw key. Sophisticated Storage's block keys
     * are patterns its items fill in with the wood ("%s%sIron Chest"); said bare they show their placeholders, so a
     * key that takes arguments is named by the item too.
     */
    public static Component of(Block block) {
        if (block.asItem() == Items.AIR) {
            return block.getName();
        }
        Language language = Language.getInstance();
        String key = block.getDescriptionId();
        if (language.has(key) && !language.getOrDefault(key).contains("%")) {
            return block.getName();
        }
        return new ItemStack(block).getHoverName();
    }
}
