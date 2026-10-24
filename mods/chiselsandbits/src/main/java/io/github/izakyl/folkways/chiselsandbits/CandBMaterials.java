package io.github.izakyl.folkways.chiselsandbits;

import java.util.List;
import java.util.Map;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.api.item.bit.IBitItemManager;
import net.minecraft.world.item.ItemStack;

public final class CandBMaterials {
    private CandBMaterials() {
    }

    public static List<ItemStack> of(StateEntryStorage storage) {
        return of(CandBStructureCodec.counts(storage));
    }

    public static List<ItemStack> of(mod.chiselsandbits.block.entities.ChiseledBlockEntity entity) {
        var counted = ((SchematicBits) entity).folkways$counts();
        return of(counted.isEmpty() ? CandBStructureCodec.counts(entity.payload().storage()) : counted);
    }

    private static List<ItemStack> of(Map<BlockInformation, Integer> counted) {
        return counted.entrySet().stream().map(entry -> {
            ItemStack bit = IBitItemManager.getInstance().create(entry.getKey(), 1);
            if (bit.isEmpty()) {
                throw new IllegalArgumentException("Material cannot be represented by bits");
            }
            bit.setCount(entry.getValue());
            return bit;
        }).toList();
    }
}
