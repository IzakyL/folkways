package io.github.izakyl.folkways.chiselsandbits.mixin;

import com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.izakyl.folkways.chiselsandbits.BitRequirement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SchematicannonBlockEntity.class, remap = false)
abstract class SchematicannonMixin {
    @Shadow public LinkedHashSet<IItemHandler> attachedInventories;
    @Shadow public boolean hasCreativeCrate;

    private record Draw(IItemHandler inventory, int slot, int count) {}

    @ModifyExpressionValue(method = "tickPrinter", at = @At(value = "FIELD",
        target = "Lcom/simibubi/create/content/schematics/requirement/ItemRequirement$StackRequirement;stack:Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack folkways$bitIcon(ItemStack stack) {
        // The missing-item and projectile icons are persisted as ordinary stacks, unlike requirement totals.
        return stack.is(mod.chiselsandbits.registrars.ModItems.ITEM_BLOCK_BIT.get()) && stack.getCount() > stack.getMaxStackSize()
            ? stack.copyWithCount(1) : stack;
    }

    @Inject(method = "grabItemsFromAttachedInventories", at = @At("HEAD"), cancellable = true)
    private void folkways$drawBits(ItemRequirement.StackRequirement required, boolean simulate,
            CallbackInfoReturnable<Boolean> cir) {
        if (!(required instanceof BitRequirement) || hasCreativeCrate) return;
        int remaining = required.stack.getCount();
        var draws = new ArrayList<Draw>();
        for (IItemHandler inventory : attachedInventories) {
            if (inventory == null) continue;
            for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++) {
                var found = inventory.extractItem(slot, remaining, true);
                if (!required.matches(found)) continue;
                int count = Math.min(remaining, found.getCount());
                draws.add(new Draw(inventory, slot, count));
                remaining -= count;
            }
            if (remaining == 0) break;
        }
        if (remaining != 0) {
            cir.setReturnValue(false);
            return;
        }
        // The same slot plan checks availability and removes exactly the outstanding amount.
        if (!simulate) draws.forEach(draw -> draw.inventory().extractItem(draw.slot(), draw.count(), false));
        cir.setReturnValue(true);
    }
}
