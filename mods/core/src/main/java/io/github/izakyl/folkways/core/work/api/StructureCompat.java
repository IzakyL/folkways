package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.world.api.StructurePayload;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Per-block configuration hooks; integrations do not replace the placement adapter. */
public final class StructureCompat {
    public static final String DESCRIPTOR = "folkways:structure";

    public static final class Failure extends IllegalArgumentException {
        private final String key;

        public Failure(String key) {
            super(key);
            this.key = key;
        }

        public Component component() {
            return Component.translatable(key);
        }
    }

    public static Failure invalid() {
        return new Failure("folkways.refusal.build.micro_data_invalid");
    }

    public interface Handler {
        ResourceLocation id();

        default Component name() {
            return Component.translatable("folkways.structure." + id().getPath());
        }

        default Processing processing() {
            return () -> { };
        }

        default void inspect(StructurePayload payload) {
        }

        /** Charge every placed copy, including payloads already held in the decode cache. */
        default void inspectPlacement(StructurePayload payload) {
            inspect(payload);
        }

        BlockEntity detached(HolderLookup.Provider registries, BlockPos pos, BlockState state, StructurePayload payload);

        CompoundTag capture(BlockEntity entity);

        /** Native placement data for an inspected payload, independent of an adapter's ordinary-block whitelist. */
        default CompoundTag placementData(HolderLookup.Provider registries, BlockPos pos, BlockState state,
                StructurePayload payload) {
            return capture(detached(registries, pos, state, payload));
        }

        List<Need> materials(BlockEntity entity);

        default boolean carves() {
            return false;
        }

        default Voxels voxels(StructurePayload payload) {
            throw invalid();
        }

        default Voxels voxels(BlockEntity entity) {
            throw invalid();
        }

        default boolean matches(BlockEntity entity, StructurePayload payload) {
            return false;
        }

        default double changed(BlockEntity entity, StructurePayload payload) {
            return 1;
        }

        default Optional<ItemSpec> solid(BlockState state) {
            return Optional.empty();
        }

        StructurePayload transformed(StructurePayload payload, Mirror mirror, Rotation rotation);

        void turn(BlockEntity entity, Mirror mirror, Rotation rotation);
    }

    public record Voxels(int perBlock, Map<ItemSpec, Integer> counts) {
        public Voxels {
            if (perBlock < 1) {
                throw invalid();
            }
            counts = Map.copyOf(counts);
        }
    }

    public interface Processing extends AutoCloseable {
        @Override
        void close();
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

    public static Optional<Handler> carver() {
        return HANDLERS.values().stream().filter(Handler::carves).findFirst();
    }

    public static Processing processing() {
        var scopes = HANDLERS.values().stream().distinct().map(Handler::processing).toList();
        return () -> {
            for (int index = scopes.size() - 1; index >= 0; index--) {
                scopes.get(index).close();
            }
        };
    }

    public static void inspect(BlockState state, StructurePayload payload) {
        validate(payload);
        try {
            of(state).ifPresent(handler -> handler.inspect(payload));
        } catch (Failure failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw invalid();
        }
    }

    public static void inspectPlacement(BlockState state, StructurePayload payload) {
        validate(payload);
        of(state).ifPresent(handler -> handler.inspectPlacement(payload));
    }

    public static void validate(StructurePayload payload) {
        CompoundTag tag = payload.tag();
        if (!tag.contains(DESCRIPTOR)) {
            return;
        }
        CompoundTag descriptor = tag.getCompound(DESCRIPTOR);
        if (!tag.contains(DESCRIPTOR, net.minecraft.nbt.Tag.TAG_COMPOUND) || descriptor.getInt("version") != 1
            || HANDLERS.values().stream().noneMatch(handler -> handler.id().toString().equals(descriptor.getString("id")))) {
            throw new Failure("folkways.refusal.build.missing_integration");
        }
    }

    public static StructurePayload tagged(BlockState state, CompoundTag tag) {
        CompoundTag kept = tag.copy();
        of(state).ifPresent(handler -> {
            CompoundTag descriptor = new CompoundTag();
            descriptor.putString("id", handler.id().toString());
            descriptor.putInt("version", 1);
            kept.put(DESCRIPTOR, descriptor);
        });
        return new StructurePayload(kept);
    }

    public static StructurePayload transform(BlockState state, StructurePayload payload, Mirror mirror, Rotation rotation) {
        validate(payload);
        if (!payload.present() || mirror == Mirror.NONE && rotation == Rotation.NONE) {
            return payload;
        }
        try {
            return of(state).map(handler -> tagged(state, handler.transformed(payload, mirror, rotation).tag()))
                .orElse(payload);
        } catch (Failure failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw invalid();
        }
    }
}
