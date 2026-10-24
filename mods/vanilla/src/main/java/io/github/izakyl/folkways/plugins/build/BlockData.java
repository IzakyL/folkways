package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Blueprint configuration, kept separate from live block entities and their mutable inventories. */
public record BlockData(CompoundTag tag, List<UnloadedBlock.Turn> turns) {
    public static final BlockData EMPTY = new BlockData(new CompoundTag(), List.of());

    public BlockData {
        tag = tag.copy();
        turns = List.copyOf(turns);
    }

    @Override
    public CompoundTag tag() {
        return tag.copy();
    }

    public static BlockData of(CompoundTag tag) {
        return tag.isEmpty() ? EMPTY : new BlockData(tag, List.of());
    }

    public boolean present() {
        return !tag.isEmpty();
    }

    BlockData turned(Mirror mirror, Rotation rotation) {
        if (!present()) {
            return this;
        }
        List<UnloadedBlock.Turn> more = new ArrayList<>(turns);
        more.add(new UnloadedBlock.Turn(mirror, rotation));
        return new BlockData(tag, more);
    }

    /** A detached entity holding this data, turned as the blueprint was; it never touches the world. */
    public BlockEntity entity(ServerLevel level, BlockPos pos, BlockState state) {
        var handler = StructureCompat.of(state);
        if (handler.isPresent()) {
            BlockEntity entity = handler.get().detached(level.registryAccess(), pos, state, new StructurePayload(tag));
            for (UnloadedBlock.Turn turn : turns) {
                handler.get().turn(entity, turn.mirror(), turn.rotation());
            }
            return entity;
        }
        if (!(state.getBlock() instanceof EntityBlock block)) {
            return null;
        }
        BlockEntity entity = block.newBlockEntity(pos, state);
        if (entity != null) {
            entity.setLevel(level);
            if (present()) {
                CompoundTag data = tag();
                data.putInt("x", pos.getX());
                data.putInt("y", pos.getY());
                data.putInt("z", pos.getZ());
                entity.loadWithComponents(data, level.registryAccess());
            }
            for (UnloadedBlock.Turn turn : turns) {
                SchematicPlacement.adapter().turn(entity, turn.mirror(), turn.rotation());
            }
        }
        return entity;
    }

    CompoundTag save() {
        return Writer.of().blob("nbt", tag).children("turns", turns, UnloadedBlock.Turn::save).tag();
    }

    static BlockData load(CompoundTag tag) {
        return new BlockData(tag.getCompound("nbt"), UnloadedBlock.turnsOf(Reader.of(tag)));
    }
}
