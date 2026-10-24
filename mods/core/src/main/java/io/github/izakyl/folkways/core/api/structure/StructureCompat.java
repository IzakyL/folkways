package io.github.izakyl.folkways.core.api.structure;

import io.github.izakyl.folkways.core.api.work.Need;
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
        BlockEntity detached(HolderLookup.Provider registries, BlockPos pos, BlockState state, StructurePayload payload);

        CompoundTag capture(BlockEntity entity);

        CompoundTag safe(BlockEntity entity);

        List<Need> materials(BlockEntity entity);

        StructurePayload transformed(StructurePayload payload, Mirror mirror, Rotation rotation);

        void turn(BlockEntity entity, Mirror mirror, Rotation rotation);
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

    public static Processing processing() {
        var scopes = HANDLERS.values().stream().distinct().map(Handler::processing).toList();
        return () -> {
            for (int index = scopes.size() - 1; index >= 0; index--) scopes.get(index).close();
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

    public static void validate(StructurePayload payload) {
        CompoundTag tag = payload.tag();
        if (!tag.contains(DESCRIPTOR)) return;
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
