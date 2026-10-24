package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** A loaded selection, with only the configuration a schematic may carry. */
public final class BlueprintCapture {
    private BlueprintCapture() {
    }

    public static CompoundTag read(ServerLevel level, BoundingBox box, Predicate<BlockPos> allowed) {
        long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        if (volume > BlueprintBook.maxCaptureBlocks() || box.minY() < level.getMinBuildHeight()
            || box.maxY() >= level.getMaxBuildHeight()
            || BlueprintVolume.of(box.getXSpan(), box.getYSpan(), box.getZSpan()).isEmpty()) {
            throw new IllegalArgumentException("Selection exceeds the blueprint limit");
        }
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        ListTag states = new ListTag();
        ListTag blocks = new ListTag();
        BlockPos origin = new BlockPos(box.minX(), box.minY(), box.minZ());
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (!level.hasChunkAt(pos) || !allowed.test(pos)) {
                throw new IllegalArgumentException("Selection is not loaded or accessible");
            }
            BlockState state = level.getBlockState(pos);
            int index = palette.computeIfAbsent(state, added -> {
                states.add(NbtUtils.writeBlockState(added));
                return states.size() - 1;
            });
            CompoundTag cell = new CompoundTag();
            cell.putInt("state", index);
            BlockPos offset = pos.subtract(origin);
            cell.put("pos", ints(offset.getX(), offset.getY(), offset.getZ()));
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity != null) {
                CompoundTag data = StructureCompat.of(state).map(handler -> handler.capture(entity))
                    .orElseGet(() -> SchematicPlacement.adapter().prepare(level, state, entity));
                if (data != null && !data.isEmpty()) {
                    cell.put("nbt", StructureCompat.tagged(state, data).tag());
                }
            }
            blocks.add(cell);
        }
        CompoundTag file = new CompoundTag();
        file.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        file.put("size", ints(box.getXSpan(), box.getYSpan(), box.getZSpan()));
        file.put("palette", states);
        file.put("blocks", blocks);
        file.put("entities", new ListTag());
        return file;
    }

    private static ListTag ints(int... values) {
        ListTag tag = new ListTag();
        for (int value : values) tag.add(IntTag.valueOf(value));
        return tag;
    }
}
