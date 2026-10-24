package io.github.izakyl.folkways.plugins.wares.mixin;

import io.github.izakyl.folkways.plugins.wares.FurnaceTraining;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceTrainingMixin implements FurnaceTraining {
    @Shadow int cookingProgress;
    @Shadow int cookingTotalTime;
    @Unique private final List<CompoundTag> folkways$batches = new ArrayList<>();
    @Unique private double folkways$fraction;
    @Unique private ItemStack folkways$input = ItemStack.EMPTY;

    @Unique private void folkways$sync(int count) {
        int known = folkways$batches.stream().mapToInt(tag -> tag.getInt("count")).sum();
        int removed = Math.max(0, known - count);
        while (removed > 0 && !folkways$batches.isEmpty()) {
            CompoundTag first = folkways$batches.getFirst();
            int used = Math.min(removed, first.getInt("count"));
            first.putInt("count", first.getInt("count") - used);
            removed -= used;
            if (first.getInt("count") == 0) { folkways$batches.removeFirst(); folkways$fraction = 0; }
        }
        if (count > known) { folkways$append(count - known, 0); }
    }

    @Unique private void folkways$append(int count, double speed) {
        if (count <= 0) { return; }
        CompoundTag part = new CompoundTag();
        part.putInt("count", count);
        part.putDouble("speed", speed);
        folkways$batches.add(part);
    }

    @Override
    public void folkways$removed() {
        folkways$sync(((AbstractFurnaceBlockEntity) (Object) this).getItem(0).getCount());
    }

    @Inject(method = "setItem", at = @At("HEAD"))
    private void folkways$replace(int slot, ItemStack next, CallbackInfo ci) {
        if (slot != 0) { return; }
        ItemStack old = ((AbstractFurnaceBlockEntity) (Object) this).getItem(0);
        if (!ItemStack.isSameItemSameComponents(old, next)) {
            folkways$batches.clear();
            folkways$fraction = 0;
            folkways$input = next.copy();
        } else {
            int known = folkways$batches.stream().mapToInt(tag -> tag.getInt("count")).sum();
            if (next.getCount() < known) { folkways$sync(next.getCount()); }
        }
    }

    @Override
    public void folkways$loaded(int previousCount, int inserted, double speed) {
        folkways$sync(previousCount);
        folkways$append(inserted, Math.clamp(speed, 0, .5));
        folkways$input = ((AbstractFurnaceBlockEntity) (Object) this).getItem(0).copy();
    }

    @Inject(method = "serverTick", at = @At("HEAD"))
    private static void folkways$accelerate(Level level, BlockPos pos, BlockState state,
            AbstractFurnaceBlockEntity furnace, CallbackInfo ci) {
        FurnaceTrainingMixin self = (FurnaceTrainingMixin) (Object) furnace;
        ItemStack input = furnace.getItem(0);
        if (!ItemStack.isSameItemSameComponents(input, self.folkways$input)) {
            self.folkways$batches.clear();
            self.folkways$fraction = 0;
            self.folkways$input = input.copy();
        }
        self.folkways$sync(input.getCount());
        if (self.cookingProgress <= 0 || self.cookingTotalTime <= 1 || self.folkways$batches.isEmpty()
                || !state.getValue(BlockStateProperties.LIT)) { return; }
        self.folkways$fraction += self.folkways$batches.getFirst().getDouble("speed");
        int extra = (int) self.folkways$fraction;
        self.folkways$fraction -= extra;
        self.cookingProgress = Math.min(self.cookingTotalTime - 1, self.cookingProgress + extra);
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void folkways$save(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        ListTag list = new ListTag();
        folkways$batches.forEach(part -> list.add(part.copy()));
        tag.put("FolkwaysFurnaceBatches", list);
        tag.putDouble("FolkwaysFurnaceFraction", folkways$fraction);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void folkways$load(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        folkways$batches.clear();
        for (Tag entry : tag.getList("FolkwaysFurnaceBatches", Tag.TAG_COMPOUND)) {
            CompoundTag part = (CompoundTag) entry;
            if (part.getInt("count") > 0) { folkways$batches.add(part.copy()); }
        }
        folkways$fraction = tag.getDouble("FolkwaysFurnaceFraction");
        folkways$input = ((AbstractFurnaceBlockEntity) (Object) this).getItem(0).copy();
    }
}
