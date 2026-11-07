package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.world.api.StructurePayload;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

/** Detached, thread-safe voxel encoding. Coordinates are already in world axes, with x/y/z indexing. */
public interface Voxelizer {

    int resolution();

    boolean supports(BlockState material);

    BlockState carrier();

    StructurePayload encode(BlockState[] materials);

    /** Drafting has its own deadline; per-cell validation still applies. */
    default StructureCompat.Processing drawing() {
        return () -> { };
    }

    static Optional<Voxelizer> installed() {
        return Optional.ofNullable(Registry.instance);
    }

    static void register(Voxelizer voxelizer) {
        if (Registry.instance != null) {
            throw new IllegalStateException("Voxelizer already registered");
        }
        Registry.instance = java.util.Objects.requireNonNull(voxelizer);
    }

    final class Registry {
        private static volatile Voxelizer instance;

        private Registry() {
        }
    }
}
