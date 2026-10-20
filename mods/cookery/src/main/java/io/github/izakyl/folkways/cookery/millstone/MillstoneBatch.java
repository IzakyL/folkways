package io.github.izakyl.folkways.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

final class MillstoneBatch {
    static final String KEY = "folkways.cookery.millstone";
    record Batch(UUID colony, UUID id, ResourceLocation recipe, boolean ready) { }

    private MillstoneBatch() { }

    static Optional<Batch> read(MillstoneBlockEntity mill, ServerLevel level) {
        CompoundTag tag = mill.getPersistentData().getCompound(KEY);
        ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("recipe"));
        if (!tag.hasUUID("colony") || !tag.hasUUID("batch") || recipe == null
                || !tag.getCompound("fingerprint").equals(fingerprint(mill, level))) {
            return Optional.empty();
        }
        return Optional.of(new Batch(tag.getUUID("colony"), tag.getUUID("batch"), recipe, tag.getBoolean("ready")));
    }

    static boolean ours(MillstoneBlockEntity mill, UUID colony) {
        CompoundTag tag = mill.getPersistentData().getCompound(KEY);
        return tag.hasUUID("colony") && colony.equals(tag.getUUID("colony"));
    }

    static void remember(MillstoneBlockEntity mill, ServerLevel level, Batch batch) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", batch.colony());
        tag.putUUID("batch", batch.id());
        tag.putString("recipe", batch.recipe().toString());
        tag.putBoolean("ready", batch.ready());
        tag.put("fingerprint", fingerprint(mill, level));
        mill.getPersistentData().put(KEY, tag);
        mill.setChanged();
    }

    static void clear(MillstoneBlockEntity mill) {
        mill.getPersistentData().remove(KEY);
        mill.setChanged();
    }

    private static CompoundTag fingerprint(MillstoneBlockEntity mill, ServerLevel level) {
        CompoundTag saved = mill.saveWithoutMetadata(level.registryAccess());
        CompoundTag answer = new CompoundTag();
        for (String key : List.of("InputItem", "OutputItem")) {
            Tag value = saved.get(key);
            if (value != null) {
                answer.put(key, value.copy());
            }
        }
        return answer;
    }
}
