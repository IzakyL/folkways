package io.github.izakyl.folkways.plugins.cookery.steamer;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.SteamerBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.SteamerBlockEntity;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** An entire native block entity is reserved: takeFood cannot select individual slots. */
final class SteamerBatch {
    private static final String KEY = "folkways.cookery.steamer";
    record Batch(UUID colony, UUID id, ResourceLocation recipe) { }
    private SteamerBatch() { }

    static void remember(SteamerBlockEntity steamer, ServerLevel level, WorldPos anchor, Batch batch,
                         ItemStack output, int time, SteamerLids.Cap cap) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", batch.colony());
        tag.putUUID("batch", batch.id());
        tag.putString("recipe", batch.recipe().toString());
        tag.putInt("time", time);
        tag.putInt("baseOffset", anchor.block(level).getY() - steamer.getBlockPos().getY());
        ListTag inputs = new ListTag();
        ListTag outputs = new ListTag();
        for (ItemStack input : steamer.getItems()) {
            inputs.add(input.saveOptional(level.registryAccess()));
            outputs.add((input.isEmpty() ? ItemStack.EMPTY : output).saveOptional(level.registryAccess()));
        }
        tag.put("inputs", inputs);
        tag.put("outputs", outputs);
        ListTag shape = new ListTag();
        for (WorldPos part : SteamerStation.parts(level, anchor)) {
            shape.add(structure(level.getBlockState(part.block(level))));
        }
        tag.put("shape", shape);
        tag.putInt("capOffset", cap.at().getY() - steamer.getBlockPos().getY());
        tag.put("capOriginal", structure(cap.original()));
        tag.put("capExpected", structure(cap.expected()));
        steamer.getPersistentData().put(KEY, tag);
        steamer.setChanged();
    }

    static Optional<Batch> read(SteamerBlockEntity steamer, ServerLevel level) {
        CompoundTag tag = steamer.getPersistentData().getCompound(KEY);
        ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("recipe"));
        if (!tag.hasUUID("colony") || !tag.hasUUID("batch") || recipe == null) { return Optional.empty(); }
        var anchor = steamer.getBlockPos().above(tag.getInt("baseOffset"));
        ListTag shape = tag.getList("shape", Tag.TAG_COMPOUND);
        if (shape.isEmpty()) { return Optional.empty(); }
        for (int dy = 0; dy < shape.size(); dy++) {
            if (!shape.getCompound(dy).equals(structure(level.getBlockState(anchor.above(dy))))) { return Optional.empty(); }
        }
        if (SteamerStation.steamer(level, WorldPos.of(level, anchor.above(shape.size()))).isPresent()) { return Optional.empty(); }
        ListTag inputs = tag.getList("inputs", Tag.TAG_COMPOUND);
        ListTag outputs = tag.getList("outputs", Tag.TAG_COMPOUND);
        if (inputs.size() != 8 || outputs.size() != 8) { return Optional.empty(); }
        boolean nonempty = false;
        for (int slot = 0; slot < 8; slot++) {
            ItemStack input = ItemStack.parseOptional(level.registryAccess(), inputs.getCompound(slot));
            ItemStack output = ItemStack.parseOptional(level.registryAccess(), outputs.getCompound(slot));
            ItemStack actual = steamer.getItems().get(slot);
            int time = steamer.getCookingTime()[slot];
            if (input.isEmpty()) {
                if (!actual.isEmpty()) { return Optional.empty(); }
            } else {
                nonempty = true;
                if (!(time == -1 && ItemStack.matches(output, actual)
                        || time == tag.getInt("time") && ItemStack.matches(input, actual))) { return Optional.empty(); }
            }
        }
        return nonempty ? Optional.of(new Batch(tag.getUUID("colony"), tag.getUUID("batch"), recipe)) : Optional.empty();
    }

    static boolean ours(SteamerBlockEntity steamer, UUID colony) {
        CompoundTag tag = steamer.getPersistentData().getCompound(KEY);
        return tag.hasUUID("colony") && tag.getUUID("colony").equals(colony);
    }
    static boolean marked(SteamerBlockEntity steamer) { return steamer.getPersistentData().contains(KEY); }
    static void restoreLid(SteamerBlockEntity steamer, ServerLevel level) {
        CompoundTag tag = steamer.getPersistentData().getCompound(KEY);
        var at = steamer.getBlockPos().above(tag.getInt("capOffset"));
        var actual = level.getBlockState(at);
        if (actual.is(steamer.getBlockState().getBlock())
                && tag.getCompound("capExpected").equals(structure(actual))
                && !level.getBlockState(at.above()).is(actual.getBlock())) {
            boolean originallyClosed = tag.getCompound("capOriginal").getCompound("Properties").getString("has_lid").equals("true");
            SteamerLids.set(level, at, originallyClosed);
        }
    }
    // Native support updates change the cosmetic base when heat sources are replaced.
    // They do not change capacity, lid ownership or any food slot.
    private static CompoundTag structure(BlockState state) {
        return NbtUtils.writeBlockState(state.hasProperty(SteamerBlock.HAS_BASE)
            ? state.setValue(SteamerBlock.HAS_BASE, false) : state);
    }
    static void clear(SteamerBlockEntity steamer) {
        steamer.getPersistentData().remove(KEY);
        steamer.setChanged();
    }
}
