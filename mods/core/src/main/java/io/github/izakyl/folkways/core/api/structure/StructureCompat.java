package io.github.izakyl.folkways.core.api.structure;

import io.github.izakyl.folkways.core.api.work.Need;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Per-block configuration hooks; integrations do not replace the placement adapter. */
public final class StructureCompat {
    public interface Handler {
        BlockEntity detached(HolderLookup.Provider registries, BlockPos pos, BlockState state, StructurePayload payload);

        CompoundTag capture(BlockEntity entity);

        CompoundTag safe(BlockEntity entity);

        List<Need> materials(BlockEntity entity);

        StructurePayload transformed(StructurePayload payload, Mirror mirror, Rotation rotation);

        void turn(BlockEntity entity, Mirror mirror, Rotation rotation);
    }

    private static final Map<Block, Handler> HANDLERS = new IdentityHashMap<>();

    private StructureCompat() {
    }

    public static void register(Block block, Handler handler) {
        if (HANDLERS.putIfAbsent(block, handler) != null) {
            throw new IllegalStateException("Structure handler already registered for " + block);
        }
    }

    public static Optional<Handler> of(BlockState state) {
        return Optional.ofNullable(HANDLERS.get(state.getBlock()));
    }

    public static StructurePayload transform(BlockState state, StructurePayload payload, Mirror mirror, Rotation rotation) {
        return !payload.present() || mirror == Mirror.NONE && rotation == Rotation.NONE ? payload
            : of(state).map(handler -> handler.transformed(payload, mirror, rotation)).orElse(payload);
    }
}
