package io.github.izakyl.folkways.cookery.board;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class BoardOwnership {

    private static final String KEY = "folkways.cookery.board";

    private BoardOwnership() {
    }

    record Batch(UUID colony, UUID id, ResourceLocation recipe) {
    }

    static void remember(ChoppingBoardBlockEntity board, ServerLevel level, Batch batch) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", batch.colony());
        tag.putUUID("batch", batch.id());
        tag.putString("recipe", batch.recipe().toString());
        tag.put("fingerprint", fingerprint(board, level));
        board.getPersistentData().put(KEY, tag);
        board.setChanged();
    }

    static Optional<Batch> read(ChoppingBoardBlockEntity board, ServerLevel level) {
        CompoundTag tag = board.getPersistentData().getCompound(KEY);
        ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("recipe"));
        if (board.getCurrentCutStack().isEmpty() || !tag.hasUUID("colony") || !tag.hasUUID("batch")
                || recipe == null || !tag.getCompound("fingerprint").equals(fingerprint(board, level))) {
            return Optional.empty();
        }
        return Optional.of(new Batch(tag.getUUID("colony"), tag.getUUID("batch"), recipe));
    }

    static boolean ours(ChoppingBoardBlockEntity board, UUID colony) {
        CompoundTag tag = board.getPersistentData().getCompound(KEY);
        return tag.hasUUID("colony") && colony.equals(tag.getUUID("colony"));
    }
    static void clear(ChoppingBoardBlockEntity board) {
        board.getPersistentData().remove(KEY);
        board.setChanged();
    }

    static List<ItemStack> results(ChoppingBoardBlockEntity board, ServerLevel level) {
        Tag raw = board.saveWithoutMetadata(level.registryAccess()).get("ResultItem");
        if (raw instanceof ListTag list) {
            return list.stream().filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast)
                .map(tag -> ItemStack.parseOptional(level.registryAccess(), tag)).filter(stack -> !stack.isEmpty())
                .toList();
        }
        return raw instanceof CompoundTag tag
            ? List.of(ItemStack.parseOptional(level.registryAccess(), tag)) : List.of();
    }

    // Include the board's stored products as well as the input and cuts: a player's changes must never be adopted.
    private static CompoundTag fingerprint(ChoppingBoardBlockEntity board, ServerLevel level) {
        CompoundTag saved = board.saveWithoutMetadata(level.registryAccess());
        CompoundTag fingerprint = new CompoundTag();
        for (String key : List.of("CurrentCutStack", "CurrentCutCount", "MaxCutCount", "ModelId", "ResultItem")) {
            Tag value = saved.get(key);
            if (value != null) {
                fingerprint.put(key, value.copy());
            }
        }
        return fingerprint;
    }
}
