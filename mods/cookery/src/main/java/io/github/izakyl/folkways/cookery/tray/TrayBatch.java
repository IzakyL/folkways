package io.github.izakyl.folkways.cookery.tray;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.BambooTrayRecipe;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class TrayBatch {
    private static final String KEY = "folkways.cookery.tray";
    record Batch(UUID colony, UUID id, ResourceLocation recipe, int slot, BambooTrayRecipe.Subtype subtype, int time,
                 ItemStack input, ItemStack output) { }
    private TrayBatch() { }
    static void remember(BambooTrayBlockEntity tray, ServerLevel level, Batch batch) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", batch.colony()); tag.putUUID("batch", batch.id());
        tag.putString("recipe", batch.recipe().toString()); tag.putInt("slot", batch.slot());
        tag.putString("subtype", batch.subtype().getSerializedName()); tag.putInt("time", batch.time());
        tag.put("input", batch.input().saveOptional(level.registryAccess()));
        tag.put("output", batch.output().saveOptional(level.registryAccess()));
        tray.getPersistentData().put(KEY, tag); tray.setChanged();
    }
    static Optional<Batch> read(BambooTrayBlockEntity tray, ServerLevel level) {
        CompoundTag tag = tray.getPersistentData().getCompound(KEY);
        ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("recipe"));
        int slot = tag.getInt("slot");
        if (!tag.hasUUID("colony") || !tag.hasUUID("batch") || recipe == null || slot < 0 || slot >= 4) { return Optional.empty(); }
        ItemStack input = ItemStack.parseOptional(level.registryAccess(), tag.getCompound("input"));
        ItemStack output = ItemStack.parseOptional(level.registryAccess(), tag.getCompound("output"));
        ItemStack actual = tray.getItem(slot);
        if (input.isEmpty() || output.isEmpty() || !(ItemStack.matches(input, actual) || ItemStack.matches(output, actual))) { return Optional.empty(); }
        String kind = tag.getString("subtype");
        if (!kind.equals("drying") && !kind.equals("wetting")) { return Optional.empty(); }
        return Optional.of(new Batch(tag.getUUID("colony"), tag.getUUID("batch"), recipe, slot,
            BambooTrayRecipe.Subtype.fromSerializedName(kind), tag.getInt("time"), input, output));
    }
    static boolean ready(BambooTrayBlockEntity tray, ServerLevel level, Batch batch) {
        byte[] states = tray.saveWithoutMetadata(level.registryAccess()).getByteArray("CompletionStates");
        int desired = batch.subtype() == BambooTrayRecipe.Subtype.DRYING ? 1 : 2;
        return batch.slot() < states.length && states[batch.slot()] == desired && ItemStack.matches(tray.getItem(batch.slot()), batch.output());
    }
    static boolean ours(BambooTrayBlockEntity tray, UUID colony) {
        CompoundTag tag = tray.getPersistentData().getCompound(KEY);
        return tag.hasUUID("colony") && tag.getUUID("colony").equals(colony);
    }
    static void clear(BambooTrayBlockEntity tray) { tray.getPersistentData().remove(KEY); tray.setChanged(); }
}
