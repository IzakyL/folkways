package io.github.izakyl.folkways.plugins.cookery.teapot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.ITeapot;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.TeapotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class TeaBatch {
    private static final String KEY = "folkways.cookery.teapot";
    record Batch(UUID colony, UUID id, ResourceLocation recipe, ResourceLocation fluid, ItemStack ingredient,
                 ItemStack output, int time, boolean loaded) { }
    private TeaBatch() { }
    static void remember(TeapotBlockEntity pot, ServerLevel level, Batch batch) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", batch.colony()); tag.putUUID("batch", batch.id());
        tag.putString("recipe", batch.recipe().toString()); tag.putString("fluid", batch.fluid().toString());
        tag.put("ingredient", batch.ingredient().saveOptional(level.registryAccess()));
        tag.put("output", batch.output().saveOptional(level.registryAccess())); tag.putInt("time", batch.time()); tag.putBoolean("loaded", batch.loaded());
        tag.put("state", NbtUtils.writeBlockState(pot.getBlockState().setValue(TeapotBlock.VARIANT, TeapotBlock.COMMON)));
        pot.getPersistentData().put(KEY, tag); pot.setChanged();
    }
    static Optional<Batch> read(TeapotBlockEntity pot, ServerLevel level) {
        CompoundTag tag = pot.getPersistentData().getCompound(KEY);
        ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("recipe"));
        ResourceLocation fluid = ResourceLocation.tryParse(tag.getString("fluid"));
        if (!tag.hasUUID("colony") || !tag.hasUUID("batch") || recipe == null || fluid == null || !pot.getTeaFluidId().equals(fluid)
                || !tag.getCompound("state").equals(NbtUtils.writeBlockState(pot.getBlockState().setValue(TeapotBlock.VARIANT, TeapotBlock.COMMON)))) { return Optional.empty(); }
        ItemStack ingredient = ItemStack.parseOptional(level.registryAccess(), tag.getCompound("ingredient"));
        ItemStack output = ItemStack.parseOptional(level.registryAccess(), tag.getCompound("output"));
        boolean loaded = tag.getBoolean("loaded");
        if (ingredient.isEmpty() || output.isEmpty() || (loaded ? !ItemStack.matches(pot.getInput(), ingredient) : !pot.getInput().isEmpty())) { return Optional.empty(); }
        if (pot.getStatus() != ITeapot.PUT_INGREDIENT && (!loaded || !ItemStack.matches(pot.getResult(), output))) { return Optional.empty(); }
        return Optional.of(new Batch(tag.getUUID("colony"), tag.getUUID("batch"), recipe, fluid, ingredient, output, tag.getInt("time"), loaded));
    }
    static boolean ours(TeapotBlockEntity pot, UUID colony) {
        CompoundTag tag = pot.getPersistentData().getCompound(KEY);
        return tag.hasUUID("colony") && tag.getUUID("colony").equals(colony);
    }
    static void clear(TeapotBlockEntity pot) { pot.getPersistentData().remove(KEY); pot.setChanged(); }
}
