package io.github.izakyl.folkways.chiselsandbits;

import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import net.minecraft.world.item.ItemStack;

/** One material's complete voxel count, which may span several stacks and inventories. */
public final class BitRequirement extends ItemRequirement.StrictNbtStackRequirement {
    public BitRequirement(ItemStack stack) {
        super(stack, ItemRequirement.ItemUseType.CONSUME);
    }
}
