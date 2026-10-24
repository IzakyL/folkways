package io.github.izakyl.folkways.chiselsandbits;

import java.util.List;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.item.bit.IBitItemManager;
import net.minecraft.world.item.ItemStack;

public final class CandBMaterials {
    private CandBMaterials() {
    }

    public static List<ItemStack> of(StateEntryStorage storage) {
        return CandBStructureCodec.counts(storage).entrySet().stream().map(entry -> {
            ItemStack bit = IBitItemManager.getInstance().create(entry.getKey(), 1);
            if (bit.isEmpty()) {
                throw new IllegalArgumentException("Material cannot be represented by bits");
            }
            bit.setCount(entry.getValue());
            return bit;
        }).toList();
    }
}
