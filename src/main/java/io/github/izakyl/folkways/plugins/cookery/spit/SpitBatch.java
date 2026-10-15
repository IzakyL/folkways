package io.github.izakyl.folkways.plugins.cookery.spit;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class SpitBatch {
    private static final String KEY = "folkways.cookery.spit";
    record Batch(UUID colony, UUID id, ResourceLocation recipe) { }
    private SpitBatch() { }
    static void remember(ShawarmaSpitBlockEntity spit, ServerLevel level, Batch batch) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", batch.colony());
        tag.putUUID("batch", batch.id());
        tag.putString("recipe", batch.recipe().toString());
        tag.put("input", spit.cookingItem.saveOptional(level.registryAccess()));
        tag.put("output", spit.cookedItem.saveOptional(level.registryAccess()));
        spit.getPersistentData().put(KEY, tag);
        spit.setChanged();
    }
    static Optional<Batch> read(ShawarmaSpitBlockEntity spit, ServerLevel level) {
        CompoundTag tag = spit.getPersistentData().getCompound(KEY);
        ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("recipe"));
        if (!tag.hasUUID("colony") || !tag.hasUUID("batch") || recipe == null || spit.cookedItem.isEmpty()) {
            return Optional.empty();
        }
        ItemStack input = ItemStack.parseOptional(level.registryAccess(), tag.getCompound("input"));
        ItemStack output = ItemStack.parseOptional(level.registryAccess(), tag.getCompound("output"));
        if (!ItemStack.matches(output, spit.cookedItem)
                || !(ItemStack.matches(input, spit.cookingItem) || spit.cookingItem.isEmpty() && spit.cookTime <= 0)) {
            return Optional.empty();
        }
        return Optional.of(new Batch(tag.getUUID("colony"), tag.getUUID("batch"), recipe));
    }
    static boolean ours(ShawarmaSpitBlockEntity spit, UUID colony) {
        CompoundTag tag = spit.getPersistentData().getCompound(KEY);
        return tag.hasUUID("colony") && tag.getUUID("colony").equals(colony);
    }
    static void clear(ShawarmaSpitBlockEntity spit) {
        spit.getPersistentData().remove(KEY);
        spit.setChanged();
    }
}
