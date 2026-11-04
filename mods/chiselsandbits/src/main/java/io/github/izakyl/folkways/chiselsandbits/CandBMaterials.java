package io.github.izakyl.folkways.chiselsandbits;

import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

public final class CandBMaterials {
    private CandBMaterials() {
    }

    public static List<ItemStack> of(StateEntryStorage storage) {
        int units = storage.getSize() * storage.getSize() * storage.getSize();
        return volume(CandBStructureCodec.counts(storage), units).counts().entrySet().stream()
            .map(entry -> {
                ItemStack stack = Goods.display(entry.getKey());
                stack.setCount((entry.getValue() + units - 1) / units);
                return stack;
            }).toList();
    }

    public static List<ItemStack> of(mod.chiselsandbits.block.entities.ChiseledBlockEntity entity) {
        return of(entity.payload().storage());
    }

    static StructureCompat.Voxels volume(Map<BlockInformation, Integer> counts, int units) {
        Map<ItemSpec, Integer> materials = new LinkedHashMap<>();
        counts.forEach((material, count) -> {
            var state = material.blockState();
            ItemStack stack = new ItemStack(state.getBlock().asItem());
            if (!state.getFluidState().isEmpty() || !(stack.getItem() instanceof BlockItem block)
                || block.getBlock() != state.getBlock() || material.variant().isPresent()) {
                throw StructureCompat.invalid();
            }
            materials.merge(ItemSpec.exact(stack), count, Integer::sum);
        });
        return new StructureCompat.Voxels(units, materials);
    }
}
