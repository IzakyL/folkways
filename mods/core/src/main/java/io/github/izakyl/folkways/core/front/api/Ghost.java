package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.world.api.StructurePayload;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public record Ghost(BlockPos pos, BlockState state, StructurePayload payload, Optional<UUID> order, int payloadHash) {

    public static final int MAX_PREVIEW_GHOSTS = 2048;

    public static final StreamCodec<ByteBuf, Ghost> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, Ghost::pos,
        ByteBufCodecs.VAR_INT.map(Block::stateById, Block::getId), Ghost::state,
        ByteBufCodecs.COMPOUND_TAG.map(StructurePayload::new, StructurePayload::tag), Ghost::payload,
        UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), Ghost::order,
        ByteBufCodecs.VAR_INT, Ghost::payloadHash,
        Ghost::new);

    public Ghost(BlockPos pos, BlockState state, StructurePayload payload) {
        this(pos, state, payload, Optional.empty(), 0);
    }

    public Ghost(BlockPos pos, BlockState state, StructurePayload payload, Optional<UUID> order) {
        this(pos, state, payload, order, 0);
    }

    public Ghost(BlockPos pos, BlockState state) {
        this(pos, state, StructurePayload.EMPTY);
    }

    public Ghost {
        pos = pos.immutable();
    }

    /** Nearby configured cells keep their detail; overflow is omitted from the display only. */
    public static List<Ghost> limited(List<Ghost> ghosts, int most, BlockPos near) {
        if (most <= 0) {
            return List.of();
        }
        List<Ghost> ordered = ghosts;
        if (ghosts.size() > most || ghosts.stream().anyMatch(ghost -> ghost.payload().present())) {
            ordered = ghosts.stream().sorted(Comparator.comparingDouble(ghost -> ghost.pos().distSqr(near))).toList();
        }
        List<Ghost> kept = new ArrayList<>();
        int bytes = 0;
        for (Ghost ghost : ordered.subList(0, Math.min(most, ordered.size()))) {
            int size = ghost.payload().bytes();
            if (size <= StructurePayload.MAX_PREVIEW_BYTES - bytes) {
                kept.add(ghost);
                bytes += size;
            }
        }
        return List.copyOf(kept);
    }
}
