package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.world.api.StructurePayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record Ghost(BlockPos pos, BlockState state, StructurePayload payload, Optional<UUID> order, int payloadHash) {

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
